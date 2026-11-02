# Consolidated native d1 runtime

This is the opt-in integration candidate for the optimized d1 execution path.
It provides one source patch, a matched base/host/DSP bundle and an owned worker
adapter. It remains outside the APK; application control must explicitly call
the adapter. No model-driven action is enabled by assembling or starting it.

The supported combination is llama.cpp
`8642541b255bfb5dc39cd351a178be09013a55d6`, the pinned d1-2B Q4_K_M model in
[README.md](README.md), Android arm64 and Hexagon v79. Other architectures,
models and concurrent requests have not been validated.

## Source and build

[native-d1-runtime.patch](patches/native-d1-runtime.patch) is a complete patch
against that revision. Apply it once to a fresh checkout. Do not stack the
earlier paired-SwiGLU or graph experiments on top of it.

```sh
git -C RUNTIME_SOURCE worktree add --detach PATCHED_SOURCE 8642541b255bfb5dc39cd351a178be09013a55d6
git -C PATCHED_SOURCE apply --check ABSOLUTE_NATIVE_PATCH
git -C PATCHED_SOURCE apply ABSOLUTE_NATIVE_PATCH

cmake -S PATCHED_SOURCE -B artifacts/d1-base -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_SHARED_LINKER_FLAGS= \
  -DBUILD_SHARED_LIBS=ON -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_CURL=OFF \
  -DGGML_CPU=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_LTO=OFF \
  -DGGML_BACKEND_DL=ON -DHENYO_D1_UNVERSIONED_SONAME=ON
cmake --build artifacts/d1-base --target ggml-base -j2

cmake -S PATCHED_SOURCE/ggml/src/ggml-hexagon/htp \
  -B artifacts/d1-dsp -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release -DDSP_VERSION=v79 \
  -DPREBUILT_LIB_DIR=toolv21_v79 \
  -DHEXAGON_SDK_ROOT=ABSOLUTE_SDK_ROOT \
  -DHEXAGON_TOOLS_ROOT=ABSOLUTE_HEXAGON_TOOLS_ROOT \
  -DCMAKE_TOOLCHAIN_FILE=ABSOLUTE_PATCHED_SOURCE/ggml/src/ggml-hexagon/htp/cmake-toolchain.cmake
cmake --build artifacts/d1-dsp -j2

python experiments/d1/build_native_host.py \
  --source PATCHED_SOURCE --sdk SDK_ROOT \
  --generated-dir artifacts/d1-dsp --base-lib-dir artifacts/d1-base/bin \
  --output artifacts/d1-host --jobs 2
```

The verified DSP toolchain is SDK 6.7 with Hexagon Tools 21. Obtain it separately;
SDK binaries, generated QAIC interfaces and vendor libraries are excluded from
the patch. The DSP build generates fresh interfaces, compiles all project units,
retains LTO and runs the SDK SWIV post-link step. On an arm64 build host, an
externally supplied x86 tool wrapper and QAIC wrapper can be selected through
`HEXAGON_COMPILER_WRAPPER_DIR` and `HEXAGON_QAIC_ROOT`. This repository does not
provide those wrappers or promise that the SDK runs natively on arm64.

The base build uses the Android toolchain's shared C++ runtime. This differs
from the official base binary's embedded runtime. Bundle the matching
`libc++_shared.so` with its license. The worker requires that exact runtime
as the only mapped app C++ runtime and records its digest. Do not
attribute a comparison involving this packaging change solely to graph changes.

The base graph changes call the normal ggml APIs directly. The host/DSP pair
includes stencil convolution, paired Gate/Up with SwiGLU, residual normalization
and shape-dependent HMX plans. Host and DSP share a private kernel contract;
mixing these artifacts with another experiment or stock DSP is unsupported.

