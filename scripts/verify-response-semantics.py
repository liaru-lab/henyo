#!/usr/bin/env python3
"""Check that applied mutations remain distinct from postcondition failures."""

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from henyo import helper  # noqa: E402


daemon = helper.HelperDaemon(ROOT / "build" / "verify-response-semantics.sock")
daemon.settle_foreground = lambda expected: {
    "foreground": False,
    "expectedPackage": expected,
    "currentPackage": "different.package",
    "pendingAction": True,
    "settledMs": 5000,
}
applied = {
    "type": "result",
    "id": "launch-1",
    "ok": True,
    "result": {"ok": True},
    "durationMs": 2,
}
verified = daemon.maybe_settle_action(
    "app.launch", {"package": "expected.package"}, applied
)
assert verified["ok"] is True
assert verified["result"]["ok"] is True
assert verified["result"]["foreground"] is False
assert verified["result"]["pendingAction"] is True

failed = {"ok": False, "error": "ws_disconnected"}
assert daemon.maybe_settle_action(
    "app.launch", {"package": "expected.package"}, failed
) is failed

print("response semantics verifier passed")
