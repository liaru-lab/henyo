#!/usr/bin/env python3
"""Deterministic checks for display forwarding and lazy WS session lifecycle."""

import contextlib
import io
import json
import sys
import tempfile
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

import henyo.cli as cli  # noqa: E402
import henyo.helper as helper  # noqa: E402


class PassiveSocket:
    def __init__(self, fail_send=False):
        self.fail_send = fail_send
        self.send_count = 0
        self.closed = False

    def sendall(self, _data):
        self.send_count += 1
        if self.fail_send:
            raise OSError("simulated send failure")

    def close(self):
        self.closed = True


class RecorderWs:
    def __init__(self):
        self.calls = []
        self.batches = []
        self.progress = []
        self.completions = []

    def call(self, op, params, action_id="", timeout=10.0, display=None,
             expected_service_epoch=None, timeout_ms=None):
        self.calls.append({
            "op": op, "params": params, "actionId": action_id,
            "timeout": timeout, "display": display,
            "expectedServiceEpoch": expected_service_epoch,
            "timeoutMs": timeout_ms,
        })
        return {"type": "result", "ok": True, "result": {"ok": True}}

    def batch(self, steps, stop_on_error=True, return_tree=False, action_id="", timeout=20.0, display=None):
        self.batches.append({
            "steps": steps, "stopOnError": stop_on_error, "returnTree": return_tree,
            "actionId": action_id, "timeout": timeout, "display": display,
        })
        return {"type": "result", "ok": True, "result": {"ok": True}}

    def set_progress(self, goal="", completed=None, current="", steps=None, replan=False):
        if steps is not None:
            self.progress.append(("plan", goal, list(steps), replan))
        else:
            self.progress.append(("set", goal, list(completed or []), current))
        return {"type": "result", "ok": True, "result": {"ok": True, "applied": True}}

    def finish_progress(self):
        self.progress.append(("finish",))
        return {"type": "result", "ok": True, "result": {"ok": True, "applied": True}}

    def show_completion(self, message):
        self.completions.append(message)
        return {"type": "result", "ok": True, "result": {"ok": True, "applied": True}}


def verify_ws_payloads():
    captured = []
    original_encode = helper.encode_client_text
    ws = helper.WsClient("ws://127.0.0.1:8765/v1/ws/control", "", lambda _event: None)
    ws.sock = PassiveSocket()

    def capture(payload):
        captured.append(json.loads(json.dumps(payload)))
        ws.pending[payload["id"]].put({"type": "result", "id": payload["id"], "ok": True})
        return b"frame"

    helper.encode_client_text = capture
    try:
        display = {"summary": "対象のチャットを開きます"}
        assert ws.call(
            "ui.click", {"selector": {"text": "secret-selector"}},
            action_id="opaque-action", display=display,
            expected_service_epoch="opaque-epoch", timeout_ms=120_000,
        )["ok"]
        steps = [{
            "id": "one", "op": "ui.click", "params": {"selector": {"text": "Send"}},
            "display": {"summary": "送信します"},
        }]
        assert ws.batch(steps, display={"summary": "メッセージを送ります"})["ok"]
        plan = [
            {"text": "1件目を確認", "status": "completed"},
            {"text": "2件目を確認", "status": "in_progress"},
            {"text": "3件目を確認", "status": "pending"},
        ]
        assert ws.set_progress("3件確認", steps=plan, replan=True)["ok"]
        assert ws.set_progress("3件取得", ["1件取得"], "2件目を確認")["ok"]
        assert ws.finish_progress()["ok"]
        assert ws.show_completion("完了しました。")["ok"]
    finally:
        helper.encode_client_text = original_encode

    assert captured[0]["display"] == display
    assert captured[0]["params"]["selector"]["text"] == "secret-selector"
    assert captured[0]["actionId"] == "opaque-action"
    assert captured[0]["expectedServiceEpoch"] == "opaque-epoch"
    assert captured[0]["timeoutMs"] == 120_000
    assert captured[1]["display"] == {"summary": "メッセージを送ります"}
    assert captured[1]["steps"] == steps
    assert captured[2] == {
        "type": "call", "id": captured[2]["id"], "op": "task.progress.set",
        "params": {"goal": "3件確認", "steps": plan, "replan": True},
    }
    assert captured[3] == {
        "type": "call", "id": captured[3]["id"], "op": "task.progress.set",
        "params": {"goal": "3件取得", "completed": ["1件取得"], "current": "2件目を確認"},
    }
    assert captured[4] == {
        "type": "call", "id": captured[4]["id"], "op": "task.progress.finish", "params": {},
    }
    assert captured[5] == {
        "type": "call", "id": captured[5]["id"], "op": "task.completion.show",
        "params": {"message": "完了しました。"},
    }


