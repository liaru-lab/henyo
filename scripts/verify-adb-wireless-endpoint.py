#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
DISCOVERY = (ROOT / "src/main/java/link/liaru/henyo/AdbWirelessDiscovery.java").read_text()
TOKENS = (ROOT / "src/main/java/link/liaru/henyo/BearerTokenManager.java").read_text()
POLICY = (ROOT / "src/main/java/link/liaru/henyo/BearerAuthPolicy.java").read_text()
ACTIVITY = (ROOT / "src/main/java/link/liaru/henyo/MainActivity.java").read_text()
MANIFEST = (ROOT / "src/main/AndroidManifest.xml").read_text()
OPENAPI = (ROOT / "docs/openapi.yaml").read_text()


def require(source: str, marker: str, message: str) -> None:
    if marker not in source:
        raise AssertionError(message)


require(DISCOVERY, "NsdManager", "discovery must use Android NSD")
require(DISCOVERY, '"_adb-tls-connect._tcp"', "connect service must be discovered")
require(DISCOVERY, '"_adb-tls-pairing._tcp"', "pairing service must be discovered")
require(DISCOVERY, "localAddresses", "discovery must select the local device")
require(DISCOVERY, "CACHE_TTL_MS", "discovery results must be short-lived")
require(SERVICE, '"/v1/adb/wireless-endpoint"', "the endpoint must be routed")
require(SERVICE, "SCOPE_ADB_WIRELESS_ENDPOINT", "the endpoint must require its capability")
require(TOKENS, 'SCOPE_ADB_WIRELESS_ENDPOINT = "adb-wireless-endpoint"',
        "the dedicated token capability must be known")
require(POLICY, 'path.equals("/v1/adb/wireless-endpoint")',
        "remote endpoint must require Bearer authentication")
require(ACTIVITY, "Allow wireless ADB endpoint discovery",
        "the capability must be configurable in the Android UI")
require(ACTIVITY, "ACTION_APPLICATION_DEVELOPMENT_SETTINGS",
        "the UI must provide a developer-options entry point")
require(MANIFEST, "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "NSD discovery must declare multicast compatibility permission")
require(OPENAPI, "/v1/adb/wireless-endpoint:", "the API contract must document the endpoint")
require(OPENAPI, "adb-wireless-endpoint", "the API contract must document the capability")
require(OPENAPI, "pairingPort", "the API contract must document the pairing port")
if "pairingCode" in DISCOVERY or "pairing_code" in DISCOVERY:
    raise AssertionError("discovery must not handle pairing codes")

print("Android wireless ADB endpoint verifier passed")
