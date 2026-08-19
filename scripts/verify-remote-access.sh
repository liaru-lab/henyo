#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

echo "source access filter"
scripts/verify-source-access-filter.sh
echo

echo "bearer token auth"
scripts/verify-bearer-token-auth.sh
echo

echo "pairing TOTP"
scripts/verify-pairing-totp.sh
echo

echo "assemble debug APK"
gradle assembleDebug
echo

if curl --noproxy '*' -fsS --max-time 3 http://127.0.0.1:8765/v1/health >/dev/null; then
  echo "local service smoke"
  bin/henyo v1 health | grep -q '"ok":true'
  bin/henyo v1 current | grep -q '"ok":true'
  bin/henyo v1 tree 2 | grep -q '"ok":true'
  bin/henyo v1 back | grep -q '"ok":true'
else
  echo "local service smoke skipped: Henyo is not reachable on 127.0.0.1:8765"
fi

echo "remote access verifier passed"