def verify_helper_forwarding():
    daemon = helper.HelperDaemon(ROOT / "build" / "verify-helper-intent.sock")
    daemon.ws = RecorderWs()
    display = {"summary": "設定を開きます"}
    daemon.dispatch({
        "cmd": "call", "op": "ui.click", "params": {"selector": {"text": "Settings"}},
        "display": display,
    })
    assert daemon.ws.calls[-1]["display"] == display
    assert daemon.ws.calls[-1]["expectedServiceEpoch"] is None

    forwarded = daemon.dispatch({
        "cmd": "call", "op": "ui.tap", "params": {"x": 1, "y": 2},
        "expectedServiceEpoch": "界" * 255 + "😀", "actionId": "操作-😀",
        "timeoutMs": 120_000,
    })
    assert forwarded["ok"] is True
    assert daemon.ws.calls[-1]["expectedServiceEpoch"] == "界" * 255 + "😀"
    assert daemon.ws.calls[-1]["actionId"] == "操作-😀"
    assert daemon.ws.calls[-1]["timeoutMs"] == 120_000
    assert daemon.ws.calls[-1]["timeout"] == 125.0
    assert daemon.cache["pendingActionId"].startswith("helper-action-")
    assert daemon.cache["pendingActionId"] != "操作-😀"

    calls_before_invalid = len(daemon.ws.calls)
    invalid_requests = [
        {"cmd": "call", "op": "input.key", "params": {}, "id": "not-a-ws-id"},
        {"cmd": "call", "op": "input.key", "params": {}, "actionId": ""},
        {"cmd": "call", "op": "input.key", "params": {}, "expectedServiceEpoch": "x" * 257},
        {"cmd": "call", "op": "input.key", "params": {}, "actionId": "bad\ud800value"},
        {"cmd": "call", "op": "input.key", "params": {}, "expectedServiceEpoch": 7},
        {"cmd": "call", "op": "input.key", "params": {}, "actionId": None},
        {"cmd": "call", "op": "input.key", "params": {}, "expectedServiceEpoch": None},
        {"cmd": "call", "op": "input.key", "params": {}, "timeoutMs": True},
        {"cmd": "call", "op": "input.key", "params": {}, "timeoutMs": 0},
        {"cmd": "call", "op": "input.key", "params": {}, "timeoutMs": 120_001},
        {"cmd": "call", "op": "input.key", "params": {}, "timeoutMs": "120000"},
    ]
    for invalid_request in invalid_requests:
        rejected = daemon.dispatch(invalid_request)
        assert rejected == {"ok": False, "error": "invalid_call_request"}
        assert "bad" not in json.dumps(rejected)
    assert len(daemon.ws.calls) == calls_before_invalid

    mutation_result = {
        "type": "result", "id": "server-response", "ok": True,
        "result": {
            "ok": True,
            "mutation": {
                "mutationId": "server-mutation", "serviceEpoch": "epoch-after",
                "actionId": "caller-action", "application": "applied",
                "verification": "unavailable",
            },
        },
    }
    daemon.ws.call = lambda *args, **kwargs: json.loads(json.dumps(mutation_result))
    assert daemon.dispatch({
        "cmd": "call", "op": "input.key", "params": {"key": "ENTER"},
        "expectedServiceEpoch": "epoch-before", "actionId": "caller-action",
    }) == mutation_result

    steps = [{
        "id": "step-1", "op": "global.back", "params": {},
        "display": {"summary": "前の画面へ戻ります"},
    }]
    daemon.dispatch({
        "cmd": "batch", "steps": steps, "stopOnError": False, "returnTree": False,
        "display": {"summary": "画面を戻します"},
    })
    assert daemon.ws.batches[-1]["display"] == {"summary": "画面を戻します"}
    assert daemon.ws.batches[-1]["steps"] == steps

    plan = [
        {"text": "1件目を確認", "status": "completed"},
        {"text": "2件目を確認", "status": "in_progress"},
        {"text": "3件目を確認", "status": "pending"},
    ]
    result = daemon.dispatch({
        "cmd": "progress.set", "goal": "3件確認", "steps": plan, "replan": False,
    })
    assert result["ok"] is True
    assert daemon.ws.progress[-1] == ("plan", "3件確認", plan, False)
    result = daemon.dispatch({
        "cmd": "progress.set", "goal": "3件取得",
        "completed": ["1件取得"], "current": "2件目を確認",
    })
    assert result["ok"] is True
    assert daemon.ws.progress[-1] == ("set", "3件取得", ["1件取得"], "2件目を確認")
    assert daemon.dispatch({"cmd": "progress.finish"})["ok"] is True
    assert daemon.ws.progress[-1] == ("finish",)
    invalid = daemon.dispatch({"cmd": "progress.set", "completed": [1]})
    assert invalid == {"ok": False, "error": "invalid_progress", "message": "completed must be a string array"}
    mixed = daemon.dispatch({
        "cmd": "progress.set", "goal": "goal", "steps": plan, "current": "ambiguous",
    })
    assert mixed["error"] == "invalid_progress"
    invalid_status = daemon.dispatch({
        "cmd": "progress.set", "goal": "goal",
        "steps": [{"text": "one", "status": "running"}],
    })
    assert invalid_status["error"] == "invalid_progress"
    assert daemon.expected_package_for(
        "app.openUri", {"uri": "example-app://resource/123"}
    ) == ""
    assert daemon.expected_package_for(
        "app.openUri", {"uri": "example-app://resource/123", "package": "com.example.app"}
    ) == "com.example.app"
    assert daemon.batch_has_mutating_step([
        {"id": "open", "op": "app.openUri", "params": {"uri": "example-app://resource/123"}},
    ]) is True
    exact_completion = "🚀" * 125 + "界" * 125
    assert daemon.dispatch({"cmd": "completion.show", "message": exact_completion})["ok"] is True
    assert daemon.ws.completions[-1] == exact_completion
    completion_count = len(daemon.ws.completions)
    rejected_completion = daemon.dispatch({
        "cmd": "completion.show", "message": exact_completion + "x",
    })
    assert rejected_completion["error"] == "completion_too_long"
    assert len(daemon.ws.completions) == completion_count


