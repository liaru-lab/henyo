#!/usr/bin/env python3
import argparse
import base64
from copy import deepcopy
import errno
import hashlib
import ipaddress
import json
import os
import selectors
import secrets
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from urllib.parse import urlparse


WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
DEFAULT_CACHE_MAX_AGE_MS = 1000
BATCH_WS_DEFAULT_TIMEOUT_SECONDS = 20.0
BATCH_RESPONSE_MARGIN_SECONDS = 5.0
BATCH_HELPER_READ_MARGIN_SECONDS = 5.0
MAX_BATCH_TIMEOUT_MS = 300_000
MAX_SCREENSHOT_TIMEOUT_SECONDS = 300.0
MAX_CALL_TIMEOUT_MS = 120_000
CALL_RESPONSE_MARGIN_SECONDS = 5.0
CALL_HELPER_READ_MARGIN_SECONDS = 10.0
MAX_COMPLETION_CODE_POINTS = 250
SESSION_READY_TIMEOUT_SECONDS = 10.0
SESSION_METADATA_STRING_CODE_POINTS = 128
CALL_OPAQUE_ID_CODE_POINTS = 256
SUPPORTED_PROTOCOL_VERSION = 1
SUPPORTED_CONTRACT_REVISION = "remote-control.core/1.0.0"
SUPPORTED_CAPABILITY_PROFILE = "remote-control.core/1"
KNOWN_DEVICE_STATES = frozenset({
    "connecting", "ready", "locked", "reconnecting",
    "operator_action_required", "closed",
})
KNOWN_OPERATOR_ACTIONS = frozenset({"physical_reboot_then_first_unlock"})
KNOWN_CAPABILITY_FEATURES = frozenset({
    "expectedServiceEpoch",
    "mutationOutcome",
    "internalControlOwner",
})
KNOWN_CAPABILITY_LIMITS = frozenset({
    "inboundFrameBytes",
    "outboundJsonBytes",
    "idCodePoints",
    "batchSteps",
    "queueItems",
    "timeoutMs",
})
KNOWN_CAPABILITY_OPERATIONS = frozenset({
    "ui.tree", "ui.find", "ui.click", "ui.setText", "ui.tap", "ui.swipe",
    "ui.scroll", "ui.scrollUntil", "ui.wait", "ui.observe",
    "app.current", "app.list", "app.launch", "app.openUri", "app.start", "app.activate",
    "global.back", "global.home", "screen.screenshot", "termux.exec",
    "input.text", "input.key", "task.progress.set", "task.progress.finish",
    "task.completion.show", "session.wakeHint",
})
KNOWN_COORDINATE_SPACES = frozenset({"screen", "screenshot"})
KNOWN_INPUT_MODIFIERS = frozenset({"SHIFT", "CONTROL", "ALT", "META"})
KNOWN_INPUT_KEYS = frozenset({
    "ENTER", "TAB", "ESCAPE", "BACKSPACE", "DELETE", "SPACE",
    "ARROW_UP", "ARROW_DOWN", "ARROW_LEFT", "ARROW_RIGHT",
    "HOME", "END", "PAGE_UP", "PAGE_DOWN",
    *(chr(value) for value in range(ord("A"), ord("Z") + 1)),
    *(f"DIGIT_{value}" for value in range(10)),
})
MAX_OPERATION_ENUM_ITEMS = 64
UI_TREE_CAPABILITY_METADATA: Dict[str, Any] = {
    "schemaRevision": "henyo.ui-tree/1",
    "parameters": ["maxDepth", "maxNodes", "redact"],
    "defaultMaxDepth": 8,
    "maxDepth": 32,
    "defaultMaxNodes": 500,
    "maxNodes": 1200,
    "maxStringCodePoints": 4096,
    "maxStringUtf8Bytes": 16384,
    "maxResultBytes": 1048576,
    "timeoutMs": 60000,
    "nullableFields": ["clickable", "focused"],
    "redaction": "email-and-phone",
}
CALL_REQUEST_FIELDS = frozenset({
    "cmd", "op", "params", "display", "token", "expectedServiceEpoch", "actionId", "timeoutMs",
    "targetIdentity",
})
TARGET_BOUND_COMMANDS = frozenset({
    "session.status", "tree", "current", "observe", "progress.set",
    "progress.finish", "completion.show", "call", "batch", "auth.reload",
})


def unicode_code_point_count(value: str) -> int:
    """Match Java String.codePointCount, including explicit surrogate pairs."""
    count = 0
    index = 0
    while index < len(value):
        first = ord(value[index])
        if (0xD800 <= first <= 0xDBFF and index + 1 < len(value)
                and 0xDC00 <= ord(value[index + 1]) <= 0xDFFF):
            index += 2
        else:
            index += 1
        count += 1
    return count


def bounded_scalar_string(value: Any, maximum: int = SESSION_METADATA_STRING_CODE_POINTS) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= maximum:
        return False
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return unicode_code_point_count(value) <= maximum


def valid_call_opaque_id(value: Any) -> bool:
    return bounded_scalar_string(value, CALL_OPAQUE_ID_CODE_POINTS)


class SessionMetadataError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def sanitize_capability_enum_list(value: Any, allowed: frozenset[str]) -> list[str]:
    if not isinstance(value, list) or len(value) > MAX_OPERATION_ENUM_ITEMS:
        raise SessionMetadataError("capability_invalid")
    safe: list[str] = []
    for item in value:
        if not bounded_scalar_string(item, 64):
            raise SessionMetadataError("capability_invalid")
        if item in allowed and item not in safe:
            safe.append(item)
    return safe


def sanitize_operation_capability(name: str, detail: Any) -> Dict[str, Any]:
    if not isinstance(detail, dict) or not isinstance(detail.get("mutates"), bool):
        raise SessionMetadataError("capability_invalid")
    safe: Dict[str, Any] = {"mutates": detail["mutates"]}
    if name == "ui.tree":
        if detail["mutates"] is not False:
            raise SessionMetadataError("capability_invalid")
        for field, expected in UI_TREE_CAPABILITY_METADATA.items():
            value = detail.get(field)
            if type(value) is not type(expected) or value != expected:
                raise SessionMetadataError("capability_invalid")
            safe[field] = list(value) if isinstance(value, list) else value
    if name in ("screen.screenshot", "ui.tap") and "coordinateSpaces" in detail:
        safe["coordinateSpaces"] = sanitize_capability_enum_list(
            detail["coordinateSpaces"], KNOWN_COORDINATE_SPACES,
        )
    if name == "screen.screenshot" and "includeIndicator" in detail:
        if not isinstance(detail["includeIndicator"], bool):
            raise SessionMetadataError("capability_invalid")
        safe["includeIndicator"] = detail["includeIndicator"]
    if name == "app.activate" and "identityField" in detail:
        if detail["identityField"] != "appId":
            raise SessionMetadataError("capability_invalid")
        safe["identityField"] = "appId"
    if name == "input.text":
        fixed_values = {
            "encoding": "unicode-scalar-sequence",
            "normalization": "none",
            "pasteboardRestoration": "best-effort-compare-and-swap",
        }
        for field, expected in fixed_values.items():
            if field in detail:
                if detail[field] != expected:
                    raise SessionMetadataError("capability_invalid")
                safe[field] = expected
        for field in ("maxCodePoints", "maxUtf8Bytes"):
            if field in detail:
                value = detail[field]
                if (not isinstance(value, int) or isinstance(value, bool)
                        or not 1 <= value <= 1_073_741_824):
                    raise SessionMetadataError("capability_invalid")
                safe[field] = value
        if "secureTargetDetection" in detail:
            if not isinstance(detail["secureTargetDetection"], bool):
                raise SessionMetadataError("capability_invalid")
            safe["secureTargetDetection"] = detail["secureTargetDetection"]
    if name == "input.key":
        if "keys" in detail:
            safe["keys"] = sanitize_capability_enum_list(detail["keys"], KNOWN_INPUT_KEYS)
        if "modifiers" in detail:
            safe["modifiers"] = sanitize_capability_enum_list(
                detail["modifiers"], KNOWN_INPUT_MODIFIERS,
            )
    return safe


