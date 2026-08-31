#!/usr/bin/env python3
"""Deterministic fixture checks for the additive helper session checkpoint."""

import json
import sys
import threading
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

import henyo.helper as helper  # noqa: E402


FIXTURES = ROOT / "tests" / "fixtures" / "helper-session-status"


class FakeWs:
    def __init__(self, daemon, event=None, available=True):
        self.daemon = daemon
        self.event = event
        self.available = available
        self.ensure_calls = 0
        self.lock = threading.RLock()
        self.url = "ws://fixture.invalid/v1/ws/control"
        self.generation = 7
        self.sock = object() if available else None
        self.last_error = "private endpoint detail"
        self.calls = []

    def ensure_connected(self):
        self.ensure_calls += 1
        if not self.available:
            return False
        if self.event is not None:
            event, self.event = self.event, None
            self.daemon.handle_event(event)
        return True

    def close(self, reason="ws_disconnected", expected_sock=None):
        del expected_sock
        with self.lock:
            if self.sock is None:
                return False
            self.sock = None
            self.generation += 1
        self.daemon.handle_disconnect(reason)
        return True

    def call(self, op, params, *args, **kwargs):
        self.calls.append((op, params, args, kwargs))
        return {"type": "result", "ok": True, "result": {"ok": True}}


def fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def daemon_with(event=None, available=True):
    daemon = helper.HelperDaemon(ROOT / "build" / "verify-session-status.sock")
    daemon.ws = FakeWs(daemon, event, available)
    return daemon


