#!/usr/bin/env python3
"""Deterministic checks for helper observation freshness semantics."""

import contextlib
import io
import json
import sys
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

import henyo.helper as helper  # noqa: E402
import henyo.cli as cli  # noqa: E402


class Clock:
    def __init__(self):
        self.value = 10_000

    def __call__(self):
        return self.value


class DummyWs:
    def __init__(self):
        self.calls = []
        self.responses = {}

    def call(self, op, params, action_id="", timeout=10.0):
        self.calls.append((op, params, action_id, timeout))
        return self.responses.get(op, {"type": "result", "ok": True, "result": {"ok": True}})


def tree_event(epoch="epoch-a", version=1, text="fresh", **metadata):
    event = {
        "type": "event",
        "event": "ui.tree",
        "serviceEpoch": epoch,
        "treeVersion": version,
        "eventSeq": version,
        "currentApp": {"package": "example.app"},
        "root": {"text": text},
    }
    event.update(metadata)
    return event


def main():
    # TCP connect/Upgrade is bounded, but the established WS reader must not
    # inherit the five-second socket timeout across idle periods.
    class HandshakeSocket:
        def __init__(self):
            self.request = b""
            self.timeouts = []
            self.incoming = bytearray()
            self.initialized = False

        def sendall(self, data):
            self.request += data

        def recv(self, size):
            if not self.initialized:
                self.initialized = True
                key_line = next(line for line in self.request.decode("ascii").split("\r\n") if line.startswith("Sec-WebSocket-Key:"))
                key = key_line.split(":", 1)[1].strip()
                greeting = b'{"type":"event","event":"session.ready","serviceEpoch":"coalesced-epoch"}'
                frame = bytes([0x81, len(greeting)]) + greeting
                self.incoming.extend(("HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: " +
                        helper.websocket_accept(key) + "\r\n\r\n").encode("ascii") + frame)
            if not self.incoming:
                time.sleep(0.02)
                return b""
            chunk = bytes(self.incoming[:size])
            del self.incoming[:size]
            return chunk

        def settimeout(self, value):
            self.timeouts.append(value)

        def close(self):
            pass

    handshake_socket = HandshakeSocket()
    handshake_events = []
    original_create_connection = helper.socket.create_connection
    helper.socket.create_connection = lambda address, timeout: handshake_socket
    try:
        ws = helper.WsClient("ws://127.0.0.1:8765/v1/ws/control", "", handshake_events.append)
        ws.connect()
        assert handshake_socket.timeouts == [None]
        deadline = time.time() + 0.5
        while not handshake_events and time.time() < deadline:
            time.sleep(0.01)
        assert handshake_events[0]["serviceEpoch"] == "coalesced-epoch"
        ws.close()
    finally:
        helper.socket.create_connection = original_create_connection

    clock = Clock()
    original_clock = helper.monotonic_ms
    helper.monotonic_ms = clock
    try:
        daemon = helper.HelperDaemon(ROOT / "build" / "verify-helper-freshness.sock")
        daemon.ws = DummyWs()

        # A modern event populates both views, but only for the default one-second TTL.
        daemon.handle_event(tree_event())
        assert daemon.dispatch({"cmd": "tree"})["cached"] is True
        assert daemon.dispatch({"cmd": "current"})["cached"] is True
        clock.value += 1001
        assert daemon.dispatch({"cmd": "tree"})["cached"] is False
        assert daemon.ws.calls[-1][0] == "ui.tree"
        assert daemon.dispatch({"cmd": "current"}).get("cached") is not True
        assert daemon.ws.calls[-1][0] == "app.current"

        # Caller maxAgeMs can tighten, but never relax, the daemon safety TTL.
        daemon.handle_event(tree_event(version=2))
        clock.value += 11
        assert daemon.dispatch({"cmd": "tree", "maxAgeMs": 10})["cached"] is False
        assert daemon.dispatch({"cmd": "tree", "maxAgeMs": 10_000})["cached"] is True
        clock.value += 990
        assert daemon.dispatch({"cmd": "tree", "maxAgeMs": 10_000})["cached"] is False

        # A lightweight dirty event invalidates tree and current immediately.
        daemon.handle_event(tree_event(version=3))
        daemon.handle_event({
            "type": "event",
            "event": "ui.dirty",
            "serviceEpoch": "epoch-a",
            "eventSeq": 9,
            "eventElapsedRealtimeMs": 1234,
        })
        assert daemon.cache["tree"] is None
        assert daemon.cache["current"] is None
        assert daemon.cache["lastDirtyEventSeq"] == 9

        # A service restart invalidates observations from the previous epoch.
        daemon.handle_event(tree_event(epoch="epoch-a", version=4))
        daemon.note_service_epoch("epoch-b")
        assert daemon.cache["tree"] is None
        assert daemon.cache["current"] is None
        assert daemon.cache["serviceEpoch"] == "epoch-b"

        # The connection greeting exposes a restart before the first UI event.
        daemon.handle_event(tree_event(epoch="epoch-b", version=5))
        daemon.handle_event({
            "type": "event",
            "event": "session.ready",
            "protocolVersion": 1,
            "requiresAuth": False,
            "serviceEpoch": "epoch-c",
        })
        assert daemon.cache["tree"] is None
        assert daemon.cache["current"] is None
        assert daemon.cache["serviceEpoch"] == "epoch-c"

        # A direct current read cannot settle an action without an expected package.
        daemon.mark_action_pending()
        pending_id = daemon.cache["pendingActionId"]
        daemon.note_fresh_current({"ok": True, "result": {"package": "example.app"}})
        assert daemon.cache["pendingAction"] is True
        assert daemon.cache["pendingActionId"] == pending_id
        daemon.cache.update({"pendingAction": False, "pendingActionId": "", "expectedPackage": ""})

        # Cached data is bound to the helper-local action generation.
        daemon.handle_event(tree_event(epoch="epoch-c", version=6))
        daemon.cache["actionGeneration"] += 1
        assert daemon.cache_is_current("tree", 1000) is False
        assert daemon.cache_is_current("current", 1000) is False

        # Explicitly unstable tree events never replace the last accepted cache.
        daemon.cache["actionGeneration"] -= 1
        accepted_tree = daemon.cache["tree"]
        daemon.handle_event(tree_event(epoch="epoch-b", version=6, text="unstable", stable=False))
        assert daemon.cache["tree"] is accepted_tree
        daemon.handle_event(tree_event(
            epoch="epoch-b",
            version=7,
            text="crossed-event",
            captureBeginEventSeq=20,
            captureEndEventSeq=21,
        ))
        assert daemon.cache["tree"] is accepted_tree

        # A payload-free unstable timeout records status but never replaces or
        # settles the pending observation.
        daemon.mark_action_pending()
        pending_id = daemon.cache["pendingActionId"]
        cached_before_timeout = daemon.cache["tree"]
        daemon.handle_event({
            "type": "event", "event": "ui.tree", "serviceEpoch": "epoch-c",
            "eventSeq": 30, "actionId": pending_id, "reason": "after_action_timeout",
            "settled": False, "timedOut": True, "ok": False, "code": "ui_unstable",
        })
        assert daemon.cache["tree"] is cached_before_timeout
        assert daemon.cache["pendingAction"] is True
        assert daemon.cache["lastTreeTimedOut"] is True
        assert daemon.cache["lastTreeErrorCode"] == "ui_unstable"
        daemon.cache.update({"pendingAction": False, "pendingActionId": "", "expectedPackage": ""})

        # ui.observe is always direct. Unstable pairs are rejected and neither
        # their tree nor screenshot is copied into helper cache.
        sensitive_observation = {
            "type": "result",
            "ok": True,
            "result": {
                "ok": True,
                "observation": {"stable": False, "unstableReason": "event_seq_changed"},
                "tree": {"root": {"text": "secret"}},
                "screenshot": {"data": "sensitive-base64"},
            },
        }
        daemon.ws.responses["ui.observe"] = sensitive_observation
        before = daemon.cache["tree"]
        rejected = daemon.dispatch({"cmd": "observe", "params": {"maxAttempts": 2}})
        assert rejected["ok"] is False
        assert rejected["error"] == "unstable_observation"
        assert daemon.cache["tree"] is before

        daemon.ws.responses["ui.observe"] = {
            "type": "result",
            "ok": True,
            "result": {"ok": True, "observation": {"stable": True}},
        }
        accepted = daemon.dispatch({"cmd": "observe", "params": {}})
        assert accepted["ok"] is True

        daemon.ws.responses["ui.observe"] = {
            "type": "result", "ok": True,
            "result": {"ok": True, "observation": {"stable": True}},
        }
        daemon.dispatch({"cmd": "observe", "params": {"timeout": 10_000, "maxAttempts": 3}})
        assert daemon.ws.calls[-1][3] == 35.0
        assert helper.helper_read_timeout({
            "cmd": "observe", "params": {"timeout": 10_000, "maxAttempts": 3},
        }) == 40.0

        # Dirty invalidation and a cached response are atomic with respect to each other.
        daemon.cache.update({"pendingAction": False, "pendingActionId": "", "expectedPackage": ""})
        daemon.handle_event(tree_event(epoch="epoch-c", version=20))
        responses = []
        start = threading.Barrier(3)

        def read_cache():
            start.wait()
            responses.append(daemon.dispatch({"cmd": "tree"}))

        reader_a = threading.Thread(target=read_cache)
        reader_b = threading.Thread(target=read_cache)
        reader_a.start()
        reader_b.start()
        start.wait()
        daemon.handle_event({"type": "event", "event": "ui.dirty", "serviceEpoch": "epoch-c", "eventSeq": 21})
        reader_a.join()
        reader_b.join()
        assert all(not item.get("cached") or item.get("tree") is not None for item in responses)
        assert daemon.dispatch({"cmd": "tree"}).get("cached") is not True

        # Concurrent mutations use the action id returned by their own atomic mark.
        mark_barrier = threading.Barrier(2)
        original_mark = daemon.mark_action_pending

        def synchronized_mark(expected_package=""):
            action_id = original_mark(expected_package)
            mark_barrier.wait()
            return action_id

        daemon.mark_action_pending = synchronized_mark
        before_calls = len(daemon.ws.calls)
        callers = [
            threading.Thread(target=lambda: daemon.dispatch({"cmd": "call", "op": "global.back", "params": {}}))
            for _ in range(2)
        ]
        for caller in callers:
            caller.start()
        for caller in callers:
            caller.join()
        daemon.mark_action_pending = original_mark
        action_ids = [call[2] for call in daemon.ws.calls[before_calls:]]
        assert len(action_ids) == 2
        assert len(set(action_ids)) == 2
        assert all(action_id.startswith("helper-action-") for action_id in action_ids)

        # The CLI emits metadata only, even though helper IPC returns the full pair.
        original_helper_request = cli.helper_request
        cli.helper_request = lambda payload: sensitive_observation
        output = io.StringIO()
        try:
            with contextlib.redirect_stdout(output):
                assert cli.observe([]) == 0
        finally:
            cli.helper_request = original_helper_request
        summary = json.loads(output.getvalue())
        assert summary["observation"]["stable"] is False
        assert "secret" not in output.getvalue()
        assert "sensitive-base64" not in output.getvalue()

        # Missing freshness metadata remains usable during an older APK rollout,
        # while receiving the same TTL protection.
        daemon.cache["serviceEpoch"] = ""
        daemon.handle_event({
            "type": "event",
            "event": "ui.tree",
            "treeVersion": 1,
            "currentApp": {"package": "legacy.app"},
            "root": {"text": "legacy"},
        })
        assert daemon.dispatch({"cmd": "tree"})["cached"] is True
    finally:
        helper.monotonic_ms = original_clock

    print("helper freshness verifier passed")


if __name__ == "__main__":
    main()
