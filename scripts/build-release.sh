#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
EXPECTED_APP_ID="link.liaru.henyo"
EXPECTED_CERT_SHA256="c3299697893a8efb2552cfd72e7958a417c303716019d4cdd4bebc4430cd6e0d"
CREDENTIALS_FILE="${HENYO_RELEASE_CREDENTIALS:-${HOME}/.config/henyo/signing/link.liaru.henyo-release.env}"

usage() {
    echo "usage: scripts/build-release.sh VERSION_NAME VERSION_CODE" >&2
    echo "example: scripts/build-release.sh 0.1.0 1" >&2
}

[[ $# -eq 2 ]] || { usage; exit 2; }
VERSION_NAME=$1
VERSION_CODE=$2

if [[ ! -f "$CREDENTIALS_FILE" ]]; then
    echo "release build: signing credentials not found: $CREDENTIALS_FILE" >&2
    exit 1
fi

# The credentials file is local-only and exports the HENYO_RELEASE_* variables.
source "$CREDENTIALS_FILE"

required_variables=(
    HENYO_RELEASE_KEYSTORE
    HENYO_RELEASE_STORE_PASSWORD
    HENYO_RELEASE_KEY_PASSWORD
    HENYO_RELEASE_KEY_ALIAS
)
for variable in "${required_variables[@]}"; do
    if [[ -z ${!variable:-} ]]; then
        echo "release build: $variable is not configured" >&2
        exit 1
    fi
done

for command in aapt2 apksigner gradle keytool sha256sum; do
    command -v "$command" >/dev/null || {
        echo "release build: required command not found: $command" >&2
        exit 1
    }
done

if [[ ! -f "$HENYO_RELEASE_KEYSTORE" ]]; then
    echo "release build: keystore not found: $HENYO_RELEASE_KEYSTORE" >&2
    exit 1
fi

keystore_cert=$(
    keytool -exportcert \
        -alias "$HENYO_RELEASE_KEY_ALIAS" \
        -keystore "$HENYO_RELEASE_KEYSTORE" \
        -storepass:env HENYO_RELEASE_STORE_PASSWORD |
        sha256sum |
        awk '{print tolower($1)}'
)
if [[ "$keystore_cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "release build: keystore certificate does not match the pinned Henyo release certificate" >&2
    exit 1
fi

cd "$PROJECT_DIR"
HENYO_VERSION_NAME="$VERSION_NAME" HENYO_VERSION_CODE="$VERSION_CODE" \
    gradle --no-daemon clean check assembleRelease

APK="${PROJECT_DIR}/build/henyo-v${VERSION_NAME}.apk"
if [[ ! -f "$APK" ]]; then
    echo "release build: expected APK was not produced: $APK" >&2
    exit 1
fi

built_app_id=$(aapt2 dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
if [[ "$built_app_id" != "$EXPECTED_APP_ID" ]]; then
    echo "release build: APK application ID is '$built_app_id', expected '$EXPECTED_APP_ID'" >&2
    exit 1
fi

apksigner verify --verbose "$APK" >/dev/null
built_cert=$(
    apksigner verify --print-certs "$APK" |
        sed -n 's/^.*certificate SHA-256 digest: //p' |
        tr '[:upper:]' '[:lower:]' |
        head -n 1
)
if [[ "$built_cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "release build: APK certificate does not match the pinned Henyo release certificate" >&2
    exit 1
fi

(
    cd "${PROJECT_DIR}/build"
    sha256sum "$(basename "$APK")" >"$(basename "$APK").sha256"
)

echo "release build: verified $APK"
echo "release build: certificate $EXPECTED_CERT_SHA256"
echo "release build: checksum ${APK}.sha256"