The paired epilogue adapts
[Tandem at 6fed0a4](https://github.com/flyingdoog/tandem/tree/6fed0a4555e72f23c9bfa982aa5cbec24b5ed5df).
Its MIT copyright/license is identical to the pinned ggml project's notice,
retained in [LICENSE.ggml](patches/LICENSE.ggml). Retain this attribution when
carrying the patch into another build.

## One bundle and configuration

Create a private component descriptor under ignored `artifacts/`, with this
shape. Each path identifies a just-built artifact; each digest is its SHA-256.
The patch digest refers to the complete patch above.

```json
{
  "schema": 1,
  "runtime_revision": "8642541b255bfb5dc39cd351a178be09013a55d6",
  "kernel_contract": "henyo.d1.htp-v79/1",
  "components": {
    "base": {"path": "BASE_LIBRARY", "sha256": "BASE_SHA256"},
    "host": {"path": "HOST_LIBRARY", "sha256": "HOST_SHA256"},
    "dsp": {"path": "DSP_LIBRARY", "sha256": "DSP_SHA256"}
  },
  "source_patch_sha256": "PATCH_SHA256"
}
```

```sh
python experiments/d1/prepare_native.py \
  --runtime-root PINNED_ANDROID_RUNTIME --components artifacts/components.json \
  --output artifacts/d1-bundle --config artifacts/d1-config.json \
  --model MODEL_FILE --system-environment-file artifacts/driver-environment.json \
  --cxx-runtime MATCHED_CXX_RUNTIME --cxx-license CXX_LICENSE
python experiments/d1/native_runtime.py \
  --config artifacts/d1-config.json --input artifacts/requests.jsonl
```

The Android runtime must be the pinned official `bin/llama-server` and `lib/`
distribution. The optional private driver environment accepts only
`LD_LIBRARY_PATH` and `ADSP_LIBRARY_PATH`. Never commit its device-specific
contents. The assembler preserves the known runtime library set and overlays
the matched base, host, DSP and C++ runtime. It verifies component/model hashes,
writes a hash manifest and refuses to overwrite existing output.

Configuration selects `stable` or `off` explicitly. `stable` fixes the validated
native graph/fusion/tile settings. `off` disables these graph/fusion switches
on the same patched runtime; it is an ablation, not an unmodified upstream
baseline. Inherited experimental variables and `LD_PRELOAD` are removed.
The worker verifies actual base/host mappings, the model build and full layer
placement. Driver availability is established at startup, not bundle assembly.

One `NativeRuntime` owns a lazy, persistent, authenticated loopback worker with
one slot and a 2048-token context. It accepts one question per request. Reuse
the instance across observations; close it on cancellation or session end.
Requests have a whole-exchange deadline and failures terminate that worker
without automatic replay. Logs and raw responses belong under `artifacts/`.

## Calling from readiness checks

```python
from native_runtime import NativeRuntime
from readiness import guarded_request

with NativeRuntime(private_configuration) as runtime:
    result = guarded_request(
        body, expected_package=independently_selected_package,
        infer=runtime.request,
    )
```

Rejected necessary conditions skip model verification, loading and inference.
A model response is advisory. The caller must apply its confidence/abstention
policy, discard late or cancelled responses, and revalidate the current window
and unique target before any action. This adapter neither observes UI nor
dispatches actions. Existing confident model errors remain possible.

Naive A8 activations, QNN execution, ML Drift and packed multiple questions
remain research alternatives. They are not hidden defaults in `stable`.

## Verification and measurement

```sh
python experiments/d1/verify_native_runtime.py
python experiments/d1/verify_readiness.py
```

Model-free checks cover bundle boundaries, rejected observations, isolation of
settings, deadlines and cancellation during startup. Actual device verification
also needs matched raw answers/probabilities over short/long shape boundaries
and changing histories, positive native placement, and independently checked
kernel results. Build success alone is insufficient.

Measure clean, sequential processes without diagnostic profiling or concurrent
compilation. Compare identical request bodies in alternating order and record
the exact bundle, loaded runtime and profile. Report lazy first-call time,
process startup, first inference and warm API latency separately. Digest
verification warms the file cache, so startup is not storage-cold loading.
`startupMs` begins at process launch; `callMs` also includes lazy verification
and startup; `apiMs` covers the local HTTP exchange. All exclude UI capture,
external agent planning, target revalidation, input dispatch and app loading.
Measure those stages separately before claiming a whole-task speedup.
