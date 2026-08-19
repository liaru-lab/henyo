# WebSocket Control Protocol

Henyo's primary agent control path is WebSocket. HTTP v1 remains available
during migration for health, pairing, token bootstrap, local debugging, and
compatibility, but new high-speed agent workflows should use WS.

## Endpoint And Auth

Endpoint:

```text
GET /v1/ws/control
```

The socket uses the same listener, source CIDR filtering, and Bearer token
model as authenticated HTTP control endpoints.

- Localhost may connect without a Bearer token for compatibility.
- Remote clients must come from an allowed CIDR and authenticate with
  `Authorization: Bearer <token>` during the HTTP Upgrade or by sending an
  `auth` frame before control calls.
- Unauthenticated sessions may only send `hello`, `auth`, `ping`, or `close`.
- Text frames are UTF-8 JSON. Binary frames are reserved for future screenshot
  transport and must be rejected until explicitly negotiated.
- Implementations must enforce bounded frame size and close malformed sessions.

### Handshake/Auth Expectations (Tasks 022/023)

- Upgrade request should include the standard WS headers and target `GET /v1/ws/control`.
- On successful WS negotiation, send `session.ready`; `requiresAuth` should be:
  - `false` for localhost sessions accepted without token.
  - `true` for sessions that still need auth.
- Remote callers must satisfy source-CIDR checks before control-level processing.
- If a valid bearer token is present in the upgrade request, the session should
  move to authenticated state immediately and may emit `session.authenticated`.
- For token-less localhost sessions, control may proceed after `session.ready`
  (`requiresAuth:false`), without sending `auth`.
- `termux.exec` is the exception: it always requires a non-revoked paired token
  whose local Android settings enable the `termux-command` capability, including
  on localhost. New tokens never receive this capability automatically.
- For token-less remote sessions, the first successful `auth` frame switches the
  session to authenticated; all other control operations should return
  `auth_required` until that succeeds.
- Expired/revoked/malformed auth should return `auth_invalid` or
  `auth_revoked` and keep the socket available only for auth retries when safe.

Tree payloads, screenshots, pairing PINs, pairing secrets, and raw Bearer tokens
must not be written to Henyo, helper, or CLI logs. Authenticated WS sessions
receive full, unredacted UI tree snapshots by default.

## Frame Envelope

Common fields:

```json
{"type":"call","id":"req-1","op":"app.current","params":{}}
```

- `type`: one of `hello`, `auth`, `call`, `batch`, `result`, `event`, `error`,
  `ping`, `pong`.
- `id`: client-supplied string for request/response correlation. Required for
  `auth`, `call`, `batch`, `ping`; echoed by `result`, `error`, and `pong`.
- `time`: optional ISO-8601 timestamp emitted by Henyo.
- `display`: optional, non-authoritative presentation metadata accepted on
  `call`, `batch`, and individual batch steps. Its current field is a short
  `summary` describing the caller's user-facing intent. Henyo sanitizes and
  bounds the text before displaying it, never derives it from sensitive
  operation params, and never echoes it in results or writes it to logs. Older
  clients may omit this object without changing operation behavior.

Task progress is a separate presentation-only operation contract. It never
changes another operation's parameters, authorization, result, or settling
behavior and is never derived from `display`, selectors, or input.

Errors:

```json
{"type":"error","id":"req-1","ok":false,"code":"auth_required","message":"Bearer token required"}
```

Standard codes include `bad_request`, `auth_required`, `auth_malformed`,
`auth_invalid`, `auth_revoked`, `source_not_allowed`, `op_unknown`,
`op_invalid`, `op_timeout`, `batch_timeout`, `accessibility_unavailable`, and
`internal_error`.

## Session Frames

Server greeting:

```json
{"type":"event","event":"session.ready","protocolVersion":1,"requiresAuth":true,"serviceEpoch":"service-epoch-id"}
```

`serviceEpoch` is present on the initial greeting and `hello` response so a
reconnecting client can discard observations from the previous service before
the first UI event arrives.

Protocol-v1 endpoints may add `contractRevision`, `platform`, and
`capabilities` to `session.ready`. Their omission remains valid for the legacy
Android greeting. The helper's `session.status` checkpoint accepts the
additive fields only as a complete validated tuple and never republishes
unknown capability data.
The iPhone greeting additionally carries `deviceReady`, bounded `deviceState`,
and optional bounded `operatorAction`; the helper validates these together and
clears them with the rest of the session checkpoint on disconnect. Legacy
Android may omit these lifecycle fields.