def sanitize_session_ready(event: Dict[str, Any]) -> Dict[str, Any]:
    version = event.get("protocolVersion")
    if not isinstance(version, int) or isinstance(version, bool):
        raise SessionMetadataError("protocol_invalid")
    if version != SUPPORTED_PROTOCOL_VERSION:
        raise SessionMetadataError("protocol_incompatible")
    requires_auth = event.get("requiresAuth")
    if not isinstance(requires_auth, bool):
        raise SessionMetadataError("protocol_invalid")
    if requires_auth:
        raise SessionMetadataError("auth_required")
    epoch = event.get("serviceEpoch")
    if not bounded_scalar_string(epoch):
        raise SessionMetadataError("protocol_invalid")

    sanitized: Dict[str, Any] = {
        "protocolVersion": version,
        "serviceEpoch": epoch,
    }
    additive_names = ("contractRevision", "platform", "capabilities")
    present = tuple(name in event for name in additive_names)
    if not any(present):
        return sanitized
    if not all(present):
        raise SessionMetadataError("protocol_invalid")
    if event.get("contractRevision") != SUPPORTED_CONTRACT_REVISION:
        raise SessionMetadataError("protocol_incompatible")
    platform = event.get("platform")
    if not isinstance(platform, dict):
        raise SessionMetadataError("protocol_invalid")
    platform_name = platform.get("name")
    platform_version = platform.get("version")
    if platform_name not in ("android", "ios") or not bounded_scalar_string(platform_version, 64):
        raise SessionMetadataError("protocol_invalid")

    capabilities = event.get("capabilities")
    if not isinstance(capabilities, dict) or capabilities.get("profile") != SUPPORTED_CAPABILITY_PROFILE:
        raise SessionMetadataError("capability_invalid")
    features = capabilities.get("features")
    limits = capabilities.get("limits")
    operations = capabilities.get("operations")
    if not isinstance(features, list) or not isinstance(limits, dict) or not isinstance(operations, dict):
        raise SessionMetadataError("capability_invalid")
    if any(not isinstance(item, str) for item in features):
        raise SessionMetadataError("capability_invalid")

    safe_features = []
    for item in features:
        if item in KNOWN_CAPABILITY_FEATURES and item not in safe_features:
            safe_features.append(item)
    safe_limits: Dict[str, int] = {}
    for name in KNOWN_CAPABILITY_LIMITS:
        if name not in limits:
            continue
        value = limits[name]
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 1_073_741_824:
            raise SessionMetadataError("capability_invalid")
        safe_limits[name] = value
    safe_operations: Dict[str, Dict[str, Any]] = {}
    for name, detail in operations.items():
        if name not in KNOWN_CAPABILITY_OPERATIONS:
            continue
        safe_operations[name] = sanitize_operation_capability(name, detail)

    sanitized.update({
        "contractRevision": SUPPORTED_CONTRACT_REVISION,
        "platform": {"name": platform_name, "version": platform_version},
        "capabilities": {
            "profile": SUPPORTED_CAPABILITY_PROFILE,
            "features": safe_features,
            "limits": safe_limits,
            "operations": safe_operations,
        },
    })
    if platform_name == "ios":
        device_ready = event.get("deviceReady")
        device_state = event.get("deviceState")
        operator_action = event.get("operatorAction")
        if not isinstance(device_ready, bool) or device_state not in KNOWN_DEVICE_STATES:
            raise SessionMetadataError("session_metadata_invalid")
        if device_ready != (device_state == "ready"):
            raise SessionMetadataError("session_metadata_invalid")
        if operator_action is not None:
            if (device_state != "operator_action_required"
                    or operator_action not in KNOWN_OPERATOR_ACTIONS):
                raise SessionMetadataError("session_metadata_invalid")
        sanitized["deviceReady"] = device_ready
        sanitized["deviceState"] = device_state
        if operator_action is not None:
            sanitized["operatorAction"] = operator_action
    return sanitized


def validate_batch_timeout_ms(value: Any) -> Optional[int]:
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MAX_BATCH_TIMEOUT_MS:
        raise ValueError(f"timeoutMs must be an integer between 1 and {MAX_BATCH_TIMEOUT_MS}")
    return value


def validate_call_timeout_ms(value: Any) -> Optional[int]:
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MAX_CALL_TIMEOUT_MS:
        raise ValueError(f"timeoutMs must be an integer between 1 and {MAX_CALL_TIMEOUT_MS}")
    return value


def batch_ws_timeout_seconds(timeout_ms: Optional[int]) -> float:
    if timeout_ms is None:
        return BATCH_WS_DEFAULT_TIMEOUT_SECONDS
    return timeout_ms / 1000.0 + BATCH_RESPONSE_MARGIN_SECONDS


def observe_timeout_seconds(params: Dict[str, Any], margin_seconds: float = 5.0) -> float:
    timeout_ms = params.get("timeout", 5000)
    if not isinstance(timeout_ms, int) or isinstance(timeout_ms, bool) or timeout_ms <= 0:
        timeout_ms = 5000
    attempts = params.get("maxAttempts", 3)
    if not isinstance(attempts, int) or isinstance(attempts, bool):
        attempts = 3
    attempts = max(1, min(5, attempts))
    return attempts * timeout_ms / 1000.0 + margin_seconds


def runtime_dir() -> Path:
    base = os.environ.get("XDG_RUNTIME_DIR") or os.environ.get("TMPDIR")
    if not base:
        base = tempfile.gettempdir() if os.name == "nt" else "/tmp"
    return Path(base) / "henyo"


def env_timeout(name: str, default: float) -> float:
    value = os.environ.get(name)
    if value is None:
        return default
    try:
        timeout = float(value)
    except ValueError:
        return default
    return timeout if timeout > 0 else default


def default_socket_path() -> Path:
    return Path(os.environ.get("HENYO_HELPER_SOCKET", runtime_dir() / "helper.sock"))


def default_discovery_path() -> Path:
    return Path(os.environ.get("HENYO_HELPER_DISCOVERY", runtime_dir() / "helper.json"))


def default_log_path() -> Path:
    return Path(os.environ.get("HENYO_HELPER_LOG", runtime_dir() / "helper.log"))


def default_pid_path() -> Path:
    return Path(os.environ.get("HENYO_HELPER_PID", runtime_dir() / "helper.pid"))


def config_path() -> Path:
    if os.environ.get("HENYO_CONFIG"):
        return Path(os.environ["HENYO_CONFIG"])
    return Path.home() / ".config" / "henyo" / "config"


def configured_token() -> str:
    if os.environ.get("HENYO_TOKEN"):
        return os.environ["HENYO_TOKEN"].strip()
    path = config_path()
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == "token":
                return value.strip()
    except FileNotFoundError:
        return ""
    return ""


def canonical_ws_url(value: str) -> str:
    parsed = urlparse(value)
    scheme = parsed.scheme.lower()
    if scheme not in ("ws", "wss"):
        raise ValueError("Henyo WebSocket URL must use ws:// or wss://")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("Henyo WebSocket URL must not contain credentials")
    hostname = parsed.hostname
    if not hostname:
        raise ValueError("Henyo WebSocket URL must contain a host")
    hostname = hostname.lower()
    rendered_host = f"[{hostname}]" if ":" in hostname else hostname
    # WsClient currently uses 8765 whenever the URL omits a port, for both
    # supported schemes. Canonicalization must describe the endpoint it really
    # opens rather than applying browser WebSocket defaults.
    port = parsed.port or 8765
    path = parsed.path or "/v1/ws/control"
    return f"{scheme}://{rendered_host}:{port}{path}"


def ws_url() -> str:
    if os.environ.get("HENYO_WS_URL"):
        return canonical_ws_url(os.environ["HENYO_WS_URL"])
    base = os.environ.get("HENYO_URL", "http://127.0.0.1:8765")
    parsed = urlparse(base)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    host = parsed.netloc or "127.0.0.1:8765"
    return canonical_ws_url(f"{scheme}://{host}/v1/ws/control")


def now_ms() -> int:
    return int(time.time() * 1000)


def monotonic_ms() -> int:
    return int(time.monotonic() * 1000)


