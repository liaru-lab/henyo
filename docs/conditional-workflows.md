# Experimental conditional workflows

`workflow.run` executes a small, predefined fragment in one WebSocket request. It is opt-in and does not replace agent reasoning. Discover `conditionalWorkflows` and the operation metadata before use. The CLI accepts `henyo workflow run FILE`; the helper allocates a task ID like other mutations. Complete the overall task explicitly afterwards.

A plan has `target` (`package`, `windowId`, `displayId`), `steps`, a mandatory exact-selector `verify`, and optional `budgetMs`. Selectors contain `text`, optional `field` (`text`, `desc`, `viewId`), and optional `exact: true`. Use a freshly observed target. This prototype requires the same foreground window throughout; it cannot follow a recreated window or another app.

```json
{
  "target": {"package": "com.example.search", "windowId": 7, "displayId": 0},
  "steps": [
    {"if": {"text": "Search"}, "then": [
      {"op": "ui.click", "params": {"text": "Search"}}
    ]},
    {"wait": {"text": "Example Notes"}, "timeoutMs": 1500},
    {"op": "ui.click", "params": {"text": "Example Notes"}},
    {"wait": {"text": "Opened Example Notes"}, "timeoutMs": 1000}
  ],
  "verify": {"text": "Opened Example Notes"},
  "budgetMs": 5000
}
```

`if` optionally takes `else`; `require` takes a selector and stops unless it is present. `wait` performs read-only polling. Actions are limited to exact-selector `ui.click`, `ui.setText` (also `value`), and `ui.scroll` (`direction` and optional `duration`). No scripts, app switching, global keys, nested workflows, or workflows inside batches are supported.

Limits: 32 KiB plan, 16 steps including all branches and final verification, two branch levels, five seconds overall, two seconds per wait, 256 code points per selector, 1024 code points per text value, and 1000 ms per scroll. Native calls retain their existing bounds and permissions. The earlier caller deadline also applies.

A predicate must have exactly one visible, actionable match in a complete bounded traversal. Duplicate matches, inaccessible children, traversal limits, changing observations, or occlusion are unknown, never absence. Branch guards are checked again before the first mutation in that branch; every selector action gets a fresh uniqueness probe. There remains a race between accessibility observation and native dispatch: this is not an atomic UI transaction. Verify meaningful application outcomes afterwards.

Ownership, original admission ticket, token scopes, deadline and exact target are revalidated throughout and immediately before native submission. Stop or disconnect prevents subsequent dispatch. Resume does not renew an old workflow. Already accepted Android actions cannot be rolled back or assumed cancelled.

The result contains `state`, `stepsVisited`, `actionsAccepted`, `effectsPossible`, and bounded per-step status records. States include `completed`, `needs_agent`, `stopped`, `deadline_exceeded`, and `failed`. Successful native acceptance alone does not prove completion: the final selector must be observed. An error frame retains the partial `result`; its aggregate `outcome` is `unknown` when an earlier action was accepted or an attempted action may have taken effect, otherwise `not_applied`. `actionsAccepted` records known acceptances only. Do not retry an entire failed workflow: inspect the report and fresh UI, then decide how to continue. No mutation is retried automatically.

## Evaluation

`examples/workflows/search.html` is a local synthetic search/navigation fixture with delayed results and action counters. Serve it on loopback, open a dedicated test tab, freshly resolve its target and compare individual find/click/wait/verify calls against one workflow. `?ambiguous=1` creates duplicate result labels; `?lang=ja` provides Japanese labels. Keep raw measurements and actual device targets under ignored `artifacts/`.

This endpoint remains experimental. Broader production adoption requires representative application tasks, failure-rate measurements and evidence beyond this controlled fixture.

### Initial device results

On the synthetic local page, 12 measured runs after two warmups completed with no duplicate actions in each mode. Individual calls used 12 requests per task: median 589 ms, p95 606 ms. The workflow used one request: median 650 ms, p95 697 ms. Both include the fixture's 350 ms asynchronous result delay. Reset, target discovery and counter inspection are outside the timed interval. The original task waits explicitly for both result and completion.

This is a reduction in request count, not a local latency win: additional conservative tree probes cost about 61 ms at the median here. Eleven avoided round trips could offset that at roughly 5.6 ms additional round-trip overhead each, but this is an estimate, not a remote-network or agent-model benchmark. Keep the endpoint opt-in and experimental; these results do not support making it the default. No external agent inference was measured.

Native checks rejected malformed/oversized-limit plans, prevented dispatch after deadline and an incorrect window pin, and stopped a pending wait with zero subsequent actions. Measured UI Stop to workflow return was 126 ms including input-command overhead. Resume did not restart the old fragment. Model checks additionally cover stale tickets, branch changes, incomplete/duplicate observations, partial failures and resource recycling.

A separate immediately-after-launch run stopped conservatively after the first accepted action when the next predicate became uncertain during accessibility updates; the partial report was retained and no result click followed. A later settled-state run completed. The warm-run success rate above excludes that startup case. This sensitivity is another reason to retain the experimental label. A duplicate-label native test likewise accepted only the initial search, then returned a bounded wait timeout without opening either ambiguous result.

Reproduce the timing comparison with `python scripts/benchmark-workflows.py --ws-url WS_URL --package BROWSER_PACKAGE --output artifacts/workflow-benchmark.json` while the dedicated English fixture is foreground. The script resets only that synthetic page, verifies action counters and releases control. Never point it at an ordinary user page.