Client auth:

```json
{"type":"auth","id":"auth-1","scheme":"Bearer","token":"henyo_..."}
```

Successful auth:

```json
{"type":"result","id":"auth-1","ok":true,"result":{"authenticated":true}}
{"type":"event","event":"session.authenticated","tokenId":"..."}
```

Heartbeat:

```json
{"type":"ping","id":"ping-1"}
{"type":"pong","id":"ping-1"}
```

Closing:

```json
{"type":"event","event":"session.closing","reason":"service_stopping"}
```

An authenticated control session is also closed after 60 seconds without an
accepted `call` or `batch`. Before the normal WebSocket close frame, Henyo sends
`session.closing` with `reason:"idle_timeout"`. Protocol ping/pong frames,
JSON `ping`/`pong`, and `hello` keep their normal response behavior but do not
extend this control-idle deadline. Long-running accepted operations refresh the
deadline both before and after execution, so they are not treated as idle while
they run. Clients should discard cached observations on disconnect and reconnect
lazily when the next control request arrives.

Task progress belongs to the authenticated/local WebSocket session that last
set it. Henyo clears it when that owning session disconnects, including the
normal idle close. Neither Android nor the Python helper replays it on
reconnect. Progress calls use the same existing control-idle accounting as
every other accepted call; they do not change the 60-second deadline or
connection-indicator semantics.

## Calls

Call request:

```json
{"type":"call","id":"c1","op":"ui.click","params":{"selector":{"text":"Battery","exact":true,"field":"text"}}}
```

An agent may attach a brief explanation shown on the device before execution:

```json
{"type":"call","id":"c1","op":"ui.click","params":{"selector":{"text":"Battery"}},"display":{"summary":"設定を確認するため、バッテリー項目を開きます"}}
```

The summary is presentation-only: it cannot alter authorization, dispatch,
parameters, results, or settling behavior.

Call result:

```json
{"type":"result","id":"c1","ok":true,"result":{"ok":true,"strategy":"node"},"durationMs":42}
```

`timeoutMs` may be included on any call. If omitted, Henyo chooses an
operation-appropriate default. Timeouts return `op_timeout`.

## Task Progress Presentation

`task.progress.set` accepts an ordered structured plan snapshot:

```json
{"type":"call","id":"progress-1","op":"task.progress.set","params":{"goal":"3件の確認を完了する","steps":[{"text":"1件目を確認","status":"completed"},{"text":"2件目を確認","status":"in_progress"},{"text":"3件目を確認","status":"pending"}],"replan":false}}
```

`status` is exactly `pending`, `in_progress`, or `completed`. Henyo renders all
one-to-six steps in caller order. The first structured snapshot installs the
plan. Later calls with omitted/false `replan` are status-only updates: after
sanitization, `goal`, step count, text, and order must exactly match the visible
plan or the call fails with `op_invalid` and changes nothing. Set `replan:true`
only when intentionally replacing the goal, step text, count, or order.

Structured calls require a non-empty goal and cannot include `completed` or
`current`. Each string is sanitized and bounded to 72 Unicode code points. The
plan has a fixed presentation anchor independent of per-operation
`display.summary` captions.

For migration, the legacy complete snapshot remains accepted:

```json
{"type":"call","id":"progress-1","op":"task.progress.set","params":{"goal":"レビュー本文を異なる3件分取得できたら終了","completed":["1件目を取得"],"current":"2件目の本文を確認"}}
```

In this legacy form, `goal` and `current` are strings and `completed` is a
string array. It remains a full replacement, not a merge: omitted fields are
empty. At least one non-empty field is required. Henyo retains at most the
newest three non-empty completed milestones and renders exactly one current
row. The result never echoes supplied text:

```json
{"type":"result","id":"progress-1","ok":true,"result":{"ok":true,"applied":true},"durationMs":4}
```

Finish or cancel explicitly so stale presentation is removed:

```json
{"type":"call","id":"progress-2","op":"task.progress.finish","params":{}}
{"type":"result","id":"progress-2","ok":true,"result":{"ok":true,"applied":true,"cleared":true},"durationMs":2}
```