def json_dumps(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def validate_tcp_host(host: str) -> str:
    try:
        address = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ValueError("HENYO_HELPER_HOST must be an IPv4 loopback address") from exc
    if address.version != 4 or not address.is_loopback:
        raise ValueError("HENYO_HELPER_HOST must be an IPv4 loopback address")
    return host


BATCH_MUTATING_OPS = frozenset({
    "ui.click",
    "ui.setText",
    "ui.tap",
    "ui.swipe",
    "ui.scroll",
    "ui.scrollUntil",
    "app.launch",
    "app.openUri",
    "app.start",
    "global.back",
    "global.home",
})


class HelperTransport:
    def __init__(
        self,
        transport: str,
        socket_path: Path,
        host: str,
        port: int,
        discovery_path: Path,
    ):
        self.transport = transport
        self.socket_path = socket_path
        self.host = host
        self.port = port
        self.discovery_path = discovery_path

    @classmethod
    def from_env(cls, socket_path: Optional[Path] = None) -> "HelperTransport":
        transport = os.environ.get("HENYO_HELPER_TRANSPORT")
        if not transport:
            transport = "tcp" if os.name == "nt" else "unix"
        transport = transport.strip().lower()
        if transport not in ("unix", "tcp"):
            raise ValueError("HENYO_HELPER_TRANSPORT must be unix or tcp")
        selected_socket = socket_path or default_socket_path()
        host = os.environ.get("HENYO_HELPER_HOST", "127.0.0.1")
        if transport == "tcp":
            host = validate_tcp_host(host)
        try:
            port = int(os.environ.get("HENYO_HELPER_PORT", "0"))
        except ValueError as exc:
            raise ValueError("HENYO_HELPER_PORT must be an integer") from exc
        if port < 0 or port > 65535:
            raise ValueError("HENYO_HELPER_PORT must be between 0 and 65535")
        return cls(transport, selected_socket, host, port, default_discovery_path())

    def read_discovery(self) -> Dict[str, Any]:
        return json.loads(self.discovery_path.read_text(encoding="utf-8"))


def ensure_private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass


def write_discovery(path: Path, data: Dict[str, Any]) -> None:
    ensure_private_dir(path.parent)
    tmp = path.with_name(path.name + f".tmp.{os.getpid()}")
    encoded = (json_dumps(data) + "\n").encode("utf-8")
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    fd = os.open(tmp, flags, 0o600)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(encoded)
        os.chmod(tmp, 0o600)
        tmp.replace(path)
    except Exception:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise


def remove_file(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def websocket_accept(key: str) -> str:
    digest = hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def recv_exact(sock: socket.socket, size: int) -> bytes:
    out = bytearray()
    while len(out) < size:
        chunk = sock.recv(size - len(out))
        if not chunk:
            raise OSError("socket closed")
        out.extend(chunk)
    return bytes(out)


def encode_client_text(payload: Dict[str, Any]) -> bytes:
    body = json_dumps(payload).encode("utf-8")
    mask = os.urandom(4)
    header = bytearray([0x81])
    size = len(body)
    if size <= 125:
        header.append(0x80 | size)
    elif size <= 65535:
        header.extend([0x80 | 126])
        header.extend(struct.pack("!H", size))
    else:
        header.extend([0x80 | 127])
        header.extend(struct.pack("!Q", size))
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(body))
    return bytes(header) + mask + masked


def decode_server_frame(sock: socket.socket) -> Tuple[int, Any]:
    first, second = recv_exact(sock, 2)
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    size = second & 0x7F
    if size == 126:
        size = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif size == 127:
        size = struct.unpack("!Q", recv_exact(sock, 8))[0]
    mask = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, size)
    if mask:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    if opcode == 1:
        return opcode, json.loads(payload.decode("utf-8"))
    return opcode, payload


class WsClient:
    def __init__(self, url: str, token: str, on_event, on_disconnect=None):
        self.url = url
        self.token = token
        self.on_event = on_event
        self.sock: Optional[socket.socket] = None
        self.lock = threading.RLock()
        # Serializes publishing a new connection, retiring the old generation,
        # its disconnect callback, and delivery of decoded frames.  An RLock is
        # required because the reader handles a close frame while already in
        # this generation-critical section.
        self.connect_lock = threading.RLock()
        self.pending: Dict[str, "queue.Queue[Dict[str, Any]]"] = {}
        self.next_id = 1
        self.connected_at = 0
        self.last_error = ""
        self.reader: Optional[threading.Thread] = None
        self.generation = 0
        self.on_disconnect = on_disconnect
        import queue
        self.queue_mod = queue

    def connect(self) -> None:
        parsed = urlparse(self.url)
        if parsed.scheme not in ("ws", "wss"):
            raise ValueError("only ws:// is supported by the helper")
        if parsed.scheme == "wss":
            raise ValueError("wss:// is not implemented yet")
        host = parsed.hostname or "127.0.0.1"
        port = parsed.port or 8765
        path = parsed.path or "/v1/ws/control"
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        sock = socket.create_connection((host, port), timeout=5)
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
        )
        if self.token:
            request += f"Authorization: Bearer {self.token}\r\n"
        request += "\r\n"
        try:
            sock.sendall(request.encode("ascii"))
            response = bytearray()
            while b"\r\n\r\n" not in response:
                if len(response) >= 65_536:
                    raise OSError("websocket Upgrade response headers are too large")
                chunk = sock.recv(1)
                if not chunk:
                    raise OSError("socket closed during websocket Upgrade")
                response.extend(chunk)
            text = bytes(response).decode("iso-8859-1", errors="replace")
            if not text.startswith("HTTP/1.1 101"):
                raise OSError(text.split("\r\n", 1)[0])
            if websocket_accept(key) not in text:
                raise OSError("bad Sec-WebSocket-Accept")
            # The create timeout only bounds TCP connect and the HTTP Upgrade.
            # An established socket blocks until an event, result, or idle close.
            sock.settimeout(None)
            with self.connect_lock:
                with self.lock:
                    if self.sock is not None:
                        sock.close()
                        return
                    self.sock = sock
                    self.generation += 1
                    generation = self.generation
                    self.connected_at = now_ms()
                    self.reader = threading.Thread(
                        target=self._read_loop,
                        args=(sock, generation),
                        name="henyo-ws-reader",
                        daemon=True,
                    )
                    self.reader.start()
        except Exception:
            try:
                sock.close()
            except OSError:
                pass
            raise

    def close(self, reason: str = "ws_disconnected", expected_sock=None) -> bool:
        with self.connect_lock:
            with self.lock:
                if expected_sock is not None and self.sock is not expected_sock:
                    return False
                sock = self.sock
                if sock is None:
                    return False
                self.sock = None
                self.generation += 1
                pending = list(self.pending.values())
                self.pending.clear()
            try:
                sock.close()
            except OSError:
                pass
            failure = {"ok": False, "error": "ws_disconnected", "reason": reason}
            for waiter in pending:
                try:
                    waiter.put_nowait(failure)
                except self.queue_mod.Full:
                    pass
            # Complete cache invalidation before another thread can publish a
            # replacement socket and receive its session.ready event.
            if self.on_disconnect is not None:
                self.on_disconnect(reason)
            return True

    def ensure_connected(self) -> bool:
        with self.lock:
            if self.sock is not None:
                return True
        with self.connect_lock:
            with self.lock:
                if self.sock is not None:
                    return True
            backoff = 0.2
            for attempt in range(5):
                try:
                    self.connect()
                    self.last_error = ""
                    return True
                except Exception as exc:
                    self.last_error = str(exc)
                    if attempt < 4:
                        time.sleep(backoff)
                        backoff = min(2.0, backoff * 2)
            return False

    def call(
        self,
        op: str,
        params: Optional[Dict[str, Any]] = None,
        action_id: str = "",
        timeout: float = 10.0,
        display: Optional[Dict[str, Any]] = None,
        expected_service_epoch: Optional[str] = None,
        timeout_ms: Optional[int] = None,
    ) -> Dict[str, Any]:
        timeout_ms = validate_call_timeout_ms(timeout_ms)
        if not self.ensure_connected():
            return {"ok": False, "error": "ws_unavailable", "message": self.last_error}
        request_id = ""
        try:
            with self.lock:
                if self.sock is None:
                    return {"ok": False, "error": "ws_disconnected"}
                sock = self.sock
                request_id = f"helper-{self.next_id}"
                self.next_id += 1
                q: "queue.Queue[Dict[str, Any]]" = self.queue_mod.Queue(maxsize=1)
                self.pending[request_id] = q
                payload = {"type": "call", "id": request_id, "op": op, "params": params or {}}
                if action_id:
                    payload["actionId"] = action_id
                if expected_service_epoch is not None:
                    payload["expectedServiceEpoch"] = expected_service_epoch
                if timeout_ms is not None:
                    payload["timeoutMs"] = timeout_ms
                if display is not None:
                    payload["display"] = display
                sock.sendall(encode_client_text(payload))
        except Exception as exc:
            self.last_error = str(exc)
            self.close(expected_sock=sock)
            return {"ok": False, "error": "ws_disconnected"}
        try:
            return q.get(timeout=timeout)
        except Exception:
            return {"ok": False, "error": "timeout"}
        finally:
            with self.lock:
                self.pending.pop(request_id, None)

    def batch(
        self,
        steps,
        stop_on_error=True,
        return_tree=False,
        action_id: str = "",
        timeout: Optional[float] = None,
        display: Optional[Dict[str, Any]] = None,
        timeout_ms: Optional[int] = None,
    ) -> Dict[str, Any]:
        timeout_ms = validate_batch_timeout_ms(timeout_ms)
        wait_timeout = batch_ws_timeout_seconds(timeout_ms) if timeout is None else timeout
        if not self.ensure_connected():
            return {"ok": False, "error": "ws_unavailable", "message": self.last_error}
        request_id = ""
        try:
            with self.lock:
                if self.sock is None:
                    return {"ok": False, "error": "ws_disconnected"}
                sock = self.sock
                request_id = f"helper-{self.next_id}"
                self.next_id += 1
                q = self.queue_mod.Queue(maxsize=1)
                self.pending[request_id] = q
                payload = {
                    "type": "batch",
                    "id": request_id,
                    "steps": steps,
                    "stopOnError": stop_on_error,
                    "returnTree": return_tree,
                }
                if timeout_ms is not None:
                    payload["timeoutMs"] = timeout_ms
                if action_id:
                    payload["actionId"] = action_id
                if display is not None:
                    payload["display"] = display
                sock.sendall(encode_client_text(payload))
        except Exception as exc:
            self.last_error = str(exc)
            self.close(expected_sock=sock)
            return {"ok": False, "error": "ws_disconnected"}
        try:
            return q.get(timeout=wait_timeout)
        except Exception:
            return {"ok": False, "error": "timeout"}
        finally:
            with self.lock:
                self.pending.pop(request_id, None)

    def set_progress(self, goal: str = "", completed=None, current: str = "",
                     steps=None, replan: bool = False) -> Dict[str, Any]:
        """Replace session-ephemeral presentation state; never cache or replay it."""
        if steps is not None:
            return self.call("task.progress.set", {
                "goal": goal,
                "steps": list(steps),
                "replan": replan,
            })
        return self.call("task.progress.set", {
            "goal": goal, "completed": list(completed or []), "current": current,
        })

    def finish_progress(self) -> Dict[str, Any]:
        """Explicitly clear session-ephemeral task progress presentation."""
        return self.call("task.progress.finish", {})

    def show_completion(self, message: str) -> Dict[str, Any]:
        """Show one replace-only completion without caching, logging, or replay."""
        return self.call("task.completion.show", {"message": message})

    def _read_loop(self, sock, generation: int) -> None:
        while True:
            try:
                opcode, payload = decode_server_frame(sock)
                # Decoding happens without locks so a slow network cannot block
                # callers.  Once decoded, generation validation and delivery are
                # atomic with respect to close/reconnect; otherwise an old reader
                # can invalidate or repopulate the replacement session's cache.
                with self.connect_lock:
                    with self.lock:
                        if self.sock is not sock or self.generation != generation:
                            return
                    if opcode == 8:
                        self.close(expected_sock=sock)
                        return
                    if opcode != 1:
                        continue
                    if payload.get("type") == "event":
                        if payload.get("event") == "session.closing":
                            reason = payload.get("reason") if isinstance(payload.get("reason"), str) else "ws_disconnected"
                            self.close(reason=reason, expected_sock=sock)
                            return
                        self.on_event(payload)
                        continue
                    request_id = payload.get("id")
                    with self.lock:
                        waiter = self.pending.get(request_id)
                    if waiter is not None:
                        try:
                            waiter.put_nowait(payload)
                        except self.queue_mod.Full:
                            pass
            except Exception as exc:
                self.last_error = str(exc)
                self.close(expected_sock=sock)
                return


