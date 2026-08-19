# WS Verification Notes

Concise local checks for WS server task `022` and operation adapter task `023`.

## Local smoke checks

Assume Henyo is reachable on `127.0.0.1:8765` and accessibility is active.

The low-level frame helper and local handshake can be checked with:

```sh
scripts/verify-websocket-protocol.sh
scripts/verify-ws-handshake.py
scripts/verify-ws-operations.py
scripts/verify-ws-batch.py
scripts/verify-ws-tree-events.py --timeout 20
scripts/verify-helper-daemon.py
scripts/verify-python-cli.py
scripts/verify-helper-auth-config.py
scripts/verify-ws-screenshot.py
HENYO_BENCH_COUNT=5 scripts/benchmark-ws-control.py
```

1. Keep legacy HTTP alive:

```sh
curl --noproxy '*' -sS http://127.0.0.1:8765/v1/health
curl --noproxy '*' -sS http://127.0.0.1:8765/v1/remote/access
curl --noproxy '*' -sS http://127.0.0.1:8765/v1/app/current
```

2. WS handshake + greeting:

```sh
npx -y wscat -c ws://127.0.0.1:8765/v1/ws/control
```

Send:

```json
{"type":"hello"}
{"type":"ping","id":"ping-1"}
```

Expect `session.ready` and `pong`.

3. WS auth path (if a token exists in `HENYO_TOKEN`):

```sh
npx -y wscat -H "Authorization: Bearer ${HENYO_TOKEN:-<token>}" \
  -c ws://127.0.0.1:8765/v1/ws/control
```

Expect `session.ready` with `requiresAuth:false`, then:

```json
{"type":"call","id":"smoke-current","op":"app.current"}
```

4. Auth frame fallback and auth failure smoke:

```text
{"type":"auth","id":"auth-1","scheme":"Bearer","token":"<invalid-token>"}
{"type":"auth","id":"auth-2","scheme":"Bearer","token":"<valid-token>"}
{"type":"call","id":"smoke-tree","op":"ui.tree","params":{"maxDepth":1}}
```

Expected:

- First `auth` should return an `error` with `auth_invalid` (or equivalent).
- Second `auth` should return `ok:true`.
- Tree call should return `result` for the authenticated session.

5. Token revocation should affect an already-open remote WS session before its
next `call` or `batch`. Revoke the session token from another client, then send
a control frame on the existing socket:

```json
{"type":"call","id":"after-revoke","op":"app.current","params":{}}
```

Expected: an `error` frame with `auth_revoked`, and subsequent control frames
must require a fresh successful `auth` frame.

6. Ensure HTTP remains usable while WS is active:

```sh
curl --noproxy '*' -sS http://127.0.0.1:8765/v1/health
curl --noproxy '*' -sS -H "Authorization: Bearer ${HENYO_TOKEN:-<token>}" \
  'http://127.0.0.1:8765/v1/ui/tree?maxDepth=1'
```

If these fail, it indicates the WS migration path is accidentally bypassing or
regressing the existing HTTP v1 behavior.

## Benchmark Notes

`scripts/benchmark-ws-control.py` prints JSON with local median timings for:

- `http.current`
- `ws.current`
- `ws.batch.current_tree`
- `helper.cached_current`
- `helper.cached_tree`

The benchmark is intentionally lightweight and includes local Python/helper
overhead. Use it to confirm that cache hits are reported and that a batch
groups multiple operations into one WS request. It is not a device-independent
performance score.