`cleared:false` is an idempotent success meaning no progress was visible. The
overlay continues to show separately supplied `display.summary` captions,
except that a sanitized caption identical to the current progress action is
suppressed rather than drawn twice.

## Task Completion Presentation

After a multi-step task is actually complete, first send `task.progress.finish`
and wait for its successful response. Then send one dedicated completion call:

```json
{"type":"call","id":"completion-1","op":"task.completion.show","params":{"message":"訪問情報が揃いました。王立美術館は評価4.5で現在は18時まで営業、公式チケット表示は13ユーロです。中央駅から徒歩約8分。展示は好評で待ち時間なしの声が多い一方、受付対応には厳しい意見もありました。"}}
```

`params.message` is a required non-empty JSON string of at most 250 Unicode
code points. Supplementary characters count as one code point. Henyo preserves
every accepted code point, wraps the full message without ellipsis, shows no
spinner or caret, holds it for 30 seconds, and then fades it over 1.8 seconds.
A newer accepted completion replaces the prior one. A later ordinary operation
caption dismisses the completion because work has resumed.

Henyo rejects an over-limit message with stable code `completion_too_long` and
does not alter the current completion. Missing, empty, or non-string input uses
`completion_invalid`. If task progress is still visible, the call fails with
`completion_progress_active`; callers must explicitly finish progress and then
retry the completion call. Henyo never silently clears progress for this API.

Success acknowledges presentation only and never echoes the supplied message:

```json
{"type":"result","id":"completion-1","ok":true,"result":{"ok":true,"applied":true},"durationMs":3}
```

The message is presentation-only and ephemeral. Henyo does not log it, include
it in diagnostics/metrics/discovery, persist it, cache it, or replay it after a
disconnect. Do not use `app.current`, `ui.tree`, or another unrelated operation
to display final text. Continue using short `display.summary` only for immediate
per-operation intent.

## Batch

Batch request:

```json
{
  "type": "batch",
  "id": "b1",
  "stopOnError": true,
  "timeoutMs": 8000,
  "returnTree": true,
  "display": {"summary":"検索結果から対象の会話を開きます"},
  "steps": [
    {"id":"s1","op":"ui.click","params":{"selector":{"text":"Search","exact":true,"field":"desc"}},"display":{"summary":"検索欄を開きます"}},
    {"id":"s2","op":"ui.wait","params":{"selector":{"text":"Search","exact":true},"timeout":3000}}
  ]
}
```

A batch-level summary is shown once before the batch starts. Step summaries are
shown only when that step explicitly supplies one; Henyo does not invent noisy
messages for every internal step and suppresses consecutive duplicates of the
batch or previous step summary.

Batch result:

```json
{
  "type": "result",
  "id": "b1",
  "ok": true,
  "result": {
    "ok": true,
    "stoppedOnError": false,
    "steps": [
      {"id":"s1","ok":true,"result":{"ok":true},"durationMs":35},
      {"id":"s2","ok":true,"result":{"ok":true},"durationMs":120}
    ]
  },
  "durationMs": 155
}
```

When `stopOnError=true`, execution stops at the first failed step. With
`stopOnError=false`, later steps continue and each step carries its own `ok`,
`code`, `message`, and `durationMs`. `returnTree=true` requests a full
`ui.tree` event after the batch completes.

If the batch-level `timeoutMs` deadline expires while requested steps remain,
the returned ordered step prefix ends with a synthetic failure whose `id` is
`batch-timeout`, `ok` is `false`, and `code` is `batch_timeout`.
`stoppedOnError` is then `true`. A timeout after the final requested step does
not add a synthetic result.

## Local performance diagnostics

The localhost-only `GET /v1/debug/performance` endpoint exposes bounded numeric
timing counters for overlay/app-launch diagnosis. `POST
/v1/debug/performance/reset` resets the counters. The payload contains no UI
text, package identifiers, trees, screenshots, credentials, location, or app
content. These debug endpoints are not WS operations and are denied to remote
sources; helper, CLI control, and agent consumers do not depend on them.

## Events

### `ui.tree`

Tree snapshots now carry settling metadata so callers can distinguish
provisional post-action pushes from the final settled snapshot:

