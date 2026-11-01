# Android d1 kernels and reuse

This follow-up measures the pinned d1-2B Q4_K_M runtime described in
[README.md](README.md). It remains outside the APK and production control loop.
The implementation delivered here removes unused activation preparation from
the OpenCL DP4A path. Measured latency effects were small and workload-dependent;
a several-fold acceleration was not achieved.

## Reproducible change

The integer Q4_K matrix multiplication reads the original activations and
quantizes them to Q8_1. Previously it also created two image objects, allocated
a transposed buffer and dispatched an FP32-to-FP16 transpose whose result it
never read. [The patch](patches/opencl-dp4a-lazy-transpose.patch) moves that
preparation after the integer branch's early return. The floating-point fallback
still performs the transpose. Arithmetic, quantization, dispatch conditions and
model weights are unchanged. No persistent cache or new allocation is added.
The upstream code in the patch is covered by [ggml's MIT license](patches/LICENSE.ggml).

Applying the patch is opt-in. Keep the unmodified build as a fallback. Apply only
to source commit `8642541b255bfb5dc39cd351a178be09013a55d6`, using a separate source
checkout and build directory:

```sh
git -C RUNTIME_SOURCE worktree add --detach PATCHED_SOURCE 8642541b255bfb5dc39cd351a178be09013a55d6
git -C PATCHED_SOURCE apply --check ABSOLUTE_PATH_TO_PATCH
git -C PATCHED_SOURCE apply ABSOLUTE_PATH_TO_PATCH
cmake -S PATCHED_SOURCE -B PATCHED_BUILD -DCMAKE_BUILD_TYPE=Release \
  -DGGML_OPENMP=OFF -DGGML_OPENCL=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_OPENSSL=OFF
cmake --build PATCHED_BUILD -j2 --target llama-server
```

Replace uppercase placeholders with local paths. Keep runtime sources, binaries,
logs and model files under ignored `artifacts/`. Build the baseline with exactly
the same compiler/options. Follow README's driver setup and confirm hardware GPU
enumeration. Compilation must finish before latency measurements.

```sh
python experiments/d1/verify.py
python experiments/d1/verify_kernels.py
python experiments/d1/compare_kernels.py \
  --baseline-bin BASELINE_BUILD/bin/llama-server \
  --candidate-bin PATCHED_BUILD/bin/llama-server \
  --model MODEL_FILE --device DEVICE_ID \
  --output artifacts/d1-kernel-comparison.json
```

The comparison fixes request bodies, model digest, context/batch/microbatch 2048,
four CPU threads, one slot and full GPU offload. It uses ABBA order, two warmups
per process, and two repetitions of each of 18 distinct synthetic cases per
process. It records whole-response answers, startup, first requests, RSS and
bounded owned-worker shutdown separately. Outputs cannot overwrite an existing
report or escape `artifacts/`. SIGINT/SIGTERM discards the pending answer and
cleans up the owned worker. Request timeouts are whole-exchange deadlines.
`--dp4a 0` checks the floating-point fallback. Optional
`--baseline-library-dir` / `--candidate-library-dir` select separately built
backends with an otherwise identical server binary.

For direct dispatch regression checks, compile `check_matmul.cpp` against the
pinned ggml headers/libraries. Run the same binary with each backend library,
passing the enumerated GPU device and a distinct ignored output file. Compare
the complete files with `cmp`:

```sh
c++ -O2 -std=c++17 -I RUNTIME_SOURCE/ggml/include \
  experiments/d1/check_matmul.cpp -L BASELINE_BUILD/bin \
  -Wl,-rpath,ABSOLUTE_BASELINE_BUILD/bin -lggml -lggml-base \
  -o artifacts/check-matmul
GGML_OPENCL_Q4K_DENSE_DP4A=1 artifacts/check-matmul DEVICE_ID artifacts/matmul-baseline.bin
LD_LIBRARY_PATH="ABSOLUTE_PATCHED_BUILD/bin:$LD_LIBRARY_PATH" \
  GGML_OPENCL_Q4K_DENSE_DP4A=1 \
  artifacts/check-matmul DEVICE_ID artifacts/matmul-patched.bin
cmp artifacts/matmul-baseline.bin artifacts/matmul-patched.bin
```

It exercises Q4_K × F32 at 42 shapes with two
activation versions each, including small batches, tile edges and an unaligned
row count. Repeat with `GGML_OPENCL_Q4K_DENSE_DP4A=0`, then with DP4A enabled,
then with `GGML_OPENCL_Q4K_DENSE_DP4A_WIMG=1`. This checks equality to the original
arithmetic, not the accuracy of Q4_K against an unquantized model.
All three paired runs passed on Android: every output value was byte-identical
across 42 shapes × two activation versions per mode, including the fallback.

## Measured results

Recorded on 2026-11-01.

The Android GPU comparison used the same model and inputs for both variants.
External cooling was reported; fan speed, ambient conditions, OS cache and GPU
clocks were not controlled. These are small exploratory measurements, not a
device-wide latency guarantee. The synthetic tasks differ from the earlier
recorded UI readiness task; their absolute times must not be substituted for it.

| Synthetic task | Requests per variant | Baseline median / p95 | Patched median / p95 |
| --- | ---: | ---: | ---: |
| Narrow candidate semantics | 48 | 140.6 / 141.9 ms | 139.1 / 140.2 ms |
| Control-state classification | 24 | 385.5 / 433.0 ms | 378.0 / 424.4 ms |

All 126 comparisons after the first observation of each case matched the entire
answer object exactly, including probabilities. There were 144 measured requests
and 18 distinct cases; repeats are not independent accuracy evidence. The patch
does not fix the model's classification errors. Latencies include loopback HTTP
and parsing, but exclude UI acquisition, action execution and outer-agent inference.

In these four process starts, baseline readiness took 5.09–5.71 s and patched
readiness 6.97 s. The first short request took 5.72–5.96 s versus 6.09–6.10 s;
these preparation costs are excluded from the warm table. This does not establish
a startup improvement. Both builds used an existing program cache; storage-cold
and driver-cold loading were not measured. Post-workload RSS was approximately
362–363 MiB versus 360–361 MiB, with high-water RSS about 529–530 MiB in both.
RSS does not include all GPU allocations on unified-memory hardware. Successful
idle worker shutdown took 465–516 ms in these trials.
In a separate interruption check, SIGTERM during a pending request stopped and
reaped the patched worker in 1365 ms; owner cleanup completed in 1418 ms. The
report recorded cancellation and consumed no completed answer. Backend mappings
confirmed the selected patched library was actually loaded.

An additional ABBA comparison used 28 unchanged recorded requests, with two
repetitions in each process. All 196 answer comparisons after each case's first
observation were exact. This workload **did not demonstrate a consistent speedup**:

| Recorded task | Requests per variant | Baseline median / p95 | Patched median / p95 |
| --- | ---: | ---: | ---: |
| Narrow semantics | 96 | 149.4 / 200.5 ms | 147.0 / 384.2 ms |
| Full readiness | 16 | 1261.8 / 1699.4 ms | 1291.3 / 1663.7 ms |

Late short-request outliers affected both variants, especially the second
candidate pass. Baseline pass medians drifted from 145.3 to 154.0 ms; candidate
pass medians from 144.4 to 151.2 ms. The cause was not isolated. This prevents
claiming a general latency or tail improvement from the small synthetic win.
Retain the patch as an opt-in experiment; these results do not justify production
integration as a proven acceleration. Raw recorded inputs remain private.

## Other implemented experiments

The following prototypes were measured separately and are not included in the
patch or enabled by the comparison script. Raw evidence and rejected prototypes
remain local. Operator-only timings were used for screening; whole-model
measurements decided adoption.

| Technique | Observation and decision |
| --- | --- |
| GPU command recording/replay, analogous to CUDA Graphs | A Qualcomm recording-queue prototype replayed 612 dispatches and preserved answers when input values changed at equal shape. Hits were about 138–143 ms; capture misses about 169–176 ms. No material improvement over normal short inference; do not adopt. |
| Pre-expanded Q4 weight cache | About 4–5% lower warm latency in a single 28-request comparison, including the lazy-transpose change. A diagnostic run allocated an additional 1,240,465,408 bytes (about 1.16 GiB) in 123 buffers. Too much extra memory for this preliminary gain; do not adopt. Host RSS alone concealed this cost. |
| Shared FP16 tiles, cooperative token lanes, larger K chunks | Changed operators passed numerical checks, but promising microbenchmarks did not translate to faster inference. Two integer variants increased short latency from about 146 ms to 170 and 200 ms. The FP16 tiled model trial timed out. Reject these variants. |
| Expand the quantized model to FP16 | Approximately tripled model storage without restoring information lost in quantization. Full inference became slower; reject. |
| Vulkan from the same runtime revision | Default execution failed pipeline creation. A forced MMVQ path ran, but two short requests took about 2.46 and 3.37 s and a classification differed. Not an equivalent or faster replacement on the tested driver. |

A separate short-input profiling run attributed roughly 65% of recorded GPU
event time to Q4_K integer matrix multiplication and another 12% to Q6_K integer
matrix multiplication. Across two requests it recorded about 269 ms of GPU work.
Profiler timing is diagnostic, not interchangeable with clean request latency.
It explains why eliminating CPU command encoding alone cannot produce a large
speedup for this workload.

## Which caching is valid for d1?

The official checkpoint uses `d1_attention="set"`. State tokens can attend to
the questions, and each question can attend to the state and itself. For a
single question this includes bidirectional attention over the prompt. Changing
UI state changes intermediate representations of an otherwise identical question;
ordinary causal-prefix KV reuse is therefore not valid across the full model.
The pinned native decision graph is memoryless and processes the prompt together.
See the [official attention implementation](https://huggingface.co/LiquidAI/d1-2B/blob/main/attention.py)
and [checkpoint configuration](https://huggingface.co/LiquidAI/d1-2B/blob/main/config.json).

Useful distinctions:

- Exact-result reuse can avoid inference when the complete inference payload,
  model, quantization and readout configuration are identical. It needs fresh UI
  observation and independent action guards. Similar text alone is insufficient:
  loading, enabled state, identity or a single changed label may change the answer.
- Tokenized templates save CPU preparation, not the dominant GPU computation.
  Tokenization across concatenation boundaries must remain equivalent.
- Command and allocation reuse can accept new values at the same shape. The
  recording prototype above validates this possibility, but measured little gain.
- The first two causal convolution layers precede the first bidirectional layer.
  Exact common-prefix work may be reusable there, with correct position and
  convolution state. This is a limited fraction of the 24-layer model, not whole
  model KV reuse, and was not implemented here.
- The reference supports multiple questions over a shared state. Implementing
  its set mask correctly could amortize work across questions; the pinned native
  path currently evaluates them separately. This is not a demonstrated reduction
  in single-question latency. Switching to the different `state` mask also changes
  the checkpoint's inference contract and needs separate accuracy validation.

## Lessons from other engines and GPU constraints

The tested Adreno-class GPU reports 32 KiB workgroup local memory, packed 4×INT8
dot-product acceleration and FP16 support. Its Vulkan driver exposes subgroup
width 64 but no cooperative-matrix extension. A roughly 5.4 GiB OpenCL memory
budget is shared system memory, not dedicated VRAM. The reported cache size is
not a measurement of the entire SoC cache hierarchy. NVIDIA Tensor Core or AMD
matrix instructions cannot simply be copied into a portable kernel.

| Primary source | Applicable idea and limitation |
| --- | --- |
| [CUTLASS GEMM](https://docs.nvidia.com/cutlass/latest/media/docs/cpp/efficient_gemm.html), [rocWMMA](https://rocm.docs.amd.com/projects/rocWMMA/en/latest/conceptual/programmers-guide.html) | Hierarchical tiles, register/shared-memory reuse and pipelining. Tile sizes and occupancy must be retuned for the mobile GPU; specialized matrix instructions are not portable. |
| [vLLM CUDA Graphs](https://docs.vllm.ai/en/latest/design/cuda_graphs/), [SGLang compilation/fusion](https://www.lmsys.org/blog/2024-09-04-sglang-v0-3/) | Shape-specific execution reuse and fused small operations. Command reuse was tested above; ordinary prefix caches assume an attention contract this checkpoint does not have. |
| [ML Drift](https://arxiv.org/html/2505.00232v1), [LiteRT-LM](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/) | Mobile-specific buffer/image layout, weight preparation, fusion and separate prefill/decode kernels. Integer activation quantization is relevant; speculative/multiple-token generation is not, since this task generates no text tokens. |
| [ExecuTorch Vulkan discussion](https://github.com/pytorch/executorch/issues/12920#issuecomment-3129861418) | Reuse shader/dispatch state and overlap CPU encoding with GPU execution. Existing native Vulkan code already contains early-submit logic; avoid counting it as a new optimization. |
| [Transformer-Lite](https://arxiv.org/abs/2403.20041) | Dynamic shapes, operation priority and low-overhead quantization. Padding every short input to a large fixed maximum would waste compute. Quantization changes require numeric and accuracy validation. |
| [Qualcomm ML SDK](https://www.qualcomm.com/developer/blog/2021/05/accelerate-your-models-our-opencl-ml-sdk), [Adreno architecture](https://www.qualcomm.com/processors/adreno) | Vendor GEMM and memory-layout specialization warrant investigation. An advertised extension does not establish d1 operator coverage or a speedup; no SDK/NPU conversion was validated here. |

Published multipliers concern other models, runtimes and hardware. None is a
measured Henyo speedup. The lazy-transpose patch is suitable to retain as a small
experimental optimization. A several-fold reduction still requires substantially
less matrix work, a better measured GEMM implementation, or avoiding a repeated
decision altogether; this study does not claim that objective was achieved.
