#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
EXPECTED_APP_ID="link.liaru.henyo"
EXPECTED_CERT_SHA256="c3299697893a8efb2552cfd72e7958a417c303716019d4cdd4bebc4430cd6e0d"
EXPECTED_KEYSTORE="${HOME}/.config/henyo/signing/link.liaru.henyo-release.keystore"
CREDENTIALS_FILE="${HENYO_RELEASE_CREDENTIALS:-${HOME}/.config/henyo/signing/link.liaru.henyo-release.env}"
VERSION_NAME="${HENYO_VERSION_NAME:-0.1.0}"
VERSION_CODE="${HENYO_VERSION_CODE:-1}"
APK="${PROJECT_DIR}/build/henyo-v${VERSION_NAME}.apk"
INSTALL=1
SERIAL="${ADB_SERIAL:-}"

usage() {
    echo "usage: scripts/deploy-android.sh [--check-only] [--serial SERIAL]" >&2
}

while (($#)); do
    case "$1" in
        --check-only)
            INSTALL=0
            shift
            ;;
        --serial)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            SERIAL="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

for command in adb aapt2 apksigner gradle keytool sha256sum; do
    command -v "$command" >/dev/null || {
        echo "deploy guard: required command not found: $command" >&2
        exit 1
    }
done

if [[ ! -f "$CREDENTIALS_FILE" ]]; then
    echo "deploy guard: release credentials are missing: $CREDENTIALS_FILE" >&2
    exit 1
fi
source "$CREDENTIALS_FILE"

if [[ ${HENYO_RELEASE_KEYSTORE:-} != "$EXPECTED_KEYSTORE" ]]; then
    echo "deploy guard: configured keystore is not the canonical Henyo release keystore" >&2
    exit 1
fi
if [[ ${HENYO_RELEASE_KEY_ALIAS:-} != "henyo" || -z ${HENYO_RELEASE_STORE_PASSWORD:-} || -z ${HENYO_RELEASE_KEY_PASSWORD:-} ]]; then
    echo "deploy guard: release signing credentials are incomplete" >&2
    exit 1
fi

if [[ -z "$SERIAL" ]]; then
    mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ ${#DEVICES[@]} -ne 1 ]]; then
        echo "deploy guard: expected exactly one authorized ADB device; pass --serial explicitly" >&2
        exit 1
    fi
    SERIAL="${DEVICES[0]}"
fi

if ! adb devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" {found=1} END {exit !found}'; then
    echo "deploy guard: ADB target is not an authorized online device: $SERIAL" >&2
    exit 1
fi

if [[ ! -f "$EXPECTED_KEYSTORE" ]]; then
    echo "deploy guard: canonical update keystore is missing: $EXPECTED_KEYSTORE" >&2
    echo "deploy guard: refusing to generate a replacement key or application ID" >&2
    exit 1
fi

canonical_cert=$(
    keytool -exportcert \
        -alias "$HENYO_RELEASE_KEY_ALIAS" \
        -keystore "$EXPECTED_KEYSTORE" \
        -storepass:env HENYO_RELEASE_STORE_PASSWORD |
        sha256sum |
        awk '{print tolower($1)}'
)
if [[ "$canonical_cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "deploy guard: canonical release keystore does not match the pinned certificate" >&2
    exit 1
fi

remote_path=$(adb -s "$SERIAL" shell pm path "$EXPECTED_APP_ID" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
if [[ -z "$remote_path" ]]; then
    echo "deploy guard: existing $EXPECTED_APP_ID installation was not found" >&2
    echo "deploy guard: refusing a first install; investigate the device/package selection" >&2
    exit 1
fi

tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
installed_apk="$tmp_dir/installed.apk"
adb -s "$SERIAL" pull "$remote_path" "$installed_apk" >/dev/null

installed_app_id=$(aapt2 dump badging "$installed_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
if [[ "$installed_app_id" != "$EXPECTED_APP_ID" ]]; then
    echo "deploy guard: pulled installed APK is '$installed_app_id', expected '$EXPECTED_APP_ID'" >&2
    exit 1
fi

cert_digest() {
    apksigner verify --print-certs "$1" |
        sed -n 's/^.*certificate SHA-256 digest: //p' |
        tr '[:upper:]' '[:lower:]' |
        head -n 1
}

installed_cert=$(cert_digest "$installed_apk")
if [[ "$installed_cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "deploy guard: installed $EXPECTED_APP_ID certificate is not the pinned release certificate" >&2
    exit 1
fi

cd "$PROJECT_DIR"
HENYO_VERSION_NAME="$VERSION_NAME" HENYO_VERSION_CODE="$VERSION_CODE" \
    gradle --no-daemon clean check assembleRelease

if [[ ! -f "$APK" ]]; then
    echo "deploy guard: expected APK was not produced: $APK" >&2
    exit 1
fi

built_app_id=$(aapt2 dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
if [[ "$built_app_id" != "$EXPECTED_APP_ID" ]]; then
    echo "deploy guard: APK application ID is '$built_app_id', expected '$EXPECTED_APP_ID'" >&2
    exit 1
fi

built_cert=$(cert_digest "$APK")
if [[ "$built_cert" != "$EXPECTED_CERT_SHA256" || "$built_cert" != "$installed_cert" ]]; then
    echo "deploy guard: built APK certificate does not match the pinned and installed release certificate" >&2
    exit 1
fi

echo "deploy guard: preflight passed for $EXPECTED_APP_ID on $SERIAL"
echo "deploy guard: certificate $EXPECTED_CERT_SHA256"

if [[ $INSTALL -eq 0 ]]; then
    echo "deploy guard: check-only mode; APK was not installed"
    exit 0
fi

adb -s "$SERIAL" install -r "$APK"

post_remote_path=$(adb -s "$SERIAL" shell pm path "$EXPECTED_APP_ID" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
if [[ -z "$post_remote_path" ]]; then
    echo "deploy guard: package missing after adb install -r" >&2
    exit 1
fi
post_apk="$tmp_dir/post-install.apk"
adb -s "$SERIAL" pull "$post_remote_path" "$post_apk" >/dev/null
post_cert=$(cert_digest "$post_apk")
post_app_id=$(aapt2 dump badging "$post_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
if [[ "$post_app_id" != "$EXPECTED_APP_ID" || "$post_cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "deploy guard: post-install package or certificate verification failed" >&2
    exit 1
fi

echo "deploy guard: updated and verified $EXPECTED_APP_ID on $SERIAL"