```json
{
  "type": "event",
  "event": "ui.tree",
  "treeVersion": 42,
  "eventSeq": 104,
  "serviceEpoch": "service-epoch-id",
  "captureBeginEventSeq": 104,
  "captureEndEventSeq": 104,
  "captureBeginElapsedRealtimeMs": 120000,
  "captureEndElapsedRealtimeMs": 120015,
  "capturedAt": "2026-06-20T00:00:00Z",
  "actionId": "action-12",
  "reason": "after_action",
  "settled": false,
  "timedOut": false,
  "changed": true,
  "treeDigest": "8c3f7f3a8c7f9f9f6fd5c5a5a5f7d2a2c7b9c6f8f2c0d9e1f1b2c3d4e5f6a7b8",
  "currentApp": {"package":"com.android.settings","className":"android.widget.FrameLayout"},
  "root": {"text":"","desc":"","className":"android.widget.FrameLayout","children":[]},
  "truncated": false
}
```

Field notes:

- `treeVersion` is the snapshot order on the service side.
- `eventSeq` is the monotonic UI event sequence observed by the service.
- `serviceEpoch` is regenerated for each accessibility-service connection.
  Callers must not compare or reuse cached observations across epochs.
- `captureBeginEventSeq` and `captureEndEventSeq` bracket tree traversal. A
  mismatch means a relevant UI event crossed the capture and the tree must not
  be treated as stable.
- `captureBeginElapsedRealtimeMs` and `captureEndElapsedRealtimeMs` are Android
  monotonic-clock values for the same capture range.
- `capturedAt` is the capture timestamp in ISO-8601 form.
- `actionId` links the tree to a post-action burst. It is empty for major-change
  pushes that were not tied to a specific mutating action.
- `reason` explains why the snapshot was sent. Current values include
  `after_action`, `after_action_timeout`, and `major_change`.
- `settled=true` marks the reliable handoff point for helper pending state.
  `settled=false` means the tree is still provisional.
- `timedOut=true` means the post-action settling deadline expired. Deadline
  snapshots always use `settled=false`; timeout does not make a retained or
  newly captured tree stable.
  If every bounded deadline recapture is invalidated, Henyo emits one
  payload-free conclusion with `ok:false`, `code:"ui_unstable"`,
  `settled:false`, and `timedOut:true`. It contains no root, current-app,
  screenshot, or tree digest and must not replace a cache.
- `changed=true` means the effective tree/current-app payload differs from the
  last snapshot sent to that session.
- `treeDigest` is a digest of the meaningful tree/current-app state used for
  duplicate suppression.

`settled=true` requires a quiet relevant-event window and two consecutive tree
captures with matching digests, with no event crossing the final traversal. It
is the strong post-action release signal, but it is still a tree snapshot, not
a guarantee that an app has finished all rendering work. For a specific visible
state after an action, `ui.wait` remains the strongest wait primitive.

### `ui.dirty`

Every relevant accessibility event is broadcast to authenticated sessions as a
lightweight invalidation event before any replacement tree is required:

```json
{"type":"event","event":"ui.dirty","serviceEpoch":"service-epoch-id","eventSeq":105,"eventElapsedRealtimeMs":120020}
```

`eventSeq` and `eventElapsedRealtimeMs` identify the relevant event using the
service's sequence and Android monotonic clock. Clients must invalidate cached
tree and current-app state immediately. The event deliberately contains no UI
payload. Content-only `com.android.systemui` events are classified as noise and
do not emit `ui.dirty`; window changes from System UI remain relevant.
Henyo suppresses a tree event captured before the latest relevant event, and
does not send a replacement tree until that session has received the matching
dirty sequence.

### `ui.observe`

`ui.observe` captures a tree followed by a screenshot within one observation
range. Params are `maxDepth`, `maxNodes`, `onlyTextNodes`, `redact`, screenshot
`timeout`, `includeIndicator`, and `maxAttempts` (default 3, clamped to 1-5).
The connection/activity overlay is hidden before screenshot composition by
default; `includeIndicator:true` is an explicit visual-debugging override.
Henyo retries when a
relevant event crosses an attempt. The result contains:

- `observation`: `observationId`, `serviceEpoch`, `attempt`, `maxAttempts`,
  `stable`, `unstableReason`, `beginEventSeq`, `endEventSeq`, and monotonic
  `beginElapsedRealtimeMs`/`endElapsedRealtimeMs`.
- `tree`: the normal direct-tree result, including `treeVersion`, digest,
  `capturedAt`, service epoch, and tree capture sequence/time range.
