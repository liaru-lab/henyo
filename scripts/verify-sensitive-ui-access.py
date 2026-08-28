#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "build.gradle").read_text()
CONFIG = (ROOT / "src/main/res/xml/accessibility_service_config.xml").read_text()
TOKENS = (ROOT / "src/main/java/link/liaru/henyo/BearerTokenManager.java").read_text()
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
POLICY = (ROOT / "src/main/java/link/liaru/henyo/SensitiveUiAccessPolicy.java").read_text()
ACTIVITY = (ROOT / "src/main/java/link/liaru/henyo/MainActivity.java").read_text()
PROTOCOL = (ROOT / "docs/ws-control-protocol.md").read_text()
OPENAPI = (ROOT / "docs/openapi.yaml").read_text()


def require(source, fragment, message):
    if fragment not in source:
        raise AssertionError(message)


require(BUILD, "def resourceAndroidJar", "resource link platform must be explicit")
require(BUILD, 'def minSdk = "26"', "minimum SDK must remain unchanged")
require(BUILD, 'def targetSdk = "30"', "target SDK must remain unchanged")
require(CONFIG, 'android:isAccessibilityTool="true"', "accessibility-tool declaration missing")
require(TOKENS, 'SCOPE_SENSITIVE_UI_CONTROL = "sensitive-ui-control"', "scope constant missing")
require(TOKENS, "hasActiveScope", "active-token scope check missing")
require(ACTIVITY, "confirmSensitiveUiControl(record)", "on-device confirmation missing")
require(ACTIVITY, "Allow protected Android controls?", "warning title missing")
require(SERVICE, "sensitive_ui_permission_required", "authorization failure missing")
require(SERVICE, "activeWindowContainsSensitiveUi()", "platform sensitivity check missing")
require(SERVICE, "node.isAccessibilityDataSensitive()", "sensitive-node detection missing")
require(POLICY, 'path.startsWith("/ui/")', "legacy UI endpoint guard missing")
require(POLICY, 'path.startsWith("/screen/")', "legacy screenshot endpoint guard missing")
require(PROTOCOL, "sensitive-ui-control", "protocol authorization documentation missing")
require(OPENAPI, "enum: [control, token-management, termux-command, sensitive-ui-control]",
        "token metadata scope schema missing sensitive UI scope")

requested_scopes = SERVICE[SERVICE.index("private static List<String> requestedScopes"):
                           SERVICE.index("private Response tokenList", SERVICE.index("private static List<String> requestedScopes"))]
if "sensitive-ui-control" in requested_scopes or "SCOPE_SENSITIVE_UI_CONTROL" in requested_scopes:
    raise AssertionError("remote scope requests must not grant sensitive UI control")

print("sensitive UI access verification passed")
