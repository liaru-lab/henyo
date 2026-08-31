#!/usr/bin/env python3
import argparse
import base64
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, build_opener, ProxyHandler

from henyo import helper


ROOT = Path(__file__).resolve().parents[2]


class HelperTargetMismatchError(RuntimeError):
    def __init__(self, response: Dict[str, Any]):
        super().__init__(dumps(response))
        self.response = response


class HelperCallError(RuntimeError):
    def __init__(self, response: Dict[str, Any]):
        super().__init__(dumps(response))
        self.response = response


def dumps(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def base_url() -> str:
    return os.environ.get("HENYO_URL", "http://127.0.0.1:8765").rstrip("/")


def token() -> str:
    return helper.configured_token()


def http_request(method: str, path: str, body: Dict[str, Any] | None = None, raw: bool = False) -> bytes:
    data = None if body is None else dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token():
        headers["Authorization"] = f"Bearer {token()}"
    req = Request(base_url() + path, data=data, headers=headers, method=method)
    opener = build_opener(ProxyHandler({}))
    try:
        with opener.open(req, timeout=10) as response:
            return response.read()
    except HTTPError as exc:
        if raw:
            raise
        return exc.read()


def print_json(value: Any) -> int:
    print(dumps(value))
    return 0


def helper_request(payload: Dict[str, Any]) -> Dict[str, Any]:
    socket_path = helper.default_socket_path()
    if os.environ.get("HENYO_NO_HELPER"):
        return {"ok": False, "error": "helper_disabled"}
    try:
        return helper.request_helper(payload, socket_path)
    except OSError:
        started = helper.start_background(socket_path)
        if not started.get("ok"):
            return started
        return helper.request_helper(payload, socket_path)


def selector(text: str, args: List[str]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"text": text, "field": "any"}
    i = 0
    while i < len(args):
        if args[i] == "--exact":
            out["exact"] = True
        elif args[i] == "--field":
            i += 1
            out["field"] = args[i]
        elif args[i] == "--clickable-only":
            out["clickableOnly"] = True
        elif args[i] == "--redact":
            pass
        else:
            raise SystemExit(f"unknown selector option: {args[i]}")
        i += 1
    return out


def target_options(argv: List[str]) -> tuple[List[str], Dict[str, Any]]:
    rest: List[str] = []
    target: Dict[str, Any] = {}
    i = 0
    while i < len(argv):
        option = argv[i]
        if option in ("--package", "--window-id", "--display-id"):
            i += 1
            if i >= len(argv):
                raise SystemExit(f"{option} requires a value")
            key = {"--package": "package", "--window-id": "windowId",
                   "--display-id": "displayId"}[option]
            try:
                target[key] = argv[i] if option == "--package" else int(argv[i])
            except ValueError as exc:
                raise SystemExit(f"{option} requires a non-negative integer") from exc
            if option != "--package" and target[key] < 0:
                raise SystemExit(f"{option} requires a non-negative integer")
        else:
            rest.append(option)
        i += 1
    return rest, target


def selector_call_params(text: str, argv: List[str]) -> Dict[str, Any]:
    rest, target = target_options(argv)
    return {"selector": selector(text, rest), **target}


def intent_options(argv: List[str]) -> tuple[List[str], Dict[str, str] | None]:
    """Remove the common --intent option without inspecting operation params."""
    rest: List[str] = []
    summary: str | None = None
    i = 0
    while i < len(argv):
        value = argv[i]
        if value == "--":
            rest.extend(argv[i:])
            break
        if value == "--intent":
            i += 1
            if i >= len(argv):
                raise SystemExit("--intent requires text")
            summary = argv[i]
        else:
            rest.append(value)
        i += 1
    return rest, ({"summary": summary} if summary is not None else None)


def add_display(payload: Dict[str, Any], display: Dict[str, str] | None) -> Dict[str, Any]:
    if display is not None:
        payload["display"] = display
    return payload


def control_call(
    op: str,
    params: Dict[str, Any] | None = None,
    display: Dict[str, str] | None = None,
) -> int:
    response = helper_request(add_display(
        {"cmd": "call", "op": op, "params": params or {}}, display
    ))
    if response.get("type") == "result" and isinstance(response.get("result"), dict):
        return print_json(response["result"])
    return print_json(response)


def coordinate_gesture_params(kind: str, argv: List[str]) -> Dict[str, Any]:
    coordinate_count = 2 if kind == "tap" else 4 if kind == "swipe" else 0
    if coordinate_count == 0:
        raise ValueError(f"unknown coordinate gesture: {kind}")
    if len(argv) < coordinate_count:
        raise SystemExit(f"{kind} requires {coordinate_count} coordinates")

    names = ("x", "y") if kind == "tap" else ("x1", "y1", "x2", "y2")
    params: Dict[str, Any] = {}
    try:
        for name, value in zip(names, argv[:coordinate_count]):
            params[name] = int(value)
    except ValueError as exc:
        raise SystemExit(f"{kind} coordinates must be integers") from exc

    i = coordinate_count
    if kind == "swipe" and i < len(argv) and not argv[i].startswith("--"):
        try:
            params["duration"] = int(argv[i])
        except ValueError as exc:
            raise SystemExit("swipe duration must be an integer") from exc
        i += 1
    elif kind == "swipe":
        params["duration"] = 300

    coordinate_space = ""
    capture_id = ""
    while i < len(argv):
        option = argv[i]
        if option == "--coordinate-space":
            i += 1
            if i >= len(argv):
                raise SystemExit("--coordinate-space requires screen or screenshot")
            coordinate_space = argv[i]
        elif option == "--capture-id":
            i += 1
            if i >= len(argv):
                raise SystemExit("--capture-id requires an id")
            capture_id = argv[i]
        elif option in ("--package", "--window-id", "--display-id"):
            i += 1
            if i >= len(argv):
                raise SystemExit(f"{option} requires a value")
            key = {"--package": "package", "--window-id": "windowId",
                   "--display-id": "displayId"}[option]
            params[key] = argv[i] if option == "--package" else int(argv[i])
        else:
            raise SystemExit(f"unknown {kind} option: {option}")
        i += 1

    if coordinate_space and coordinate_space not in {"screen", "screenshot"}:
        raise SystemExit("--coordinate-space must be screen or screenshot")
    if coordinate_space == "screenshot" and not capture_id:
        raise SystemExit("--capture-id is required for screenshot coordinates")
    if capture_id and coordinate_space != "screenshot":
        raise SystemExit("--capture-id requires --coordinate-space screenshot")
    if coordinate_space:
        params["coordinateSpace"] = coordinate_space
    if capture_id:
        params["captureId"] = capture_id
    return params


def termux_cmd(argv: List[str]) -> int:
    argv, display = intent_options(argv)
    if not argv or argv[0] != "exec":
        usage(); return 2
    workdir = ""
    stdin = ""
    timeout = 30000
    command: List[str] = []
    i = 1
    while i < len(argv):
        value = argv[i]
        if value == "--":
            command = argv[i + 1:]
            break
        if value == "--workdir":
            i += 1
            if i >= len(argv): raise SystemExit("--workdir requires a path")
            workdir = argv[i]
        elif value == "--stdin":
            i += 1
            if i >= len(argv): raise SystemExit("--stdin requires text")
            stdin = argv[i]
        elif value == "--timeout":
            i += 1
            if i >= len(argv): raise SystemExit("--timeout requires milliseconds")
            timeout = int(argv[i])
        elif value.startswith("-"):
            raise SystemExit(f"unknown termux exec option: {value}")
        else:
            command = argv[i:]
            break
        i += 1
    if not command:
        raise SystemExit("termux exec requires a command after --")
    params: Dict[str, Any] = {
        "commandPath": command[0],
        "arguments": command[1:],
        "timeout": timeout,
    }
    if workdir: params["workdir"] = workdir
    if stdin: params["stdin"] = stdin
    return control_call("termux.exec", params, display)


TERMUX_PREFIX = "/data/data/com.termux/files/usr/bin"


def operation_result(op: str, params: Dict[str, Any] | None = None) -> Dict[str, Any]:
    """Return the operation payload while preserving WS/helper errors."""
    response = helper_request({"cmd": "call", "op": op, "params": params or {}})
    if response.get("type") == "result" and isinstance(response.get("result"), dict):
        return response["result"]
    if isinstance(response, dict):
        return response
    return {"ok": False, "error": "invalid_helper_response"}


def termux_exec_result(command: str, arguments: List[str], timeout: int = 10000) -> Dict[str, Any]:
    return operation_result("termux.exec", {
        "commandPath": command,
        "arguments": arguments,
        "timeout": timeout,
    })


def adb_devices(stdout: str) -> List[Dict[str, str]]:
    devices: List[Dict[str, str]] = []
    for line in stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2:
            devices.append({"serial": fields[0], "state": fields[1]})
    return devices


def chrome_cdp_prepare(argv: List[str]) -> int:
    adb_serial = os.environ.get("ADB_SERIAL", "")
    package = "com.android.chrome"
    local_port = 9222
    remote_socket = "chrome_devtools_remote"
    ready_timeout_ms = 10000
    include_targets = False
    i = 0
    while i < len(argv):
        value = argv[i]
        if value == "--include-targets":
            include_targets = True
        elif value in ("--adb", "--package", "--port", "--socket", "--timeout"):
            i += 1
            if i >= len(argv):
                raise SystemExit(f"{value} requires a value")
            if value == "--adb": adb_serial = argv[i]
            elif value == "--package": package = argv[i]
            elif value == "--port": local_port = int(argv[i])
            elif value == "--socket": remote_socket = argv[i]
            else: ready_timeout_ms = int(argv[i])
        else:
            raise SystemExit(f"unknown chrome cdp prepare option: {value}")
        i += 1
    if not 1024 <= local_port <= 65535:
        raise SystemExit("--port must be between 1024 and 65535")
    if not 1000 <= ready_timeout_ms <= 120000:
        raise SystemExit("--timeout must be between 1000 and 120000 milliseconds")
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", package):
        raise SystemExit("--package contains invalid characters")
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", remote_socket):
        raise SystemExit("--socket contains invalid characters")

    launch = operation_result("app.launch", {"package": package})
    if not launch.get("ok") or launch.get("foreground") is False:
        return print_json({
            "ok": False,
            "stage": "launch",
            "error": "chrome_launch_failed",
            "launch": launch,
        }) or 1

    adb_path = f"{TERMUX_PREFIX}/adb"
    listed = termux_exec_result(adb_path, ["devices", "-l"])
    if not listed.get("ok") or listed.get("exitCode") != 0:
        return print_json({"ok": False, "stage": "adb_devices", "result": listed}) or 1
    devices = adb_devices(str(listed.get("stdout", "")))
    ready = [device["serial"] for device in devices if device["state"] == "device"]
    if adb_serial:
        if adb_serial not in ready:
            connected = termux_exec_result(adb_path, ["connect", adb_serial])
            if not connected.get("ok") or connected.get("exitCode") != 0:
                return print_json({"ok": False, "stage": "adb_connect", "serial": adb_serial,
                                   "result": connected}) or 1
    elif len(ready) == 1:
        adb_serial = ready[0]
    else:
        return print_json({
            "ok": False,
            "stage": "adb_select",
            "error": "adb_device_unavailable" if not ready else "adb_device_ambiguous",
            "devices": devices,
            "hint": "Pass --adb SERIAL when more than one device is connected.",
        }) or 1

    forwarded = termux_exec_result(adb_path, [
        "-s", adb_serial, "forward", f"tcp:{local_port}", f"localabstract:{remote_socket}",
    ])
    if not forwarded.get("ok") or forwarded.get("exitCode") != 0:
        return print_json({"ok": False, "stage": "adb_forward", "serial": adb_serial,
                           "result": forwarded}) or 1

    curl_path = f"{TERMUX_PREFIX}/curl"
    base = f"http://127.0.0.1:{local_port}"
    deadline = time.monotonic() + ready_timeout_ms / 1000.0
    version_result: Dict[str, Any] = {}
    version: Dict[str, Any] = {}
    while time.monotonic() < deadline:
        version_result = termux_exec_result(curl_path, [
            "--fail", "--silent", "--show-error", "--max-time", "2", f"{base}/json/version",
        ], timeout=5000)
        if version_result.get("ok") and version_result.get("exitCode") == 0:
            try:
                parsed = json.loads(str(version_result.get("stdout", "")))
                if isinstance(parsed, dict):
                    version = parsed
                    break
            except json.JSONDecodeError:
                pass
        time.sleep(0.25)
    if not version:
        return print_json({"ok": False, "stage": "cdp_probe", "serial": adb_serial,
                           "endpoint": base, "result": version_result}) or 1

    targets_result = termux_exec_result(curl_path, [
        "--fail", "--silent", "--show-error", "--max-time", "2", f"{base}/json/list",
    ], timeout=5000)
    targets: List[Dict[str, Any]] = []
    if targets_result.get("ok") and targets_result.get("exitCode") == 0:
        try:
            parsed_targets = json.loads(str(targets_result.get("stdout", "")))
            if isinstance(parsed_targets, list):
                targets = parsed_targets
        except json.JSONDecodeError:
            pass

    result = {
        "ok": True,
        "package": package,
        "foreground": launch.get("foreground", True),
        "adbSerial": adb_serial,
        "localEndpoint": base,
        "devtoolsSocket": remote_socket,
        "browser": version.get("Browser", ""),
        "protocolVersion": version.get("Protocol-Version", ""),
        "browserWebSocketDebuggerUrl": version.get("webSocketDebuggerUrl", ""),
        "targetCount": len(targets),
    }
    if include_targets:
        result["targets"] = targets
    return print_json(result)


def chrome_cmd(argv: List[str]) -> int:
    if len(argv) >= 2 and argv[0] == "cdp" and argv[1] == "prepare":
        return chrome_cdp_prepare(argv[2:])
    usage(); return 2


def cache_request_options(argv: List[str]) -> tuple[Dict[str, Any], List[str]]:
    request: Dict[str, Any] = {"fresh": bool(os.environ.get("HENYO_FRESH"))}
    env_max_age = os.environ.get("HENYO_MAX_AGE_MS")
    if env_max_age is not None:
        request["maxAgeMs"] = int(env_max_age)
    rest: List[str] = []
    i = 0
    while i < len(argv):
        if argv[i] == "--fresh":
            request["fresh"] = True
        elif argv[i] == "--max-age":
            i += 1
            if i >= len(argv):
                raise SystemExit("--max-age requires milliseconds")
            request["maxAgeMs"] = int(argv[i])
        else:
            rest.append(argv[i])
        i += 1
    return request, rest


def current(argv: List[str] | None = None, display: Dict[str, str] | None = None) -> int:
    request, rest = cache_request_options(argv or [])
    if rest:
        raise SystemExit(f"unknown current option: {rest[0]}")
    request["cmd"] = "current"
    response = helper_request(add_display(request, display))
    if isinstance(response.get("result"), dict):
        return print_json(response["result"])
    return print_json(response)


def tree(argv: List[str] | None = None, display: Dict[str, str] | None = None) -> int:
    request, rest = cache_request_options(argv or [])
    rest, target = target_options(rest)
    max_depth = 8
    if rest:
        if len(rest) != 1:
            raise SystemExit(f"unknown tree option: {rest[1]}")
        max_depth = int(rest[0])
    request.update({
        "cmd": "tree",
        "params": {"maxDepth": max_depth, **target},
    })
    response = helper_request(add_display(request, display))
    if response.get("cached") and isinstance(response.get("tree"), dict):
        tree_event = response["tree"]
        if isinstance(tree_event.get("root"), dict):
            output = {"ok": True, "root": tree_event["root"],
                      "truncated": tree_event.get("truncated", False)}
            if isinstance(tree_event.get("target"), dict):
                output["target"] = tree_event["target"]
            return print_json(output)
        if isinstance(tree_event.get("payload"), dict):
            return print_json(tree_event["payload"])
    result = response.get("result")
    if isinstance(result, dict):
        if isinstance(result.get("result"), dict):
            return print_json(result["result"])
        return print_json(result)
    return print_json(response)


def observe(argv: List[str], display: Dict[str, str] | None = None) -> int:
    argv, target = target_options(argv)
    params: Dict[str, Any] = dict(target)
    i = 0
    while i < len(argv):
        option = argv[i]
        if option in ("--max-depth", "--max-attempts", "--timeout"):
            i += 1
            if i >= len(argv):
                raise SystemExit(f"{option} requires an integer")
            key = {
                "--max-depth": "maxDepth",
                "--max-attempts": "maxAttempts",
                "--timeout": "timeout",
            }[option]
            params[key] = int(argv[i])
        elif option == "--capture-mode":
            i += 1
            if i >= len(argv):
                raise SystemExit("--capture-mode requires auto, window, or display")
            if argv[i] not in ("auto", "window", "display"):
                raise SystemExit("--capture-mode requires auto, window, or display")
            params["captureMode"] = argv[i]
        elif option == "--include-indicator":
            params["includeIndicator"] = True
        else:
            raise SystemExit(f"unknown observe option: {option}")
        i += 1
    response = helper_request(add_display({"cmd": "observe", "params": params}, display))
    # The diagnostic CLI deliberately omits the UI tree, current-app fields,
    # and base64 screenshot. Those payloads remain available only to IPC users.
    frame = response.get("result") if response.get("error") == "unstable_observation" else response
    operation = frame.get("result") if isinstance(frame, dict) and isinstance(frame.get("result"), dict) else {}
    observation = operation.get("observation") if isinstance(operation.get("observation"), dict) else {}
    tree_result = operation.get("tree") if isinstance(operation.get("tree"), dict) else {}
    screenshot_result = operation.get("screenshot") if isinstance(operation.get("screenshot"), dict) else {}
    error = response.get("error")
    if not error and isinstance(frame, dict):
        error = frame.get("error") or frame.get("code")
    code = response.get("code")
    message = response.get("message")
    if isinstance(frame, dict):
        code = code or frame.get("code")
        message = message or frame.get("message")
    summary = {
        "ok": bool(response.get("ok")),
        "error": error,
        "code": code,
        "message": message,
        "observation": observation,
        "tree": {
            key: tree_result[key]
            for key in (
                "treeVersion", "treeDigest", "capturedAt", "serviceEpoch",
                "captureBeginEventSeq", "captureEndEventSeq",
                "captureBeginElapsedRealtimeMs", "captureEndElapsedRealtimeMs", "truncated",
            )
            if key in tree_result
        },
        "screenshot": {
            key: screenshot_result[key]
            for key in (
                "contentType", "encoding", "byteLength", "captureTimestampElapsedRealtimeMs",
                "captureBeginElapsedRealtimeMs", "captureEndElapsedRealtimeMs", "coordinates",
            )
            if key in screenshot_result
        },
    }
    if isinstance(tree_result.get("target"), dict):
        summary["target"] = tree_result["target"]
    return print_json(summary)


def batch(path: str, display: Dict[str, str] | None = None) -> int:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    timeout_ms = None
    if isinstance(payload, list):
        steps = payload
        stop = True
        return_tree = True
    else:
        steps = payload.get("steps", [])
        stop = bool(payload.get("stopOnError", True))
        return_tree = bool(payload.get("returnTree", True))
        try:
            timeout_ms = helper.validate_batch_timeout_ms(payload.get("timeoutMs"))
        except ValueError as exc:
            raise SystemExit(str(exc)) from exc
        if display is None and isinstance(payload.get("display"), dict):
            display = payload["display"]
    request = {"cmd": "batch", "steps": steps, "stopOnError": stop, "returnTree": return_tree}
    if timeout_ms is not None:
        request["timeoutMs"] = timeout_ms
    return print_json(helper_request(add_display(request, display)))


def apps(argv: List[str], display: Dict[str, str] | None = None) -> int:
    all_apps = False
    for arg in argv:
        if arg == "--all":
            all_apps = True
        else:
            usage()
            return 2
    return control_call("app.list", {"all": all_apps}, display)


def open_uri(argv: List[str], display: Dict[str, str] | None = None) -> int:
    uri: str | None = None
    package: str | None = None
    i = 0
    while i < len(argv):
        value = argv[i]
        if value == "--package":
            i += 1
            if i >= len(argv):
                raise SystemExit("--package requires a package name")
            package = argv[i]
        elif value.startswith("--"):
            raise SystemExit(f"unknown open-uri option: {value}")
        elif uri is None:
            uri = value
        else:
            raise SystemExit("open-uri requires exactly one URI")
        i += 1
    if uri is None:
        raise SystemExit("open-uri requires a URI")
    params = {"uri": uri}
    if package is not None:
        params["package"] = package
    return control_call("app.openUri", params, display)


def helper_cmd(argv: List[str]) -> int:
    sub = argv[0] if argv else "status"
    socket_path = helper.default_socket_path()
    if sub == "start":
        return print_json(helper.start_background(socket_path))
    if sub == "status":
        try:
            return print_json(helper.request_helper({"cmd": "status"}, socket_path))
        except OSError as exc:
            return print_json({"ok": False, "error": "helper_unavailable", "message": str(exc)})
    if sub == "stop":
        try:
            return print_json(helper.request_helper({"cmd": "stop"}, socket_path))
        except OSError as exc:
            return print_json({"ok": False, "error": "helper_unavailable", "message": str(exc)})
    if sub == "logs":
        return print_json({"ok": True, "path": str(helper.default_log_path())})
    if sub == "reload-auth":
        try:
            return print_json(helper.request_helper({"cmd": "auth.reload"}, socket_path))
        except OSError as exc:
            return print_json({"ok": False, "error": "helper_unavailable", "message": str(exc)})
    usage()
    return 2


def save_token(raw: str) -> Path:
    path = helper.config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    tmp = path.with_name(path.name + f".tmp.{os.getpid()}")
    old = os.umask(0o077)
    try:
        tmp.write_text(f"token={raw}\n", encoding="utf-8")
        os.chmod(tmp, 0o600)
        tmp.replace(path)
    finally:
        os.umask(old)
    return path


def reload_helper_auth_if_running() -> None:
    try:
        helper.request_helper({"cmd": "auth.reload"}, helper.default_socket_path())
    except OSError:
        pass


def active_pairing_id() -> str:
    status = json.loads(http_request("GET", "/v1/remote/pairing").decode())
    pairing_id = status.get("pairingId")
    if status.get("ok") and status.get("active") and isinstance(pairing_id, str) and pairing_id:
        return pairing_id
    raise SystemExit(dumps({
        "ok": False,
        "error": "pairing_required",
        "message": "start pairing on the Android device and pass the visible 6-digit PIN",
        "status": status,
    }))


def auth_cmd(argv: List[str]) -> int:
    sub = argv[0] if argv else ""
    rest = argv[1:]
    if sub == "tokens":
        return print_json(json.loads(http_request("GET", "/v1/auth/tokens").decode()))
    if sub == "revoke":
        if not rest:
            raise SystemExit("token id required")
        return print_json(json.loads(http_request("DELETE", "/v1/auth/tokens/" + quote(rest[0], safe="")).decode()))
    if sub == "save-token":
        if not rest:
            raise SystemExit("token required")
        path = save_token(rest[0])
        reload_helper_auth_if_running()
        return print_json({"ok": True, "savedTokenPath": str(path)})
    if sub == "register":
        name = pairing_id = pin = ""
        save = False
        i = 0
        while i < len(rest):
            if rest[i] == "--name":
                i += 1; name = rest[i]
            elif rest[i] == "--pairing-id":
                i += 1; pairing_id = rest[i]
            elif rest[i] == "--pin":
                i += 1; pin = rest[i]
            elif rest[i] == "--save":
                save = True
            else:
                raise SystemExit(f"unknown auth register option: {rest[i]}")
            i += 1
        if not pairing_id:
            pairing_id = active_pairing_id()
        body = {"clientName": name, "pairingId": pairing_id, "pin": pin}
        response = json.loads(http_request("POST", "/v1/remote/pairing/register", body).decode())
        if save and response.get("ok") and response.get("token"):
            path = save_token(response["token"])
            reload_helper_auth_if_running()
            response["token"] = "<saved>"
            response["savedTokenPath"] = str(path)
        return print_json(response)
    usage()
    return 2


def cleanup_screenshots(directory: Path, prefix: str) -> None:
    pattern = re.compile(r"^" + re.escape(prefix) + r"-\d{14}Z-delete-after-(\d{14})Z\.png$")
    now = dt.datetime.now(dt.timezone.utc)
    for child in directory.iterdir():
        match = pattern.match(child.name)
        if not match:
            continue
        delete_after = dt.datetime.strptime(match.group(1), "%Y%m%d%H%M%S").replace(tzinfo=dt.timezone.utc)
        if delete_after <= now:
            try:
                child.unlink()
            except FileNotFoundError:
                pass


def screenshot_payload_via_helper(
    timeout: str,
    display: Dict[str, str] | None = None,
    target: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    if os.environ.get("HENYO_NO_HELPER"):
        raise RuntimeError("helper_disabled")
    params: Dict[str, Any] = {"timeout": int(timeout)}
    if target:
        params.update(target)
    response = helper_request(add_display(
        {"cmd": "call", "op": "screen.screenshot", "params": params},
        display,
    ))
    if response.get("error") == "helper_target_mismatch":
        raise HelperTargetMismatchError(response)
    result = response.get("result") if isinstance(response, dict) else None
    if not isinstance(result, dict) or not result.get("ok"):
        raise HelperCallError(response)
    if result.get("contentType") != "image/png" or result.get("encoding") != "base64":
        raise RuntimeError("unsupported_screenshot_payload")
    return result


def screenshot_via_helper(timeout: str, display: Dict[str, str] | None = None) -> bytes:
    result = screenshot_payload_via_helper(timeout, display)
    return base64.b64decode(result.get("data", ""), validate=True)


def screenshot(
    argv: List[str],
    prefer_v1: bool = True,
    display: Dict[str, str] | None = None,
) -> int:
    ttl = int(os.environ.get("HENYO_SCREENSHOT_TTL_SECONDS", "86400"))
    prefix = os.environ.get("HENYO_SCREENSHOT_PREFIX", "henyo-screenshot")
    timeout = os.environ.get("HENYO_SCREENSHOT_TIMEOUT_MS", "5000")
    json_output = False
    target: Dict[str, Any] = {}
    i = 0
    while i < len(argv):
        if argv[i] == "--ttl":
            i += 1; ttl = int(argv[i])
        elif argv[i] == "--prefix":
            i += 1; prefix = argv[i]
        elif argv[i] == "--timeout":
            i += 1; timeout = argv[i]
        elif argv[i] == "--json":
            json_output = True
        elif argv[i] == "--capture-mode":
            i += 1
            if i >= len(argv) or argv[i] not in ("auto", "window", "display"):
                raise SystemExit("--capture-mode requires auto, window, or display")
            target["captureMode"] = argv[i]
        elif argv[i] == "--include-indicator":
            target["includeIndicator"] = True
        elif argv[i] in ("--package", "--window-id", "--display-id"):
            option = argv[i]
            i += 1
            if i >= len(argv):
                raise SystemExit(f"{option} requires a value")
            key = {"--package": "package", "--window-id": "windowId",
                   "--display-id": "displayId"}[option]
            target[key] = argv[i] if option == "--package" else int(argv[i])
        else:
            usage(); return 2
        i += 1
    directory = Path(os.environ.get("TMPDIR", tempfile.gettempdir())) / "henyo" / "screens"
    directory.mkdir(parents=True, exist_ok=True)
    cleanup_screenshots(directory, prefix)
    created = dt.datetime.now(dt.timezone.utc)
    delete_after = created + dt.timedelta(seconds=ttl)
    path = directory / f"{prefix}-{created:%Y%m%d%H%M%S}Z-delete-after-{delete_after:%Y%m%d%H%M%S}Z.png"
    tmp = path.with_suffix(path.suffix + f".part.{os.getpid()}")
    coordinates: Dict[str, Any] | None = None
    try:
        result = screenshot_payload_via_helper(timeout, display, target)
        tmp.write_bytes(base64.b64decode(result.get("data", ""), validate=True))
        if isinstance(result.get("coordinates"), dict):
            coordinates = result["coordinates"]
    except HelperTargetMismatchError as exc:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        return print_json(exc.response)
    except HelperCallError as exc:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        return print_json(exc.response)
    except Exception:
        if target:
            try:
                tmp.unlink()
            except FileNotFoundError:
                pass
            return print_json({"ok": False, "error": "target_capture_failed"})
        try:
            tmp.write_bytes(http_request("GET", f"/v1/screen/screenshot?timeout={quote(str(timeout))}", raw=True))
        except Exception:
            serial = os.environ.get("ADB_SERIAL", "127.0.0.1:5555")
            with tmp.open("wb") as fh:
                subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=fh, check=True)
    tmp.replace(path)
    if json_output:
        return print_json({"ok": True, "path": str(path), "coordinates": coordinates})
    print(path)
    return 0


def v1_cmd(argv: List[str], display: Dict[str, str] | None = None) -> int:
    if not argv:
        usage(); return 2
    sub, rest = argv[0], argv[1:]
    rest, parsed_display = intent_options(rest)
    if parsed_display is not None:
        display = parsed_display
    if sub == "health":
        return print_json(json.loads(http_request("GET", "/v1/health").decode()))
    if sub == "tree":
        return tree(rest, display)
    if sub == "current":
        return current(rest, display)
    if sub == "observe":
        return observe(rest, display)
    if sub == "find":
        return control_call("ui.find", selector_call_params(rest[0], rest[1:]), display)
    if sub == "click":
        return control_call("ui.click", selector_call_params(rest[0], rest[1:]), display)
    if sub == "click-point":
        options, target = target_options(rest[2:])
        if options: raise SystemExit(f"unknown click-point option: {options[0]}")
        return control_call("ui.click", {"x": int(rest[0]), "y": int(rest[1]), **target}, display)
    if sub == "click-bounds":
        options, target = target_options(rest[4:])
        if options: raise SystemExit(f"unknown click-bounds option: {options[0]}")
        return control_call("ui.click", {"bounds": ",".join(rest[:4]), **target}, display)
    if sub == "set":
        params = selector_call_params(rest[0], rest[2:])
        params["value"] = rest[1]
        return control_call("ui.setText", params, display)
    if sub == "tap":
        return control_call("ui.tap", coordinate_gesture_params("tap", rest), display)
    if sub == "swipe":
        return control_call("ui.swipe", coordinate_gesture_params("swipe", rest), display)
    if sub == "scroll":
        options, target = target_options(rest)
        if len(options) > 1: raise SystemExit(f"unknown scroll option: {options[1]}")
        return control_call("ui.scroll", {"direction": options[0] if options else "down", **target}, display)
    if sub == "scroll-until":
        options, target = target_options(rest[1:])
        attempts = 8
        if options:
            if len(options) != 2 or options[0] != "--attempts":
                raise SystemExit(f"unknown scroll-until option: {options[0]}")
            attempts = int(options[1])
        return control_call("ui.scrollUntil", {"text": rest[0], "attempts": attempts, **target}, display)
    if sub == "wait":
        timeout = 5000; interval = 100; opts = []
        text = rest[0]; wait_args, target = target_options(rest[1:]); i = 0
        while i < len(wait_args):
            if wait_args[i] == "--timeout":
                i += 1; timeout = int(wait_args[i])
            elif wait_args[i] == "--interval":
                i += 1; interval = int(wait_args[i])
            else:
                opts.append(wait_args[i])
            i += 1
        return control_call("ui.wait", {"selector": selector(text, opts), "timeout": timeout, "interval": interval, **target}, display)
    if sub == "launch":
        return control_call("app.launch", {"package": rest[0]}, display)
    if sub == "start":
        return control_call("app.start", {"component": rest[0]}, display)
    if sub == "back":
        return control_call("global.back", display=display)
    if sub == "home":
        return control_call("global.home", display=display)
    if sub == "screenshot":
        return screenshot(rest, display=display)
    usage(); return 2


def version_cmd() -> int:
    response = helper_request({"cmd": "session.status"})
    if not response.get("ok"):
        return print_json(response)
    application = response.get("application") if isinstance(response.get("application"), dict) else None
    protocol = {"version": response.get("protocolVersion")}
    if isinstance(response.get("contractRevision"), str):
        protocol["contractRevision"] = response["contractRevision"]
    return print_json({
        "ok": True,
        "application": application,
        "protocol": protocol,
        "platform": response.get("platform") if isinstance(response.get("platform"), dict) else None,
        "capabilities": response.get("capabilities") if isinstance(response.get("capabilities"), dict) else None,
    })


def usage() -> None:
    print("""Usage:
  henyo helper start|stop|status|logs
  henyo helper reload-auth
  henyo cache tree|clear
  henyo progress set --goal TEXT [--step STATUS TEXT ...] [--replan]
  henyo progress set [--goal TEXT] [--completed TEXT ...] [--current TEXT]
  henyo progress finish
  henyo completion show TEXT
  henyo batch FILE [--intent TEXT]
  henyo auth register --name NAME --pin PIN [--pairing-id ID] [--save]
  henyo auth tokens
  henyo auth revoke TOKEN_ID
  henyo termux exec [--workdir PATH] [--stdin TEXT] [--timeout MS] -- COMMAND [ARG ...]
  henyo chrome cdp prepare [--adb SERIAL] [--port PORT] [--package PACKAGE] [--socket NAME] [--timeout MS] [--include-targets]
  henyo apps [--all]
  henyo open-uri URI [--package PACKAGE] [--intent TEXT]
  henyo version
  henyo health
  henyo v1 health|tree|observe|find|click|current|back|home|screenshot ...
  henyo tree [DEPTH] [--fresh] [--max-age MS] [--package PACKAGE] [--window-id ID] [--display-id ID]
  henyo current [--fresh] [--max-age MS]
  henyo observe [--max-depth N] [--max-attempts N] [--timeout MS] [--intent TEXT]
  henyo tap X Y [--coordinate-space screen|screenshot] [--capture-id ID] [--intent TEXT]
  henyo swipe X1 Y1 X2 Y2 [DURATION] [--coordinate-space screen|screenshot] [--capture-id ID] [--intent TEXT]
  henyo screenshot [--ttl SECONDS] [--prefix NAME] [--timeout MS] [--json] [--capture-mode auto|window|display] [--intent TEXT]
  henyo find|click|wait|set|scroll|scroll-until|launch|start|back|home ... [--intent TEXT]
""")


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        usage(); return 2
    cmd, rest = argv[0], argv[1:]
    display = None
    if cmd in {
        "apps", "tree", "current", "observe", "find", "click", "wait", "wait-gone",
        "set", "tap", "swipe", "scroll", "scroll-until", "launch", "start", "back",
        "home", "screenshot", "batch", "open-uri",
    }:
        rest, display = intent_options(rest)
    if cmd == "helper":
        return helper_cmd(rest)
    if cmd == "cache":
        if rest and rest[0] == "tree":
            return tree(rest[1:])
        if rest and rest[0] == "clear":
            return print_json(helper_request({"cmd": "cache.clear"}))
        usage(); return 2
    if cmd == "completion":
        if len(rest) != 2 or rest[0] != "show":
            usage(); return 2
        return print_json(helper_request({"cmd": "completion.show", "message": rest[1]}))
    if cmd == "progress":
        if not rest:
            usage(); return 2
        if rest[0] == "finish" and len(rest) == 1:
            return print_json(helper_request({"cmd": "progress.finish"}))
        if rest[0] != "set":
            usage(); return 2
        goal = ""
        progress_current = ""
        completed: List[str] = []
        steps: List[Dict[str, str]] = []
        replan = False
        i = 1
        while i < len(rest):
            option = rest[i]
            if option == "--replan":
                replan = True
                i += 1
                continue
            if option == "--step":
                if i + 2 >= len(rest):
                    raise SystemExit("--step requires STATUS and TEXT")
                status = rest[i + 1]
                if status not in ("pending", "in_progress", "completed"):
                    raise SystemExit("--step status must be pending, in_progress, or completed")
                steps.append({"status": status, "text": rest[i + 2]})
                i += 3
                continue
            if option not in ("--goal", "--completed", "--current"):
                raise SystemExit(f"unknown progress option: {option}")
            i += 1
            if i >= len(rest):
                raise SystemExit(f"{option} requires text")
            if option == "--goal": goal = rest[i]
            elif option == "--current": progress_current = rest[i]
            else: completed.append(rest[i])
            i += 1
        if steps:
            if completed or progress_current:
                raise SystemExit("--step cannot be mixed with --completed or --current")
            if not goal.strip():
                raise SystemExit("structured progress requires --goal")
            return print_json(helper_request({
                "cmd": "progress.set", "goal": goal, "steps": steps, "replan": replan,
            }))
        if replan:
            raise SystemExit("--replan requires at least one --step")
        if not goal.strip() and not progress_current.strip() and not any(item.strip() for item in completed):
            raise SystemExit("progress set requires --goal, --completed, or --current")
        return print_json(helper_request({
            "cmd": "progress.set", "goal": goal,
            "completed": completed, "current": progress_current,
        }))
    if cmd == "batch":
        return batch(rest[0], display)
    if cmd == "auth":
        return auth_cmd(rest)
    if cmd == "termux":
        return termux_cmd(rest)
    if cmd == "chrome":
        return chrome_cmd(rest)
    if cmd == "v1":
        return v1_cmd(rest)
    if cmd == "version":
        if rest:
            raise SystemExit(f"unknown version option: {rest[0]}")
        return version_cmd()
    if cmd == "health":
        return print_json(json.loads(http_request("GET", "/v1/health").decode()))
    if cmd == "apps":
        return apps(rest, display)
    if cmd == "open-uri":
        return open_uri(rest, display)
    if cmd == "tree":
        return tree(rest, display)
    if cmd == "observe":
        return observe(rest, display)
    if cmd == "find":
        return control_call("ui.find", selector_call_params(rest[0], rest[1:]), display)
    if cmd == "click":
        return control_call("ui.click", selector_call_params(rest[0], rest[1:]), display)
    if cmd == "wait":
        return v1_cmd(["wait", *rest], display)
    if cmd == "wait-gone":
        wait_args, target = target_options(rest[1:])
        timeout = int(wait_args[1]) if len(wait_args) == 2 and wait_args[0] == "--timeout" else 5000
        if wait_args and not (len(wait_args) == 2 and wait_args[0] == "--timeout"):
            raise SystemExit(f"unknown wait-gone option: {wait_args[0]}")
        return control_call("ui.wait", {"selector": {"text": rest[0]}, "gone": True, "timeout": timeout, **target}, display)
    if cmd == "set":
        params = selector_call_params(rest[0], rest[2:])
        params["value"] = rest[1]
        return control_call("ui.setText", params, display)
    if cmd == "tap":
        return control_call("ui.tap", coordinate_gesture_params("tap", rest), display)
    if cmd == "swipe":
        return v1_cmd(["swipe", *rest], display)
    if cmd == "scroll":
        return v1_cmd(["scroll", *rest], display)
    if cmd == "scroll-until":
        return v1_cmd(["scroll-until", *rest], display)
    if cmd == "launch":
        return control_call("app.launch", {"package": rest[0]}, display)
    if cmd == "start":
        return control_call("app.start", {"component": rest[0]}, display)
    if cmd == "current":
        return current(rest, display)
    if cmd == "back":
        return control_call("global.back", display=display)
    if cmd == "home":
        return control_call("global.home", display=display)
    if cmd == "screenshot":
        return screenshot(rest, display=display)
    usage(); return 2


if __name__ == "__main__":
    raise SystemExit(main())
