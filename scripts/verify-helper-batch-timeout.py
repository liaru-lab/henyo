#!/usr/bin/env python3
"""Deterministic batch timeout forwarding and deadline checks."""

import contextlib
import io
import json
import os
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

import henyo.cli as cli  # noqa: E402
import henyo.helper as helper  # noqa: E402


class PassiveSocket:
    def sendall(self, _data):
        pass


class RecordingQueue:
    waits = []

    def __init__(self, maxsize=0):
        self.value = None

    def put(self, value):
        self.value = value

    def get(self, timeout=None):
        self.waits.append(timeout)
        return self.value


class RecordingQueueModule:
    Queue = RecordingQueue


def verify_deadlines():
    assert helper.batch_ws_timeout_seconds(None) == 20.0
    assert helper.helper_read_timeout({"cmd": "batch"}) == 30.0
    assert helper.batch_ws_timeout_seconds(25_000) == 30.0
    assert helper.helper_read_timeout({"cmd": "batch", "timeoutMs": 25_000}) == 35.0
    assert helper.batch_ws_timeout_seconds(300_000) == 305.0
    assert helper.helper_read_timeout({"cmd": "batch", "timeoutMs": 300_000}) == 310.0
    assert helper.helper_read_timeout({
        "cmd": "call", "op": "screen.screenshot", "params": {"timeout": 300_000},
    }) == 310.0
    assert helper.helper_read_timeout({
        "cmd": "call", "op": "app.activate", "timeoutMs": 120_000,
    }) == 130.0

    original = os.environ.get("HENYO_HELPER_READ_TIMEOUT")
    try:
        os.environ["HENYO_HELPER_READ_TIMEOUT"] = "1"
        assert helper.helper_read_timeout({"cmd": "batch", "timeoutMs": 25_000}) == 35.0
        assert helper.helper_read_timeout({
            "cmd": "call", "op": "screen.screenshot", "params": {"timeout": 300_000},
        }) == 310.0
        assert helper.helper_read_timeout({
            "cmd": "call", "op": "app.activate", "timeoutMs": 120_000,
        }) == 130.0
        os.environ["HENYO_HELPER_READ_TIMEOUT"] = "40"
        assert helper.helper_read_timeout({"cmd": "batch", "timeoutMs": 25_000}) == 40.0
    finally:
        if original is None:
            os.environ.pop("HENYO_HELPER_READ_TIMEOUT", None)
        else:
            os.environ["HENYO_HELPER_READ_TIMEOUT"] = original


def verify_ws_payload():
    captured = []
    original_encode = helper.encode_client_text
    ws = helper.WsClient("ws://127.0.0.1:8765/v1/ws/control", "", lambda _event: None)
    ws.sock = PassiveSocket()
    ws.queue_mod = RecordingQueueModule
    RecordingQueue.waits = []

    def capture(payload):
        captured.append(json.loads(json.dumps(payload)))
        ws.pending[payload["id"]].put({"type": "result", "id": payload["id"], "ok": True})
        return b"frame"

    helper.encode_client_text = capture
    try:
        assert ws.batch([{"id": "one", "op": "app.current"}])["ok"]
        assert ws.batch([{"id": "two", "op": "app.current"}], timeout_ms=25_000)["ok"]
        assert ws.batch([{"id": "three", "op": "app.current"}], timeout_ms=300_000)["ok"]
    finally:
        helper.encode_client_text = original_encode

    assert "timeoutMs" not in captured[0]
    assert captured[1]["timeoutMs"] == 25_000
    assert captured[2]["timeoutMs"] == 300_000
    assert RecordingQueue.waits == [20.0, 30.0, 305.0]


def verify_helper_forwarding_and_validation():
    class RecorderWs:
        def __init__(self):
            self.calls = []

        def batch(self, *args, **kwargs):
            self.calls.append((args, kwargs))
            return {"ok": True, "result": {"ok": True}}

        def call(self, op, params, action_id="", timeout=10.0, **kwargs):
            self.calls.append(((op, params, action_id), {"timeout": timeout, **kwargs}))
            return {"ok": True, "result": {"ok": True}}

    daemon = helper.HelperDaemon(ROOT / "build" / "verify-helper-batch-timeout.sock")
    daemon.ws = RecorderWs()
    steps = [{"id": "one", "op": "app.current"}]

    assert daemon.dispatch({"cmd": "batch", "steps": steps})["ok"]
    assert daemon.ws.calls[-1][1] == {}
    assert daemon.dispatch({"cmd": "batch", "steps": steps, "timeoutMs": 25_000})["ok"]
    assert daemon.ws.calls[-1][1]["timeout_ms"] == 25_000
    assert daemon.dispatch({"cmd": "batch", "steps": steps, "timeoutMs": 300_000})["ok"]
    assert daemon.ws.calls[-1][1]["timeout_ms"] == 300_000
    assert daemon.dispatch({
        "cmd": "call", "op": "screen.screenshot", "params": {"timeout": 300_000},
    })["ok"]
    assert daemon.ws.calls[-1][1]["timeout"] == 305.0
    assert daemon.dispatch({"cmd": "call", "op": "app.current", "params": {}})["ok"]
    assert daemon.ws.calls[-1][1]["timeout"] == 10.0
    assert daemon.dispatch({
        "cmd": "call", "op": "termux.exec", "params": {"timeout": 300_000},
    })["ok"]
    assert daemon.ws.calls[-1][1]["timeout"] == 125.0

    for invalid in (True, 0, -1, 300_001, 1.5, "25000"):
        response = daemon.dispatch({"cmd": "batch", "steps": steps, "timeoutMs": invalid})
        assert response["ok"] is False
        assert response["error"] == "invalid_batch_timeout"


def verify_cli_forwarding():
    requests = []
    original_request = cli.helper_request
    cli.helper_request = lambda payload: requests.append(payload) or {"ok": True}
    try:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "batch.json"
            path.write_text(json.dumps({
                "timeoutMs": 25_000,
                "steps": [{"id": "one", "op": "app.current"}],
            }), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                assert cli.batch(str(path)) == 0
            assert requests[-1]["timeoutMs"] == 25_000

            path.write_text(json.dumps({"timeoutMs": 0, "steps": []}), encoding="utf-8")
            try:
                cli.batch(str(path))
            except SystemExit as exc:
                assert "timeoutMs" in str(exc)
            else:
                raise AssertionError("CLI accepted invalid timeoutMs")
    finally:
        cli.helper_request = original_request


def main():
    verify_deadlines()
    verify_ws_payload()
    verify_helper_forwarding_and_validation()
    verify_cli_forwarding()
    print("helper batch timeout verifier passed")


if __name__ == "__main__":
    main()