def verify_cli_forwarding():
    requests = []
    original_request = cli.helper_request
    cli.helper_request = lambda payload: requests.append(payload) or {"ok": True}
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            assert cli.main(["click", "Send", "--intent", "メッセージを送信します"]) == 0
            assert cli.main(["set", "Search", "private value"]) == 0
            assert cli.main([
                "progress", "set", "--goal", "3件確認",
                "--step", "completed", "1件目を確認",
                "--step", "in_progress", "2件目を確認",
                "--step", "pending", "3件目を確認", "--replan",
            ]) == 0
            assert cli.main([
                "progress", "set", "--goal", "3件取得", "--completed", "1件取得",
                "--current", "2件目を確認",
            ]) == 0
            assert cli.main(["progress", "finish"]) == 0
            assert cli.main(["completion", "show", "完了しました。"]) == 0
            assert cli.main([
                "open-uri", "example-app://resource/123?q=one%20two&mode=test",
                "--package", "com.example.app", "--intent", "Opening the requested resource",
            ]) == 0
            assert cli.main(["open-uri", "custom:value"]) == 0
        assert requests[0]["display"] == {"summary": "メッセージを送信します"}
        assert "display" not in requests[1]
        assert requests[1]["params"]["value"] == "private value"
        assert requests[2] == {
            "cmd": "progress.set", "goal": "3件確認", "steps": [
                {"status": "completed", "text": "1件目を確認"},
                {"status": "in_progress", "text": "2件目を確認"},
                {"status": "pending", "text": "3件目を確認"},
            ], "replan": True,
        }
        assert requests[3] == {
            "cmd": "progress.set", "goal": "3件取得",
            "completed": ["1件取得"], "current": "2件目を確認",
        }
        assert requests[4] == {"cmd": "progress.finish"}
        assert requests[5] == {"cmd": "completion.show", "message": "完了しました。"}
        assert requests[6] == {
            "cmd": "call", "op": "app.openUri",
            "params": {
                "uri": "example-app://resource/123?q=one%20two&mode=test",
                "package": "com.example.app",
            },
            "display": {"summary": "Opening the requested resource"},
        }
        assert requests[7] == {
            "cmd": "call", "op": "app.openUri", "params": {"uri": "custom:value"},
        }

        with tempfile.TemporaryDirectory() as directory:
            batch_path = Path(directory) / "batch.json"
            batch_payload = {
                "display": {"summary": "チャットを操作します"},
                "steps": [{
                    "id": "step-1", "op": "ui.click", "params": {},
                    "display": {"summary": "対象を開きます"},
                }],
            }
            batch_path.write_text(json.dumps(batch_payload), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                assert cli.main(["batch", str(batch_path)]) == 0
            assert requests[-1]["display"] == batch_payload["display"]
            assert requests[-1]["steps"] == batch_payload["steps"]
    finally:
        cli.helper_request = original_request

    rest, display = cli.intent_options(["exec", "--", "command", "--intent", "literal"])
    assert rest == ["exec", "--", "command", "--intent", "literal"]
    assert display is None


def verify_disconnect_semantics():
    daemon = helper.HelperDaemon(ROOT / "build" / "verify-helper-disconnect.sock")
    daemon.cache.update({
        "tree": {"root": {}}, "current": {"package": "example"},
        "serviceEpoch": "epoch-a", "pendingAction": True,
        "pendingActionId": "action-a", "latestActionId": "action-a",
        "expectedPackage": "example", "actionStartedAt": 1,
    })
    generation = daemon.cache["actionGeneration"]
    daemon.handle_event({"type": "event", "event": "session.closing", "reason": "idle_timeout"})
    assert daemon.cache["tree"] is None and daemon.cache["current"] is None
    assert daemon.cache["serviceEpoch"] == ""
    assert daemon.cache["pendingAction"] is False
    assert daemon.cache["pendingActionId"] == ""
    assert daemon.cache["actionGeneration"] == generation + 1

    ws = helper.WsClient("ws://127.0.0.1:8765/v1/ws/control", "", lambda _event: None)
    socket_one = PassiveSocket()
    ws.sock = socket_one
    result = {}
    caller = threading.Thread(target=lambda: result.update(ws.call("global.back", timeout=5)))
    caller.start()
    deadline = time.time() + 1
    while not ws.pending and time.time() < deadline:
        time.sleep(0.01)
    assert ws.pending
    assert ws.close(reason="idle_timeout", expected_sock=socket_one)
    caller.join(timeout=1)
    assert not caller.is_alive()
    assert result["error"] == "ws_disconnected"
    assert result["reason"] == "idle_timeout"

    replacement = PassiveSocket()
    ws.sock = replacement
    assert ws.close(expected_sock=socket_one) is False
    assert ws.sock is replacement and not replacement.closed

    failing = PassiveSocket(fail_send=True)
    ws.sock = failing
    response = ws.call("global.home")
    assert response["error"] == "ws_disconnected"
    assert failing.send_count == 1
    assert ws.sock is None

    timed_out = PassiveSocket()
    ws.sock = timed_out
    response = ws.call(
        "input.key", {"key": "ENTER"}, action_id="one-shot-action",
        timeout=0.01, expected_service_epoch="one-shot-epoch",
    )
    assert response == {"ok": False, "error": "timeout"}
    assert timed_out.send_count == 1
    assert not ws.pending


def verify_generation_handoff_is_atomic():
    delivered = []
    callback_started = threading.Event()
    release_callback = threading.Event()
    connection_published = threading.Event()
    ordering = []

    def on_disconnect(_reason):
        ordering.append("disconnect-start")
        callback_started.set()
        assert release_callback.wait(timeout=2)
        ordering.append("disconnect-end")

    ws = helper.WsClient(
        "ws://127.0.0.1:8765/v1/ws/control", "", delivered.append, on_disconnect
    )
    old_socket = PassiveSocket()
    replacement = PassiveSocket()
    ws.sock = old_socket
    ws.generation = 1

    closer = threading.Thread(
        target=lambda: ws.close(reason="idle_timeout", expected_sock=old_socket)
    )
    closer.start()
    assert callback_started.wait(timeout=1)

    def publish_replacement():
        def connect_once():
            with ws.lock:
                ws.sock = replacement
                ws.generation += 1
            ordering.append("connected")
            connection_published.set()

        ws.connect = connect_once
        assert ws.ensure_connected()

    connector = threading.Thread(target=publish_replacement)
    connector.start()
    time.sleep(0.05)
    assert not connection_published.is_set(), "reconnect published before disconnect callback finished"
    release_callback.set()
    closer.join(timeout=1)
    connector.join(timeout=1)
    assert not closer.is_alive() and not connector.is_alive()
    assert ordering == ["disconnect-start", "disconnect-end", "connected"], ordering

    # A frame decoded by the retired reader must not reach the event callback
    # after the replacement socket has been published.
    original_decode = helper.decode_server_frame
    helper.decode_server_frame = lambda _sock: (
        1, {"type": "event", "event": "ui.tree", "serviceEpoch": "old"}
    )
    try:
        ws._read_loop(old_socket, 1)
    finally:
        helper.decode_server_frame = original_decode
    assert delivered == [], delivered
    assert ws.sock is replacement


def verify_concurrent_lazy_connect():
    ws = helper.WsClient("ws://127.0.0.1:8765/v1/ws/control", "", lambda _event: None)
    connect_count = 0
    count_lock = threading.Lock()

    def connect_once():
        nonlocal connect_count
        with count_lock:
            connect_count += 1
        time.sleep(0.05)
        with ws.lock:
            ws.sock = PassiveSocket()

    ws.connect = connect_once
    results = []
    threads = [threading.Thread(target=lambda: results.append(ws.ensure_connected())) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert results == [True] * 4
    assert connect_count == 1

    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        transport = helper.HelperTransport(
            "tcp", base / "unused.sock", "127.0.0.1", 0, base / "helper.json"
        )
        daemon = helper.HelperDaemon(transport)
        eager_connects = []
        daemon.ws.ensure_connected = lambda: eager_connects.append(True) or True
        server = threading.Thread(target=daemon.serve)
        server.start()
        deadline = time.time() + 2
        while not transport.discovery_path.exists() and time.time() < deadline:
            time.sleep(0.01)
        assert transport.discovery_path.exists()
        daemon.stop_event.set()
        server.join(timeout=2)
        assert not server.is_alive()
        assert eager_connects == []


def main():
    verify_ws_payloads()
    verify_helper_forwarding()
    verify_cli_forwarding()
    verify_disconnect_semantics()
    verify_generation_handoff_is_atomic()
    verify_concurrent_lazy_connect()
    print("helper intent/session verifier passed")


if __name__ == "__main__":
    main()
