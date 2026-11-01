# d1-2B on-device evaluation

This is a standalone experiment, outside the APK and production control path. It evaluates whether native decision inference can improve a Henyo control loop while keeping observations local. The runner uses only generated English/Japanese fixtures and never sends UI events.

## Reproducible configuration

- Runtime: [llama.cpp b11960](https://github.com/ggml-org/llama.cpp/tree/8642541b255bfb5dc39cd351a178be09013a55d6), commit `8642541b255bfb5dc39cd351a178be09013a55d6`.
- Model: [LiquidAI/d1-2B-GGUF](https://huggingface.co/LiquidAI/d1-2B-GGUF/tree/e968f6575f0d4fad928312d7af15cad90fd365c0), revision `e968f6575f0d4fad928312d7af15cad90fd365c0`, file `d1-2B-Q4_K_M.gguf`.
- Model file: 1,099,629,888 bytes; SHA-256 `adca5789ad30ae6027e8e936749bb9f19673097492ab26fa1c5b69643ca50b4b`.
- Native Android arm64 CPU build with dot-product and i8mm support; OpenMP disabled. No GPU offload, image projector, or APK integration.
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

## Device findings and decision

**Reject this configuration as a production control fast path.** Keep deterministic rules and bounded bridge operations for known UI states. The measured local model is much slower for these decisions, and confidence gating still permits incorrect recommendations. This verdict concerns this CPU runtime, quantization, prompt and workload; it does not establish the limits of other hardware, prompts, fine-tuning or future runtimes.

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
