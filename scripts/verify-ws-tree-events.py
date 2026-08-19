#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
import os
import random
import socket
import struct
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple


SEC_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TREE_EVENT = "ui.tree"
DEFAULT_ACTION_OPS = ("app.launch", "app.start", "global.back", "global.home")
DEFAULT_SNAPSHOT_OPS = ("ui.treeSnapshot", "ui.tree")


class UnsupportedOperation(RuntimeError):
    pass


def recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise RuntimeError("socket closed while reading")
        chunks.extend(chunk)
    return bytes(chunks)


def encode_masked_text_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    mask = struct.pack(
        "BBBB",
        random.randint(0, 255),
        random.randint(0, 255),
        random.randint(0, 255),
        random.randint(0, 255),
    )
    length = len(payload)
    header = bytearray([0x81])
    if length <= 125:
        header.append(0x80 | length)
    elif length <= 0xFFFF:
        header.append(0x80 | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(0x80 | 127)
        header.extend(struct.pack("!Q", length))
    masked_payload = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
    return bytes(header) + mask + masked_payload


def decode_ws_frame(sock: socket.socket) -> Dict[str, Any]:
    first, second = recv_exact(sock, 2)
    fin = (first >> 7) & 1
    opcode = first & 0x0F
    masked = (second >> 7) & 1
    payload_len = second & 0x7F

    if payload_len == 126:
        payload_len = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif payload_len == 127:
        payload_len = struct.unpack("!Q", recv_exact(sock, 8))[0]
    if fin == 0:
        raise RuntimeError("fragmented frames are not supported")

    mask_key = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, payload_len)
    if mask_key:
        payload = bytes(payload[i] ^ mask_key[i % 4] for i in range(len(payload)))

    if opcode == 0x8:
        return {"opcode": "close", "payload": payload}
    if opcode == 0x9:
        return {"opcode": "ping", "payload": payload}
    if opcode == 0xA:
        return {"opcode": "pong", "payload": payload}
    if opcode == 0x1:
        return {"opcode": "text", "payload": payload, "text": payload.decode("utf-8")}
    raise RuntimeError(f"unsupported websocket opcode: {opcode}")


def read_json_frame(sock: socket.socket, timeout_s: float) -> Dict[str, Any]:
    sock.settimeout(timeout_s)
    try:
        frame = decode_ws_frame(sock)
    except socket.timeout as exc:
        raise RuntimeError("timed out waiting for websocket frame") from exc
    finally:
        sock.settimeout(None)

    if frame["opcode"] in {"ping", "pong"}:
        return {"type": frame["opcode"]}
    if frame["opcode"] == "close":
        raise RuntimeError("server closed websocket connection")
    return json.loads(frame["text"])


def websocket_key() -> str:
    return base64.b64encode(os.urandom(16)).decode("ascii")


