#!/usr/bin/env python3
import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "python" / "henyo" / "helper.py"
SOCKET = ROOT / "build" / "verify-helper.sock"
DISCOVERY = ROOT / "build" / "verify-helper.json"
LOG = ROOT / "build" / "verify-helper.log"
PID = ROOT / "build" / "verify-helper.pid"

sys.path.insert(0, str(ROOT / "python"))
from henyo.helper import HelperDaemon  # noqa: E402


def run(*args, check=True):
    env = os.environ.copy()
    env["PYTHONPATH"] = str(ROOT / "python")
    env["HENYO_HELPER_DISCOVERY"] = str(DISCOVERY)
    env["HENYO_HELPER_LOG"] = str(LOG)
    env["HENYO_HELPER_PID"] = str(PID)
    proc = subprocess.run(
        [sys.executable, str(HELPER), *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout)
    return proc


def helper_json(*args):
    out = run(*args).stdout.strip()
    if not out:
        raise RuntimeError("empty helper response")
    return json.loads(out)


def request(payload):
    return helper_json("request", "--socket", str(SOCKET), json.dumps(payload, separators=(",", ":")))


def verify_pending_action_event_filter():
    daemon = HelperDaemon(SOCKET.with_name("verify-helper-unit.sock"))
    daemon.cache["current"] = {"ok": True, "package": "app.lawnchair"}
    daemon.cache["tree"] = {"event": "ui.tree", "currentApp": {"package": "app.lawnchair"}}

    daemon.mark_action_pending("com.android.settings")
    pending_action_id = daemon.cache["pendingActionId"]
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["pendingActionId"].startswith("helper-action-")
    assert daemon.cache["current"] is None
    assert daemon.cache["tree"] is None

    daemon.handle_event({
        "event": "ui.tree",
        "treeVersion": 1,
        "eventSeq": 11,
        "capturedAt": "2026-06-20T00:00:00Z",
        "actionId": "action-7",
        "reason": "after_action",
        "settled": False,
        "changed": True,
        "treeDigest": "digest-a",
        "currentApp": {"ok": True, "package": "app.lawnchair", "className": "android.widget.FrameLayout"},
        "root": {"text": "launcher"},
    })
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["current"] is None
    assert daemon.cache["tree"] is None
    assert daemon.cache["lastTreeReason"] == ""
    assert daemon.cache["lastTreeSettled"] is None
    assert daemon.cache["lastTreeChanged"] is None
    assert daemon.cache["lastTreeEventSeq"] == 0
    assert daemon.cache["lastTreeCapturedAt"] == ""
    assert daemon.cache["lastTreeActionId"] == ""
    assert daemon.cache["lastTreeDigest"] == ""

    daemon.handle_event({
        "event": "ui.tree",
        "treeVersion": 1,
        "eventSeq": 11,
        "capturedAt": "2026-06-20T00:00:00Z",
        "actionId": pending_action_id,
        "reason": "after_action",
        "settled": False,
        "changed": True,
        "treeDigest": "digest-a",
        "currentApp": {"ok": True, "package": "com.android.settings", "className": "android.widget.FrameLayout"},
        "root": {"text": "settings"},
    })
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["current"]["package"] == "com.android.settings"
    assert daemon.cache["tree"]["treeVersion"] == 1
    assert daemon.cache["lastTreeReason"] == "after_action"
    assert daemon.cache["lastTreeSettled"] is False
    assert daemon.cache["lastTreeChanged"] is True
    assert daemon.cache["lastTreeEventSeq"] == 11
    assert daemon.cache["lastTreeCapturedAt"] == "2026-06-20T00:00:00Z"
    assert daemon.cache["lastTreeActionId"] == pending_action_id
    assert daemon.cache["lastTreeDigest"] == "digest-a"

    daemon.handle_event({
        "event": "ui.tree",
        "treeVersion": 2,
        "eventSeq": 12,
        "capturedAt": "2026-06-20T00:00:01Z",
        "actionId": "",
        "reason": "major_change",
        "settled": True,
        "changed": False,
        "treeDigest": "digest-b",
        "currentApp": {"ok": True, "package": "com.android.settings", "className": "android.widget.FrameLayout"},
        "root": {"text": "settings"},
    })
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["pendingActionId"] == pending_action_id
    assert daemon.cache["lastTreeReason"] == "after_action"
    assert daemon.cache["lastTreeSettled"] is False
    assert daemon.cache["lastTreeChanged"] is True
    assert daemon.cache["lastTreeActionId"] == pending_action_id
    assert daemon.cache["lastTreeDigest"] == "digest-a"

    daemon.handle_event({
        "event": "ui.tree",
        "treeVersion": 3,
        "eventSeq": 13,
        "capturedAt": "2026-06-20T00:00:02Z",
        "actionId": pending_action_id,
        "reason": "after_action",
        "settled": True,
        "changed": False,
        "treeDigest": "digest-c",
        "currentApp": {"ok": True, "package": "com.android.settings", "className": "android.widget.FrameLayout"},
        "root": {"text": "settings"},
    })
    assert daemon.cache["pendingAction"] is False
    assert daemon.cache["expectedPackage"] == ""
    assert daemon.cache["pendingActionId"] == ""
    assert daemon.cache["current"]["package"] == "com.android.settings"
    assert daemon.cache["tree"]["treeVersion"] == 3
    assert daemon.cache["lastTreeSettled"] is True
    assert daemon.cache["lastTreeChanged"] is False
    status = daemon.dispatch({"cmd": "status"})
    assert status["pendingAction"] is False
    assert status["pendingActionId"] == ""
    assert status["lastTreeReason"] == "after_action"
    assert status["lastTreeSettled"] is True
    assert status["lastTreeChanged"] is False

    daemon.mark_action_pending("com.android.settings")
    daemon.handle_event({
        "event": "ui.tree",
        "treeVersion": 3,
        "reason": "legacy_tree",
        "currentApp": {"ok": True, "package": "com.android.settings", "className": "android.widget.FrameLayout"},
        "root": {"text": "legacy"},
    })
    assert daemon.cache["pendingAction"] is False
    assert daemon.cache["tree"]["treeVersion"] == 3
    assert daemon.cache["lastTreeReason"] == "legacy_tree"
    assert daemon.cache["lastTreeSettled"] is None
    assert daemon.cache["lastTreeChanged"] is None
    assert daemon.cache["lastTreeDigest"] == ""

    class DummyWs:
        def __init__(self):
            self.calls = []

        def call(self, op, params, action_id=""):
            self.calls.append((op, params, action_id))
            return {"ok": True, "result": {"root": {"text": "fresh"}}}

    daemon.cache["pendingAction"] = True
    daemon.cache["expectedPackage"] = "com.android.settings"
    daemon.cache["tree"] = None
    daemon.ws = DummyWs()
    response = daemon.dispatch({"cmd": "tree", "fresh": True, "params": {"maxDepth": 1}})
    assert response["ok"] is True
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["expectedPackage"] == "com.android.settings"
    assert daemon.ws.calls == [("ui.tree", {"maxDepth": 1}, "")]


def verify_batch_pending_action_detector():
    daemon = HelperDaemon(SOCKET.with_name("verify-helper-unit.sock"))

    class DummyWs:
        def __init__(self):
            self.calls = []

        def batch(self, steps, stop_on_error=True, return_tree=False, action_id=""):
            self.calls.append((steps, stop_on_error, return_tree, action_id))
            return {"ok": True, "result": {"ok": True, "stoppedOnError": False, "steps": []}}

    daemon.ws = DummyWs()

    read_only_steps = [
        {"id": "step-current", "op": "app.current", "params": {}},
        {"id": "step-list", "op": "app.list", "params": {}},
        {"id": "step-tree", "op": "ui.tree", "params": {"maxDepth": 1}},
    ]
    response = daemon.dispatch({"cmd": "batch", "steps": read_only_steps, "stopOnError": True, "returnTree": True})
    assert response["ok"] is True
    assert daemon.cache["pendingAction"] is False
    assert daemon.cache["pendingActionId"] == ""
    assert daemon.ws.calls[-1] == (read_only_steps, True, True, "")

    response = daemon.dispatch({"cmd": "batch", "steps": read_only_steps, "stopOnError": False, "returnTree": False})
    assert response["ok"] is True
    assert daemon.cache["pendingAction"] is False
    assert daemon.cache["pendingActionId"] == ""
    assert daemon.ws.calls[-1] == (read_only_steps, False, False, "")

    mutating_steps = [
        {"id": "step-back", "op": "global.back", "params": {}},
        {"id": "step-tree", "op": "ui.tree", "params": {"maxDepth": 1}},
    ]
    response = daemon.dispatch({"cmd": "batch", "steps": mutating_steps, "stopOnError": True, "returnTree": True})
    assert response["ok"] is True
    assert daemon.cache["pendingAction"] is True
    assert daemon.cache["pendingActionId"].startswith("helper-action-")
    assert daemon.ws.calls[-1][0] == mutating_steps
    assert daemon.ws.calls[-1][3] == daemon.cache["pendingActionId"]


def main():
    verify_pending_action_event_filter()
    verify_batch_pending_action_detector()

    try:
        SOCKET.unlink()
    except FileNotFoundError:
        pass
    for path in (DISCOVERY, LOG, PID):
        try:
            path.unlink()
        except FileNotFoundError:
            pass

    started = helper_json("start", "--socket", str(SOCKET))
    assert started["ok"], started
    current = request({"cmd": "current"})
    assert current.get("type") == "result" and current.get("ok") is True, current
    status = helper_json("status", "--socket", str(SOCKET))
    assert status["ok"] and status["wsConnected"], status

    apps = request({"cmd": "call", "op": "app.list", "params": {}})
    assert apps.get("type") == "result" and apps.get("ok") is True, apps
    assert apps.get("result", {}).get("ok") is True and apps.get("result", {}).get("apps"), apps

    apps_all = request({"cmd": "call", "op": "app.list", "params": {"all": True}})
    assert apps_all.get("type") == "result" and apps_all.get("ok") is True, apps_all
    assert len(apps_all.get("result", {}).get("apps", [])) >= len(apps.get("result", {}).get("apps", [])), apps_all

    first_tree = request({"cmd": "tree", "params": {"maxDepth": 1}})
    assert first_tree.get("ok") is True, first_tree
    if first_tree.get("cached"):
        assert isinstance(first_tree.get("tree"), dict), first_tree
    else:
        assert isinstance(first_tree.get("result"), dict), first_tree
    import time
    time.sleep(0.5)
    second_tree = request({"cmd": "tree", "params": {"maxDepth": 1}})
    assert second_tree.get("ok") is True, second_tree
    if not second_tree.get("cached"):
        print("ASSUMPTION: second tree fetch was uncached; cache population is asynchronous here.")

    action = request({"cmd": "call", "op": "global.back", "params": {}})
    assert action.get("type") == "result" and action.get("ok") is True, action
    after_action = helper_json("status", "--socket", str(SOCKET))
    assert after_action["ok"], after_action

    clear = request({"cmd": "cache.clear"})
    assert clear.get("ok") is True, clear

    stopped = helper_json("stop", "--socket", str(SOCKET))
    assert stopped["ok"], stopped
    print("helper daemon verifier passed")


if __name__ == "__main__":
    main()
