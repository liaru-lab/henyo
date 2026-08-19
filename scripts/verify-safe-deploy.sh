#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEPLOY_SCRIPT="$PROJECT_DIR/scripts/deploy-android.sh"
EXPECTED_CERT="c3299697893a8efb2552cfd72e7958a417c303716019d4cdd4bebc4430cd6e0d"

fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/project/scripts" "$fixture/bin" "$fixture/home/.config/henyo/signing"
cp "$DEPLOY_SCRIPT" "$fixture/project/scripts/deploy-android.sh"

tool_stub="$fixture/bin/tool-stub"
apply_stub() {
    local name=$1
    ln -s tool-stub "$fixture/bin/$name"
}

# One multi-call executable stands in for every external deployment command.
# It never talks to a device. DEPLOY_TEST_CASE selects a bounded failure gate.
sed \
    -e "s|@EXPECTED_CERT@|$EXPECTED_CERT|g" \
    -e 's/^+//' \
    >"$tool_stub" <<'STUB'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
name=$(basename "$0")
case "$name" in
    adb)
        if [[ ${1:-} == devices ]]; then
            printf 'List of devices attached\n'
            if [[ ${DEPLOY_TEST_CASE:-} == multiple_devices ]]; then
                printf 'device-one\tdevice\ndevice-two\tdevice\n'
            else
                printf 'device-one\tdevice\n'
            fi
            exit 0
        fi
        [[ ${1:-} == -s && ${2:-} == device-one ]] || exit 91
        shift 2
        if [[ ${1:-} == shell && ${2:-} == pm && ${3:-} == path ]]; then
            [[ ${DEPLOY_TEST_CASE:-} == missing_package ]] || \
                printf 'package:/data/app/link.liaru.henyo/base.apk\n'
            exit 0
        fi
        if [[ ${1:-} == pull ]]; then
            printf 'installed apk\n' >"$3"
            exit 0
        fi
        if [[ ${1:-} == install ]]; then
            printf 'INSTALL CALLED\n' >>"$DEPLOY_TEST_LOG"
            exit 0
        fi
        exit 92
        ;;
    gradle)
        mkdir -p build
        printf 'built apk\n' >build/henyo-v0.1.0.apk
        ;;
    aapt2)
        app_id=link.liaru.henyo
        target=${!#}
        if [[ ${DEPLOY_TEST_CASE:-} == installed_package_mismatch && $target == */installed.apk ]]; then
            app_id=link.liaru.wrong
        elif [[ ${DEPLOY_TEST_CASE:-} == built_package_mismatch && $target == */henyo-v0.1.0.apk ]]; then
            app_id=link.liaru.henyo.v3
        fi
        printf "package: name='%s' versionCode='1' versionName='0.1.0'\n" "$app_id"
        ;;
    apksigner)
        cert='@EXPECTED_CERT@'
        target=${!#}
        if [[ ${DEPLOY_TEST_CASE:-} == installed_cert_mismatch && $target == */installed.apk ]]; then
            cert=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        elif [[ ${DEPLOY_TEST_CASE:-} == built_cert_mismatch && $target == */henyo-v0.1.0.apk ]]; then
            cert=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
        fi
        printf 'Signer #1 certificate SHA-256 digest: %s\n' "$cert"
        ;;
    keytool)
        printf 'canonical certificate bytes'
        ;;
    sha256sum)
        cert='@EXPECTED_CERT@'
        [[ ${DEPLOY_TEST_CASE:-} == keystore_cert_mismatch ]] && \
            cert=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
        printf '%s  -\n' "$cert"
        ;;
    *)
        exit 93
        ;;
esac
STUB
chmod +x "$tool_stub"
for command in adb aapt2 apksigner gradle keytool sha256sum; do
    apply_stub "$command"
done

run_case() {
    local case_name=$1
    local expected_status=$2
    local key_present=${3:-1}
    local case_dir="$fixture/cases/$case_name"
    local output="$case_dir/output"
    local log="$case_dir/tool.log"
    mkdir -p "$case_dir"
    : >"$log"
    local signing_dir="$fixture/home/.config/henyo/signing"
    local keystore="$signing_dir/link.liaru.henyo-release.keystore"
    local credentials="$signing_dir/link.liaru.henyo-release.env"
    rm -f "$keystore" "$credentials"
    {
        printf 'export HENYO_RELEASE_KEYSTORE=%q\n' "$keystore"
        printf 'export HENYO_RELEASE_STORE_PASSWORD=%q\n' android
        printf 'export HENYO_RELEASE_KEY_PASSWORD=%q\n' android
        printf 'export HENYO_RELEASE_KEY_ALIAS=%q\n' henyo
    } >"$credentials"
    if [[ $key_present -eq 1 ]]; then
        : >"$keystore"
    fi

    set +e
    (
        cd "$fixture/project"
        HOME="$fixture/home" \
        PATH="$fixture/bin:/data/data/com.termux/files/usr/bin" \
        DEPLOY_TEST_CASE="$case_name" \
        DEPLOY_TEST_LOG="$log" \
            scripts/deploy-android.sh --check-only
    ) >"$output" 2>&1
    status=$?
    set -e

    if [[ $expected_status == success && $status -ne 0 ]]; then
        echo "verify-safe-deploy: $case_name unexpectedly failed" >&2
        sed -n '1,120p' "$output" >&2
        exit 1
    fi
    if [[ $expected_status == failure && $status -eq 0 ]]; then
        echo "verify-safe-deploy: $case_name unexpectedly passed" >&2
        exit 1
    fi
    if grep -q 'INSTALL CALLED' "$log"; then
        echo "verify-safe-deploy: $case_name called adb install in check-only/failure mode" >&2
        exit 1
    fi
    echo "verify-safe-deploy: $case_name passed"
}

run_case success success
run_case missing_key failure 0
run_case multiple_devices failure
run_case missing_package failure
run_case keystore_cert_mismatch failure
run_case installed_package_mismatch failure
run_case installed_cert_mismatch failure
run_case built_package_mismatch failure
run_case built_cert_mismatch failure

echo "verify-safe-deploy: all stubbed deployment checks passed"
