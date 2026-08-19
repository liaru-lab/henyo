#!/usr/bin/env python3
"""Deterministic fail-closed coverage for helper remote-target isolation."""

import contextlib
import io
import json
import os
import socket
import tempfile
import threading
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
os.sys.path.insert(0, str(ROOT / "python"))

import henyo.cli as cli  # noqa: E402
import henyo.helper as helper  # noqa: E402


class RecordingWs:
    def __init__(self):
        self.calls = []

    def call(self, op, params=None, *args, **kwargs):
        self.calls.append((op, params, args, kwargs))
        return {"type": "result", "ok": True, "result": {"ok": True, "package": "target-a"}}

    def batch(self, *args, **kwargs):
        self.calls.append(("batch", args, kwargs))
        return {"type": "batchResult", "ok": True, "results": []}

    def set_progress(self, *args, **kwargs):
        self.calls.append(("progress.set", args, kwargs))
        return {"ok": True}

    def finish_progress(self):
        self.calls.append(("progress.finish",))
        return {"ok": True}

    def show_completion(self, message):
        self.calls.append(("completion.show", message))
        return {"ok": True}


def ipc_request(daemon, payload):
    client, server = socket.socketpair()
    worker = threading.Thread(target=daemon.handle_client, args=(server,))
    worker.start()
    try:
        client.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))
        raw = b""
        while not raw.endswith(b"\n"):
            chunk = client.recv(65536)
            if not chunk:
                break
            raw += chunk
    finally:
        client.close()
        worker.join(timeout=2)
    assert not worker.is_alive()
    return json.loads(raw.decode("utf-8"))


def verify_canonical_identity():
    assert helper.canonical_ws_url("WS://Device-A/v1/ws/control") == (
        "ws://device-a:8765/v1/ws/control"
    )
    assert helper.canonical_ws_url("ws://device-a:9000") == "ws://device-a:9000/v1/ws/control"
    assert helper.canonical_ws_url("wss://[2001:DB8::1]:9443/control") == (
        "wss://[2001:db8::1]:9443/control"
    )


def verify_fail_closed_before_dispatch(tmp: Path):
    old_url = os.environ.get("HENYO_URL")
    old_ws_url = os.environ.get("HENYO_WS_URL")
    os.environ["HENYO_URL"] = "http://device-a:8765"
    os.environ.pop("HENYO_WS_URL", None)
    try:
        transport = helper.HelperTransport(
            "unix", tmp / "helper.sock", "127.0.0.1", 0, tmp / "helper.json"
        )
        daemon = helper.HelperDaemon(transport)
    finally:
        if old_url is None:
            os.environ.pop("HENYO_URL", None)
        else:
            os.environ["HENYO_URL"] = old_url
        if old_ws_url is None:
            os.environ.pop("HENYO_WS_URL", None)
        else:
            os.environ["HENYO_WS_URL"] = old_ws_url

    recording = RecordingWs()
    daemon.ws = recording
    secret_tree = {"root": {"text": "target-a-private"}}
    daemon.cache.update({
        "tree": secret_tree,
        "treeActionGeneration": 0,
        "treeUpdatedMonotonicMs": helper.monotonic_ms(),
    })
    target_b = "ws://device-b:8765/v1/ws/control"
    requests = [
        {"cmd": "tree"},
        {"cmd": "current"},
        {"cmd": "observe"},
        {"cmd": "call", "op": "screen.screenshot", "params": {}},
        {"cmd": "call", "op": "global.home", "params": {}},
        {"cmd": "call", "op": "termux.exec", "params": {"commandPath": "/bin/false"}},
        {"cmd": "batch", "steps": [{"op": "global.home", "params": {}}]},
        {"cmd": "progress.set", "goal": "wrong target", "current": "unsafe"},
        {"cmd": "progress.finish"},
        {"cmd": "completion.show", "message": "wrong target"},
        {"cmd": "session.status"},
        {"cmd": "auth.reload"},
    ]
    for request in requests:
        request["targetIdentity"] = target_b
        response = ipc_request(daemon, request)
        assert response["error"] == "helper_target_mismatch", (request, response)
        assert response["expectedTarget"] == target_b, response
        assert response["boundTarget"] == daemon.target_identity, response
        assert "target-a-private" not in json.dumps(response), response
    missing = ipc_request(daemon, {"cmd": "tree"})
    assert missing["error"] == "helper_target_mismatch", missing
    assert recording.calls == [], recording.calls
    assert daemon.cache["tree"] == secret_tree
    assert daemon.cache["pendingAction"] is False

    accepted = ipc_request(daemon, {
        "cmd": "call",
        "targetIdentity": daemon.target_identity,
        "op": "app.current",
        "params": {},
    })
    assert accepted["ok"] is True, accepted
    assert recording.calls[0][0] == "app.current", recording.calls


