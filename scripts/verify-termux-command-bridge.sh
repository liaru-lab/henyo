#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

grep -q 'com.termux.permission.RUN_COMMAND' src/main/AndroidManifest.xml
grep -q 'android:exported="false"' src/main/AndroidManifest.xml
grep -q 'SCOPE_TERMUX_COMMAND = "termux-command"' src/main/java/link/liaru/henyo/BearerTokenManager.java
grep -q 'authorizedTermuxToken(session)' src/main/java/link/liaru/henyo/HenyoAccessibilityService.java
grep -q 'hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND)' src/main/java/link/liaru/henyo/HenyoAccessibilityService.java
grep -q 'PendingIntent.FLAG_ONE_SHOT' src/main/java/link/liaru/henyo/TermuxCommandBridge.java
grep -q 'MAX_TIMEOUT_MS = 120_000L' src/main/java/link/liaru/henyo/TermuxCommandBridge.java
grep -q 'OP_TERMUX_EXEC = "termux.exec"' src/main/java/link/liaru/henyo/WsOperation.java
grep -q 'henyo termux exec' python/henyo/cli.py

python -m py_compile python/henyo/cli.py python/henyo/helper.py

echo "token-scoped Termux command bridge verifier passed"
