#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
HENYO="$ROOT/bin/henyo"
SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^127\.0\.0\.1:/ {print $1; found=1; exit} END { if (!found) exit 1 }' || adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"

[ -n "$SERIAL" ] || { echo "No local adb device found" >&2; exit 1; }

echo "health"
"$HENYO" health
echo

echo "launch settings"
"$HENYO" launch com.android.settings
echo
sleep 1

echo "current"
"$HENYO" current
echo

echo "find Battery exact"
"$HENYO" find Battery --exact
echo

echo "click Battery"
"$HENYO" click Battery --exact
echo
sleep 1

echo "wait Battery title"
"$HENYO" wait Battery --exact --timeout 5000
echo

echo "back"
"$HENYO" back
echo

echo "open search and set text"
"$HENYO" click Search --exact --field desc
echo
sleep 1
curl --noproxy '*' -sS 'http://127.0.0.1:8765/ui/set-text?target=Search&field=desc&value=Battery'
echo
"$HENYO" wait Battery --exact --timeout 5000
echo

echo "return and scroll"
"$HENYO" back >/dev/null
sleep 1
curl --noproxy '*' -sS 'http://127.0.0.1:8765/ui/tap?x=92&y=182' >/dev/null
sleep 1
"$HENYO" scroll-until "Accessibility features" --attempts 8
echo

echo "home"
"$HENYO" home
echo