def websocket_accept(key: str) -> str:
    digest = hashlib.sha1((key + SEC_WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def read_http_response(sock: socket.socket) -> str:
    data = bytearray()
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(1)
        if not chunk:
            break
        data.extend(chunk)
    return data.decode("iso-8859-1", errors="replace")


def get_header(response: str, name: str) -> str:
    for line in response.split("\r\n"):
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        if key.strip().lower() == name.lower():
            return value.strip()
    return ""


def expect(condition: bool, message: str, detail: Any) -> None:
    if not condition:
        raise AssertionError(f"{message}: {detail}")


def send_json(sock: socket.socket, payload: Dict[str, Any]) -> None:
    sock.sendall(encode_masked_text_frame(json.dumps(payload, separators=(",", ":"))))


def build_request(host: str, port: int, path: str, key: str, token: Optional[str]) -> bytes:
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
    )
    if token:
        request += f"Authorization: Bearer {token}\r\n"
    request += "\r\n"
    return request.encode("ascii")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify WS tree events, treeVersion monotonicity, and snapshot requests."
    )
    parser.add_argument("--host", default=os.environ.get("HENYO_WS_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("HENYO_WS_PORT", "8765")))
    parser.add_argument("--path", default="/v1/ws/control")
    parser.add_argument("--token", default=os.environ.get("HENYO_WS_TOKEN", os.environ.get("HENYO_TOKEN")))
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--quiet-ms", type=float, default=250.0)
    parser.add_argument("--action-op", help="Override the UI-changing op used to provoke a tree push.")
    parser.add_argument("--snapshot-op", help="Override the snapshot op used to request a tree on demand.")
    return parser.parse_args()


def read_session_event(sock: socket.socket, timeout_s: float) -> Dict[str, Any]:
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if not isinstance(frame, dict):
            raise RuntimeError(f"unexpected websocket payload: {frame}")
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            return frame
        if frame.get("type") == "pong":
            continue
        if time.time() >= end:
            raise RuntimeError("timed out waiting for initial session event")


def authenticate_if_needed(sock: socket.socket, session_event: Dict[str, Any], token: Optional[str], timeout_s: float) -> None:
    if session_event.get("event") != "session.ready":
        return
    if not session_event.get("requiresAuth"):
        return
    if not token:
        raise RuntimeError("session requires auth but no Bearer token was supplied")
    send_json(
        sock,
        {
            "type": "auth",
            "id": "auth-1",
            "scheme": "Bearer",
            "token": token,
        },
    )
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "result" and frame.get("id") == "auth-1":
            expect(frame.get("ok") is True, "auth request failed", frame)
            return
        if frame.get("type") == "error" and frame.get("id") == "auth-1":
            raise RuntimeError(f"auth request failed: {frame.get('code')} - {frame.get('message')}")
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        if frame.get("type") == "pong":
            continue
        if time.time() >= end:
            raise RuntimeError("timed out waiting for auth result")


def is_unsupported_frame(frame: Dict[str, Any]) -> bool:
    if frame.get("type") != "error":
        return False
    code = str(frame.get("code", "")).lower()
    message = str(frame.get("message", "")).lower()
    haystack = f"{code} {message}"
    return any(
        token in haystack
        for token in (
            "unsupported",
            "unimplemented",
            "op_unknown",
            "unknown op",
            "unknown ws operation",
            "unknown_operation",
            "no such op",
            "not supported",
            "not found",
        )
    )


def wait_for_result(
    sock: socket.socket,
    request_id: str,
    timeout_s: float,
    collect_tree: bool = False,
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    end = time.time() + timeout_s
    tree_frames: List[Dict[str, Any]] = []
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "result" and frame.get("id") == request_id:
            expect(frame.get("ok") is True, "operation returned error result", frame)
            return frame, tree_frames
        if frame.get("type") == "error" and frame.get("id") == request_id:
            if is_unsupported_frame(frame):
                raise UnsupportedOperation(f"{frame.get('code')} - {frame.get('message')}")
            raise RuntimeError(f"operation failed for {request_id}: {frame.get('code')} - {frame.get('message')}")
        if collect_tree and frame.get("type") == "event" and frame.get("event") == TREE_EVENT:
            tree_frames.append(frame)
            continue
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        if frame.get("type") == "pong":
            continue
        if time.time() >= end:
            raise RuntimeError(f"timed out waiting for result frame {request_id}")


def wait_for_tree_event(sock: socket.socket, timeout_s: float) -> Optional[Dict[str, Any]]:
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "event" and frame.get("event") == TREE_EVENT:
            return frame
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        if frame.get("type") == "pong":
            continue
        if frame.get("type") == "result" or frame.get("type") == "error":
            continue
        if time.time() >= end:
            return None


def ensure_no_tree_event(sock: socket.socket, quiet_ms: float) -> None:
    deadline = time.time() + quiet_ms / 1000.0
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            return
        sock.settimeout(max(0.05, remaining))
        try:
            frame = read_json_frame(sock, max(0.05, remaining))
        except RuntimeError as exc:
            if "timed out waiting for websocket frame" in str(exc):
                return
            raise
        finally:
            sock.settimeout(None)
        if frame.get("type") == "event" and frame.get("event") == TREE_EVENT:
            raise AssertionError(f"unexpected ui.tree event after app.current: {frame}")
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        if frame.get("type") == "pong":
            continue


def call_op(
    sock: socket.socket,
    op: str,
    request_id: str,
    timeout_s: float,
    params: Optional[Dict[str, Any]] = None,
    action_id: Optional[str] = None,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {"type": "call", "id": request_id, "op": op, "params": params or {}}
    if action_id:
        payload["actionId"] = action_id
    send_json(sock, payload)
    frame, _ = wait_for_result(sock, request_id, timeout_s)
    return frame


def call_op_and_collect_tree(
    sock: socket.socket,
    op: str,
    request_id: str,
    timeout_s: float,
    params: Optional[Dict[str, Any]] = None,
    action_id: Optional[str] = None,
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    payload: Dict[str, Any] = {"type": "call", "id": request_id, "op": op, "params": params or {}}
    if action_id:
        payload["actionId"] = action_id
    send_json(sock, payload)
    return wait_for_result(sock, request_id, timeout_s, collect_tree=True)


def call_op_and_wait_for_settled_tree(
    sock: socket.socket,
    op: str,
    request_id: str,
    action_id: str,
    timeout_s: float,
    params: Optional[Dict[str, Any]] = None,
    previous_version: Optional[int] = None,
) -> Tuple[Dict[str, Any], Dict[str, Any], int]:
    payload: Dict[str, Any] = {"type": "call", "id": request_id, "op": op, "params": params or {}}
    if action_id:
        payload["actionId"] = action_id
    send_json(sock, payload)
    end = time.time() + timeout_s
    result_frame: Optional[Dict[str, Any]] = None
    last_tree: Optional[Dict[str, Any]] = None
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "result" and frame.get("id") == request_id:
            expect(frame.get("ok") is True, "operation returned error result", frame)
            result_frame = frame
        elif frame.get("type") == "error" and frame.get("id") == request_id:
            if is_unsupported_frame(frame):
                raise UnsupportedOperation(f"{frame.get('code')} - {frame.get('message')}")
            raise RuntimeError(f"operation failed for {request_id}: {frame.get('code')} - {frame.get('message')}")
        elif frame.get("type") == "event" and frame.get("event") == TREE_EVENT and frame.get("actionId") == action_id:
            last_tree = frame
            tree_msg = normalize_tree_message(frame)
            expect(tree_msg is not None, "received a non-tree event while waiting for settled tree", frame)
        elif frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        elif frame.get("type") == "pong":
            continue
        if result_frame is not None and last_tree is not None and last_tree.get("settled") is True:
            tree_msg = normalize_tree_message(last_tree)
            expect(tree_msg is not None, "received a non-tree event while waiting for settled tree", last_tree)
            version = assert_tree_message(tree_msg, previous_version, require_metadata=True)
            return result_frame, last_tree, version
        if time.time() >= end:
            break
    raise RuntimeError(f"timed out waiting for settled tree event for {action_id}: {last_tree}")


def normalize_tree_message(frame: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not isinstance(frame, dict):
        return None
    if frame.get("type") == "event" and frame.get("event") == TREE_EVENT:
        return {"frame": frame, "source": frame, "kind": "event"}
    result = frame.get("result")
    if isinstance(result, dict):
        if "root" in result or "payload" in result or "treeVersion" in result:
            return {"frame": frame, "source": result, "kind": "result"}
        payload = result.get("payload")
        if isinstance(payload, dict) and ("root" in payload or "treeVersion" in payload):
            return {"frame": frame, "source": payload, "kind": "result.payload"}
    return None


def tree_root_shape(source: Dict[str, Any]) -> Dict[str, Any]:
    root = source.get("root")
    if not isinstance(root, dict):
        payload = source.get("payload")
        if isinstance(payload, dict):
            root = payload.get("root")
    expect(isinstance(root, dict), "tree payload missing root object", source)
    expect(isinstance(root.get("className"), str) and root["className"], "tree root missing className", root)
    expect(isinstance(root.get("children"), list), "tree root children is not a list", root)
    return root


def assert_tree_event_metadata(source: Dict[str, Any]) -> None:
    expect(isinstance(source.get("actionId"), str), "actionId missing from tree event", source)
    expect(isinstance(source.get("settled"), bool), "settled missing from tree event", source)
    expect(isinstance(source.get("changed"), bool), "changed missing from tree event", source)
    expect(isinstance(source.get("eventSeq"), int), "eventSeq missing from tree event", source)
    expect(not isinstance(source.get("eventSeq"), bool), "eventSeq is malformed", source)
    expect(isinstance(source.get("treeDigest"), str), "treeDigest missing from tree event", source)
    expect(isinstance(source.get("capturedAt"), str) and source["capturedAt"], "capturedAt missing from tree event", source)


def assert_tree_message(
    tree_msg: Dict[str, Any],
    previous_version: Optional[int],
    require_metadata: bool,
) -> int:
    source = tree_msg["source"]
    version = source.get("treeVersion")
    expect(isinstance(version, int), "treeVersion is missing or not an int", source)
    if previous_version is not None:
        expect(version > previous_version, "treeVersion did not increase", {"previous": previous_version, "current": version})

    captured_at = source.get("capturedAt")
    reason = source.get("reason")
    if require_metadata:
        expect(isinstance(reason, str) and reason, "reason missing from tree event", source)
        assert_tree_event_metadata(source)
    else:
        if captured_at is not None:
            expect(isinstance(captured_at, str) and captured_at, "capturedAt is malformed", source)
        if reason is not None:
            expect(isinstance(reason, str) and reason, "reason is malformed", source)

    tree_root_shape(source)
    return version


def request_snapshot(
    sock: socket.socket,
    op: str,
    timeout_s: float,
    previous_version: Optional[int],
) -> Tuple[Optional[Dict[str, Any]], Optional[int]]:
    request_id = f"snapshot-{op}"
    frame, tree_frames = call_op_and_collect_tree(sock, op, request_id, timeout_s, {})
    tree_msg = normalize_tree_message(frame)
    if tree_msg is not None:
        if tree_msg["kind"] == "event" or "treeVersion" in tree_msg["source"]:
            version = assert_tree_message(tree_msg, previous_version, require_metadata=(tree_msg["kind"] == "event"))
            return frame, version

    if tree_frames:
        tree_msg = normalize_tree_message(tree_frames[-1])
        expect(tree_msg is not None, "snapshot op returned a non-tree event", tree_frames[-1])
        version = assert_tree_message(tree_msg, previous_version, require_metadata=True)
        return tree_frames[-1], version

    try:
        tree_event = wait_for_tree_event(sock, timeout_s)
    except RuntimeError as exc:
        if "timed out waiting for websocket frame" in str(exc):
            return None, previous_version
        raise
    if tree_event is None:
        return None, previous_version
    tree_msg = normalize_tree_message(tree_event)
    expect(tree_msg is not None, "snapshot op returned a non-tree event", tree_event)
    version = assert_tree_message(tree_msg, previous_version, require_metadata=True)
    return tree_event, version


def attempt_action_candidates(
    sock: socket.socket,
    ops: Sequence[str],
    timeout_s: float,
    previous_version: Optional[int],
) -> Tuple[str, Dict[str, Any], int]:
    failures: List[str] = []
    for index, op in enumerate(ops, start=1):
        request_id = f"action-{index}"
        # Keep the action correlation id distinct from the transport request id.
        # Otherwise a server that accidentally falls back to the request id can
        # pass this verification while helpers reject every pushed tree.
        action_id = f"{request_id}-correlation"
        params = action_params_for(op)
        try:
            result_frame, frame, version = call_op_and_wait_for_settled_tree(
                sock, op, request_id, action_id, timeout_s, params, previous_version
            )
        except UnsupportedOperation as exc:
            failures.append(f"{op}: {exc}")
            continue
        except RuntimeError as exc:
            failures.append(f"{op}: {exc}")
            continue

        tree_msg = normalize_tree_message(frame)
        expect(tree_msg is not None, "received a non-tree event while waiting for tree push", frame)
        version = assert_tree_message(tree_msg, previous_version, require_metadata=True)
        expect(isinstance(result_frame.get("ok"), bool) and result_frame.get("ok") is True, "operation result missing ok", result_frame)
        return op, frame, version

    raise RuntimeError("no supported action op produced a tree event; tried: " + "; ".join(failures))


def choose_ops(override: Optional[str], defaults: Sequence[str]) -> Sequence[str]:
    if override:
        return (override,)
    return defaults


def action_params_for(op: str) -> Dict[str, Any]:
    if op == "app.launch":
        return {"package": "com.android.settings"}
    if op == "app.start":
        return {"component": "com.android.settings/.Settings"}
    return {}


def verify_no_change_settled_case(sock: socket.socket, timeout_s: float, previous_version: Optional[int]) -> Optional[int]:
    noop_request = "noop-settings-launch"
    params = {"package": "com.android.settings"}
    try:
        _, _, current_version = call_op_and_wait_for_settled_tree(
            sock,
            "app.launch",
            f"{noop_request}-prime",
            f"{noop_request}-prime-correlation",
            timeout_s,
            params,
            previous_version,
        )
    except UnsupportedOperation as exc:
        print(f"ASSUMPTION: no-op verification skipped because app.launch is unsupported ({exc}).")
        return None

    try:
        _, tree_event, version = call_op_and_wait_for_settled_tree(
            sock,
            "app.launch",
            f"{noop_request}-verify",
            f"{noop_request}-verify-correlation",
            timeout_s,
            params,
            current_version,
        )
    except UnsupportedOperation as exc:
        print(f"ASSUMPTION: no-op verification skipped because app.launch is unsupported ({exc}).")
        return None
    expect(tree_event.get("changed") is False, "no-op action produced a changed tree", tree_event)
    print(f"no-op settled tree ok: app.launch (treeVersion={version})")
    return version


def main() -> None:
    args = parse_args()
    key = websocket_key()
    token = args.token
    request = build_request(args.host, args.port, args.path, key, token)

    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.sendall(request)
        response = read_http_response(sock)
        status = response.split("\r\n", 1)[0].strip()
        expect(status.startswith("HTTP/1.1 101"), "websocket handshake failed", status)
        expect(
            get_header(response, "Sec-WebSocket-Accept") == websocket_accept(key),
            "bad websocket accept header",
            get_header(response, "Sec-WebSocket-Accept"),
        )

        session_event = read_session_event(sock, args.timeout)
        authenticate_if_needed(sock, session_event, token, args.timeout)

        app_current = call_op(sock, "app.current", "app-current-1", args.timeout, {})
        expect(isinstance(app_current.get("result"), dict), "app.current result is not an object", app_current)
        ensure_no_tree_event(sock, args.quiet_ms)
        print("app.current ok")

        snapshot_ops = choose_ops(args.snapshot_op, DEFAULT_SNAPSHOT_OPS)
        previous_version: Optional[int] = None
        snapshot_frame: Optional[Dict[str, Any]] = None
        snapshot_op_used: Optional[str] = None

        for op in snapshot_ops:
            try:
                snapshot_frame, previous_version = request_snapshot(sock, op, args.timeout, previous_version)
            except UnsupportedOperation:
                continue
            except RuntimeError as exc:
                if "operation returned error result" in str(exc) and "unsupported" in str(exc).lower():
                    continue
                raise
            else:
                snapshot_op_used = op
                break

        if snapshot_frame is not None and snapshot_op_used is not None:
            snapshot_tree_msg = normalize_tree_message(snapshot_frame)
            if snapshot_tree_msg is not None and snapshot_tree_msg["kind"] == "event":
                assert_tree_event_metadata(snapshot_tree_msg["source"])
            print(f"snapshot op ok: {snapshot_op_used} (treeVersion={previous_version})")
        else:
            print("ASSUMPTION: no snapshot op was confirmed; treeVersion monotonicity will be checked from the first push event only.")

        action_ops = choose_ops(args.action_op, DEFAULT_ACTION_OPS)
        action_op_used, action_frame, action_version = attempt_action_candidates(sock, action_ops, args.timeout, previous_version)
        action_tree_msg = normalize_tree_message(action_frame)
        if action_tree_msg is not None and action_tree_msg["kind"] == "event":
            assert_tree_event_metadata(action_tree_msg["source"])
        print(f"action op ok: {action_op_used} (treeVersion={action_version})")

        if previous_version is not None:
            expect(action_version > previous_version, "treeVersion did not increase after action", {"previous": previous_version, "current": action_version})
        else:
            print("ASSUMPTION: no pre-action snapshot version was available to compare against.")

        # If the action itself did not return the tree payload, make one more
        # immediate snapshot request so the contract can be inspected directly.
        if normalize_tree_message(action_frame) is None and snapshot_op_used is not None:
            followup_frame, followup_version = request_snapshot(sock, snapshot_op_used, args.timeout, action_version)
            if followup_frame is not None:
                print(f"follow-up snapshot ok: {snapshot_op_used} (treeVersion={followup_version})")

        no_change_version = verify_no_change_settled_case(sock, args.timeout, action_version)
        if no_change_version is not None:
            expect(no_change_version > action_version, "no-op verification did not advance treeVersion", {
                "previous": action_version,
                "current": no_change_version,
            })

        print("WS tree verifier passed")


if __name__ == "__main__":
    main()