- `screenshot`: `contentType`, `encoding`, `byteLength`, base64 `data`, the
  Android capture timestamp, and screenshot capture begin/end monotonic times.

`stable:true` means no relevant event crossed the outer observation range or
the tree traversal on the successful attempt. If all bounded attempts are
crossed, Henyo still returns the last pair with `stable:false` and
`unstableReason:"relevant_event_during_capture"`; callers must not present it
as fresh. The helper exposes this as `error:"unstable_observation"` and does not
cache either payload.

### Major-change pushes

Henyo also pushes `ui.tree` snapshots to authenticated WS sessions when the UI
changes outside a Henyo action. Major changes are debounced for roughly 200 ms
so transition bursts do not emit stale intermediate trees. Eligible triggers
include `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOWS_CHANGED`, and foreground
package changes reported by relevant accessibility events. Event source
`className` values are not treated as foreground changes because they commonly
identify individual widgets rather than the foreground activity. Content-only
`com.android.systemui` changes are filtered when they are just status bar noise;
window-state changes from System UI remain eligible.

### Post-action burst

Mutating calls start a short-lived post-action burst. Henyo emits tree snapshots
for up to 10 seconds with a widening schedule:

```text
200ms, 400ms, 600ms, 800ms, then 1000ms until the 10s window ends
```

Snapshots are deduplicated where possible using `treeDigest` and the last sent
session snapshot. The final settled/no-change snapshot may still be sent even if
the tree did not materially change, so helper state can clear cleanly.

Other events:

```json
{"type":"event","event":"app.current","package":"com.android.settings","className":"android.widget.FrameLayout"}
{"type":"event","event":"session.ready","protocolVersion":1}
{"type":"event","event":"session.authenticated","tokenId":"..."}
{"type":"event","event":"session.closing","reason":"client_close"}
```

Clients may cache the most recent full `ui.tree` and `app.current` event.
Actions invalidate the client cache until Henyo sends a newer, settled tree or
an explicit fresh query is made.

## Operations

WS operations intentionally mirror HTTP v1 request shapes where practical.

| WS op | HTTP v1 equivalent | Params | Result |
| --- | --- | --- | --- |
| `ui.tree` | `GET /v1/ui/tree` | `maxDepth`, `onlyTextNodes`, `redact`, `maxNodes` | Direct tree payload with service epoch and capture sequence/time range |
| `ui.observe` | WS only | `maxDepth`, `onlyTextNodes`, `redact`, `maxNodes`, `timeout`, `maxAttempts`, `includeIndicator` | Tree and screenshot with shared observation stability metadata; indicator excluded by default |
| `ui.find` | `POST /v1/ui/find` | `selector`, `redact` | Existing find payload |
| `ui.click` | `POST /v1/ui/click` | `selector` or `x/y` or `bounds` | Existing click payload |
| `ui.setText` | `POST /v1/ui/set-text` | `selector`, `value` | Existing set-text payload |
| `ui.tap` | `POST /v1/ui/tap` | `x`, `y`; optional `coordinateSpace`, `captureId` | Screen-coordinate tap by default; safely mapped screenshot-coordinate tap when requested |
| `ui.swipe` | `POST /v1/ui/swipe` | `x1`, `y1`, `x2`, `y2`, `duration`; optional `coordinateSpace`, `captureId` | Screen-coordinate swipe by default; safely mapped screenshot-coordinate swipe when requested |
| `ui.scroll` | `POST /v1/ui/scroll` | `direction` | Existing scroll payload |
| `ui.scrollUntil` | `POST /v1/ui/scroll-until` | `text`, `attempts` | Existing scroll-until payload |
| `ui.wait` | `POST /v1/ui/wait` | `selector`, `timeout`, `interval`, `gone` | Existing wait payload |
| `app.current` | `GET /v1/app/current` | none | Existing current-app payload |
| `app.list` | WS only | `all` | Launcher apps by default; installed package list when `all:true` |
| `app.launch` | `POST /v1/app/launch` | `package` | Existing launch payload |
| `app.start` | `POST /v1/app/start` | `component` | Existing start payload |
| `global.back` | `POST /v1/global/back` | none | Existing global-action payload |
| `global.home` | `POST /v1/global/home` | none | Existing global-action payload |
| `screen.screenshot` | `GET /v1/screen/screenshot` | `timeout`, `includeIndicator` | Indicator-free PNG metadata/data by default, capture timing, and a screenshot-to-screen coordinate mapping |
| `termux.exec` | WS only | `commandPath`, `arguments`, `workdir`, `stdin`, `timeout` | Structured bounded stdout/stderr, exit code, Termux internal error, original lengths, and duration |
| `task.progress.set` | WS only | structured full snapshot: `goal`, ordered `steps[{text,status}]`, optional `replan`; or legacy `goal`, `completed`, `current` | Presentation application acknowledgement; never echoes text |
| `task.progress.finish` | WS only | none | Idempotent presentation clear acknowledgement |
| `task.completion.show` | WS only | required `message` string, 1–250 Unicode code points | Replace-only completion presentation acknowledgement; never echoes text |

