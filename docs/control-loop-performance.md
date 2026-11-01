# Control-loop measurements

`/v1/debug/performance` and its `/reset` endpoint are local-only. The `phases`
object exposes count, retained sample count, and p50/p95 in microseconds for
request queueing, target resolution, tree traversal, native gesture submission
and completion, screenshot acquisition, bitmap copy, PNG encoding, and accepted
cue presentation. Each phase keeps at most 256 recent durations and no request
IDs, target identifiers, UI text, coordinates, or image data. Reset clears the
sample window. Concurrent background observations can contribute samples;
phase percentiles are independent distributions and must not be added together.
Acquisition includes callback executor queueing; presentation measures work on
the main thread, excluding its queue delay.

Cursor travel and caption attachment are asynchronous. Only accepted native
inputs schedule a cursor cue, guarded against stale tasks and user stop. Cursor
travel continues smoothly if native input finishes before the cue arrives.

Coordinate gestures wait for Android's completion callback before another
serialized operation can submit a gesture. This prevents fast consecutive calls
from cancelling one another. A cancellation, missing callback, interruption,
or loss of authorization after submission has an unknown effect and must not
be replayed automatically. Waiting polls current authorization, ownership and
request lifetime every 16ms; it never holds the ownership lock. A callback is
allowed the gesture duration plus a bounded one-second margin. Completion of
a native gesture still does not establish an application postcondition.

All sampled swipe points use one fresh window/root inspection per validation
pass. Initial coordinate validation and the final dispatch validation remain
separate; a moved/resized, missing, replaced, or occluded target is rejected.
`scroll-until` waits for native completion and only the remaining configured
interval, rather than waiting the gesture duration again. Rejected scrolls end
the operation instead of silently proceeding to another mutation.

## Reproduce

Open Henyo's own settings screen with its first two headings visible, ensure
control is available, and use the same local connection, capture mode, sample
count, and observation setting for both builds:

```sh
python scripts/benchmark-control-loop.py \
  --ws-url '<CONTROL_WS_URL>' --http-url '<LOCAL_HTTP_URL>' \
  --samples 24 --output artifacts/control-loop.json
```

The script checks the public headings are inert and targets only Henyo. It
measures tree, exact search, alternating heading taps, and display capture.
Four warm-up requests are excluded from end-to-end quantiles. Capture requests
are spaced by 450ms outside measured duration. Phase counters include warm-up
and concurrent background work. It records failures alongside timing and checks
that input stays in Henyo and completion releases control. Keep raw output
ignored; compare sanitized aggregates, never raw device evidence.

This measures the bridge's contribution. Network distance, provider inference,
agent decisions, and application postconditions require separate end-to-end
measurements. A faster tap does not imply the same speedup for a full task.

## Local measured outcome

One unpaired run on the same device, connection and Frame glow setting gave the
following millisecond measurements. Baseline used 12 measured requests per
workload; the final build used 24, both after four warm-up requests. Every
measured request succeeded and the target remained Henyo. These small samples
are descriptive, particularly at p95.

| Workload | Baseline p50 / p95 | Final p50 / p95 |
| --- | ---: | ---: |
| Tree | 42 / 95 | 25 / 64 |
| Exact search | 25 / 32 | 17 / 59 |
| Alternating inert taps | 241 / 269 | 86 / 99 |
| Display capture | 231 / 252 | 201 / 651 |

An independent 24-tap baseline also had p50 242ms and p95 273ms. The final tap
median is approximately 2.8 times faster while waiting for actual native
completion. This is not a full-agent speedup. Capture and search tails remain
variable; the capture tail increased in this run, so no uniform capture latency
improvement is claimed.

Final phase samples (including warm-up/background work) show:

| Phase / workload | p50 / p95, ms |
| --- | ---: |
| Tree traversal / tree | 1.17 / 1.95 |
| Target resolution / taps | 3.61 / 12.91 |
| Native submission / taps | 0.68 / 1.15 |
| Native completion / taps | 51.88 / 57.59 |
| Accepted cue work / taps | 0.07 / 0.10 |
| Screenshot acquisition / capture | 8.90 / 12.27 |
| Bitmap copy / capture | 21.68 / 27.43 |
| PNG encoding / capture | 71.47 / 87.12 |

The unloaded request queue was below 0.01ms at both percentiles. Background
observations make these phase sample counts differ from request counts. Rare
end-to-end capture delays are not characterized by adding these percentiles;
correlated trace work would be needed to attribute them.

A device stop check during a serialized UI wait bypassed the operation queue in
66ms and rejected further input. Revoking a synthetic client during a five-second
native gesture ended the client/server wait in 27ms, with an unknown outcome;
the server's completion wait was 16ms. These checks do not establish cancellation
of an already submitted Android gesture. Model tests separately cover native
completion, cancellation, callback loss, interruption by stop, stale cue guards,
and continuous cursor motion on early acceptance. Device evidence remains
ignored and untracked.

Twelve measured progress → fresh tree → completion loops after four warm-ups
had p50 155ms, p95 219ms, and zero failures; every loop released ownership. This
is a final-build correctness/stress measurement, not a before/after comparison.