def verify_screenshot_never_falls_back(tmp: Path):
    mismatch = {
        "ok": False,
        "error": "helper_target_mismatch",
        "expectedTarget": "ws://device-b:8765/v1/ws/control",
        "boundTarget": "ws://device-a:8765/v1/ws/control",
    }
    original_helper_request = cli.helper_request
    original_http_request = cli.http_request
    original_run = cli.subprocess.run
    fallback_calls = []
    cli.helper_request = lambda payload: mismatch
    cli.http_request = lambda *args, **kwargs: fallback_calls.append((args, kwargs))
    cli.subprocess.run = lambda *args, **kwargs: fallback_calls.append((args, kwargs))
    old_tmpdir = os.environ.get("TMPDIR")
    os.environ["TMPDIR"] = str(tmp)
    try:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = cli.screenshot(["--json"])
        assert code == 0
        assert json.loads(output.getvalue())["error"] == "helper_target_mismatch"
        assert fallback_calls == [], fallback_calls
        assert list((tmp / "henyo" / "screens").glob("*.png")) == []
    finally:
        cli.helper_request = original_helper_request
        cli.http_request = original_http_request
        cli.subprocess.run = original_run
        if old_tmpdir is None:
            os.environ.pop("TMPDIR", None)
        else:
            os.environ["TMPDIR"] = old_tmpdir


def verify_daemon_reuse_mismatch(tmp: Path):
    managed = {
        "HENYO_HELPER_SOCKET": str(tmp / "managed.sock"),
        "HENYO_HELPER_DISCOVERY": str(tmp / "managed.json"),
        "HENYO_HELPER_LOG": str(tmp / "managed.log"),
        "HENYO_HELPER_PID": str(tmp / "managed.pid"),
        "HENYO_HELPER_TRANSPORT": "unix",
    }
    names = [*managed, "HENYO_URL", "HENYO_WS_URL"]
    previous = {name: os.environ.get(name) for name in names}
    os.environ.update(managed)
    os.environ.pop("HENYO_WS_URL", None)
    os.environ["HENYO_URL"] = "http://device-a:8765"
    try:
        started = helper.start_background(Path(managed["HENYO_HELPER_SOCKET"]))
        assert started["ok"] is True, started
        assert started["targetIdentity"] == "ws://device-a:8765/v1/ws/control", started

        os.environ["HENYO_URL"] = "http://device-b:8765"
        reused = helper.start_background(Path(managed["HENYO_HELPER_SOCKET"]))
        assert reused["error"] == "helper_target_mismatch", reused
        assert reused["boundTarget"] == "ws://device-a:8765/v1/ws/control", reused
        current = helper.request_helper(
            {"cmd": "current"}, Path(managed["HENYO_HELPER_SOCKET"])
        )
        assert current["error"] == "helper_target_mismatch", current
    finally:
        try:
            helper.stop_helper(Path(managed["HENYO_HELPER_SOCKET"]))
        except OSError:
            pass
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def main():
    verify_canonical_identity()
    with tempfile.TemporaryDirectory(prefix="henyo-target-isolation-") as tmpdir:
        tmp = Path(tmpdir)
        verify_fail_closed_before_dispatch(tmp)
        verify_screenshot_never_falls_back(tmp)
        verify_daemon_reuse_mismatch(tmp)
    print("helper target isolation verifier passed")


if __name__ == "__main__":
    main()
