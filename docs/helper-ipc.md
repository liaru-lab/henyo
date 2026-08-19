# Henyo Helper IPC

The Python CLI uses a local helper daemon for normal WebSocket control. External
callers and Codex skills can use the same helper by reading the runtime
discovery file and sending one newline-delimited JSON request.

## Transport Selection

The helper supports these local IPC transports:

- `unix`: Unix domain socket transport, used by default on Unix-like systems.
- `tcp`: loopback TCP transport, used by default on Windows and available on
  Unix-like systems for compatibility verification.

Configure helper IPC with these environment variables:

```text
HENYO_HELPER_TRANSPORT=unix|tcp
HENYO_HELPER_SOCKET=/path/to/helper.sock
HENYO_HELPER_HOST=127.0.0.1
HENYO_HELPER_PORT=0
HENYO_HELPER_DISCOVERY=/path/to/helper.json
```

`HENYO_HELPER_SOCKET` applies to Unix socket transport. `HENYO_HELPER_HOST` and
`HENYO_HELPER_PORT` apply to TCP transport. TCP hosts must be IPv4 loopback
addresses; binding to `0.0.0.0` or any non-loopback address is rejected.
`HENYO_HELPER_PORT=0` lets the operating system choose an available local port.

The default discovery file path is:

```text
${runtime_dir()}/helper.json
```

where `runtime_dir()` is `${XDG_RUNTIME_DIR}/henyo`, `${TMPDIR}/henyo`, or
`/tmp/henyo`.

## Discovery File

The helper writes a JSON discovery file when it starts and removes it during
normal shutdown. The file and its parent directory use restrictive permissions
where the platform supports them.

Unix socket discovery:

```json
{
  "transport": "unix",
  "socket": "/tmp/henyo/helper.sock",
  "pid": 12345,
  "startedAt": 1781840000000
}
```

TCP discovery:

```json
{
  "transport": "tcp",
  "host": "127.0.0.1",
  "port": 49321,
  "token": "runtime-random-token",
  "pid": 12345,
  "startedAt": 1781840000000
}
```

The TCP `token` is a local helper runtime token generated when the daemon
starts. It is not the remote Henyo Bearer token used for HTTP or WebSocket
authentication, and the discovery file must not be treated as remote auth
material.

## Client Sequence

Clients should:

1. Read `HENYO_HELPER_DISCOVERY` or the default `helper.json`.
2. Check `transport`.
3. Connect to `socket` for Unix transport, or to `host:port` for TCP transport.
4. For TCP, copy the discovery `token` into the request JSON as `token`.
5. Send one UTF-8 JSON object followed by `\n`.
6. Read one UTF-8 JSON object ending in `\n`.

Every request that can read from or act on the remote device must also include
`targetIdentity`. This is the canonical, non-secret WebSocket endpoint expected
by the caller. The CLI derives it from `HENYO_WS_URL`, or from `HENYO_URL` by
mapping HTTP(S) to WS(S) and selecting `/v1/ws/control`. Canonical form uses a
lowercase host, an explicit port (8765 when omitted), and the effective path:

```json
{"cmd":"current","targetIdentity":"ws://device-a:8765/v1/ws/control"}
```

The daemon validates this value before cache lookup, state mutation, or
WebSocket use. A missing or different identity fails closed:

```json
{"ok":false,"error":"helper_target_mismatch","expectedTarget":"ws://device-b:8765/v1/ws/control","boundTarget":"ws://device-a:8765/v1/ws/control","message":"helper is bound to a different target; stop it before switching targets"}
```

`status`, `stop`, `cache.clear`, and other strictly local diagnostics or
management remain available without a target identity so a mismatch can be
diagnosed and recovered. `auth.reload` is target-bound because it changes the
credentials of the remote WebSocket connection. Target identity never contains
Bearer credentials or the local TCP helper token. A client switching targets
must stop the old single-target helper before starting the replacement; it must
not retry a mutation automatically.

Example TCP request:

```json
{"cmd":"status","token":"runtime-random-token"}
```

Example response:

```json
{"ok":true,"transport":"tcp","host":"127.0.0.1","port":49321}
```

## WebSocket session checkpoint

The existing `status` command remains local-only and never opens WebSocket.
The additive checkpoint command is:

```json
{"cmd":"session.status","targetIdentity":"ws://device-a:8765/v1/ws/control"}
```

It lazily connects WebSocket, waits for the current connection generation's
`session.ready`, and returns only validated, allowlisted metadata. Current
Android remains compatible and returns:

```json
{"ok":true,"protocolVersion":1,"serviceEpoch":"opaque"}
```

An additive iPhone gateway returns:

```json
{"ok":true,"protocolVersion":1,"contractRevision":"remote-control.core/1.0.0","serviceEpoch":"opaque","platform":{"name":"ios","version":"27.0"},"deviceReady":true,"deviceState":"ready","capabilities":{"profile":"remote-control.core/1","features":["expectedServiceEpoch","mutationOutcome"],"limits":{"inboundFrameBytes":65536,"batchSteps":64},"operations":{"ui.tap":{"mutates":true,"coordinateSpaces":["screen","screenshot"]},"input.key":{"mutates":true,"keys":["ENTER","A"],"modifiers":["SHIFT","META"]}}}}
```

`contractRevision`, `platform`, and `capabilities` must either all be absent
(legacy Android) or all be valid. Unknown capability fields, features, limits,
and operations are discarded and never published. Known operation entries
retain `mutates` plus only the bounded metadata defined by
`remote-control.core/1`: screenshot/tap `coordinateSpaces`, screenshot
`includeIndicator`, activation `identityField`, text `encoding`,
`normalization`, size limits, `secureTargetDetection`, and
`pasteboardRestoration`, and logical-key `keys` and `modifiers`. Unknown fields
and enum members are omitted; malformed or unbounded known fields fail with
`capability_invalid`. Disconnect and `session.closing` clear the entire
checkpoint before a replacement connection can publish one.

For `ui.tree`, the helper publishes the complete, exact
`henyo.ui-tree/1` prerequisite metadata: non-mutating operation, parameters
`maxDepth`, `maxNodes`, and `redact`; depth defaults/maximum 8/32; node defaults/
maximum 500/1200; string limits 4096 code points and 16384 UTF-8 bytes; result
limit 1048576 bytes; timeout 60000 ms; nullable fields `clickable` and `focused`;
and redaction mode `email-and-phone`. Missing or different known metadata fails
closed. Backend-only and unknown fields are not published. This advertises the
common gateway result contract; it does not add `schemaRevision` to Android's
existing nested or `onlyTextNodes` flat payloads.
For iOS, `deviceReady` is required and must agree with the bounded
`deviceState` enum (`connecting`, `ready`, `locked`, `reconnecting`,
`operator_action_required`, or `closed`). `operatorAction` is optional and is
currently allowlisted only as `physical_reboot_then_first_unlock` while state is
`operator_action_required`. Legacy Android may omit all three fields. A
compatible client can map these sanitized values to its own availability,
state, and operator-action fields.

Stable safe failures are:

```json
{"ok":false,"error":"ws_unavailable"}
{"ok":false,"error":"ws_disconnected"}
{"ok":false,"error":"timeout"}
{"ok":false,"error":"auth_required"}
{"ok":false,"error":"protocol_incompatible"}
{"ok":false,"error":"protocol_invalid"}
{"ok":false,"error":"capability_invalid"}
{"ok":false,"error":"session_metadata_invalid"}
```

Failures never include the raw greeting, token, endpoint, capabilities,
screenshot, or input text. For TCP helper transport the existing local helper
token requirement still applies and may return `helper_auth_failed` before any
WebSocket work.

## Observation Commands

`tree` and `current` accept two cache controls:

```text
fresh: boolean
maxAgeMs: non-negative integer
```

The helper's default and absolute maximum cache age is 1000 ms. `fresh:true`
bypasses the cache. `maxAgeMs` may require a stricter age but cannot relax the
1000 ms maximum. Cached observations are memory-only and are invalidated by
`ui.dirty`, a changed `serviceEpoch`, a new action generation, or an unstable
capture range. Cached responses include `cached:true` and `cacheAgeMs`; direct
requests use `cached:false` where applicable.

The `observe` helper command forwards params to the WS `ui.observe` operation:

```json
{"cmd":"observe","targetIdentity":"ws://device-a:8765/v1/ws/control","params":{"maxDepth":8,"maxAttempts":3,"timeout":5000}}
```

A stable response contains the WS result, including tree and base64 screenshot
payloads. If every bounded attempt is unstable, the helper returns
`ok:false`, `error:"unstable_observation"`, and the final WS result for explicit
diagnosis, without caching either payload. IPC clients must inspect
`observation.stable` and must never log or persist UI trees or screenshot data.

The top-level `bin/henyo observe` command is deliberately safer for diagnostics:
it prints only observation, tree-capture, and screenshot metadata. It omits the
tree/root/current-app content and base64 screenshot regardless of stability.

Approved clients can forward `termux.exec` through the generic helper call:

```json
{"cmd":"call","targetIdentity":"ws://device-a:8765/v1/ws/control","op":"termux.exec","params":{"commandPath":"/data/data/com.termux/files/usr/bin/id","arguments":[],"timeout":30000}}
```

The helper extends its read timeout to the bounded command timeout. The remote
Bearer token configured for its WS connection must have the local Android
`termux-command` capability; the helper runtime token does not grant it.

The generic `call` command also accepts two optional, bounded top-level
mutation-context fields and one bounded operation deadline:

```json
{"cmd":"call","targetIdentity":"ws://device-a:8765/v1/ws/control","op":"input.key","params":{"key":"ENTER"},"expectedServiceEpoch":"opaque-current-epoch","actionId":"opaque-caller-action","timeoutMs":120000}
```

When present, `expectedServiceEpoch` and `actionId` must each be a well-formed
Unicode scalar string of 1 through 256 code points and are forwarded unchanged
to the WebSocket v1 call envelope. They are opaque correlation/context values,
not the helper-generated WebSocket request `id`, retry keys, or permission to
replay. Unknown top-level call fields or invalid context return
`{"ok":false,"error":"invalid_call_request"}` before send and never echo the
rejected value. When both fields are absent, legacy behavior is unchanged.
`timeoutMs` must be an integer from 1 through 120000. The helper forwards it
unchanged on the WebSocket call frame, waits five additional seconds for the
response, and permits local IPC callers a further five-second margin. It never
retries or replays a timed-out call. Omitting it preserves the existing
operation-specific call defaults.

## User-visible action intent

Callers may attach a short, user-facing explanation without putting it in
operation params:

```json
{"cmd":"call","op":"ui.click","params":{"selector":{"text":"Send"}},"display":{"summary":"メッセージを送信します"}}
```

The helper forwards an explicitly supplied `display` object to WebSocket. It
never derives a summary from selectors, input values, or other params, and does
not log the summary. Omitting `display` preserves the existing behavior.

Batch IPC preserves both the top-level explanation and per-step explanations:

```json
{
  "cmd":"batch",
  "timeoutMs":25000,
  "display":{"summary":"対象のチャットを操作します"},
  "steps":[
    {"id":"open","op":"ui.click","params":{"selector":{"text":"Chat"}},"display":{"summary":"対象のチャットを開きます"}}
  ]
}
```

`timeoutMs` is optional and must be an integer from 1 through 300000. When it
is present, the helper forwards it as the Android batch deadline, waits five
additional seconds for the WebSocket response, and gives local helper IPC a
further five-second margin. Thus `timeoutMs:25000` uses an approximately
30-second WebSocket wait and a 35-second IPC read timeout. An explicitly
configured `HENYO_HELPER_READ_TIMEOUT` may increase, but cannot shorten, that
required IPC deadline. Omitting `timeoutMs` preserves the legacy 20-second
WebSocket and 30-second IPC waits and sends no Android batch deadline.

The generic `screen.screenshot` call derives its WebSocket wait from the
bounded `params.timeout` value up to 300000 milliseconds, adds a five-second
response margin, and gives local helper IPC a further five-second margin. It
does not retry or replay a timed-out screenshot. Other call operations retain
their existing timeout behavior.

The CLI accepts the same metadata as `--intent TEXT`:

```sh
bin/henyo click Send --exact --intent 'メッセージを送信します'
bin/henyo batch actions.json --intent '対象のチャットを操作します'
```

For a batch object, top-level `display` from the JSON file is used when the CLI
option is absent. Step objects are forwarded unchanged. Do not place secrets,
message bodies, selectors, or raw input values in summaries.

## Task progress presentation

Personal Assistant and other helper clients set an ordered ephemeral plan with:

```json
{"cmd":"progress.set","goal":"3件の確認を完了する","steps":[{"text":"1件目を確認","status":"completed"},{"text":"2件目を確認","status":"in_progress"},{"text":"3件目を確認","status":"pending"}],"replan":false}
```

`steps` must contain one to six objects in fixed display order. Each `status`
is `pending`, `in_progress`, or `completed`. The first structured request
installs the plan. Subsequent requests update statuses only and must repeat the
same goal/text/order. To intentionally replace that identity, send
`"replan":true`. A mismatch without replan is rejected and leaves the current
plan untouched. Structured requests cannot mix `steps` with legacy
`completed`/`current`.

For migration, the legacy complete snapshot remains supported:

```json
{"cmd":"progress.set","goal":"レビュー本文を異なる3件分取得できたら終了","completed":["1件目を取得"],"current":"2件目の本文を確認"}
```

