---
name: henyo-android-control
description: Use when Codex needs to control or inspect Android apps on this Termux device through the local Henyo accessibility bridge, including launching apps, reading UI trees, finding/clicking/waiting for text, setting text, scrolling, and recovering the accessibility service.
---

# Henyo Android Control

Use the local Henyo bridge to inspect and control Android apps from Termux/Codex.

When this skill runs on a remote Linux Tailnet client, use that machine's Henyo
clone. Set both `HENYO_URL` and
`HENYO_CONFIG` explicitly for the target device; do not overwrite another
device's default credential profile.

## Location

Resolve the real path of this `SKILL.md`. If that path is inside a Henyo source
checkout, use the containing repository root. A separately installed copy of
this skill does not identify a checkout by its relative location; use the
Henyo checkout supplied by the host or user, or the current workspace when it
is a Henyo checkout. Require the selected root to contain `bin/henyo`. If more
than one valid checkout remains and the intended one is ambiguous, ask the
user instead of choosing one. Run commands from the selected repository root
and invoke the CLI as `bin/henyo` unless there is a reason not to.

For direct Tailnet access from another machine, enable remote access in Henyo
MainActivity, start pairing on the Android screen, then register and save a
token:

```sh
export HENYO_URL='http://100.64.x.y:8765'
bin/henyo auth register --name codex --pin PIN --save
bin/henyo v1 health
bin/henyo v1 current
```

The CLI fetches the active public pairing id automatically. Ask the user for
only the 6-digit pairing code shown in Henyo; if the app says the code is about
to change, use the displayed next code instead.

`HENYO_TOKEN` overrides the saved token. Local Termux usage continues to work
without a token while Henyo is listening on `127.0.0.1`.

For a named remote profile:

```sh
export HENYO_URL='http://android-device.example:8765'
export HENYO_CONFIG="$HOME/.config/henyo/remote-device.config"
bin/henyo health
```

The config file contains a Bearer credential. Keep it outside repositories with
mode `0600`, never print it, and never include it in tool output or chat.

The CLI uses a local Python helper and WebSocket control path by default. Useful
helper commands:

```sh
bin/henyo helper status
bin/henyo helper reload-auth
bin/henyo cache tree
bin/henyo cache clear
```

The helper publishes its local IPC endpoint through `helper.json`; see
`docs/helper-ipc.md` in the Henyo project for the full contract. Skills that
call the helper directly must read the discovery JSON, connect to the declared
Unix socket or loopback TCP `host:port`, include the TCP runtime helper token
from discovery when `transport` is `tcp`, send one newline-terminated JSON
request, and read one newline-terminated JSON response. The discovery token is
only for local helper IPC and is not the remote Henyo Bearer token.

Every direct IPC request that reads or changes a remote device must include the
canonical non-secret `targetIdentity` for the intended WebSocket endpoint. A
`helper_target_mismatch` is a hard stop: do not consume cached output, do not
fall back to another screen or device, and never retry a mutation. Inspect
`bin/henyo helper status`, then explicitly stop the old single-target helper
before starting one for the new `HENYO_URL`. Prefer the CLI because it derives
and supplies this identity automatically.

## Transport Policy

Use HTTP only for service health and pairing/auth/token management:

```sh
bin/henyo v1 health
bin/henyo auth register --name codex --pin PIN --save
bin/henyo auth tokens
```

Use the WebSocket helper path for normal observation and control: current app,
UI tree, find/click/wait, text input, scrolling, app launch/start/URI opening, global
actions, and screenshots. The top-level CLI commands use WS by default:

```sh
bin/henyo current
bin/henyo tree 3 --fresh
bin/henyo observe --max-depth 8 --max-attempts 3 --timeout 5000
bin/henyo apps
bin/henyo apps --all
bin/henyo click Battery --exact
bin/henyo screenshot --json --ttl 300 --prefix ui-check
bin/henyo open-uri 'example-app://resource/123' --intent "Opening the requested resource in an app"
```

