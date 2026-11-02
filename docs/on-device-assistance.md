# Optional on-device assistance

Henyo can run the pinned d1-2B decision model locally for short semantic checks.
Enable **On-device assistance** in the Android settings screen, explicitly
**Download model**, then wait for **Ready — on-device NPU**. The model is about
1.02 GiB and is downloaded separately from its pinned upstream revision. Turning
assistance off stops the worker and invalidates pending model results. Existing
bridge operations do not require the model or the setting.

The shipped accelerator path is currently Hexagon v79 on Android arm64. Other
backends are unverified. Unsupported or absent runtimes report unavailable;
there is no automatic CPU or cloud inference fallback. Unsupported CPU ABIs
retain the Java control bridge and report that the assistance runtime is absent.
The first preparation verifies model/runtime hashes and loads the model. Reuse
one worker across decisions; idle workers are released and can be prepared again
from settings. Global Stop also releases the worker. Resume does not replay a
plan or restart a pending decision.

## Discover and evaluate

```sh
bin/henyo version
bin/henyo assistance status
bin/henyo assistance prepare
bin/henyo assistance evaluate artifacts/decision.json
```

Require the `onDeviceAssistance` capability and inspect the current status before
using it. The setting belongs to the device user; clients cannot toggle it or
start a download through the control API. `assistance.status` returns `enabled`,
`state`, `runtimeAvailable`, `modelAvailable`, model/download byte counts,
`startupMs`, `lastApiMs` and a bounded error code. States are `disabled`,
`missing_model`, `downloading`, `preparing`, `ready`, `idle` and `unavailable`.
`runtimeAvailable` means native files are packaged, not that the accelerator has
successfully initialized. Only `ready` represents successful model placement.

When assistance is enabled and a model is stored, `assistance.prepare` can
request asynchronous preparation after idle release. It returns the current
status; wait boundedly for `ready` before submitting work. It cannot enable
the setting, download a model, override Stop or replay an old operation.

`assistance.evaluate` is a read-only WS operation. Its parameters are the native
single-question request: a JSON object `state` and exactly one question in
`questions`, of type `noul`, `choice` or `score`. Request limit is 32 KiB, context
2048 tokens, and inference has a 30-second ceiling plus the caller's earlier
request deadline. JSON nesting is limited to 16 levels and native responses to
64 KiB. Each connection permits eight pending operations plus one running
operation; overflow reports `session_busy`. One evaluation runs at a time. Raw `answers` are
returned without dispatching any UI action. Scores are uncalibrated; an answer
cannot establish current target identity, permissions or completion.

Invalid requests return `assistance_invalid`; inputs beyond the native context
or physical-batch budget return `assistance_context_limit`. These input errors
preserve a healthy worker. Reduce the observation instead of repeating the same
oversized request or restarting the model.

Model initialization happens off the UI thread. Cancellation/ownership/deadline
checks run throughout the exchange; stopped, disabled or late results are not
authority for another action. The model worker is authenticated and loopback
only. UI content supplied to inference stays on the device; model download
accesses the upstream host. Native logs remain in app-private storage.

Connection readers remain responsive during queued operations and inference.
Close/EOF cancels that connection's pending work, including when another
connection for the same client remains open. Disabling assistance invalidates
its pending decisions; an already submitted Android action may still finish.
Worker shutdown is reaped in the background before another worker starts.

## Agent-defined fragments

Use the existing bounded `workflow.run` operation when a teacher agent knows a
short sequence and can describe a candidate selector, a local question and the
final outcome. Add a `semantic` step after deterministic waits/requirements:

```json
{
  "target": {"package": "com.example.search", "windowId": 7, "displayId": 0},
  "steps": [
    {"op": "ui.click", "params": {"text": "Search"}},
    {"wait": {"text": "Example Notes"}, "timeoutMs": 1500},
    {"semantic": {
      "candidate": {"text": "Example Notes", "field": "text", "exact": true},
      "question": "Does this candidate name the Example Notes application itself rather than a support page or an advertisement?"
    }},
    {"op": "ui.click", "params": {"text": "Example Notes"}},
    {"wait": {"text": "Opened Example Notes"}, "timeoutMs": 1000}
  ],
  "verify": {"text": "Opened Example Notes"},
  "budgetMs": 5000
}
```

The question has at most 512 Unicode code points. The candidate uses the existing
exact-selector schema, including `viewId` for known controls with dynamic text.
The server resolves one visible enabled candidate in a complete bounded tree,
checks its actionable region and enabled/clickable ancestry, and extracts only
that node, up to eight direct children and up to eight ancestors. Text fields
are capped at 128 code points each. Oversized, incomplete or changing context
stops the fragment; it is not silently treated as a successful match. Ask only
questions answerable from this local context. Longer/global reasoning belongs
to the teacher agent.

A `noul` probability of at least 0.85 permits continuation; at most 0.15 reports
`workflow_semantic_mismatch`, otherwise `workflow_semantic_uncertain`. This
threshold does not guarantee correctness. Before the next planned mutation,
Henyo re-captures and compares that context, then performs the usual target,
uniqueness, permission, ownership and deadline checks. Context changes return
`workflow_semantic_changed`. This is not an atomic UI transaction. Verify the
actual app outcome afterwards. The semantic guard is consumed by the next
accepted action; add another check if a later action requires a new judgment.

All existing limits still apply: one foreground window, 16 steps, two branch
levels, five seconds total and two seconds per wait. A cold worker should be
prepared before submitting a fragment. Waits perform bounded read-only polling;
no model is called on every loading poll or repeatedly for an unchanged state.
The model is evaluated only at the explicit semantic step.

## Failure and teacher correction

Retain the partial `workflow.run` result: `state`, `code`, per-step records,
`actionsAccepted` and `effectsPossible`. Disabled/unprepared assistance and
semantic uncertainty return control without automatically retrying a mutation.
Stop, target changes and deadlines keep their existing control outcomes.

The teacher should observe fresh UI, determine which effects already occurred,
finish or correct the remaining work manually, and compare that trace with the
original plan. Distinguish a loading delay, selector/context mismatch, semantic
misclassification and failed final verification. Revise the relevant wait,
selector, question or completion predicate before reusing the fragment. Three
successes alone do not prove a general rule. Re-resolve target and selector
identities on every run, and retain failure cases when evaluating a recipe.

Reusable recipes and teacher comparisons can be retained by the agent in its
session or explicitly managed local artifacts. This release does not train model
weights, automatically promote recipes or persist captured UI as a learning
corpus. Do not advertise those capabilities as implemented.

## Native build and measurement

Build the validated source path using [NATIVE.md](../experiments/d1/NATIVE.md),
then prepare fresh APK runtime files:

```sh
python scripts/prepare-d1-android.py --bundle VALIDATED_BUNDLE \
  --source PATCHED_PINNED_SOURCE --output artifacts/d1-android-runtime
HENYO_D1_ANDROID_RUNTIME=ABSOLUTE_ANDROID_RUNTIME scripts/build-release.sh VERSION CODE
```

The packager builds the actual upstream CPU/dynamic backend registry, excludes
unused OpenCL/RPC/stock-Hexagon registration dependencies, adjusts runpaths on
fresh copies and strips debug information. The optimized graph/host/DSP kernels
are retained. Model weights and SDK inputs remain outside Git. Licenses and the
native manifest are included in APK assets. Without an explicit runtime bundle,
contributor builds retain the normal bridge and report assistance unavailable;
the public release entrypoint requires the prepared runtime.

Record startup separately from warm inference and UI-task time. A twofold native
API improvement is not a twofold complete-agent speedup. Measure fresh target
capture, model evaluation, revalidation, action and application wait separately;
keep device-specific evidence under ignored `artifacts/`.
