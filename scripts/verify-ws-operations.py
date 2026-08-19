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
from typing import Optional


SEC_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def recv_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("socket closed while reading")
        data.extend(chunk)
    return bytes(data)


def encode_masked_text_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    mask = struct.pack("BBBB", random.randint(0, 255), random.randint(0, 255), random.randint(0, 255), random.randint(0, 255))
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


def decode_ws_frame(sock: socket.socket):
    first, second = recv_exact(sock, 2)
    fin = (first >> 7) & 1
    opcode = first & 0x0F
    mask = (second >> 7) & 1
    payload_len = second & 0x7F

    if payload_len == 126:
        payload_len = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif payload_len == 127:
        payload_len = struct.unpack("!Q", recv_exact(sock, 8))[0]
    if fin == 0:
        raise RuntimeError("fragmented frames are not supported")

    if mask:
        mask_key = recv_exact(sock, 4)
    else:
        mask_key = None

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
        try:
            return {"opcode": "text", "payload": payload, "text": payload.decode("utf-8")}
        except UnicodeDecodeError as exc:
            raise RuntimeError("received non-UTF-8 frame") from exc
    raise RuntimeError(f"unsupported opcode: {opcode}")


def read_json_frame(sock: socket.socket, timeout_s: float):
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
    chunks = bytearray()
    while b"\r\n\r\n" not in chunks:
        block = sock.recv(1)
        if not block:
            break
        chunks.extend(block)
    return chunks.decode("iso-8859-1", errors="replace")


def read_status(response: str) -> str:
    line = response.split("\r\n", 1)[0]
    return line.strip()


def get_header(response: str, name: str) -> str:
    for line in response.split("\r\n"):
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        if key.strip().lower() == name.lower():
            return value.strip()
    return ""


def expect(predicate, message, detail):
    if not predicate:
        raise AssertionError(message + f": {detail}")


def wait_for_result(sock: socket.socket, request_id: str, timeout_s: float):
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "result" and frame.get("id") == request_id:
            expect(frame.get("ok") is True, "operation returned error result", frame)
            return frame
        if frame.get("type") == "error" and frame.get("id") == request_id:
            raise RuntimeError(f"operation failed for {request_id}: {frame.get('code')} - {frame.get('message')}")
        if frame.get("type") == "event" and frame.get("event") in {"session.ready", "session.authenticated"}:
            continue
        if frame.get("type") == "pong":
            continue
        if time.time() >= end:
            raise RuntimeError(f"timed out waiting for result frame {request_id}")


def read_initial_session(sock: socket.socket, timeout_s: float):
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if not isinstance(frame, dict):
            raise RuntimeError(f"unexpected ws payload: {frame}")
        if frame.get("type") == "event" and frame.get("event") == "session.ready":
            return frame
        if frame.get("type") == "pong":
            continue
        if frame.get("type") == "event" and frame.get("event") == "session.authenticated":
            continue
        if time.time() >= end:
            raise RuntimeError("timed out waiting for session.ready")


def send_json(sock: socket.socket, payload: dict):
    send_text = json.dumps(payload, separators=(",", ":"))
    sock.sendall(encode_masked_text_frame(send_text))


def send_auth_if_needed(
    sock: socket.socket,
    session_ready,
    token: Optional[str],
    timeout_s: float,
) -> None:
    if not isinstance(session_ready, dict):
        raise RuntimeError(f"invalid session.ready payload: {session_ready}")
    if not session_ready.get("requiresAuth"):
        return
    if not token:
        raise RuntimeError("session requires auth but no Bearer token supplied")
    send_json(
        sock,
        {
            "type": "auth",
            "id": "auth-1",
            "scheme": "Bearer",
            "token": token,
        },
    )
    frame = wait_for_result(sock, "auth-1", timeout_s)
    expect(frame.get("ok"), "auth request failed", frame)


def call_operation(sock: socket.socket, op: str, req_id: str, timeout_s: float, params=None):
    send_json(sock, {"type": "call", "id": req_id, "op": op, "params": params or {}})
    frame = wait_for_result(sock, req_id, timeout_s)
    assert_frame(frame, op)
    return frame


def assert_frame(frame: dict, op: str):
    expect(frame.get("type") == "result", "expected result frame", frame)
    expect(frame.get("id"), "missing request id", frame)
    if op == "app.current":
        expect(isinstance(frame.get("result"), dict), "app.current result is not a dict", frame)
    elif op == "app.list":
        result = frame.get("result")
        expect(isinstance(result, dict), "app.list result is not a dict", frame)
        apps = result.get("apps")
        expect(result.get("ok") is True and isinstance(apps, list) and len(apps) > 0, "app.list returned no apps", result)
        first = apps[0]
        expect(isinstance(first.get("label"), str), "app.list app missing label", first)
        expect(isinstance(first.get("package"), str) and first.get("package"), "app.list app missing package", first)
        expect(isinstance(first.get("system"), bool), "app.list app missing system flag", first)
        expect(isinstance(first.get("enabled"), bool), "app.list app missing enabled flag", first)
        expect(isinstance(first.get("launchable"), bool), "app.list app missing launchable flag", first)
        if result.get("all") is not True:
            expect(all(app.get("launchable") is True for app in apps), "default app.list returned non-launchable app", apps[:5])
    elif op == "global.back":
        expect(frame["result"] == {} or isinstance(frame["result"], dict), "global.back result is malformed", frame)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Smoke test WS calls for app.current and global.back"
    )
    parser.add_argument("--host", default=os.environ.get("HENYO_WS_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("HENYO_WS_PORT", "8765")))
    parser.add_argument("--token", default=os.environ.get("HENYO_WS_TOKEN", os.environ.get("HENYO_TOKEN")))
    parser.add_argument("--path", default="/v1/ws/control")
    parser.add_argument("--timeout", type=float, default=5.0)
    return parser.parse_args()


def main():
    args = parse_args()
    token = args.token
    key = websocket_key()
    host_port = f"{args.host}:{args.port}"
    request = (
        f"GET {args.path} HTTP/1.1\r\n"
        f"Host: {host_port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
    )
    if token:
        request += f"Authorization: Bearer {token}\r\n"
    request += "\r\n"

    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.sendall(request.encode("ascii"))
        response = read_http_response(sock)
        status = read_status(response)
        expect(status.startswith("HTTP/1.1 101"), "websocket handshake failed", status)
        expect(
            get_header(response, "Sec-WebSocket-Accept") == websocket_accept(key),
            "bad websocket accept header",
            get_header(response, "Sec-WebSocket-Accept"),
        )

        session_ready = read_initial_session(sock, args.timeout)
        send_auth_if_needed(sock, session_ready, token, args.timeout)

        app_current = call_operation(sock, "app.current", "smoke-current", args.timeout)
        app_list = call_operation(sock, "app.list", "smoke-app-list", args.timeout)
        app_list_all = call_operation(sock, "app.list", "smoke-app-list-all", args.timeout, {"all": True})
        expect(
            len(app_list_all["result"]["apps"]) >= len(app_list["result"]["apps"]),
            "app.list --all returned fewer apps than default",
            {"default": len(app_list["result"]["apps"]), "all": len(app_list_all["result"]["apps"])},
        )
        global_back = call_operation(sock, "global.back", "smoke-back", args.timeout)
        print("app.current result:", json.dumps(app_current, sort_keys=True))
        print("app.list count:", len(app_list["result"]["apps"]))
        print("app.list --all count:", len(app_list_all["result"]["apps"]))
        print("global.back result:", json.dumps(global_back, sort_keys=True))


if __name__ == "__main__":
    main()
