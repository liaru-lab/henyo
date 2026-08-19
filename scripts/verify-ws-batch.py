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
from typing import Any, Dict, Optional


SEC_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


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


def read_initial_session(sock: socket.socket, timeout_s: float) -> Dict[str, Any]:
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


def wait_for_frame(sock: socket.socket, request_id: str, timeout_s: float) -> Dict[str, Any]:
    end = time.time() + timeout_s
    while True:
        remaining = max(0.5, end - time.time())
        frame = read_json_frame(sock, remaining)
        if frame.get("type") == "result" and frame.get("id") == request_id:
            expect(frame.get("ok") is True, "request returned error result", frame)
            return frame
        if frame.get("type") == "error" and frame.get("id") == request_id:
            raise RuntimeError(f"request failed for {request_id}: {frame.get('code')} - {frame.get('message')}")
        if frame.get("type") == "event":
            continue
        if frame.get("type") == "pong":
            continue
        if time.time() >= end:
            raise RuntimeError(f"timed out waiting for result frame {request_id}")


def wait_for_batch(sock: socket.socket, batch_id: str, timeout_s: float) -> Dict[str, Any]:
    frame = wait_for_frame(sock, batch_id, timeout_s)
    result = frame.get("result")
    expect(isinstance(result, dict), "batch result payload is not an object", frame)
    expect("steps" in result, "batch result missing steps", frame)
    expect(isinstance(result.get("steps"), list), "batch result steps is not a list", frame)
    return frame


def assert_step(step: Dict[str, Any], expected_id: str, expected_ok: bool) -> None:
    expect(step.get("id") == expected_id, "unexpected step id", step)
    expect(step.get("ok") is expected_ok, "unexpected step ok flag", step)
    expect("durationMs" in step, "missing step duration", step)
    if expected_ok:
        expect("result" in step, "missing step result", step)
    else:
        expect("code" in step, "missing step error code", step)
        expect("message" in step, "missing step error message", step)


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
    parser = argparse.ArgumentParser(description="Verify WS batch execution for app.current/global.back.")
    parser.add_argument("--host", default=os.environ.get("HENYO_WS_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("HENYO_WS_PORT", "8765")))
    parser.add_argument("--path", default="/v1/ws/control")
    parser.add_argument("--token", default=os.environ.get("HENYO_WS_TOKEN", os.environ.get("HENYO_TOKEN")))
    parser.add_argument("--timeout", type=float, default=5.0)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    key = websocket_key()
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.sendall(build_request(args.host, args.port, args.path, key, args.token))
        response = read_http_response(sock)
        expect(response.startswith("HTTP/1.1 101"), "websocket handshake failed", response.split("\r\n", 1)[0])
        expect(
            get_header(response, "Sec-WebSocket-Accept") == websocket_accept(key),
            "bad websocket accept header",
            get_header(response, "Sec-WebSocket-Accept"),
        )

        initial = read_initial_session(sock, args.timeout)
        expect(initial.get("event") in {"session.ready", "session.authenticated"}, "unexpected initial event", initial)

        batch_id = "batch-1"
        send_json(
            sock,
            {
                "type": "batch",
                "id": batch_id,
                "stopOnError": False,
                "steps": [
                    {"id": "step-current", "op": "app.current", "params": {}},
                    {"id": "step-back", "op": "global.back", "params": {}},
                ],
            },
        )
        batch_frame = wait_for_batch(sock, batch_id, args.timeout)
        batch_result = batch_frame["result"]
        expect(batch_result.get("stoppedOnError") is False, "unexpected stoppedOnError flag", batch_frame)
        steps = batch_result["steps"]
        expect(len(steps) == 2, "batch should contain two step results", batch_frame)
        assert_step(steps[0], "step-current", True)
        assert_step(steps[1], "step-back", True)
        expect(isinstance(steps[0].get("result"), dict), "app.current batch result is not an object", steps[0])
        expect(isinstance(steps[1].get("result"), dict), "global.back batch result is not an object", steps[1])

        stop_batch_id = "batch-2"
        send_json(
            sock,
            {
                "type": "batch",
                "id": stop_batch_id,
                "stopOnError": True,
                "steps": [
                    {"id": "step-unknown", "op": "op.does.not.exist", "params": {}},
                    {"id": "step-after-error", "op": "app.current", "params": {}},
                ],
            },
        )
        stop_batch_frame = wait_for_batch(sock, stop_batch_id, args.timeout)
        stop_batch_result = stop_batch_frame["result"]
        expect(stop_batch_result.get("stoppedOnError") is True, "batch did not stop on first error", stop_batch_frame)
        stop_steps = stop_batch_result["steps"]
        expect(len(stop_steps) == 1, "stopOnError batch should stop after the first failing step", stop_batch_frame)
        assert_step(stop_steps[0], "step-unknown", False)
        expect(isinstance(stop_steps[0].get("code"), str) and bool(stop_steps[0].get("code")), "missing failure code", stop_steps[0])

    print("WS batch verifier passed")


if __name__ == "__main__":
    main()
