#!/usr/bin/env python3
import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HENYO = ROOT / "bin" / "henyo"
SOCKET = ROOT / "build" / "verify-python-cli.sock"

sys.path.insert(0, str(ROOT / "python"))
from henyo.cli import coordinate_gesture_params  # noqa: E402


def run(*args):
    env = os.environ.copy()
    env["HENYO_HELPER_SOCKET"] = str(SOCKET)
    proc = subprocess.run(
        [str(HENYO), *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout)
    return proc.stdout.strip()


def json_cmd(*args):
    out = run(*args)
    if not out:
        raise RuntimeError(f"empty output from {args}")
    return json.loads(out)


def verify_coordinate_gesture_parsing():
    assert coordinate_gesture_params("tap", ["12", "34"]) == {"x": 12, "y": 34}
    assert coordinate_gesture_params("swipe", ["1", "2", "3", "4"]) == {
        "x1": 1, "y1": 2, "x2": 3, "y2": 4, "duration": 300,
    }
    assert coordinate_gesture_params("tap", [
        "12", "34", "--coordinate-space", "screenshot", "--capture-id", "capture-1",
    ]) == {
        "x": 12, "y": 34, "coordinateSpace": "screenshot", "captureId": "capture-1",
    }
    assert coordinate_gesture_params("swipe", [
        "1", "2", "3", "4", "450", "--coordinate-space", "screenshot",
        "--capture-id", "capture-2",
    ]) == {
        "x1": 1, "y1": 2, "x2": 3, "y2": 4, "duration": 450,
        "coordinateSpace": "screenshot", "captureId": "capture-2",
    }
    for args in (
        ["1", "2", "--coordinate-space", "screenshot"],
        ["1", "2", "--capture-id", "capture-1"],
        ["1", "2", "--coordinate-space", "bitmap", "--capture-id", "capture-1"],
    ):
        try:
            coordinate_gesture_params("tap", args)
        except SystemExit:
            pass
        else:
            raise AssertionError(f"invalid coordinate options accepted: {args}")


def main():
    verify_coordinate_gesture_parsing()
    try:
        SOCKET.unlink()
    except FileNotFoundError:
        pass
    json_cmd("helper", "stop") if SOCKET.exists() else None
    # Health uses the HTTP compatibility path. Check it before the helper owns
    # the service's single long-lived WebSocket control session.
    assert json_cmd("health")["ok"]
    start = json_cmd("helper", "start")
    assert start["ok"], start
    assert json_cmd("helper", "status")["ok"]
    assert json_cmd("current")["ok"]
    assert json_cmd("v1", "current")["ok"]
    apps = json_cmd("apps")
    assert apps["ok"] and isinstance(apps["apps"], list) and apps["apps"], apps
    assert all(app.get("launchable") is True for app in apps["apps"]), apps["apps"][:5]
    apps_all = json_cmd("apps", "--all")
    assert apps_all["ok"] and isinstance(apps_all["apps"], list), apps_all
    assert len(apps_all["apps"]) >= len(apps["apps"]), (len(apps_all["apps"]), len(apps["apps"]))
    assert any(app.get("package") == "link.liaru.henyo" for app in apps_all["apps"]), apps_all["apps"][:10]
    tree = json_cmd("tree", "1")
    assert tree["ok"] and "root" in tree, tree
    screenshot = json_cmd(
        "screenshot", "--json", "--ttl", "60", "--prefix", "henyo-cli-verify",
    )
    assert screenshot["ok"] is True and Path(screenshot["path"]).is_file(), screenshot
    coordinates = screenshot.get("coordinates")
    assert isinstance(coordinates, dict) and coordinates.get("captureId"), screenshot
    assert coordinates.get("mappingCertain") is True, coordinates
    assert "data" not in screenshot and "encoding" not in screenshot, screenshot
    rejected = json_cmd(
        "tap", str(coordinates["imageWidth"]), "0",
        "--coordinate-space", "screenshot", "--capture-id", coordinates["captureId"],
    )
    assert rejected.get("ok") is False, rejected
    assert rejected.get("code") == "screenshot_coordinate_out_of_bounds", rejected
    Path(screenshot["path"]).unlink()
    assert json_cmd("back")["ok"]
    assert json_cmd("cache", "clear")["ok"]
    assert json_cmd("helper", "stop")["ok"]
    print("python CLI verifier passed")


if __name__ == "__main__":
    main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
