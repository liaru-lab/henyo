# Henyo repository rules

## Android deployment safety

- The public Henyo application ID is `link.liaru.henyo`. Do not invent another
  application ID as a workaround for signing problems.
- The release key and its credentials are kept under
  `~/.config/henyo/signing/`, deliberately outside the repository and
  `build/`. Never delete, replace, regenerate, move, or expose them during
  normal development.
- Never use `gradle installDebug`, a raw `adb install`, or an uninstall/reinstall
  cycle for this app. Deploy only with `scripts/deploy-android.sh`.
- A missing key, unexpected package ID, unexpected certificate, absent existing
  installation, multiple ADB devices, or failed post-install verification is a
  hard stop. Do not bypass a failed preflight by changing the package name.
- Preserve the existing installation and its app data. `adb uninstall`,
  `pm uninstall`, `pm clear`, and `install -d` are prohibited unless the user
  explicitly requests the destructive operation.
- After deployment, recover the accessibility service if necessary and verify
  health plus the active package before claiming success.

## Issue and pull request workflow

- Use GitHub Issues as the source of truth for planned and active work. Do not
  create a parallel repository-local task backlog.
- For non-trivial work, use a branch named `codex/<issue-number>-<slug>` and
  open a pull request that links the issue.
- Do not close an issue until its acceptance criteria, review, automated checks,
  and required device verification are complete.
- Keep device-specific captures, logs, identifiers, ports, UI text, and other
  raw evidence under ignored `artifacts/`; never commit them.
- Preserve unrelated user work, including untracked files.
