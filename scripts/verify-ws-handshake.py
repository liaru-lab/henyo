#!/usr/bin/env python3
import base64
import json
import os
import socket
import struct
import time

HOST = os.environ.get("HENYO_WS_HOST", "127.0.0.1")
PORT = int(os.environ.get("HENYO_WS_PORT", "8765"))


def encode_client_text(text):
    payload = text.encode("utf-8")
    mask = os.urandom(4)
    header = bytearray([0x81])
    size = len(payload)
    if size <= 125:
        header.append(0x80 | size)
    elif size <= 65535:
        header.extend([0x80 | 126])
        header.extend(struct.pack("!H", size))
    else:
        header.extend([0x80 | 127])
        header.extend(struct.pack("!Q", size))
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return bytes(header) + mask + masked


def read_frame(sock):
    first = sock.recv(1)
    second = sock.recv(1)
    if not first or not second:
        raise RuntimeError("socket closed")
    opcode = first[0] & 0x0F
    size = second[0] & 0x7F
    if size == 126:
        size = struct.unpack("!H", sock.recv(2))[0]
    elif size == 127:
        size = struct.unpack("!Q", sock.recv(8))[0]
    payload = b""
    while len(payload) < size:
        payload += sock.recv(size - len(payload))
    if opcode != 1:
        raise RuntimeError(f"expected text frame, got opcode {opcode}")
    return json.loads(payload.decode("utf-8"))


def main():
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    with socket.create_connection((HOST, PORT), timeout=5) as sock:
        sock.settimeout(5)
        request = (
            "GET /v1/ws/control HTTP/1.1\r\n"
            f"Host: {HOST}:{PORT}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(request.encode("ascii"))
        response_bytes = bytearray()
        while b"\r\n\r\n" not in response_bytes:
            chunk = sock.recv(1)
            if not chunk:
                break
            response_bytes.extend(chunk)
        response = response_bytes.decode("iso-8859-1")
        if "101 Switching Protocols" not in response:
            raise RuntimeError(response)

        ready = read_frame(sock)
        if ready.get("event") != "session.ready":
            raise RuntimeError(f"expected session.ready, got {ready}")

        sock.sendall(encode_client_text('{"type":"ping","id":"ping-1"}'))
        frames = []
        deadline = time.time() + 5
        while time.time() < deadline:
            frame = read_frame(sock)
            frames.append(frame)
            if frame.get("type") == "pong" and frame.get("id") == "ping-1":
                break
        if not any(frame.get("type") == "pong" and frame.get("id") == "ping-1" for frame in frames):
            raise RuntimeError(f"missing pong in {frames}")

        sock.sendall(encode_client_text('{"type":"call","id":"call-1","op":"app.current"}'))
        response = read_frame(sock)
        if response.get("type") != "result" or response.get("id") != "call-1" or response.get("ok") is not True:
            raise RuntimeError(f"expected app.current result, got {response}")

    print("WS handshake verifier passed")


if __name__ == "__main__":
    main()
