#!/usr/bin/env python3
import json
import os
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import build_opener, ProxyHandler, Request


ROOT = Path(__file__).resolve().parents[1]
HENYO = ROOT / "bin" / "henyo"


def dumps(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=True)


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


def helper_request(payload, env):
    return json.loads(run("helper", "start", env=env)) if payload == {"cmd": "start"} else json.loads(
        subprocess.run(
            [
                sys.executable,
                "-m",
                "henyo.helper",
                "request",
                "--socket",
                env["HENYO_HELPER_SOCKET"],
                dumps(payload),
            ],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env={**env, "PYTHONPATH": str(ROOT / "python")},
            check=True,
        ).stdout
    )


def http_current():
    opener = build_opener(ProxyHandler({}))
    with opener.open(Request("http://127.0.0.1:8765/v1/app/current"), timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def measure(label, count, fn):
    samples = []
    last = None
    for _ in range(count):
        started = time.perf_counter()
        last = fn()
        samples.append((time.perf_counter() - started) * 1000)
    return {
        "label": label,
        "count": count,
        "minMs": round(min(samples), 2),
        "medianMs": round(statistics.median(samples), 2),
        "maxMs": round(max(samples), 2),
        "lastOk": bool(isinstance(last, dict) and last.get("ok", True)),
    }


def main():
    count = int(os.environ.get("HENYO_BENCH_COUNT", "8"))
    with tempfile.TemporaryDirectory(prefix="henyo-bench-") as tmpdir:
        env = os.environ.copy()
        env["HENYO_HELPER_SOCKET"] = str(Path(tmpdir) / "helper.sock")
        helper_request({"cmd": "start"}, env)

        helper_request({"cmd": "cache.clear"}, env)
        helper_request({"cmd": "current"}, env)
        helper_request({"cmd": "tree", "params": {"maxDepth": 1}}, env)
        current_cache = helper_request({"cmd": "current"}, env)
        tree_cache = helper_request({"cmd": "tree", "params": {"maxDepth": 1}}, env)

        results = [
            measure("http.current", count, http_current),
            measure("ws.current", count, lambda: helper_request({"cmd": "call", "op": "app.current", "params": {}}, env)),
            measure("ws.batch.current_tree", count, lambda: helper_request({
                "cmd": "batch",
                "steps": [
                    {"op": "app.current", "params": {}},
                    {"op": "ui.tree", "params": {"maxDepth": 1}},
                ],
                "returnTree": False,
            }, env)),
            measure("helper.cached_current", count, lambda: helper_request({"cmd": "current"}, env)),
            measure("helper.cached_tree", count, lambda: helper_request({"cmd": "tree", "params": {"maxDepth": 1}}, env)),
        ]

        helper_request({"cmd": "stop"}, env)

    print(dumps({
        "ok": True,
        "count": count,
        "cache": {
            "currentCached": current_cache.get("cached") is True,
            "treeCached": tree_cache.get("cached") is True,
        },
        "results": results,
    }))


if __name__ == "__main__":
    main()
