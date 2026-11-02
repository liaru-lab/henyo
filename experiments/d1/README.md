# d1-2B on-device evaluation

For the shipped optional APK feature, see
[On-device assistance](../../docs/on-device-assistance.md). The CPU/OpenCL
measurements below describe the earlier standalone experiments.

For the consolidated opt-in NPU execution candidate, see
[Native runtime integration](NATIVE.md). The earlier paired-kernel experiment
and necessary-condition checks are described in
[Hexagon execution and readiness](HEXAGON.md).

This is a standalone experiment, outside the APK and production control path. It evaluates whether CPU or OpenCL GPU decision inference can improve a Henyo control loop while keeping observations local. The runner uses only generated English/Japanese fixtures and never sends UI events.

See [Android kernels and reuse](KERNELS.md) for the follow-up patch, fixed-input
comparison harness, KV-cache constraints and measured alternatives to the current
OpenCL execution path.

## Reproducible configuration

- Runtime: [llama.cpp b11960](https://github.com/ggml-org/llama.cpp/tree/8642541b255bfb5dc39cd351a178be09013a55d6), commit `8642541b255bfb5dc39cd351a178be09013a55d6`.
- Model: [LiquidAI/d1-2B-GGUF](https://huggingface.co/LiquidAI/d1-2B-GGUF/tree/e968f6575f0d4fad928312d7af15cad90fd365c0), revision `e968f6575f0d4fad928312d7af15cad90fd365c0`, file `d1-2B-Q4_K_M.gguf`.
- Model file: 1,099,629,888 bytes; SHA-256 `adca5789ad30ae6027e8e936749bb9f19673097492ab26fa1c5b69643ca50b4b`.
- Native Android arm64 CPU backend with dot-product and i8mm support; OpenMP disabled. The GPU follow-up enables the Adreno OpenCL backend. Image projectors and APK integration are outside this evaluation.
- Context 2048; batch and microbatch 1024; one slot; two or four CPU threads; warmup disabled; loopback-only listener with web UI disabled.

The pinned runtime serves the native decision API at `/v1/systemone`, with `choice`, `noul` and `score`. All three were smoke-tested. It is not a chat-completion model. Extraction, multiple-choice multi-answer outputs and bounding-box tasks are not part of this tested runtime subset. Image/video paths were not evaluated. Read the [pinned server documentation](https://github.com/ggml-org/llama.cpp/blob/8642541b255bfb5dc39cd351a178be09013a55d6/tools/server/README.md#post-v1systemone-typesafe-compatible-system-one-api) and [model card](https://huggingface.co/LiquidAI/d1-2B) before extending the experiment. Server/GPU latency claims do not describe these Android CPU measurements.

Build the pinned runtime in an ignored directory with CMake, a C++ compiler and matching C++ runtime:

```sh
cmake -S RUNTIME_SOURCE -B RUNTIME_BUILD -DCMAKE_BUILD_TYPE=Release \
  -DGGML_OPENMP=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_OPENSSL=OFF
cmake --build RUNTIME_BUILD -j2 --target llama-server
python experiments/d1/verify.py
python experiments/d1/evaluate.py --server-bin RUNTIME_BUILD/bin/llama-server \
  --model MODEL_FILE --threads 2 --output artifacts/d1-two-threads.json
```

Use a separately downloaded model matching the pin; the runner checks its size and digest before launch. The runtime source revision must be checked independently. Run sequentially with `--threads 4` for comparison. `--case-limit` bounds a smoke run; use all 48 cases for the evaluation. Hash verification warms the file cache, so process startup must not be described as storage-cold loading. Run performance trials without compilation or other heavy work.

## Evaluation contract

The generated cases include English and Japanese search, loading, unique results, completion, unavailable trees, stale state, duplicate labels, disabled results, wrong applications, login, destructive confirmation and missing results. Expected answers are never included in the model prompt. The prompt and criteria are fixed in `evaluate.py`. The deterministic baseline recognizes this closed fixture vocabulary; its accuracy does not imply general app understanding.

The confidence gate was selected before the full run: top probability at least 0.85 and a margin of at least 0.20. These scores are uncalibrated and do not establish safety. Even a confident decision cannot override freshness, permissions, unique target checks or user Stop. `needs_agent` is an abstention rather than an operation. Track raw accuracy separately from accepted accuracy and actionable coverage; an accepted `needs_agent` label is still an abstention.

The worker is owned by the runner, isolated from the app and bound to loopback. Requests are at most 32 KiB, responses 64 KiB, whole-exchange request timeout 12 seconds, startup timeout 20 seconds. A request failure terminates the owned worker; it never retries a mutation or falls back to a cloud API. Shutdown allows one second for termination and then kills only that owned process, with a bounded wait. Raw output, local endpoints, paths and measurements belong under ignored `artifacts/`. The public experiment contains no weights, binaries or device captures. HTTP proxy settings are bypassed for local inference.

No real user observations, credentials or app contents were sent to the model. A local model protects against off-device transmission by this experiment; it does not replace Android access controls or make a localhost service inaccessible to other local processes.

## GPU follow-up: current findings

The OpenCL GPU run changes the latency conclusion: the same-device matched workload is about **9.04 times faster** than the CPU run. Keep GPU-backed d1 as an experimental candidate. Classification quality still needs work before enabling model-driven production control: the full workload retains four confident incorrect action recommendations. This worker remains outside the APK.

Both matched runs used the same pinned runtime source/model, four CPU threads, one slot, fixed prompt/criteria, six English/Japanese cases and two read-only warmups. Runs were sequential with compilation finished. The CPU run explicitly selected `--device none --gpu-layers 0`; the GPU run explicitly selected the enumerated OpenCL device and requested full layer offload.

| Matched workload | Measured samples | Median | Empirical p95 |
| --- | ---: | ---: | ---: |
| CPU | 6 | 4.499 s | 5.050 s |
| OpenCL hardware GPU | 6 | 0.498 s | 0.560 s |

These six samples are a small balanced comparison, not a reliable tail estimate. The separate GPU 48-sample workload after two warmups had median **0.491 s**, p95 **0.585 s**, with no request failures. It used the unchanged 38-distinct-state evaluation set. No external agent inference or whole-agent speedup was measured.

GPU use was demonstrated by hardware OpenCL enumeration, runtime model-buffer allocation, **25/25 offloaded layers**, and a separate successful request with backend scheduling diagnostics. That request assigned 154 matrix operations and 13 convolution operations to OpenCL; two embedding/index `GET_ROWS` operations stayed on CPU. Layer-offload count alone does not mean every operation ran on GPU. Diagnostic logging was disabled for the timing runs.

Compared with the prior 48-case CPU run, all **48 top-choice labels agree**, giving **39/48 correct** in both. GPU confidence values are not identical: the largest absolute confidence difference was **0.0601**, and one correct Japanese completion case fell below the preselected 0.85 gate. GPU results therefore had **27 gate-passing predictions, 23 correct**, with the same **four confident incorrect action recommendations**. Recalibrate on the intended backend; a CPU confidence threshold is not a safety guarantee on GPU. Native permissions, target validation, uniqueness and Stop remain independent requirements.

First initialization with the experiment's empty OpenCL program-cache directory took **53.797 s** to readiness. Its first diagnostic request took **4.906 s**; tracing and first-use preparation make that unsuitable for a warm timing comparison. Two subsequent cached GPU starts took **7.459 s** and **6.855 s**. These are single observations, not cold-start percentile estimates. OS and driver-internal caches were uncontrolled, and model digest verification warms file cache. Model startup and program preparation are outside the measured request times. Reusing a persistent worker is necessary to amortize these costs.

After the full GPU workload the process reported about **367 MiB RSS**, with **533 MiB high-water RSS**. The backend separately reported **1040.90 MiB GPU model buffers** and **126.01 MiB GPU compute buffers**, plus CPU-side model/compute/output buffers. Process RSS is not total GPU memory, and these figures must not be blindly summed on unified-memory hardware. Battery temperature samples around GPU evaluation/stress work went from **30.5 to 32.8 °C**, externally powered; the sampled system thermal status was normal. There was no joule measurement or continuous thermal/frequency trace.

During a separate pending GPU warmup request, twelve measured inert Henyo taps had median/p95 **67/95 ms**, and UI Stop was acknowledged in **69 ms** including input-command overhead. Further input was rejected until Resume. The read-only wait ended later by its own timeout. This is a small responsiveness check, not a scheduling guarantee; it is separate from the clean timing comparison. The experimental model worker and Henyo control cancellation are separate paths.

Sending SIGTERM to the owning runner during a pending GPU request marked the run `cancelled`, stopped/reaped its GPU child in **816 ms**, and completed owner cleanup in **909 ms**. The owner did not consume or replay a late classification. Graceful shutdown can allow in-flight GPU work to drain; callers must discard answers after cancellation. The one-second grace period and forced-termination fallback remain bounded. All evaluation workers were stopped afterwards.

## Reproducing GPU execution

Install `opencl-headers`, `opencl-clhpp`, `ocl-icd`, `opencl-vendor-driver`, `clinfo`, `patchelf` and LLVM tooling in Termux, then build the same pinned source with the earlier CPU build options plus `-DGGML_OPENCL=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON` in a separate GPU build directory. The measured run used source kernels, without the optional vendor precompiled-kernel library. See the [pinned OpenCL backend documentation](https://github.com/ggml-org/llama.cpp/blob/8642541b255bfb5dc39cd351a178be09013a55d6/docs/backend/OPENCL.md).

Some vendor loaders dynamically open a separate implementation that is not listed among their ELF dependencies. In this environment the default loader registration enumerated zero platforms. Local copies of the loader, its separately loaded implementation and required non-system dependencies restored hardware GPU enumeration. `prepare_opencl.py` uses the local-copy/runpath approach of the [official Termux vendor-driver installer](https://github.com/termux/termux-packages/blob/master/packages/opencl-vendor-driver/postinst.sh), with explicit additional implementation files. It patches only fresh copies under ignored `artifacts/`, rejects a nonempty output directory, and leaves system/package libraries intact. Vendor binaries are never committed or redistributed.

For a vendor with this layout:

```sh
python experiments/d1/prepare_opencl.py \
  --implementation /vendor/lib64/libOpenCL_adreno.so \
  --output artifacts/d1-local-driver
```

The implementation filename is vendor-dependent; omit or change it as appropriate. Set `LD_LIBRARY_PATH` to the resulting **absolute** local-driver directory for the following commands. Verify with `clinfo` that the selected device is hardware GPU; a CPU OpenCL device or software renderer is not a GPU measurement. Use `llama-server --list-devices` from the GPU build to obtain the exact device ID.

```sh
GPU_BUILD/bin/llama-server --list-devices
python experiments/d1/evaluate.py --server-bin GPU_BUILD/bin/llama-server \
  --model MODEL_FILE --threads 4 --backend opencl --device DEVICE_ID \
  --startup-timeout 180 --request-timeout 120 --case-limit 1 \
  --diagnostic-schedule --output artifacts/d1-gpu-diagnostic.json
```

Set `GGML_OPENCL_KERNEL_CACHE_DIR` to an ignored directory to retain the program cache. The initial diagnostic has explicitly larger bounded startup/request budgets for first-use setup. It is not included in timing statistics. The runner rejects GPU runs without an explicit device or positive runtime layer-offload evidence. Inspect the diagnostic graph assignments to identify CPU fallbacks.

Run the matched six-case set twice with the **same GPU-capable binary**, first `--backend cpu`, then `--backend opencl --device DEVICE_ID`, using `--threads 4 --warmup 2` and:

```sh
--case-ids en-wait-0 en-open_result-0 en-finish-0 ja-wait-0 ja-open_result-0 ja-finish-0
```

Use distinct ignored outputs. For GPU runs allow `--startup-timeout 180`; the normal per-request budget remains 12 seconds. Omit `--case-ids` for all 48 cases. Never use diagnostic scheduling logs in timing comparisons. `--warmup` records up to four read-only warmups separately. Explicit startup/request bounds are limited to 180/120 seconds; defaults remain 20/12 seconds. SIGINT/SIGTERM records cancellation and always cleans up the owned worker.


## Historical CPU-only findings

**Historical CPU-only decision: reject the measured CPU configuration as a production control fast path.** Keep deterministic rules and bounded bridge operations for known UI states. The measured local model is much slower for these decisions, and confidence gating still permits incorrect recommendations. This verdict concerns this CPU runtime, quantization, prompt and workload; it does not establish the limits of other hardware, prompts, fine-tuning or future runtimes.

The fixed workload has 48 samples (38 distinct generated states), 24 in each language. Some variants intentionally repeat the same state. Expected labels are not model inputs. The two-thread process answered every request without timeout. The workload median was **7.537 s**, p95 **9.543 s**; English median/p95 7.525/9.554 s, Japanese 7.567/9.537 s. These are a mix of prompt lengths and decisions rather than repeated timing of one prompt. An occasional UI interference check ran during the matrix; no compilation ran concurrently.

| Classification measure | Result |
| --- | --- |
| Raw top-choice accuracy | 39/48 (81.3%) |
| English / Japanese correct | 21/24 / 18/24 |
| Predictions passing the preselected confidence gate | 28/48 |
| Correct among gate-passing predictions | 24/28 (85.7%) |
| High-confidence incorrect action recommendations | 4 |
| Effective correct decisions when rejected predictions become `needs_agent` | 34/48 |
| Gate-passing actionable predictions / correct actionable predictions | 12 / 8 |
| Correct accepted actions among 16 legitimate action states | 8/16 |

The four confident failures recommended opening a result despite stale state or duplicate matches, in both languages. They were classifications only; no device action was executed from this matrix. The fixed-vocabulary rules returned all 48 expected labels, with median 0.0025 ms and p95 0.0075 ms for classification alone. This is a closed synthetic baseline, not evidence that rules generalize to arbitrary apps.

On the actual local search/navigation fixture, three independent rules-based loops completed with two actions each, median **633 ms** including delayed results, fresh observations and verification. Three model-gated attempts returned `needs_agent` before any mutation, median **4.671 s** to abstention; none completed. All three proposed `search` with confidence 0.479, below the threshold. Reset/settling and counter inspection were outside the timer; the timed rules loop included observing the final completed label, followed by an additional structural completion check outside the timer. A completed-task latency or model speedup cannot be calculated from those attempts. Each model decision would have required another fresh target/state check and a deterministic fixture guard before dispatch. Only known synthetic body labels were normalized into model input; browser chrome and actual target identities were excluded.

The existing agent-path comparison measures bridge operations with deterministic decisions. External agent planning or cloud inference was not measured; there were no paid API calls or cloud fallback. These results do not compare vendors or establish whole-agent speedup.

Model file size is about **1.02 GiB**. The long-lived two-thread process had about **1.09 GiB RSS** after the matrix and a **2.11 GiB high-water RSS** including loading; file size is not runtime memory. Idle RSS was much lower before the matrix, so idle RSS alone would be misleading. A separately started four-thread process measured about **2.14 GiB RSS/high-water RSS** after its shorter subset; the process lifetimes and residency differ, so this does not establish that thread count doubles memory. Battery temperature increased from **28.2 to 29.5 °C** during the matrix while externally powered; sampled system thermal status was normal. No joule or power-draw measurement was available, so there is no energy-efficiency conclusion.

While inference consumed CPU, twelve measured inert taps had median **71 ms**, p95 **101 ms**, and UI Stop was acknowledged in **69 ms**, including input-command overhead. New native input was rejected while stopped. The existing read-only wait ended later by its own timeout; 69 ms is stop acknowledgement, not cancellation of every pending read. This small interference check showed responsive control on this setup, not a general scheduling guarantee.

An in-flight inference was interrupted by terminating the owned worker, then forcing termination after the one-second grace period: shutdown took **1.057 s**, the HTTP request ended without a result, and the process stopped. No mutation or model request was replayed. Model-free tests also exercise bounded forced shutdown, whole-exchange deadlines, request/response limits, fixture labels and the confidence gate.

Five additional fresh two-thread process trials used the same English loading-state question. Startup to healthy had median **2.764 s**, p95 **2.765 s**; the first request after readiness had median **7.284 s**, p95 **7.580 s**. Idle process shutdown had median/p95 **415/415 ms**. These are process-cold starts with warm/uncontrolled OS file cache, not storage-cold measurements. Five trials are too few for a reliable tail estimate; their empirical p95 is the maximum observation. Reproduce with five separate `evaluate.py --case-limit 1` invocations and distinct ignored outputs. The 48-sample workload above describes the long-lived process, including its first measured request.

A matched six-case English/Japanese subset also ran with four threads. Excluding its first request, the five corresponding long-lived-process samples had median/p95 **8.602/9.358 s** with two threads and **4.656/5.060 s** with four threads, about 1.85 times faster at the median. All six four-thread classifications were correct and passed the gate, but this subset contains loading, unique-result and completion cases, not the difficult stale/duplicate cases. It is not a repeat of the full accuracy evaluation. Faster inference here still does not approach the fixed-rule path or establish safe action decisions. The five-sample p95 is the empirical maximum and should not be generalized.

Reproduce the matched subset with:

```sh
python experiments/d1/evaluate.py --server-bin RUNTIME_BUILD/bin/llama-server \
  --model MODEL_FILE --threads 4 --output artifacts/d1-four-threads.json \
  --case-ids en-wait-0 en-open_result-0 en-finish-0 ja-wait-0 ja-open_result-0 ja-finish-0
```

The owned workers were stopped after evaluation. No model/runtime is bundled into Henyo, no automatic model-driven control was enabled, and no release is required for this experiment. Further research would need calibrated abstention and independent policy/target checks before any action, plus new hardware measurements and a genuine end-to-end agent comparison.