In legacy mode, `goal` and `current` must be strings, `completed` must be a string array, and
at least one must contain non-whitespace text. The helper sends exactly one
`task.progress.set` WS call and returns its frame unchanged:

```json
{"type":"result","id":"helper-7","ok":true,"result":{"ok":true,"applied":true},"durationMs":4}
```

Clear the presentation explicitly with `{"cmd":"progress.finish"}`. This sends
`task.progress.finish` with empty params and returns a result whose nested
payload is `{"ok":true,"applied":true,"cleared":true}`. `cleared:false` is an
idempotent success.

For TCP helper transport, both requests additionally require the discovery
`token`, exactly like every other helper request. The helper never caches task
progress, includes it in status, reconstructs it from operation data, or
replays it after WS reconnect. Callers must send a new full snapshot after a
reconnect if presentation is still desired.

Equivalent CLI commands are:

```sh
bin/henyo progress set --goal '3件の確認を完了する' \
  --step completed '1件目を確認' \
  --step in_progress '2件目を確認' \
  --step pending '3件目を確認'
bin/henyo progress set --goal '新しい計画' --replan \
  --step in_progress '新しい1件目'
bin/henyo progress set --goal '3件取得で終了' \
  --completed '1件目を取得' --current '2件目を確認'
bin/henyo progress finish
```

`--step STATUS TEXT` and legacy `--completed TEXT` may be repeated. The CLI prints the helper/WS response, which
does not echo supplied progress text.

## Task completion presentation

Personal Assistant must finish progress and wait for success before presenting
the final readable result:

```json
{"cmd":"progress.finish"}
{"cmd":"completion.show","message":"訪問情報が揃いました。王立美術館は評価4.5で現在は18時まで営業、公式チケット表示は13ユーロです。中央駅から徒歩約8分。展示は好評で待ち時間なしの声が多い一方、受付対応には厳しい意見もありました。"}
```

The helper validates a required non-empty string with at most 250 Unicode code
points, counting a supplementary character once, then sends exactly one
`task.completion.show` call with `params.message`. Exactly 250 is accepted;
251 returns stable `completion_too_long` without sending a WS call. The Android
side returns `completion_progress_active` if progress was not explicitly
finished. Success is the unchanged WS result and contains only
`{"ok":true,"applied":true}`; it never echoes the message.

The helper does not cache, log, persist, expose through status/discovery, or
replay completion text. A newer completion replaces the prior presentation.
Equivalent CLI usage is:

```sh
bin/henyo progress finish
bin/henyo completion show '調査が完了しました。結果は3件です。'
```

Use `--intent`/`display.summary` for a short immediate operation explanation.
Use `completion.show` only once work and progress are finished, for the readable
multi-sentence final result. Do not route completion text through `current`,
`tree`, or another unrelated helper command.

### Applied mutation and failed verification

For `app.launch` and `app.start`, inspect returned facts independently. A
top-level successful WS frame whose nested `result.ok` is true means dispatch
was applied. The helper's nested `foreground` boolean is the separate bounded
postcondition check. Thus `result.ok:true, foreground:false` is an applied
mutation with failed foreground verification and must not be treated as a safe
automatic retry. WS/helper transport errors remain top-level failures. The
pending-action status and later `ui.tree` facts (`settled`, `timedOut`,
`lastTreeErrorCode`, `actionId`) provide generic settling evidence without
changing the original operation result.

## Lazy WebSocket lifecycle

Starting the helper opens only its local IPC listener. It connects to Android
WebSocket lazily when an IPC request first needs Android data or control, so a
status-only helper does not leave the connected indicator visible.

When Android sends `session.closing` with `reason:"idle_timeout"`, or whenever
WebSocket otherwise disconnects, the helper immediately invalidates cached
tree/current data, `serviceEpoch`, and pending-action state. All in-flight IPC
operations wake with `error:"ws_disconnected"`. The next new operation may
establish a fresh WebSocket; the helper never automatically replays an
operation that might already have reached Android.

Task progress follows the same fail-closed lifecycle and is not retained in the
helper at all. Android clears the owning session's progress on disconnect; a
later lazy reconnect starts with no progress unless the caller explicitly sets
it again.

Unix socket requests remain tokenless for compatibility, although token-bearing
requests are also valid when callers share request-building code.

## Security Notes

TCP helper IPC is loopback-only and protected by the runtime helper token in the
discovery file. Keep the discovery file local, avoid logging its contents, and
do not expose helper IPC ports outside the device or workstation.