Treat HTTP v1 UI/control endpoints as compatibility/debug surfaces, not the
normal automation path.

## User-visible Intent

Add a concise `--intent` explanation to meaningful observation and control
commands so a person watching the device understands the immediate purpose:

```sh
bin/henyo tree 5 --fresh --intent "送信先を確認するため、画面を読み取ります"
bin/henyo click Send --exact --intent "確認したメッセージを送信します"
bin/henyo back --intent "対象を間違えたため、前の画面へ戻ります"
```

Describe the immediate action and reason in one short natural sentence. Do not
add category labels such as `[入力]` or `[観察]`. Never include passwords,
pairing codes, tokens, full message bodies, raw selectors, or unrelated private
screen content. The CLI forwards the explanation as `display.summary`; Henyo
sanitizes and bounds it and never derives one from operation parameters.

For a batch, prefer one top-level intent for the user-visible goal and add step
summaries only when a step represents a distinct action worth showing. Repeated
internal tree reads and settling checks do not need separate messages.

## Task Progress Presentation

For a multi-step task, set one ordered full plan snapshot through the normal
persistent helper before or during work:

```sh
bin/henyo progress set \
  --goal "異なる3件の確認を完了する" \
  --step completed "1件目を確認" \
  --step in_progress "2件目を確認" \
  --step pending "3件目を確認"
```

Repeat every step in fixed order on each status update. Status is exactly
`pending`, `in_progress`, or `completed`. Normal updates may change statuses
only; add `--replan` only when intentionally replacing the goal, step text,
count, or order. Henyo rejects a structural mismatch without replan and keeps
the visible plan unchanged. Legacy `--completed`/`--current` snapshots remain
available during migration but must not be mixed with `--step`. Keep all fields
concise and presentation-only. Never use
selectors, passwords, message bodies, input values, tokens, or private screen
content as progress text, and never use progress as evidence that an operation
is authorized or complete.

Always finish or cancel the presentation explicitly:

```sh
bin/henyo progress finish
```

Progress is owned by the current Android WS session, cleared on disconnect, and
never replayed by the helper. If a later session should display progress, set a
new snapshot explicitly. When the current action already says the same thing as
an operation `--intent`, Henyo suppresses the duplicate overlay row; continue to
provide useful per-operation intent where it adds distinct context.

## Task Completion Presentation

After all task work is complete, explicitly finish progress and only then show
one readable final result:

```sh
bin/henyo progress finish
bin/henyo completion show "確認が完了しました。最終結果は3件です。"
```

Use `--intent` for one short immediate operation explanation; use
`completion show` for the multi-sentence final result. Completion accepts at
most 250 Unicode code points, renders accepted text in full without a spinner,
caret, or ellipsis, remains for 30 seconds, and replaces an earlier completion.
If it returns `completion_too_long`, rewrite the result more concisely. If it
returns `completion_progress_active`, call `progress finish`, wait for success,
then retry. Never misuse `current`, `tree`, or another unrelated call to show
text. Do not include secrets or unrelated private content; Henyo does not log,
persist, cache, echo, discover, or replay the completion message.

## Termux Commands From A Remote Client

`termux.exec` is a separate, high-impact capability. A token with ordinary
`control` scope cannot invoke it. Use it only after the user has explicitly
enabled `termux-command` for that paired client in the Henyo UI.

Start with a harmless, bounded probe using an absolute Android Termux path:

```sh
bin/henyo termux exec -- /data/data/com.termux/files/usr/bin/uname -a
```

Do not infer permission for broader filesystem, package-management, process,
network, or destructive commands from a successful probe. Command output can
contain private device data; keep it bounded and do not expose unrelated data.

## Protected Android Controls

Android may mark some controls as accessibility-sensitive. A paired client
cannot access those controls unless the user has enabled **Allow protected
Android controls** for that client in Henyo's on-device UI. This local opt-in
grants `sensitive-ui-control`; ordinary `control` does not imply it, and the
client cannot grant it remotely. Treat `sensitive_ui_permission_required` as a
hard stop and ask the user to review the named client on the Android device.

