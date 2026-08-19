#!/usr/bin/env sh
set -eu

SERIAL="${ADB_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^127\.0\.0\.1:/ {print $1; found=1; exit} END { if (!found) exit 1 }' || adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
APP_ID="${HENYO_APP_ID:-link.liaru.henyo}"
SERVICE="$APP_ID/link.liaru.henyo.HenyoAccessibilityService"

[ -n "$SERIAL" ] || { echo "No local adb device found" >&2; exit 1; }

old="$(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')"
without="$(printf '%s' "$old" | sed "s#:$SERVICE##; s#$SERVICE:##; s#$SERVICE##")"

case "$without" in
  null|"") adb -s "$SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null ;;
  *) adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$without" ;;
esac
sleep 1

case "$old" in
  *"$SERVICE"*) new="$old" ;;
  null|"") new="$SERVICE" ;;
  *) new="$old:$SERVICE" ;;
esac

adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$new"
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
sleep 1

curl --noproxy '*' -sS --max-time 3 http://127.0.0.1:8765/health
printf '\n'
curl --noproxy '*' -sS --max-time 3 'http://127.0.0.1:8765/ui/tree?maxDepth=1'
printf '\n'