Example approved Termux execution:

```json
{"type":"call","id":"termux-1","op":"termux.exec","params":{"commandPath":"/data/data/com.termux/files/usr/bin/printf","arguments":["hello\\n"],"timeout":30000}}
```

The command path must be absolute or begin with `~/` or `$PREFIX/`. Arguments
remain separate argv entries; Henyo does not implicitly invoke a shell.

When callers need a specific visible state after a click, launch, or navigation
step, they should use `ui.wait` for that state instead of assuming the next
tree snapshot means rendering is complete.

### Mutation application versus verification

Operation dispatch and postcondition verification remain distinct facts. For
example, a helper `app.launch` response with top-level `ok:true` and nested
`result.ok:true` means Android accepted the launch mutation. The helper then
adds `result.foreground:true|false`; `foreground:false` means the requested
package was not verified before the bounded deadline, not that the already
applied launch can safely be replayed. Transport/protocol failures instead use
top-level `ok:false`/`error` or a WS `error` frame. For generic mutations,
`ui.tree` settling events likewise report `settled`, `timedOut`, `code`, and
`actionId` separately from the original call result. Clients must not retry an
applied mutation merely because later verification failed.

Selector shape:

```json
{"text":"Battery","exact":true,"field":"text","clickableOnly":false}
```

`field` is one of `text`, `desc`, `viewId`, or `any`.

### Screenshot coordinates

`screen.screenshot` and the nested screenshot in `ui.observe` include a
`coordinates` object alongside the PNG:

```json
{
  "captureId": "c42c...",
  "coordinateSpace": "screenshot",
  "captureMode": "window",
  "displayId": 0,
  "windowId": 17,
  "imageWidth": 1080,
  "imageHeight": 2299,
  "displayWidth": 1080,
  "displayHeight": 2412,
  "captureBoundsInScreen": {"left": 0, "top": 113, "right": 1080, "bottom": 2412},
  "scaleX": 1.0,
  "scaleY": 1.0,
  "mappingCertain": true,
  "boundsSource": "accessibility_window"
}
```

Coordinates inferred from that bitmap must be sent with both
`"coordinateSpace":"screenshot"` and its `captureId`. For example:

```json
{"type":"call","id":"tap-1","op":"ui.tap","params":{"x":540,"y":300,"coordinateSpace":"screenshot","captureId":"c42c..."}}
```

Henyo applies `screenX = left + round(x * scaleX)` and the equivalent Y
mapping. Capture mappings are memory-only, expire after 120 seconds, and are
cleared whenever the accessibility service epoch changes. Unknown, expired,
uncertain, secondary-display, and out-of-bitmap mappings are rejected before a
gesture is dispatched. Omitting `coordinateSpace` preserves the existing
screen-coordinate behavior.

The raw HTTP PNG endpoint carries the same fields in `X-Henyo-*` response
headers (`Capture-Id`, `Image-Size`, `Display-Size`, `Capture-Bounds`,
`Capture-Scale`, `Mapping-Certain`, `Window-Id`, and `Display-Id`).

## HTTP During WS Migration

HTTP v1 must remain operational while WS stabilizes so older/compatibility flows
stay usable:

- `/v1/health` for service liveness.
- `/v1/remote/access`, `/v1/remote/pairing`, and `/v1/auth/*` for management.
- Authenticated control endpoints (`/v1/app/*`, `/v1/ui/*`, `/v1/screen/*`,
  `/v1/global/*`) should keep existing behavior for localhost and remote auth
  paths.
- WS should be the preferred normal control path, but not a hard break for existing
  clients during migration.

For a minimal local smoke check list, see [ws verification](./ws-verification.md).
