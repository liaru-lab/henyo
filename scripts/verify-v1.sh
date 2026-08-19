#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
HENYO="$ROOT/bin/henyo"

run_ok() {
  output="$("$@")"
  printf '%s\n' "$output"
  printf '%s\n' "$output" | grep -q '"ok":true'
}

echo "v1 health"
run_ok "$HENYO" v1 health
echo

echo "v1 launch settings"
run_ok "$HENYO" v1 launch com.android.settings
echo
sleep 1

echo "v1 current"
run_ok "$HENYO" v1 current
echo

echo "v1 tree"
run_ok "$HENYO" v1 tree 3
echo

echo "v1 find Settings"
run_ok "$HENYO" v1 find Settings --exact --field desc
echo

echo "v1 wait Settings"
output="$(curl --noproxy '*' -sS -X POST -H 'Content-Type: application/json' \
  -d '{"selector":{"text":"Settings","exact":true,"field":"desc"},"timeout":3000,"interval":100}' \
  'http://127.0.0.1:8765/v1/ui/wait')"
printf '%s\n' "$output"
printf '%s\n' "$output" | grep -q '"ok":true'
echo

echo "v1 tap"
run_ok "$HENYO" v1 tap 10 10
echo

echo "v1 click-point"
run_ok "$HENYO" v1 click-point 10 10
echo

echo "v1 click-bounds"
run_ok "$HENYO" v1 click-bounds 0 0 20 20
echo

echo "v1 screenshot"
shot="$("$HENYO" v1 screenshot --ttl 60 --prefix henyo-verify-v1)"
printf '%s\n' "$shot"
test -s "$shot"
python - "$shot" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
if path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit("not a png")
PY
echo

echo "v1 home"
run_ok "$HENYO" v1 home
echo