def main():
    android = daemon_with(fixture("legacy-android.json"))
    before = android.dispatch({"cmd": "status"})
    assert before["ok"] is True and android.ws.ensure_calls == 0
    assert android.dispatch({"cmd": "session.status"}) == {
        "ok": True, "protocolVersion": 1, "serviceEpoch": "android-epoch",
    }
    assert android.dispatch({
        "cmd": "call", "op": "ui.tree", "params": {"package": "com.example.app"},
    }) == {"ok": False, "error": "capability_required", "capability": "windowTargeting"}

    android_event = fixture("legacy-android.json")
    android_event.update({
        "application": {
            "id": "com.example.henyo", "versionName": "1.2.3", "versionCode": 42,
        },
        "contractRevision": helper.SUPPORTED_CONTRACT_REVISION,
        "platform": {"name": "android", "version": "16"},
        "capabilities": {
            "profile": helper.SUPPORTED_CAPABILITY_PROFILE,
            "features": ["windowTargeting", "explicitCaptureMode", "windowCapture"],
            "limits": {},
            "operations": {},
        },
    })
    additive_android = daemon_with(android_event)
    additive_result = additive_android.dispatch({"cmd": "session.status"})
    assert additive_result["application"] == android_event["application"]
    assert additive_result["capabilities"]["features"] == [
        "windowTargeting", "explicitCaptureMode", "windowCapture",
    ]
    allowed = additive_android.dispatch({
        "cmd": "call", "op": "ui.tree", "params": {"package": "com.example.app"},
    })
    assert allowed["ok"] is True and additive_android.ws.calls[-1][0] == "ui.tree"
    capture_allowed = additive_android.dispatch({
        "cmd": "call", "op": "screen.screenshot", "params": {"captureMode": "window"},
    })
    assert capture_allowed["ok"] is True

    bad_application = dict(android_event)
    bad_application["application"] = dict(android_event["application"])
    bad_application["application"]["versionCode"] = "42"
    assert daemon_with(bad_application).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "session_metadata_invalid",
    }

    cached_tree = {
        "target": {"package": "com.example.first", "windowId": 7, "displayId": 2},
    }
    assert helper.HelperDaemon.tree_target_matches(
        {"package": "com.example.first", "displayId": 2}, cached_tree,
    )
    assert not helper.HelperDaemon.tree_target_matches(
        {"package": "com.example.second"}, cached_tree,
    )

    ios = daemon_with(fixture("ios-additive.json"))
    result = ios.dispatch({"cmd": "session.status"})
    assert result["ok"] is True
    assert result["platform"] == {"name": "ios", "version": "27.0"}
    assert result["deviceReady"] is True and result["deviceState"] == "ready"
    assert "operatorAction" not in result
    assert result["capabilities"]["features"] == ["expectedServiceEpoch", "mutationOutcome"]
    assert result["capabilities"]["limits"] == {"inboundFrameBytes": 65536, "batchSteps": 64}
    assert result["capabilities"]["operations"] == {
        "screen.screenshot": {
            "mutates": False, "coordinateSpaces": ["screen", "screenshot"],
        },
        "ui.tap": {"mutates": True},
    }
    assert "privateDetail" not in result["platform"] and "privateDetail" not in result["capabilities"]

    operation_ios = daemon_with(fixture("ios-operation-metadata.json"))
    operation_result = operation_ios.dispatch({"cmd": "session.status"})
    operations = operation_result["capabilities"]["operations"]
    assert operations == {
        "ui.tree": {"mutates": False, **helper.UI_TREE_CAPABILITY_METADATA},
        "screen.screenshot": {
            "mutates": False,
            "coordinateSpaces": ["screen", "screenshot"],
            "includeIndicator": False,
        },
        "ui.tap": {"mutates": True, "coordinateSpaces": ["screen", "screenshot"]},
        "app.activate": {"mutates": True, "identityField": "appId"},
        "session.wakeHint": {"mutates": True},
        "input.text": {
            "mutates": True,
            "encoding": "unicode-scalar-sequence",
            "normalization": "none",
            "maxCodePoints": 4096,
            "maxUtf8Bytes": 16384,
            "secureTargetDetection": False,
            "pasteboardRestoration": "best-effort-compare-and-swap",
        },
        "input.key": {
            "mutates": True,
            "keys": ["ENTER", "BACKSPACE", "A", "DIGIT_0"],
            "modifiers": ["SHIFT", "META"],
        },
    }
    assert "deviceSerial" not in json.dumps(operation_result)
    assert "provider" not in json.dumps(operation_result)
    assert "pasteboardId" not in json.dumps(operation_result)
    assert "rawUsages" not in json.dumps(operation_result)
    assert "backendDetail" not in json.dumps(operation_result)

    bad_operation_metadata = fixture("ios-operation-metadata.json")
    bad_operation_metadata["capabilities"]["operations"]["input.text"]["maxUtf8Bytes"] = 0
    assert daemon_with(bad_operation_metadata).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "capability_invalid",
    }

    oversized_keys = fixture("ios-operation-metadata.json")
    oversized_keys["capabilities"]["operations"]["input.key"]["keys"] = ["A"] * 65
    assert daemon_with(oversized_keys).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "capability_invalid",
    }

    bad_tree_metadata = fixture("ios-operation-metadata.json")
    bad_tree_metadata["capabilities"]["operations"]["ui.tree"]["maxDepth"] = 31
    assert daemon_with(bad_tree_metadata).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "capability_invalid",
    }

    missing_tree_metadata = fixture("ios-operation-metadata.json")
    del missing_tree_metadata["capabilities"]["operations"]["ui.tree"]["redaction"]
    assert daemon_with(missing_tree_metadata).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "capability_invalid",
    }

    reordered_tree_metadata = fixture("ios-operation-metadata.json")
    reordered_tree_metadata["capabilities"]["operations"]["ui.tree"]["parameters"] = [
        "maxNodes", "maxDepth", "redact",
    ]
    assert daemon_with(reordered_tree_metadata).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "capability_invalid",
    }

    incompatible = fixture("legacy-android.json")
    incompatible["protocolVersion"] = 2
    assert daemon_with(incompatible).dispatch({"cmd": "session.status"}) == {
        "ok": False, "error": "protocol_incompatible",
    }

    partial = fixture("legacy-android.json")
    partial["contractRevision"] = helper.SUPPORTED_CONTRACT_REVISION
    assert daemon_with(partial).dispatch({"cmd": "session.status"})["error"] == "protocol_invalid"

    auth_required = fixture("legacy-android.json")
    auth_required["requiresAuth"] = True
    assert daemon_with(auth_required).dispatch({"cmd": "session.status"})["error"] == "auth_required"

    bad_capability = fixture("ios-additive.json")
    bad_capability["capabilities"]["operations"]["screen.screenshot"]["mutates"] = "no"
    assert daemon_with(bad_capability).dispatch({"cmd": "session.status"})["error"] == "capability_invalid"

    bad_state = fixture("ios-additive.json")
    bad_state["deviceState"] = "future_state"
    assert daemon_with(bad_state).dispatch({"cmd": "session.status"})["error"] == "session_metadata_invalid"

    bad_action = fixture("ios-additive.json")
    bad_action.update({
        "deviceReady": False,
        "deviceState": "operator_action_required",
        "operatorAction": "future_action",
    })
    assert daemon_with(bad_action).dispatch({"cmd": "session.status"})["error"] == "session_metadata_invalid"

    operator = fixture("ios-additive.json")
    operator.update({
        "deviceReady": False,
        "deviceState": "operator_action_required",
        "operatorAction": "physical_reboot_then_first_unlock",
    })
    operator_result = daemon_with(operator).dispatch({"cmd": "session.status"})
    assert operator_result["operatorAction"] == "physical_reboot_then_first_unlock"

    unavailable = daemon_with(available=False)
    assert unavailable.dispatch({"cmd": "session.status"}) == {"ok": False, "error": "ws_unavailable"}
    assert "private endpoint detail" not in json.dumps(unavailable.dispatch({"cmd": "session.status"}))

    disconnected = daemon_with()
    disconnected.ws.sock = None
    assert disconnected.dispatch({"cmd": "session.status"}) == {"ok": False, "error": "ws_disconnected"}

    stale = daemon_with(fixture("ios-additive.json"))
    assert stale.dispatch({"cmd": "session.status"})["ok"] is True
    stale.handle_disconnect("idle_timeout")
    assert stale.cache["sessionMetadata"] is None
    stale.ws.available = False
    assert stale.dispatch({"cmd": "session.status"})["error"] == "ws_unavailable"

    timeout_daemon = daemon_with()
    original_timeout = helper.SESSION_READY_TIMEOUT_SECONDS
    helper.SESSION_READY_TIMEOUT_SECONDS = 0.01
    try:
        assert timeout_daemon.dispatch({"cmd": "session.status"}) == {"ok": False, "error": "timeout"}
    finally:
        helper.SESSION_READY_TIMEOUT_SECONDS = original_timeout

    retired = daemon_with()
    retired.cache["sessionMetadata"] = {"protocolVersion": 1, "serviceEpoch": "retired"}
    retired.cache["sessionMetadataGeneration"] = retired.ws.generation - 1
    helper.SESSION_READY_TIMEOUT_SECONDS = 0.01
    try:
        assert retired.dispatch({"cmd": "session.status"}) == {"ok": False, "error": "timeout"}
    finally:
        helper.SESSION_READY_TIMEOUT_SECONDS = original_timeout

    closing = daemon_with(fixture("ios-additive.json"))
    assert closing.dispatch({"cmd": "session.status"})["ok"] is True
    closing.handle_event({"type": "event", "event": "session.closing", "reason": "idle_timeout"})
    assert closing.cache["sessionMetadata"] is None

    assert helper.helper_read_timeout({"cmd": "session.status"}) >= 45.0
    print("helper session status verifier passed")


if __name__ == "__main__":
    main()
