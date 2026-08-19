#!/usr/bin/env python3
import json
import os
import socket
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "python" / "henyo" / "helper.py"


def run_helper(*args, env, check=True):
    full_env = env.copy()
    full_env["PYTHONPATH"] = str(ROOT / "python")
    proc = subprocess.run(
        [sys.executable, str(HELPER), *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=full_env,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout)
    return proc


def helper_json(*args, env, check=True):
    proc = run_helper(*args, env=env, check=check)
    out = proc.stdout.strip()
    if not out:
        raise RuntimeError(f"empty helper response from {args}: {proc.stderr}")
    return json.loads(out), proc


def read_discovery(env):
    return json.loads(Path(env["HENYO_HELPER_DISCOVERY"]).read_text(encoding="utf-8"))


def redacted(value):
    if isinstance(value, dict):
        return {key: ("<redacted>" if key == "token" else redacted(item)) for key, item in value.items()}
    if isinstance(value, list):
        return [redacted(item) for item in value]
    return value


def raw_tcp_request(discovery, payload):
    with socket.create_connection((discovery["host"], int(discovery["port"])), timeout=2) as sock:
        sock.settimeout(2)
        sock.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))
        raw = b""
        while not raw.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            raw += chunk
    if not raw:
        raise RuntimeError("empty raw TCP helper response")
    return json.loads(raw.decode("utf-8"))


def unused_loopback_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def wait_missing(path: Path, timeout=3):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not path.exists():
            return
        time.sleep(0.05)
    raise AssertionError(f"{path} still exists")


def tcp_env(tmp: Path):
    return {
        **os.environ.copy(),
        "HENYO_HELPER_TRANSPORT": "tcp",
        "HENYO_HELPER_HOST": "127.0.0.1",
        "HENYO_HELPER_PORT": "0",
        "HENYO_HELPER_DISCOVERY": str(tmp / "helper.json"),
        "HENYO_HELPER_SOCKET": str(tmp / "helper.sock"),
        "HENYO_HELPER_LOG": str(tmp / "helper.log"),
        "HENYO_HELPER_PID": str(tmp / "helper.pid"),
    }


def verify_dynamic_port_and_auth(env):
    started, _ = helper_json("start", env=env)
    assert started["ok"], started
    assert started["transport"] == "tcp", started
    assert started["host"] == "127.0.0.1", started
    assert int(started["port"]) > 0, started

    discovery_path = Path(env["HENYO_HELPER_DISCOVERY"])
    discovery = read_discovery(env)
    assert discovery["transport"] == "tcp", redacted(discovery)
    assert discovery["host"] == started["host"], redacted(discovery)
    assert discovery["port"] == started["port"], redacted(discovery)
    assert isinstance(discovery.get("token"), str) and discovery["token"], redacted(discovery)
    if os.name != "nt":
        assert mode(discovery_path.parent) == 0o700, oct(mode(discovery_path.parent))
        assert mode(discovery_path) == 0o600, oct(mode(discovery_path))

    status, _ = helper_json("status", env=env)
    assert status["ok"] and status["port"] == started["port"], status

    auto_token, _ = helper_json("request", json.dumps({"cmd": "status"}), env=env)
    assert auto_token["ok"] and auto_token["transport"] == "tcp", auto_token

    missing = raw_tcp_request(discovery, {"cmd": "status"})
    assert missing == {"ok": False, "error": "helper_auth_failed"}, missing
    wrong = raw_tcp_request(discovery, {"cmd": "status", "token": "wrong-token"})
    assert wrong == {"ok": False, "error": "helper_auth_failed"}, wrong
    correct = raw_tcp_request(discovery, {"cmd": "status", "token": discovery["token"]})
    assert correct["ok"] and correct["port"] == started["port"], correct

    stopped, _ = helper_json("stop", env=env)
    assert stopped["ok"], stopped
    wait_missing(discovery_path)


def verify_stale_discovery_recovery(env):
    stale_port = unused_loopback_port()
    stale = {
        "transport": "tcp",
        "host": "127.0.0.1",
        "port": stale_port,
        "token": "stale-token",
        "pid": 999999,
        "startedAt": 1,
    }
    discovery_path = Path(env["HENYO_HELPER_DISCOVERY"])
    discovery_path.parent.mkdir(parents=True, exist_ok=True)
    discovery_path.write_text(json.dumps(stale), encoding="utf-8")

    started, _ = helper_json("start", env=env)
    assert started["ok"], started
    fresh = read_discovery(env)
    assert fresh["transport"] == "tcp", redacted(fresh)
    assert fresh["token"] != "stale-token", redacted(fresh)
    assert int(fresh["port"]) > 0, redacted(fresh)

    stopped, _ = helper_json("stop", env=env)
    assert stopped["ok"], stopped
    wait_missing(discovery_path)


def verify_host_validation(env):
    bad_env = env.copy()
    bad_env["HENYO_HELPER_HOST"] = "0.0.0.0"
    proc = run_helper("start", env=bad_env, check=False)
    assert proc.returncode != 0, proc.stdout
    assert "IPv4 loopback" in proc.stderr, proc.stderr


def verify_fixed_port_collision(env):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as blocker:
        blocker.bind(("127.0.0.1", 0))
        blocker.listen(1)
        blocked_port = int(blocker.getsockname()[1])
        collision_env = env.copy()
        collision_env["HENYO_HELPER_PORT"] = str(blocked_port)
        result, _ = helper_json("start", env=collision_env)
    assert result["ok"] is False, result
    assert result["error"] == "helper_start_failed", result
    log = Path(collision_env["HENYO_HELPER_LOG"]).read_text(encoding="utf-8", errors="replace")
    assert "Address already in use" in log or "Errno 98" in log or "Errno 48" in log, log


def main():
    with tempfile.TemporaryDirectory(prefix="henyo-helper-tcp-") as tmpdir:
        env = tcp_env(Path(tmpdir))
        verify_dynamic_port_and_auth(env)
        verify_stale_discovery_recovery(env)
        verify_host_validation(env)
        verify_fixed_port_collision(env)
    print("helper TCP verifier passed")


if __name__ == "__main__":
    main()
