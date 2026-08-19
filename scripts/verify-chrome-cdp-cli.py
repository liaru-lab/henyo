#!/usr/bin/env python3
import importlib.util
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
spec = importlib.util.spec_from_file_location("henyo_cli", ROOT / "python" / "henyo" / "cli.py")
cli = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(cli)


def main():
    assert cli.adb_devices("List of devices attached\n10.0.0.2:4444 device product:x model:y\n") == [
        {"serial": "10.0.0.2:4444", "state": "device"}
    ]

    calls = []

    def fake_operation(op, params=None):
        calls.append((op, params or {}))
        if op == "app.launch":
            return {"ok": True, "foreground": True}
        args = (params or {}).get("arguments", [])
        if args[:2] == ["devices", "-l"]:
            return {"ok": True, "exitCode": 0,
                    "stdout": "List of devices attached\n10.0.0.2:4444 device product:x\n"}
        if "forward" in args:
            return {"ok": True, "exitCode": 0, "stdout": ""}
        if args and str(args[-1]).endswith("/json/version"):
            return {"ok": True, "exitCode": 0, "stdout": json.dumps({
                "Browser": "Chrome/Test", "Protocol-Version": "1.3",
                "webSocketDebuggerUrl": "ws://127.0.0.1:9222/devtools/browser/test",
            })}
        if args and str(args[-1]).endswith("/json/list"):
            return {"ok": True, "exitCode": 0, "stdout": "[]"}
        raise AssertionError((op, params))

    original = cli.operation_result
    cli.operation_result = fake_operation
    try:
        assert cli.chrome_cdp_prepare([]) == 0
    finally:
        cli.operation_result = original
    assert calls[0] == ("app.launch", {"package": "com.android.chrome"})
    assert any("forward" in params.get("arguments", []) for _, params in calls)
    print("chrome CDP CLI verifier passed")


if __name__ == "__main__":
    main()
