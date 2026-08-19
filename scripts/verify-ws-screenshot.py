#!/usr/bin/env python3
import base64
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
from henyo.cli import helper_request

HENYO = ROOT / "bin" / "henyo"
SOCKET = ROOT / "build" / "verify-ws-screenshot.sock"


def run(*args, env=None):
    proc = subprocess.run(
        [str(HENYO), *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env or os.environ.copy(),
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout)
    return proc.stdout.strip()


def json_cmd(*args, env=None):
    out = run(*args, env=env)
    if not out:
        raise RuntimeError(f"empty output from {args}")
    return json.loads(out)


def assert_png(data: bytes) -> None:
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("screenshot is not a PNG")


def main():
    with tempfile.TemporaryDirectory(prefix="henyo-ws-shot-") as tmpdir:
        env = os.environ.copy()
        env["HENYO_HELPER_SOCKET"] = str(SOCKET)
        env["TMPDIR"] = tmpdir
        try:
            SOCKET.unlink()
        except FileNotFoundError:
            pass

        start = json_cmd("helper", "start", env=env)
        assert start["ok"], start

        direct = json_cmd(
            "batch",
            str(write_batch(Path(tmpdir) / "shot-batch.json")),
            env=env,
        )
        assert direct["ok"], direct
        batch_result = direct["result"]
        assert batch_result["ok"], batch_result
        shot_step = batch_result["steps"][0]
        assert shot_step["ok"], shot_step
        result = shot_step["result"]
        assert result["ok"] and result["contentType"] == "image/png", result
        assert_png(base64.b64decode(result["data"], validate=True))
        coordinates = result.get("coordinates")
        assert isinstance(coordinates, dict), result
        assert coordinates.get("captureId"), coordinates
        assert coordinates.get("coordinateSpace") == "screenshot", coordinates
        assert coordinates.get("imageWidth", 0) > 0 and coordinates.get("imageHeight", 0) > 0, coordinates
        assert coordinates.get("displayWidth", 0) > 0 and coordinates.get("displayHeight", 0) > 0, coordinates
        bounds = coordinates.get("captureBoundsInScreen")
        assert isinstance(bounds, dict) and bounds.get("right", 0) > bounds.get("left", 0), coordinates
        assert bounds.get("bottom", 0) > bounds.get("top", 0), coordinates
        assert coordinates.get("scaleX", 0) > 0 and coordinates.get("scaleY", 0) > 0, coordinates

        rejected = helper_request({
            "cmd": "call",
            "op": "ui.tap",
            "params": {
                "coordinateSpace": "screenshot",
                "captureId": coordinates["captureId"],
                "x": coordinates["imageWidth"],
                "y": 0,
            },
        })
        assert rejected.get("ok") is False, rejected
        assert rejected.get("code") == "screenshot_coordinate_out_of_bounds", rejected

        out = run("screenshot", "--ttl", "60", "--prefix", "henyo-ws-verify", env=env)
        path = Path(out)
        assert path.exists(), path
        assert "delete-after" in path.name, path.name
        assert_png(path.read_bytes())

        current = json_cmd("current", env=env)
        assert current["ok"], current

        stopped = json_cmd("helper", "stop", env=env)
        assert stopped["ok"], stopped

    print("WS screenshot verifier passed")


def write_batch(path: Path) -> Path:
    path.write_text(
        json.dumps({
            "steps": [
                {"op": "screen.screenshot", "params": {"timeout": 5000}},
                {"op": "app.current", "params": {}},
            ],
            "returnTree": False,
        }),
        encoding="utf-8",
    )
    return path


if __name__ == "__main__":
    main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
