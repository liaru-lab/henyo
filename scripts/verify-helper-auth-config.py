#!/usr/bin/env python3
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HENYO = ROOT / "bin" / "henyo"


def run(*args, env=None, check=True):
    proc = subprocess.run(
        [str(HENYO), *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env or os.environ.copy(),
    )
    if check and proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout)
    return proc


def json_cmd(*args, env=None, check=True):
    proc = run(*args, env=env, check=check)
    out = proc.stdout.strip()
    if not out:
        raise RuntimeError(f"empty output from {args}")
    return json.loads(out), proc


def mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def main():
    with tempfile.TemporaryDirectory(prefix="henyo-auth-config-") as tmpdir:
        tmp = Path(tmpdir)
        config = tmp / "config" / "henyo" / "config"
        socket_path = tmp / "helper.sock"
        env = os.environ.copy()
        env["HENYO_CONFIG"] = str(config)
        env["HENYO_HELPER_SOCKET"] = str(socket_path)
        env["HENYO_HELPER_DISCOVERY"] = str(tmp / "helper.json")
        env["HENYO_HELPER_LOG"] = str(tmp / "helper.log")
        env["HENYO_HELPER_PID"] = str(tmp / "helper.pid")

        saved, _ = json_cmd("auth", "save-token", "config-token", env=env)
        assert saved["ok"] and saved["savedTokenPath"] == str(config), saved
        assert mode(config.parent) == 0o700, oct(mode(config.parent))
        assert mode(config) == 0o600, oct(mode(config))
        assert config.read_text(encoding="utf-8") == "token=config-token\n"

        env_override = env.copy()
        env_override["HENYO_TOKEN"] = "env-token"
        # A local request succeeds either way, but this exercises token lookup
        # without exposing the token in process output.
        health, proc = json_cmd("health", env=env_override)
        assert health["ok"], health
        assert "env-token" not in proc.stdout and "config-token" not in proc.stdout

        register, proc = json_cmd(
            "auth",
            "register",
            "--name",
            "verifier",
            "--pairing-id",
            "invalid",
            "--pin",
            "000000",
            env=env,
            check=True,
        )
        assert register.get("ok") is False, register
        assert register.get("code") == "source_not_allowed", register
        assert "config-token" not in proc.stdout

        start, _ = json_cmd("helper", "start", env=env)
        assert start["ok"], start
        saved2, _ = json_cmd("auth", "save-token", "new-config-token", env=env)
        assert saved2["ok"], saved2
        reloaded, _ = json_cmd("helper", "reload-auth", env=env)
        assert reloaded["ok"] and reloaded["tokenConfigured"] is True, reloaded
        stopped, _ = json_cmd("helper", "stop", env=env)
        assert stopped["ok"], stopped

    print("helper auth/config verifier passed")


if __name__ == "__main__":
    main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
