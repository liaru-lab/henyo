# Remote Access Contract

Henyo is local-only by default. The service listens on `127.0.0.1:8765` unless
remote access is explicitly enabled through local management or the Android UI.

When remote access is enabled, Henyo admits remote traffic only from configured
CIDRs. The default allowed CIDRs are the Tailscale ranges:

```text
100.64.0.0/10
fd7a:115c:a1e0::/48
```

Source CIDR filtering is not authentication. A Tailnet source IP only decides
whether the request may reach a remote-enabled endpoint class. Remote control
APIs still require `Authorization: Bearer <token>`.

## Endpoint Classes

| Class | Endpoints | Localhost | Allowed remote CIDRs | Other addresses |
| --- | --- | --- | --- | --- |
| Limited health | `GET /v1/health` | Allowed | Allowed without Bearer auth | Denied |
| Pairing status | `GET /v1/remote/pairing` | Allowed | Allowed without Bearer auth during pairing setup | Denied |
| Local-only management | `GET/PUT /v1/remote/access`, `POST/DELETE /v1/remote/pairing`, `POST /v1/auth/tokens/local` | Allowed | Denied | Denied |
| Remote pairing | `POST /v1/remote/pairing/register` | Denied | Allowed only during active pairing with PIN | Denied |
| Authenticated control | `/v1/app/*`, `/v1/ui/*`, `/v1/screen/*`, `/v1/global/*` | Allowed for compatibility | Requires Bearer auth | Denied |
| Token management | `GET /v1/auth/tokens`, `DELETE /v1/auth/tokens/{tokenId}` | Allowed | Requires Bearer auth | Denied |

Localhost compatibility keeps existing Termux control workflows working. Token
registration is the exception: `POST /v1/remote/pairing/register` must come from
an allowed remote CIDR so pairing proves both Tailnet access and access to the
target device screen. Remote callers must pass both checks: source address in an
allowed CIDR and valid endpoint authorization.

## Pairing

Pairing is started locally on the Android device. Starting a pairing session
creates a public `pairingId` and causes MainActivity to show a short-lived
6-digit TOTP PIN with a countdown ring and next-code hint. The PIN, pairing
secret, raw Bearer tokens, UI tree payloads, and screenshots must not be logged.
To accommodate agent and network latency, registration accepts the current
code, the displayed next code, and the six preceding 30-second codes. The
pairing session TTL, one-time use, source-CIDR check, and five-attempt lockout
still apply.

A remote client registers by calling `POST /v1/remote/pairing/register` from an
allowed CIDR with the `pairingId`, screen-visible PIN, and client name. On
success Henyo returns the raw Bearer token exactly once and stores only token
metadata plus a verifier/hash. Pairing sessions are invalid after success,
expiry, cancellation, or too many failed PIN attempts.

The Python CLI fetches the active public `pairingId` automatically, so normal
registration only requires the visible PIN:

```sh
bin/henyo auth register --name laptop --pin PIN --save
```

For recovery and local setup, `POST /v1/auth/tokens/local` is a localhost-only
bootstrap endpoint for creating a Bearer token. It is not reachable from allowed
remote CIDRs, returns the raw token exactly once, and stores only token metadata
plus a verifier/hash.

## Contract Source

The normative HTTP contract is `docs/openapi.yaml`. That file defines endpoint
schemas, structured auth errors, remote access status, pairing status, and token
metadata.

## Verification

Run the deterministic and local smoke checks with:

```sh
scripts/verify-remote-access.sh
```

That script verifies CIDR classification, disabled remote rejection, Bearer
token creation/verification/revocation, pairing expiry/PIN/TOTP windows, APK
assembly, and local `health`, `current`, `tree`, and `back` smoke commands when
the local service is reachable.

Manual Tailnet verification:

1. In Henyo MainActivity, enable remote access, choose `0.0.0.0`, keep the
   default Tailnet CIDRs, and save.
2. From a different Tailnet node, set `HENYO_URL` to the Henyo device's
   Tailscale IPv4 address and confirm `bin/henyo v1 health` works without a
   token.
3. Confirm a control request such as `bin/henyo v1 current` fails without
   `HENYO_TOKEN`.
4. Start pairing on the Android screen, then run
   `bin/henyo auth register --name laptop --pin PIN --save`
   from the remote node.
5. Confirm `bin/henyo v1 current`, `bin/henyo v1 tree 2`, `bin/henyo auth tokens`,
   and `bin/henyo auth revoke TOKEN_ID` work with the saved token.
6. Disable remote access again if the device should return to localhost-only
   mode.