## Core Workflow

1. Negotiate the installed APK, protocol, and capabilities, then check health:

```sh
bin/henyo version
bin/henyo health
```

Treat `capabilities.features` as authoritative. Do not infer support from the
APK version. A `capability_required` response is a hard stop for that optional
behavior; do not retry after removing target or capture fields because that may
operate on a different window.

2. If UI root is unavailable, recover the accessibility service:

```sh
scripts/recover-accessibility.sh
```

3. Launch or inspect the target app, then verify the foreground package:

```sh
bin/henyo launch com.android.settings
bin/henyo current
bin/henyo tree 3
```

4. Prefer text-driven actions over coordinate taps:

```sh
bin/henyo find Battery --exact
bin/henyo click Battery --exact
bin/henyo wait Battery --exact --timeout 5000
```

The helper uses cached tree/current state for no more than 1000 ms. Relevant UI
events emit `ui.dirty` and invalidate both caches immediately. Use `--fresh` to
bypass the cache or `--max-age MS` to require a stricter age; callers cannot
raise the 1000 ms safety maximum.

After a mutating action, treat the helper cache as stale until a settled
`ui.tree` arrives. Settling requires a quiet event window and consecutive
matching tree digests. `timedOut:true` is explicitly unsettled. Use
`bin/henyo wait` when you need a specific visible state instead of assuming the
next tree is final.

When tree/screenshot agreement matters, use `bin/henyo observe`. It retries a
bounded number of times when a relevant event crosses capture and reports
`observation.stable`. The CLI prints metadata only and never emits tree,
current-app, or base64 screenshot payloads. Stop or retry deliberately when it
reports `unstable_observation`; never treat that pair as fresh. Direct helper
IPC exposes the payloads in memory, so consumers must not log or persist them.

### Screenshot-derived coordinates

Capture coordinate metadata together with the image whenever a point comes
from screenshot pixels:

```sh
bin/henyo screenshot --json --ttl 300 --prefix coordinate-source
```

The JSON contains `path` for visual inspection and a `coordinates` object. Use
the point only when `coordinates.mappingCertain` is `true`, and pass the
`captureId` from that same response:

```sh
bin/henyo tap 540 300 --coordinate-space screenshot --capture-id CAPTURE_ID \
  --intent "スクリーンショットで確認した対象をタップします"
bin/henyo swipe 540 1800 540 900 300 --coordinate-space screenshot \
  --capture-id CAPTURE_ID --intent "スクリーンショット上の範囲をスクロールします"
```

When several application windows are present, add `--package PACKAGE` to
`find`, `click`, `observe`, `screenshot`, `tap`, or `swipe`. Add
`--window-id ID` and `--display-id ID` when one package owns more than one
candidate window. UI-node operations can re-resolve a recreated window inside
the same package; screenshot-derived coordinates cannot and require a fresh
capture when the window identity or geometry changes.

Propagate the resolved `target` tuple from an observation or successful
operation to each following operation. This is operation-scoped state, not one
session-wide active window. After launching or switching apps, re-observe. If a
window id becomes stale, retry discovery by package only, then adopt the newly
resolved window id; never transfer a stale id to another package.

Choose capture scope deliberately:

```sh
bin/henyo screenshot --json --capture-mode window --package PACKAGE --window-id WINDOW_ID
bin/henyo screenshot --json --capture-mode display --display-id DISPLAY_ID
```

Use `window` for grounding and interaction inside one app window. Use `display`
when the task requires the fully composited screen, including overlap between
windows. `auto` is compatibility behavior, not an assertion of either scope.
Do not silently substitute display capture after
`unsupported_window_capture`.

Never reuse a capture id with another screenshot. Mappings live only in the
current accessibility-service epoch and expire after 120 seconds. Capture a
fresh screenshot when the mapping is missing or uncertain, the service has
restarted, the UI has materially changed, or Henyo reports an unknown,
expired, uncertain, or out-of-bounds screenshot mapping. Do not guess an
offset, silently clamp a point, or retry it as a screen coordinate.