class HelperDaemon:
    def __init__(self, transport: Any):
        if isinstance(transport, Path):
            transport = HelperTransport.from_env(transport)
        self.transport = transport
        self.socket_path = transport.socket_path
        self.host = transport.host
        self.port = transport.port
        self.discovery_path = transport.discovery_path
        self.helper_token = secrets.token_urlsafe(32) if transport.transport == "tcp" else ""
        self.log_path = default_log_path()
        self.started_at = now_ms()
        self.stop_event = threading.Event()
        self.cache_lock = threading.RLock()
        self.session_condition = threading.Condition(self.cache_lock)
        self.cache: Dict[str, Any] = {
            "tree": None,
            "treeVersion": None,
            "current": None,
            "treeUpdatedAt": 0,
            "currentUpdatedAt": 0,
            "treeUpdatedMonotonicMs": 0,
            "currentUpdatedMonotonicMs": 0,
            "treeActionGeneration": -1,
            "currentActionGeneration": -1,
            "treeServiceEpoch": "",
            "currentServiceEpoch": "",
            "serviceEpoch": "",
            "sessionMetadata": None,
            "sessionMetadataGeneration": -1,
            "sessionMetadataError": "",
            "sessionMetadataErrorGeneration": -1,
            "lastDirtyEventSeq": 0,
            "lastDirtyElapsedRealtimeMs": 0,
            "pendingAction": False,
            "pendingActionId": "",
            "expectedPackage": "",
            "actionStartedAt": 0,
            "actionGeneration": 0,
            "latestActionId": "",
            "lastTreeReason": "",
            "lastTreeSettled": None,
            "lastTreeChanged": None,
            "lastTreeTimedOut": None,
            "lastTreeErrorCode": "",
            "lastTreeEventSeq": 0,
            "lastTreeCapturedAt": "",
            "lastTreeActionId": "",
            "lastTreeDigest": "",
        }
        self.ws = WsClient(ws_url(), configured_token(), self.handle_event, self.handle_disconnect)
        self.target_identity = canonical_ws_url(self.ws.url)

    def target_mismatch(self, request: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        if request.get("cmd") not in TARGET_BOUND_COMMANDS:
            return None
        expected = request.get("targetIdentity")
        if expected == self.target_identity:
            return None
        return {
            "ok": False,
            "error": "helper_target_mismatch",
            "expectedTarget": expected if isinstance(expected, str) else "",
            "boundTarget": self.target_identity,
            "message": "helper is bound to a different target; stop it before switching targets",
        }

    def handle_disconnect(self, reason: str = "ws_disconnected") -> None:
        # A disconnected session has no trustworthy observation generation.
        # Do not retain a pending mutation that must never be auto-replayed.
        with self.cache_lock:
            had_session_state = bool(
                self.cache.get("tree") is not None
                or self.cache.get("current") is not None
                or self.cache.get("serviceEpoch")
                or self.cache.get("pendingAction")
            )
            self.invalidate_observations()
            self.cache.update({
                "serviceEpoch": "",
                "sessionMetadata": None,
                "sessionMetadataGeneration": -1,
                "pendingAction": False,
                "pendingActionId": "",
                "latestActionId": "",
                "expectedPackage": "",
                "actionStartedAt": 0,
                "actionGeneration": (
                    int(self.cache.get("actionGeneration", 0) or 0)
                    + (1 if had_session_state else 0)
                ),
            })
            self.session_condition.notify_all()

    def handle_event(self, event: Dict[str, Any]) -> None:
        if event.get("event") == "session.ready":
            close_reason = ""
            with self.session_condition:
                generation = getattr(self.ws, "generation", 0)
                try:
                    metadata = sanitize_session_ready(event)
                except SessionMetadataError as exc:
                    self.cache["sessionMetadata"] = None
                    self.cache["sessionMetadataGeneration"] = -1
                    self.cache["sessionMetadataError"] = exc.code
                    self.cache["sessionMetadataErrorGeneration"] = generation
                    close_reason = exc.code
                else:
                    self.note_service_epoch(metadata["serviceEpoch"])
                    self.cache["sessionMetadata"] = metadata
                    self.cache["sessionMetadataGeneration"] = generation
                    self.cache["sessionMetadataError"] = ""
                    self.cache["sessionMetadataErrorGeneration"] = -1
                self.session_condition.notify_all()
            if close_reason:
                self.ws.close(reason=close_reason)
            return
        with self.cache_lock:
            self._handle_event_locked(event)

    def _handle_event_locked(self, event: Dict[str, Any]) -> None:
        event_name = event.get("event")
        if event_name == "session.closing":
            self.handle_disconnect(
                event.get("reason") if isinstance(event.get("reason"), str) else "ws_disconnected"
            )
            return
        if event_name == "ui.dirty":
            # Dirty carries no tree contents. Invalidate both derived views before
            # a later full snapshot arrives.
            self.note_service_epoch(event.get("serviceEpoch"))
            self.invalidate_observations()
            event_seq = event.get("eventSeq")
            elapsed = event.get("eventElapsedRealtimeMs")
            self.cache["lastDirtyEventSeq"] = event_seq if self.valid_int(event_seq) else 0
            self.cache["lastDirtyElapsedRealtimeMs"] = elapsed if self.valid_int(elapsed) else 0
            return
        if event_name == "ui.tree":
            # Never log the tree payload. Keep it in memory only.
            if event.get("ok") is False:
                self.note_service_epoch(event.get("serviceEpoch"))
                self.cache["lastTreeReason"] = event.get("reason", "") if isinstance(event.get("reason"), str) else ""
                self.cache["lastTreeSettled"] = event.get("settled") if isinstance(event.get("settled"), bool) else None
                self.cache["lastTreeTimedOut"] = event.get("timedOut") if isinstance(event.get("timedOut"), bool) else None
                self.cache["lastTreeEventSeq"] = event.get("eventSeq") if self.valid_int(event.get("eventSeq")) else 0
                self.cache["lastTreeActionId"] = event.get("actionId", "") if isinstance(event.get("actionId"), str) else ""
                self.cache["lastTreeErrorCode"] = event.get("code", "") if isinstance(event.get("code"), str) else ""
                return
            stable = event.get("stable")
            if stable is False:
                return
            capture_begin_seq = event.get("captureBeginEventSeq")
            capture_end_seq = event.get("captureEndEventSeq")
            if (self.valid_int(capture_begin_seq)
                    and self.valid_int(capture_end_seq)
                    and capture_begin_seq != capture_end_seq):
                return
            self.note_service_epoch(event.get("serviceEpoch"))
            current = event.get("currentApp")
            pending = bool(self.cache.get("pendingAction"))
            expected_package = self.cache.get("expectedPackage") or ""
            if pending and expected_package:
                if not isinstance(current, dict) or current.get("package") != expected_package:
                    return
            settled = event.get("settled") if isinstance(event.get("settled"), bool) else None
            changed = event.get("changed") if isinstance(event.get("changed"), bool) else None
            action_id = event.get("actionId") if isinstance(event.get("actionId"), str) else ""
            pending_action_id = self.cache.get("pendingActionId") if isinstance(self.cache.get("pendingActionId"), str) else ""
            latest_action_id = self.cache.get("latestActionId") if isinstance(self.cache.get("latestActionId"), str) else ""
            has_settling_metadata = any(key in event for key in ("actionId", "settled", "changed", "eventSeq", "treeDigest", "capturedAt"))
            legacy_tree_event = not has_settling_metadata
            incoming_version = event.get("treeVersion") if isinstance(event.get("treeVersion"), int) and not isinstance(event.get("treeVersion"), bool) else None
            cached_version = self.cache.get("treeVersion") if isinstance(self.cache.get("treeVersion"), int) and not isinstance(self.cache.get("treeVersion"), bool) else None
            incoming_event_seq = event.get("eventSeq") if isinstance(event.get("eventSeq"), int) and not isinstance(event.get("eventSeq"), bool) else None
            cached_event_seq = self.cache.get("lastTreeEventSeq") if isinstance(self.cache.get("lastTreeEventSeq"), int) and not isinstance(self.cache.get("lastTreeEventSeq"), bool) else 0
            incoming_digest = event.get("treeDigest") if isinstance(event.get("treeDigest"), str) else ""
            cached_digest = self.cache.get("lastTreeDigest") if isinstance(self.cache.get("lastTreeDigest"), str) else ""
            if action_id and latest_action_id and action_id != latest_action_id:
                return
            if pending:
                if has_settling_metadata and action_id != pending_action_id:
                    return
                if cached_version is not None and incoming_version is not None and incoming_version < cached_version:
                    return
                if (settled is False
                        and incoming_version is not None
                        and cached_version is not None
                        and incoming_version == cached_version
                        and incoming_digest == cached_digest
                        and (incoming_event_seq is None or incoming_event_seq <= cached_event_seq)):
                    return
            self.cache["tree"] = event
            self.cache["treeVersion"] = event.get("treeVersion")
            self.cache["treeUpdatedAt"] = now_ms()
            self.cache["treeUpdatedMonotonicMs"] = monotonic_ms()
            self.cache["treeActionGeneration"] = int(self.cache.get("actionGeneration", 0) or 0)
            self.cache["treeServiceEpoch"] = self.cache.get("serviceEpoch", "")
            self.cache["lastTreeReason"] = event.get("reason", "") if isinstance(event.get("reason"), str) else ""
            self.cache["lastTreeSettled"] = settled
            self.cache["lastTreeChanged"] = changed
            self.cache["lastTreeTimedOut"] = event.get("timedOut") if isinstance(event.get("timedOut"), bool) else None
            self.cache["lastTreeErrorCode"] = ""
            self.cache["lastTreeEventSeq"] = incoming_event_seq or 0
            self.cache["lastTreeCapturedAt"] = event.get("capturedAt", "") if isinstance(event.get("capturedAt"), str) else ""
            self.cache["lastTreeActionId"] = event.get("actionId", "") if isinstance(event.get("actionId"), str) else ""
            self.cache["lastTreeDigest"] = incoming_digest
            if isinstance(current, dict):
                self.cache["current"] = current
                self.cache["currentUpdatedAt"] = now_ms()
                self.cache["currentUpdatedMonotonicMs"] = monotonic_ms()
                self.cache["currentActionGeneration"] = int(self.cache.get("actionGeneration", 0) or 0)
                self.cache["currentServiceEpoch"] = self.cache.get("serviceEpoch", "")
            if pending:
                if settled is True or legacy_tree_event:
                    self.cache["pendingAction"] = False
                    self.cache["pendingActionId"] = ""
                    self.cache["expectedPackage"] = ""

    @staticmethod
    def valid_int(value: Any) -> bool:
        return isinstance(value, int) and not isinstance(value, bool)

    def invalidate_observations(self) -> None:
        with self.cache_lock:
            self.cache.update({
            "tree": None,
            "treeVersion": None,
            "current": None,
            "treeUpdatedAt": 0,
            "currentUpdatedAt": 0,
            "treeUpdatedMonotonicMs": 0,
            "currentUpdatedMonotonicMs": 0,
            "treeActionGeneration": -1,
            "currentActionGeneration": -1,
            "treeServiceEpoch": "",
            "currentServiceEpoch": "",
            })

    def note_service_epoch(self, value: Any) -> None:
        if not isinstance(value, str) or not value:
            return
        with self.cache_lock:
            active = self.cache.get("serviceEpoch")
            if active and active != value:
                self.invalidate_observations()
            self.cache["serviceEpoch"] = value

    def session_status(self) -> Dict[str, Any]:
        if not self.ws.ensure_connected():
            return {"ok": False, "error": "ws_unavailable"}
        with self.ws.lock:
            generation = self.ws.generation
            if self.ws.sock is None:
                with self.session_condition:
                    if self.cache.get("sessionMetadataErrorGeneration") == generation - 1:
                        return {"ok": False, "error": self.cache["sessionMetadataError"]}
                return {"ok": False, "error": "ws_disconnected"}
        deadline = time.monotonic() + SESSION_READY_TIMEOUT_SECONDS
        while True:
            with self.session_condition:
                if self.cache.get("sessionMetadataErrorGeneration") == generation:
                    return {"ok": False, "error": self.cache["sessionMetadataError"]}
                if self.cache.get("sessionMetadataGeneration") == generation:
                    metadata = self.cache.get("sessionMetadata")
                    if isinstance(metadata, dict):
                        return {"ok": True, **deepcopy(metadata)}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return {"ok": False, "error": "timeout"}
                self.session_condition.wait(timeout=min(remaining, 0.1))
            with self.ws.lock:
                if self.ws.sock is None or self.ws.generation != generation:
                    with self.session_condition:
                        if self.cache.get("sessionMetadataErrorGeneration") == generation:
                            return {"ok": False, "error": self.cache["sessionMetadataError"]}
                    return {"ok": False, "error": "ws_disconnected"}

    @staticmethod
    def requested_max_age_ms(request: Dict[str, Any]) -> int:
        value = request.get("maxAgeMs", DEFAULT_CACHE_MAX_AGE_MS)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return DEFAULT_CACHE_MAX_AGE_MS
        # The daemon's safety default cannot be relaxed by an individual caller.
        return min(value, DEFAULT_CACHE_MAX_AGE_MS)

    def cache_is_current(self, kind: str, max_age_ms: int) -> bool:
        with self.cache_lock:
            if self.cache.get(kind) is None or self.cache.get("pendingAction"):
                return False
            generation = int(self.cache.get("actionGeneration", 0) or 0)
            if self.cache.get(f"{kind}ActionGeneration") != generation:
                return False
            active_epoch = self.cache.get("serviceEpoch") or ""
            cached_epoch = self.cache.get(f"{kind}ServiceEpoch") or ""
            if active_epoch and cached_epoch != active_epoch:
                return False
            updated = int(self.cache.get(f"{kind}UpdatedMonotonicMs", 0) or 0)
            return updated > 0 and max(0, monotonic_ms() - updated) <= max_age_ms

    def cached_response(self, kind: str, max_age_ms: int) -> Optional[Dict[str, Any]]:
        with self.cache_lock:
            if not self.cache_is_current(kind, max_age_ms):
                return None
            value = self.cache.get(kind)
            if value is None:
                return None
            response = {
                "ok": True,
                "cached": True,
                "cacheAgeMs": max(0, monotonic_ms() - int(self.cache[f"{kind}UpdatedMonotonicMs"])),
            }
            response["tree" if kind == "tree" else "result"] = value
            return response

    def mark_action_pending(self, expected_package: str = "") -> str:
        with self.cache_lock:
            generation = int(self.cache.get("actionGeneration", 0) or 0) + 1
            action_id = f"helper-action-{generation}"
            self.cache.update({
            "tree": None,
            "treeVersion": None,
            "current": None,
            "treeUpdatedMonotonicMs": 0,
            "currentUpdatedMonotonicMs": 0,
            "treeActionGeneration": -1,
            "currentActionGeneration": -1,
            "treeServiceEpoch": "",
            "currentServiceEpoch": "",
            "pendingAction": True,
            "pendingActionId": action_id,
            "expectedPackage": expected_package,
            "actionStartedAt": now_ms(),
            "actionGeneration": generation,
            "latestActionId": action_id,
            })
            return action_id

    def note_fresh_current(self, result: Dict[str, Any]) -> None:
        if not result.get("ok") or not isinstance(result.get("result"), dict):
            return
        current = result["result"]
        with self.cache_lock:
            result_epoch = current.get("serviceEpoch") or result.get("serviceEpoch")
            self.note_service_epoch(result_epoch)
            self.cache["current"] = current
            self.cache["currentUpdatedAt"] = now_ms()
            self.cache["currentUpdatedMonotonicMs"] = monotonic_ms()
            self.cache["currentActionGeneration"] = int(self.cache.get("actionGeneration", 0) or 0)
            self.cache["currentServiceEpoch"] = self.cache.get("serviceEpoch", "")
            expected = self.cache.get("expectedPackage") or ""
            if expected and current.get("package") == expected:
                self.cache["pendingAction"] = False
                self.cache["pendingActionId"] = ""
                self.cache["expectedPackage"] = ""

    def expected_package_for(self, op: str, params: Dict[str, Any]) -> str:
        if op in ("app.launch", "app.openUri"):
            value = params.get("package")
            return value if isinstance(value, str) else ""
        if op == "app.start":
            value = params.get("component")
            if isinstance(value, str) and "/" in value:
                return value.split("/", 1)[0]
        return ""

    def batch_has_mutating_step(self, steps: Any) -> bool:
        if not isinstance(steps, list):
            return False
        for step in steps:
            if not isinstance(step, dict):
                continue
            op = step.get("op")
            if isinstance(op, str) and op in BATCH_MUTATING_OPS:
                return True
        return False

    def settle_foreground(self, expected_package: str, timeout_ms: int = 5000, interval_ms: int = 200) -> Dict[str, Any]:
        started = now_ms()
        last_current: Dict[str, Any] = {}
        deadline = started + max(0, timeout_ms)
        while now_ms() <= deadline:
            current = self.ws.call("app.current", {})
            if isinstance(current.get("result"), dict):
                last_current = current["result"]
                self.note_fresh_current(current)
                if last_current.get("package") == expected_package:
                    return {
                        "foreground": True,
                        "expectedPackage": expected_package,
                        "package": last_current.get("package", ""),
                        "className": last_current.get("className", ""),
                        "settledMs": now_ms() - started,
                    }
            time.sleep(max(0.05, interval_ms / 1000.0))
        return {
            "foreground": False,
            "expectedPackage": expected_package,
            "currentPackage": last_current.get("package", ""),
            "currentClassName": last_current.get("className", ""),
            "pendingAction": True,
            "settledMs": now_ms() - started,
        }

    def maybe_settle_action(self, op: str, params: Dict[str, Any], response: Dict[str, Any]) -> Dict[str, Any]:
        expected_package = self.expected_package_for(op, params)
        if not expected_package or not response.get("ok") or not isinstance(response.get("result"), dict):
            return response
        settle = self.settle_foreground(expected_package)
        result = dict(response["result"])
        result.update(settle)
        response = dict(response)
        response["result"] = result
        return response

    def serve(self) -> None:
        if self.transport.transport == "tcp":
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            server.bind((self.host, self.port))
            self.host, self.port = server.getsockname()[:2]
        else:
            ensure_private_dir(self.socket_path.parent)
            remove_file(self.socket_path)
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(str(self.socket_path))
            os.chmod(self.socket_path, 0o600)
        server.listen(20)
        self.write_discovery()
        selector = selectors.DefaultSelector()
        selector.register(server, selectors.EVENT_READ)
        while not self.stop_event.is_set():
            for key, _ in selector.select(0.2):
                if key.fileobj is server:
                    conn, _ = server.accept()
                    threading.Thread(target=self.handle_client, args=(conn,), daemon=True).start()
        selector.close()
        server.close()
        if self.transport.transport == "unix":
            remove_file(self.socket_path)
        remove_file(self.discovery_path)
        self.ws.close()

    def write_discovery(self) -> None:
        data: Dict[str, Any] = {
            "transport": self.transport.transport,
            "pid": os.getpid(),
            "startedAt": self.started_at,
        }
        if self.transport.transport == "tcp":
            data.update({"host": self.host, "port": self.port, "token": self.helper_token})
        else:
            data["socket"] = str(self.socket_path)
        write_discovery(self.discovery_path, data)

    def handle_client(self, conn: socket.socket) -> None:
        with conn:
            raw = b""
            while not raw.endswith(b"\n"):
                chunk = conn.recv(65536)
                if not chunk:
                    return
                raw += chunk
            try:
                request = json.loads(raw.decode("utf-8"))
                response = self.dispatch(request, validate_target=True)
            except Exception as exc:
                response = {"ok": False, "error": "helper_error", "message": str(exc)}
            conn.sendall((json_dumps(response) + "\n").encode("utf-8"))

    def dispatch(
        self,
        request: Dict[str, Any],
        validate_target: bool = False,
    ) -> Dict[str, Any]:
        if self.transport.transport == "tcp":
            token = request.get("token")
            if not isinstance(token, str) or not secrets.compare_digest(token, self.helper_token):
                return {"ok": False, "error": "helper_auth_failed"}
        if validate_target:
            mismatch = self.target_mismatch(request)
            if mismatch is not None:
                return mismatch
        cmd = request.get("cmd")
        if cmd == "session.status":
            return self.session_status()
        if cmd == "status":
            with self.cache_lock:
                cache_status = {
                    key: self.cache.get(key) for key in (
                        "treeVersion", "pendingAction", "pendingActionId", "expectedPackage",
                        "lastTreeReason", "lastTreeSettled", "lastTreeChanged", "lastTreeEventSeq",
                        "lastTreeTimedOut", "lastTreeErrorCode",
                        "lastTreeCapturedAt", "lastTreeActionId", "lastTreeDigest", "serviceEpoch",
                        "lastDirtyEventSeq", "lastDirtyElapsedRealtimeMs", "actionStartedAt",
                    )
                }
            status = {
                "ok": True,
                "pid": os.getpid(),
                "transport": self.transport.transport,
                "log": str(self.log_path),
                "discovery": str(self.discovery_path),
                "startedAt": self.started_at,
                "wsConnected": self.ws.sock is not None,
                "wsUrl": self.ws.url,
                "targetIdentity": self.target_identity,
                "lastError": self.ws.last_error,
                "treeVersion": cache_status["treeVersion"],
                "pendingAction": cache_status["pendingAction"],
                "pendingActionId": cache_status["pendingActionId"],
                "pendingActionAgeMs": max(0, now_ms() - int(cache_status["actionStartedAt"] or 0)) if cache_status["pendingAction"] else 0,
                "expectedPackage": cache_status["expectedPackage"],
                "lastTreeReason": cache_status["lastTreeReason"],
                "lastTreeSettled": cache_status["lastTreeSettled"],
                "lastTreeChanged": cache_status["lastTreeChanged"],
                "lastTreeTimedOut": cache_status["lastTreeTimedOut"],
                "lastTreeErrorCode": cache_status["lastTreeErrorCode"],
                "lastTreeEventSeq": cache_status["lastTreeEventSeq"],
                "lastTreeCapturedAt": cache_status["lastTreeCapturedAt"],
                "lastTreeActionId": cache_status["lastTreeActionId"],
                "lastTreeDigest": cache_status["lastTreeDigest"],
                "serviceEpoch": cache_status["serviceEpoch"],
                "lastDirtyEventSeq": cache_status["lastDirtyEventSeq"],
                "lastDirtyElapsedRealtimeMs": cache_status["lastDirtyElapsedRealtimeMs"],
            }
            if self.transport.transport == "tcp":
                status.update({"host": self.host, "port": self.port})
            else:
                status["socket"] = str(self.socket_path)
            return status
        if cmd == "stop":
            self.stop_event.set()
            return {"ok": True}
        if cmd == "tree":
            fresh = bool(request.get("fresh"))
            max_age_ms = self.requested_max_age_ms(request)
            cached = None if fresh else self.cached_response("tree", max_age_ms)
            if cached is not None:
                return cached
            display = request.get("display") if isinstance(request.get("display"), dict) else None
            if display is None:
                result = self.ws.call("ui.tree", request.get("params") or {})
            else:
                result = self.ws.call("ui.tree", request.get("params") or {}, display=display)
            with self.cache_lock:
                tree = self.cache.get("tree")
            return {"ok": result.get("ok", False), "cached": False, "result": result, "tree": tree}
        if cmd == "current":
            fresh = bool(request.get("fresh"))
            max_age_ms = self.requested_max_age_ms(request)
            cached = None if fresh else self.cached_response("current", max_age_ms)
            if cached is not None:
                return cached
            display = request.get("display") if isinstance(request.get("display"), dict) else None
            if display is None:
                result = self.ws.call("app.current", {})
            else:
                result = self.ws.call("app.current", {}, display=display)
            self.note_fresh_current(result)
            return result
        if cmd == "observe":
            params = request.get("params") or {}
            observe_timeout = env_timeout("HENYO_OBSERVE_TIMEOUT", max(20.0, observe_timeout_seconds(params)))
            display = request.get("display") if isinstance(request.get("display"), dict) else None
            if display is None:
                result = self.ws.call("ui.observe", params, timeout=observe_timeout)
            else:
                result = self.ws.call("ui.observe", params, timeout=observe_timeout, display=display)
            operation_result = result.get("result") if isinstance(result.get("result"), dict) else {}
            observation = operation_result.get("observation") if isinstance(operation_result.get("observation"), dict) else {}
            if observation.get("stable") is False:
                # Return the explicit failure without retaining either sensitive
                # payload in the helper cache.
                return {"ok": False, "error": "unstable_observation", "result": result}
            return result
        if cmd == "progress.set":
            goal = request.get("goal", "")
            if "steps" in request:
                steps = request.get("steps")
                replan = request.get("replan", False)
                if "completed" in request or "current" in request:
                    return {"ok": False, "error": "invalid_progress",
                            "message": "steps cannot be mixed with completed or current"}
                if not isinstance(goal, str) or not goal.strip():
                    return {"ok": False, "error": "invalid_progress",
                            "message": "structured progress requires a non-empty goal"}
                if not isinstance(replan, bool):
                    return {"ok": False, "error": "invalid_progress",
                            "message": "replan must be a boolean"}
                statuses = {"pending", "in_progress", "completed"}
                valid_steps = (isinstance(steps, list) and 1 <= len(steps) <= 6
                               and all(isinstance(step, dict)
                                       and isinstance(step.get("text"), str)
                                       and step.get("text", "").strip()
                                       and step.get("status") in statuses
                                       for step in steps))
                if not valid_steps:
                    return {"ok": False, "error": "invalid_progress",
                            "message": "steps must contain one to six text/status objects"}
                response = self.ws.set_progress(goal, steps=steps, replan=replan)
                if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                    self.handle_disconnect(response["error"])
                return response
            if "replan" in request:
                return {"ok": False, "error": "invalid_progress",
                        "message": "replan requires structured steps"}
            current = request.get("current", "")
            completed = request.get("completed", [])
            if not isinstance(goal, str) or not isinstance(current, str):
                return {"ok": False, "error": "invalid_progress", "message": "goal and current must be strings"}
            if (not isinstance(completed, list)
                    or any(not isinstance(item, str) for item in completed)):
                return {"ok": False, "error": "invalid_progress", "message": "completed must be a string array"}
            if not goal.strip() and not current.strip() and not any(item.strip() for item in completed):
                return {"ok": False, "error": "invalid_progress", "message": "progress must contain visible text"}
            response = self.ws.set_progress(goal, completed, current)
            if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                self.handle_disconnect(response["error"])
            return response
        if cmd == "progress.finish":
            response = self.ws.finish_progress()
            if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                self.handle_disconnect(response["error"])
            return response
        if cmd == "completion.show":
            message = request.get("message")
            if not isinstance(message, str) or not message:
                return {"ok": False, "error": "completion_invalid",
                        "message": "completion message must be a non-empty string"}
            if unicode_code_point_count(message) > MAX_COMPLETION_CODE_POINTS:
                return {"ok": False, "error": "completion_too_long",
                        "message": "completion message exceeds 250 Unicode code points"}
            response = self.ws.show_completion(message)
            if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                self.handle_disconnect(response["error"])
            return response
        if cmd == "call":
            if set(request) - CALL_REQUEST_FIELDS:
                return {"ok": False, "error": "invalid_call_request"}
            action_id_present = "actionId" in request
            expected_epoch_present = "expectedServiceEpoch" in request
            caller_action_id = request.get("actionId")
            expected_service_epoch = request.get("expectedServiceEpoch")
            try:
                timeout_ms = validate_call_timeout_ms(request.get("timeoutMs"))
            except ValueError:
                return {"ok": False, "error": "invalid_call_request"}
            if action_id_present and not valid_call_opaque_id(caller_action_id):
                return {"ok": False, "error": "invalid_call_request"}
            if expected_epoch_present and not valid_call_opaque_id(expected_service_epoch):
                return {"ok": False, "error": "invalid_call_request"}
            op = request.get("op")
            params = request.get("params") or {}
            internal_action_id = ""
            if op and any(op.startswith(prefix) for prefix in ("ui.", "app.", "global.")):
                if op not in ("ui.tree", "ui.find", "ui.wait", "ui.observe", "app.current", "app.list"):
                    internal_action_id = self.mark_action_pending(self.expected_package_for(op, params))
            wire_action_id = caller_action_id if action_id_present else internal_action_id
            call_timeout = 10.0
            if timeout_ms is not None:
                call_timeout = timeout_ms / 1000.0 + CALL_RESPONSE_MARGIN_SECONDS
            elif op == "termux.exec":
                call_timeout = max(1.0, min(120.0, float(params.get("timeout", 30000)) / 1000.0)) + 5.0
            elif op == "screen.screenshot":
                call_timeout = max(
                    1.0,
                    min(MAX_SCREENSHOT_TIMEOUT_SECONDS, float(params.get("timeout", 30000)) / 1000.0),
                ) + 5.0
            display = request.get("display") if isinstance(request.get("display"), dict) else None
            call_kwargs: Dict[str, Any] = {"timeout": call_timeout}
            if display is not None:
                call_kwargs["display"] = display
            if expected_epoch_present:
                call_kwargs["expected_service_epoch"] = expected_service_epoch
            if timeout_ms is not None:
                call_kwargs["timeout_ms"] = timeout_ms
            response = self.ws.call(op, params, wire_action_id, **call_kwargs)
            if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                self.handle_disconnect(response["error"])
            return self.maybe_settle_action(
                op, params, response
            )
        if cmd == "batch":
            steps = request.get("steps") or []
            try:
                timeout_ms = validate_batch_timeout_ms(request.get("timeoutMs"))
            except ValueError as exc:
                return {"ok": False, "error": "invalid_batch_timeout", "message": str(exc)}
            mutating = self.batch_has_mutating_step(steps)
            action_id = ""
            if mutating:
                action_id = self.mark_action_pending()
            display = request.get("display") if isinstance(request.get("display"), dict) else None
            batch_args = (
                steps,
                bool(request.get("stopOnError", True)),
                bool(request.get("returnTree", True)),
                action_id,
            )
            batch_kwargs: Dict[str, Any] = {}
            if display is not None:
                batch_kwargs["display"] = display
            if timeout_ms is not None:
                batch_kwargs["timeout_ms"] = timeout_ms
            response = self.ws.batch(*batch_args, **batch_kwargs)
            if response.get("error") in ("ws_disconnected", "ws_unavailable"):
                self.handle_disconnect(response["error"])
            return response
        if cmd == "cache.clear":
            with self.cache_lock:
                self.invalidate_observations()
                self.cache.update({
                "pendingAction": False,
                "pendingActionId": "",
                "latestActionId": "",
                "expectedPackage": "",
                "actionStartedAt": 0,
                "lastTreeReason": "",
                "lastTreeSettled": None,
                "lastTreeChanged": None,
                "lastTreeTimedOut": None,
                "lastTreeErrorCode": "",
                "lastTreeEventSeq": 0,
                "lastTreeCapturedAt": "",
                "lastTreeActionId": "",
                "lastTreeDigest": "",
                "lastDirtyEventSeq": 0,
                "lastDirtyElapsedRealtimeMs": 0,
                })
            return {"ok": True}
        if cmd == "auth.reload":
            self.ws.token = configured_token()
            self.ws.close()
            with self.cache_lock:
                self.invalidate_observations()
                self.cache.update({
                "pendingAction": False,
                "pendingActionId": "",
                "latestActionId": "",
                "expectedPackage": "",
                "actionStartedAt": 0,
                "lastTreeReason": "",
                "lastTreeSettled": None,
                "lastTreeChanged": None,
                "lastTreeTimedOut": None,
                "lastTreeErrorCode": "",
                "lastTreeEventSeq": 0,
                "lastTreeCapturedAt": "",
                "lastTreeActionId": "",
                "lastTreeDigest": "",
                "serviceEpoch": "",
                "lastDirtyEventSeq": 0,
                "lastDirtyElapsedRealtimeMs": 0,
                })
            return {"ok": True, "tokenConfigured": bool(self.ws.token)}
        return {"ok": False, "error": "unknown_helper_command"}


def helper_read_timeout(payload: Dict[str, Any]) -> float:
    cmd = payload.get("cmd")
    if cmd == "batch":
        timeout_ms = validate_batch_timeout_ms(payload.get("timeoutMs"))
        if timeout_ms is None:
            return env_timeout("HENYO_HELPER_READ_TIMEOUT", 30.0)
        requested = batch_ws_timeout_seconds(timeout_ms) + BATCH_HELPER_READ_MARGIN_SECONDS
        return max(requested, env_timeout("HENYO_HELPER_READ_TIMEOUT", requested))
    if cmd == "observe":
        params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
        requested = observe_timeout_seconds(params, margin_seconds=10.0)
        return env_timeout("HENYO_HELPER_READ_TIMEOUT", max(30.0, requested))
    if cmd == "call" and payload.get("timeoutMs") is not None:
        timeout_ms = validate_call_timeout_ms(payload.get("timeoutMs"))
        assert timeout_ms is not None
        requested = timeout_ms / 1000.0 + CALL_HELPER_READ_MARGIN_SECONDS
        return max(requested, env_timeout("HENYO_HELPER_READ_TIMEOUT", requested))
    if cmd == "call" and payload.get("op") == "termux.exec":
        params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
        requested = max(1.0, min(120.0, float(params.get("timeout", 30000)) / 1000.0)) + 10.0
        return env_timeout("HENYO_HELPER_READ_TIMEOUT", requested)
    if cmd == "call" and payload.get("op") == "screen.screenshot":
        params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
        requested = max(
            1.0,
            min(MAX_SCREENSHOT_TIMEOUT_SECONDS, float(params.get("timeout", 30000)) / 1000.0),
        ) + 10.0
        return max(requested, env_timeout("HENYO_HELPER_READ_TIMEOUT", requested))
    if cmd == "session.status":
        return env_timeout("HENYO_HELPER_READ_TIMEOUT", 45.0)
    if cmd in ("call", "tree", "current", "progress.set", "progress.finish",
               "completion.show"):
        return env_timeout("HENYO_HELPER_READ_TIMEOUT", 15.0)
    return env_timeout("HENYO_HELPER_FAST_READ_TIMEOUT", 2.0)


def request_helper(payload: Dict[str, Any], socket_path: Optional[Path] = None) -> Dict[str, Any]:
    transport = HelperTransport.from_env(socket_path)
    request_payload = dict(payload)
    if request_payload.get("cmd") in TARGET_BOUND_COMMANDS:
        request_payload.setdefault("targetIdentity", ws_url())
    if transport.transport == "tcp":
        try:
            discovery = transport.read_discovery()
        except FileNotFoundError:
            if transport.port == 0:
                raise OSError(errno.ENOENT, "helper discovery file not found", str(transport.discovery_path))
            discovery = {"transport": "tcp", "host": transport.host, "port": transport.port}
        if discovery.get("transport") != "tcp":
            raise OSError(errno.EINVAL, "helper discovery transport mismatch", str(transport.discovery_path))
        host = validate_tcp_host(str(discovery.get("host") or transport.host))
        port = int(discovery.get("port") or transport.port)
        token = discovery.get("token")
        request = request_payload
        if isinstance(token, str) and token:
            request.setdefault("token", token)
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        address: Any = (host, port)
    else:
        request = request_payload
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        address = str(transport.socket_path)
    try:
        sock.settimeout(env_timeout("HENYO_HELPER_CONNECT_TIMEOUT", 2.0))
        sock.connect(address)
        sock.settimeout(helper_read_timeout(payload))
        sock.sendall((json_dumps(request) + "\n").encode("utf-8"))
        raw = b""
        while not raw.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            raw += chunk
        return json.loads(raw.decode("utf-8"))
    finally:
        sock.close()


def discovery_error_is_stale(exc: Exception) -> bool:
    return isinstance(exc, (OSError, ValueError, json.JSONDecodeError, TypeError))


def wait_for_helper_shutdown(transport: HelperTransport, timeout: float = 3.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not transport.discovery_path.exists():
            return
        if transport.transport == "unix" and not transport.socket_path.exists():
            remove_file(transport.discovery_path)
            return
        time.sleep(0.05)


def stop_helper(socket_path: Optional[Path] = None) -> Dict[str, Any]:
    transport = HelperTransport.from_env(socket_path)
    response = request_helper({"cmd": "stop"}, transport.socket_path)
    if response.get("ok"):
        wait_for_helper_shutdown(transport)
    return response


def start_background(socket_path: Optional[Path] = None) -> Dict[str, Any]:
    transport = HelperTransport.from_env(socket_path)
    expected_target = ws_url()
    stale_discovery = False
    try:
        status = request_helper({"cmd": "status"}, transport.socket_path)
        if status.get("ok"):
            if status.get("targetIdentity") != expected_target:
                return {
                    "ok": False,
                    "error": "helper_target_mismatch",
                    "expectedTarget": expected_target,
                    "boundTarget": status.get("targetIdentity", ""),
                    "message": "helper is bound to a different target; stop it before switching targets",
                }
            return status
        stale_discovery = transport.transport == "tcp"
    except Exception as exc:
        if not discovery_error_is_stale(exc):
            raise
        stale_discovery = transport.transport == "tcp"
    if stale_discovery:
        remove_file(transport.discovery_path)
    log_path = default_log_path()
    ensure_private_dir(log_path.parent)
    pid_path = default_pid_path()
    cmd = [sys.executable, str(Path(__file__).resolve()), "serve", "--socket", str(transport.socket_path)]
    with log_path.open("ab", buffering=0) as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=log, start_new_session=True)
    pid_path.write_text(str(proc.pid), encoding="utf-8")
    try:
        os.chmod(pid_path, 0o600)
    except OSError:
        pass
    deadline = time.time() + 5
    while time.time() < deadline:
        code = proc.poll()
        if code is not None:
            return {"ok": False, "error": "helper_start_failed", "pid": proc.pid, "exitCode": code, "log": str(log_path)}
        try:
            status = request_helper({"cmd": "status"}, transport.socket_path)
            if status.get("ok"):
                if status.get("targetIdentity") != expected_target:
                    proc.terminate()
                    return {
                        "ok": False,
                        "error": "helper_target_mismatch",
                        "expectedTarget": expected_target,
                        "boundTarget": status.get("targetIdentity", ""),
                        "message": "helper is bound to a different target; stop it before switching targets",
                    }
                return status
        except Exception as exc:
            if not discovery_error_is_stale(exc):
                raise
        time.sleep(0.1)
    return {"ok": False, "error": "helper_start_timeout", "pid": proc.pid, "log": str(log_path)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--socket", default=str(default_socket_path()))
    start = sub.add_parser("start")
    start.add_argument("--socket", default=str(default_socket_path()))
    status = sub.add_parser("status")
    status.add_argument("--socket", default=str(default_socket_path()))
    stop = sub.add_parser("stop")
    stop.add_argument("--socket", default=str(default_socket_path()))
    req = sub.add_parser("request")
    req.add_argument("--socket", default=str(default_socket_path()))
    req.add_argument("json")
    args = parser.parse_args(argv)

    socket_path = Path(getattr(args, "socket", default_socket_path()))
    if args.cmd == "serve":
        daemon = HelperDaemon(HelperTransport.from_env(socket_path))
        signal.signal(signal.SIGTERM, lambda *_: daemon.stop_event.set())
        daemon.serve()
        return 0
    if args.cmd == "start":
        print(json_dumps(start_background(socket_path)))
        return 0
    if args.cmd == "status":
        print(json_dumps(request_helper({"cmd": "status"}, socket_path)))
        return 0
    if args.cmd == "stop":
        print(json_dumps(stop_helper(socket_path)))
        return 0
    if args.cmd == "request":
        print(json_dumps(request_helper(json.loads(args.json), socket_path)))
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
