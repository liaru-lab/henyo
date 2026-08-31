#!/usr/bin/env python3
"""Offline checks for target-aware CLI requests and version negotiation."""

import contextlib
import io
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

import henyo.cli as cli  # noqa: E402


def invoke(argv, response=None):
    calls = []
    original = cli.helper_request

    def fake(payload):
        calls.append(payload)
        if response is not None:
            return response
        return {"type": "result", "ok": True, "result": {"ok": True}}

    cli.helper_request = fake
    output = io.StringIO()
    try:
        with contextlib.redirect_stdout(output):
            code = cli.main(argv)
    finally:
        cli.helper_request = original
    return code, calls, json.loads(output.getvalue())


def assert_call(argv, op, expected):
    code, calls, result = invoke(argv)
    assert code == 0 and result == {"ok": True}
    assert calls == [{"cmd": "call", "op": op, "params": expected}]


def main():
    target = {"package": "com.example.maps", "windowId": 17, "displayId": 2}
    target_args = ["--package", target["package"], "--window-id", "17", "--display-id", "2"]

    assert_call(["find", "Search", "--exact", *target_args], "ui.find", {
        "selector": {"text": "Search", "field": "any", "exact": True}, **target,
    })
    assert_call(["click", "Search", *target_args], "ui.click", {
        "selector": {"text": "Search", "field": "any"}, **target,
    })
    assert_call(["set", "Search", "Tokyo", *target_args], "ui.setText", {
        "selector": {"text": "Search", "field": "any"}, "value": "Tokyo", **target,
    })
    assert_call(["wait", "Result", "--timeout", "9000", *target_args], "ui.wait", {
        "selector": {"text": "Result", "field": "any"},
        "timeout": 9000, "interval": 100, **target,
    })
    assert_call(["scroll", "down", *target_args], "ui.scroll", {
        "direction": "down", **target,
    })
    assert_call(["tap", "25", "30", *target_args], "ui.tap", {
        "x": 25, "y": 30, **target,
    })

    original = cli.helper_request
    screenshot_calls = []
    cli.helper_request = lambda payload: (
        screenshot_calls.append(payload) or {
            "type": "result", "ok": True, "result": {
                "ok": True, "contentType": "image/png", "encoding": "base64", "data": "",
            },
        }
    )
    try:
        cli.screenshot_payload_via_helper("5000", target={
            **target, "captureMode": "window", "includeIndicator": False,
        })
    finally:
        cli.helper_request = original
    assert screenshot_calls == [{
        "cmd": "call", "op": "screen.screenshot", "params": {
            "timeout": 5000, **target, "captureMode": "window", "includeIndicator": False,
        },
    }]

    status = {
        "ok": True,
        "application": {"id": "link.liaru.henyo", "versionName": "0.4.0", "versionCode": 14},
        "protocolVersion": 1,
        "contractRevision": "remote-control.core/1.0.0",
        "platform": {"name": "android", "version": "16"},
        "capabilities": {"profile": "remote-control.core/1", "features": ["windowTargeting"]},
    }
    code, calls, printed = invoke(["version"], status)
    assert code == 0 and calls == [{"cmd": "session.status"}]
    assert printed["application"] == status["application"]
    assert printed["protocol"] == {
        "version": 1, "contractRevision": "remote-control.core/1.0.0",
    }
    assert printed["capabilities"] == status["capabilities"]

    try:
        cli.main(["version", "--unknown"])
        raise AssertionError("version accepted an unknown option")
    except SystemExit as exc:
        assert str(exc) == "unknown version option: --unknown"

    print("window client contract verifier passed")


if __name__ == "__main__":
    main()