Treat `bin/henyo tap X Y` and `bin/henyo swipe ...` without these flags as
physical screen coordinates only. Use those legacy forms for coordinates from
the UI tree or known display geometry, never for pixels selected from a
window-scoped screenshot. `ui.observe` returns the same `coordinates` contract;
use only the capture id from that exact observation and reacquire the
observation under the same failure conditions.

5. Use scroll helpers for off-screen targets:

```sh
bin/henyo scroll-until "Accessibility features" --attempts 8
```

6. Use physical screen coordinates only as a fallback when they come from the
UI tree or known display geometry:

```sh
bin/henyo tap 540 1000
bin/henyo swipe 540 1800 540 900 300
```

## Matching Rules

Use precise matching for automation:

```sh
bin/henyo find "Battery" --exact --field text
bin/henyo click "Search" --exact --field desc
```

Supported fields:

```text
text
desc
viewId
any
```

Default matching is partial and `field=any`, which is useful for exploration but can produce false positives.

## App URI Opening

Use `open-uri` when the caller already has a complete absolute URI:

```sh
bin/henyo open-uri 'example-app://resource/123' \
  --intent "Opening the requested resource in an app"
```

Add `--package` only when resolution must be pinned to a known app:

```sh
bin/henyo open-uri 'example-app://resource/123' \
  --package com.example.app \
  --intent "Opening the requested resource in the selected app"
```

Keep the URI as one quoted argv value. Do not re-encode it, shell-evaluate it,
or include the raw URI in `--intent`. Henyo accepts custom schemes but rejects
`file`, `content`, `javascript`, `data`, and `intent`. A successful dispatch
does not prove an app-specific outcome. If a package was supplied, treat the
returned `foreground` fact as independent verification and never retry merely
because it is false.

## App Launching

Treat app launch as `attempt -> verify -> fallback`. A command returning success is not enough; the app is launched only when `bin/henyo current` reports the expected package in the foreground.

### Generic Launch Workflow

1. Resolve the app identity through Henyo first:

```sh
bin/henyo apps
bin/henyo apps --all
```

Use `bin/henyo apps` for launcher-visible apps. Use `bin/henyo apps --all`
when the target package may not expose a launcher activity.

2. If Henyo app discovery is insufficient, fall back to Android shell metadata:

```sh
cmd package list packages
cmd package resolve-activity --user 0 --brief com.android.settings
```

If Android package resolution is blocked or incomplete, inspect the APK metadata:

```sh
pm path com.example.app
aapt2 dump badging /path/to/base.apk
```

3. Try Henyo launch first:

```sh
bin/henyo launch com.android.settings
bin/henyo current
```

`launch` and `start` wait briefly for the requested package to become
foreground and invalidate stale helper cache. Check the returned `foreground`
field before assuming the app is ready. They do not guarantee that all render
work has finished, so follow with `bin/henyo wait` when the next step depends
on a specific control or screen state.

4. If the foreground package is not the target, start the resolved activity explicitly:

```sh
am start --user 0 -n com.android.settings/.Settings
bin/henyo current
```

5. If direct start reports success but the app is still not foreground, use the launcher UI:

```sh
bin/henyo home
bin/henyo swipe 540 2100 540 700 450
bin/henyo click "Settings" --exact --field text
bin/henyo current
```

6. Stop retrying after the bounded fallback chain. Capture state for diagnosis:

```sh
bin/henyo current
bin/henyo tree 5
bin/henyo screenshot --ttl 300 --prefix launch-failure
```

### Launch Blockers

Handle common blockers before continuing:

- Permission dialogs: click the visible allow option, then verify with `bin/henyo current`.
- Login or account screens: stop and report that user input is required.
- App crash or instant exit: if the foreground returns to launcher after multiple launch methods, report the crash/exit instead of looping.
- Stale accessibility root: if `tree` fails with `no_root`, run recovery before retrying.

