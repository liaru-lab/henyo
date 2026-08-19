# Security Policy

Henyo is an experimental accessibility bridge. A trusted Henyo installation
can inspect Android UI, perform gestures, capture screenshots, and—when a
client is granted the separate capability—request commands through Termux.
Security reports are therefore welcome even when the impact is device-local.

## Supported versions

Security fixes target the latest published release and the current `main`
branch. Older APKs may not receive fixes; upgrade to the latest release before
reporting a problem that has already been resolved upstream.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting form:

<https://github.com/liaru-lab/henyo/security/advisories/new>

Do not open a public issue containing an exploit, Bearer token, pairing PIN,
screenshot, device identifier, Tailnet address, or other sensitive evidence.
If private reporting is temporarily unavailable, open a public issue titled
`Security contact request` without technical details so a private channel can
be arranged.

Useful reports include:

- the affected Henyo version and Android version;
- whether access was local, over a Tailnet, or through another network path;
- the required attacker access and expected impact;
- minimal reproduction steps or a proof of concept; and
- mitigations already attempted.

This is a personal, experimental project and has no guaranteed response SLA.
Reports will be acknowledged and handled on a best-effort basis. Please allow
time for a fix and coordinated disclosure before publishing details.

## Operational precautions

- Install APKs only from this repository's releases and verify the published
  checksum.
- Keep remote access disabled unless it is needed, and restrict allowed CIDRs.
- Treat saved Bearer tokens as secrets and revoke clients that are no longer in
  use.
- Do not share raw pairing screens, helper configuration, or diagnostic dumps
  without reviewing them for private data.