### Per-App Notes

When you discover a reliable launch method for an app, preserve these facts in the task notes or a repo runbook:

- package name
- app label
- launch activity
- launch method that actually foregrounded the app
- known permission, login, or WebView/accessibility limitations

Use ADB or Android shell commands only when Henyo launch is insufficient, when resolving app metadata, or when the app requires a specific Android intent action.

## Text Input

Open/focus the target input first, then set text:

```sh
bin/henyo click Search --exact --field desc
bin/henyo set Search Battery
```

If `set-text` fails, inspect the UI tree for the actual editable node:

```sh
bin/henyo tree 5
```

## Lock Screen Unlock

When testing a locked device, keep Henyo on the WS path for observation but use
ADB for screen wake and PIN digits when ADB is already authorized. Lock-screen
UI trees and screenshots can lag, return black frames, or disagree with the
physical screen; do not rely on coordinate taps for PIN entry unless the user is
watching and has confirmed the exact screen state.

Use this bounded flow:

```sh
adb -s HOST:5555 shell input keyevent KEYCODE_WAKEUP
adb -s HOST:5555 shell input swipe 540 2320 540 250 250
adb -s HOST:5555 shell input keyevent KEYCODE_<digit>
```

Repeat the numeric keyevent once per PIN digit. Treat the PIN as a secret:
never store it in the repo, skill, shell scripts, or normal config files, and do
not paste it into user-facing output. Stop after one supervised attempt if the
device reports a wrong PIN, does not unlock, or the screen state is unclear.

Avoid Henyo coordinate taps on the lock PIN screen unless there is no ADB
alternative. A miscalibrated tap can hit the emergency dialer. If that happens,
use visible text/accessibility actions such as `Cancel` to return to the lock
screen before trying anything else.

## Recovery

After reinstalling the APK, Android may list the accessibility service as enabled while returning no active root. Run:

```sh
scripts/recover-accessibility.sh
```

If health succeeds but `tree` fails with `no_root`, recover first before continuing.

## Safe APK Deployment

Deploy only to the existing `link.liaru.henyo` installation with the guarded
script:

```sh
scripts/deploy-android.sh --check-only --serial SERIAL
scripts/deploy-android.sh --serial SERIAL
```

The guard requires the canonical update key at
`~/.config/henyo/signing/link.liaru.henyo-release.keystore` plus its local
credentials file, performs a clean release build, and compares the pinned
signing-certificate fingerprint against the installed APK and built APK before
using `adb install -r`. It verifies package and certificate again afterward.

Never work around a missing key or signature mismatch by changing
`HENYO_APP_ID`, creating another `v3` package, uninstalling, clearing app data,
or generating a replacement key. Stop and investigate. Do not use
`gradle installDebug` or a raw `adb install` for Henyo deployment.

After a successful update, recover accessibility if needed and verify:

```sh
scripts/recover-accessibility.sh
bin/henyo health
bin/henyo current
```

## Startup

Henyo starts its listener from `HenyoAccessibilityService.onServiceConnected()`.
Android owns accessibility service lifecycle and should bind the service after
boot when it remains enabled. Henyo cannot enable its own accessibility service;
if the service is disabled, use Android settings or the recovery script rather
than expecting an app-level autostart switch to fix it.

## Verification

Run the project verification scenario after changes:

```sh
scripts/verify-remote-access.sh
scripts/verify-settings.sh
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

## Safety

UI trees and screenshots may contain personal data such as account identifiers,
Wi-Fi names, notification text, token metadata, or app content. Prefer concise
summaries and small redacted/filtered excerpts in user-facing responses. Do not
paste raw full UI trees, screenshots, bearer tokens, pairing PINs, or pairing
secrets unless the user explicitly needs that diagnostic detail.

For debugging, keep tree reads bounded and redacted when possible:

```sh
bin/henyo tree 3
```

Store screenshots as artifacts/paths and describe the relevant UI state instead
of embedding or transcribing sensitive content by default.
