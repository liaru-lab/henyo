#!/usr/bin/env python3
"""Compare two native runtimes with identical synthetic requests, in ABBA order."""
import argparse
import hashlib
import json
import math
import os
import queue
import re
import signal
import socket
import statistics
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

import evaluate as ev


def workloads():
    # These labels are generated fixtures, never captured app content.
    short = []
    for target in ("Example Notes", "Example Calendar", "Example Reader"):
        for index, label in enumerate((target, "Example Calculator", target + " support", "Loading")):
            body = {"state": {"goal": "Open " + target, "row": {"text": label}},
                    "questions": {"match": {"type": "noul", "instructions":
                        "Does this candidate name the requested application itself? "
                        "Reject a different application, a support page, or a loading indicator."}}}
            short.append({"id": f"semantic-{len(short)}", "task": "semantic",
                          "expected": index == 0, "body": body})
    full = [{"id": case["id"], "task": "control", "expected": case["expected"],
             "body": json.loads(ev.payload(case["state"]))}
            for case in ev.cases if case["id"] in {
                "en-wait-0", "en-open_result-0", "en-finish-0",
                "ja-wait-0", "ja-open_result-0", "ja-finish-0"}]
    return short + full


def request(port, body, timeout):
    data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    if len(data) > 32768:
        raise ValueError("request exceeds experiment limit")
    result = queue.Queue(maxsize=1)

    def exchange():
        try:
            started = time.perf_counter()
            req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/systemone", data,
                                         {"Content-Type": "application/json"})
            with ev.OPENER.open(req, timeout=timeout) as response:
                raw = response.read(65537)
                if len(raw) > 65536:
                    raise ValueError("response exceeds experiment limit")
            result.put((True, (json.loads(raw), (time.perf_counter() - started) * 1000)))
        except Exception as error:
            result.put((False, error))

    threading.Thread(target=exchange, daemon=True).start()
    try:
        ok, value = result.get(timeout=timeout)
    except queue.Empty:
        raise TimeoutError("inference exchange timed out") from None
    if not ok:
        raise value
    return value


def distribution(values):
    ordered = sorted(values)
    return {"samples": len(ordered), "medianMs": statistics.median(ordered),
            "p95Ms": ordered[math.ceil(len(ordered) * .95) - 1]}


def loaded_backend(pid, expected_directory):
    paths = set()
    for line in Path(f"/proc/{pid}/maps").read_text().splitlines():
        fields = line.split(None, 5)
        if len(fields) == 6 and Path(fields[5]).name.startswith("libggml-opencl.so"):
            paths.add(Path(fields[5]).resolve())
    if len(paths) != 1:
        raise RuntimeError("could not identify exactly one loaded OpenCL backend")
    path = paths.pop()
    if expected_directory and path.parent != expected_directory.resolve():
        raise RuntimeError("requested backend library directory was not used")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path), "sha256": digest}


def summarize(runs):
    summary = {}
    references = {}
    agreement = []
    for run in runs:
        for row in run["rows"]:
            key = (row["task"], row["id"])
            if key in references:
                agreement.append(row["response"]["answers"] == references[key])
            else:
                references[key] = row["response"]["answers"]
    for variant in ("baseline", "candidate"):
        rows = [row for run in runs if run["variant"] == variant for row in run["rows"]]
        summary[variant] = {task: distribution([r["latencyMs"] for r in rows if r["task"] == task])
                            for task in sorted({r["task"] for r in rows})}
    summary["answerComparisons"] = len(agreement)
    summary["exactAnswerAgreement"] = all(agreement)
    return summary


def run(args, output):
    work = workloads()
    report = {"order": ["baseline", "candidate", "candidate", "baseline"],
              "inputSha256": hashlib.sha256(json.dumps(work, sort_keys=True).encode()).hexdigest(),
              "modelSha256": ev.MODEL_SHA256, "repeats": args.repeats,
              "note": "Warm loopback request timing; no UI actions. Startup and warmup excluded. "
                      "Repeated inputs are not independent accuracy observations.",
              "runs": [], "error": None}

    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2))

    try:
        for index, variant in enumerate(report["order"]):
            binary = getattr(args, variant + "_bin").resolve()
            library = getattr(args, variant + "_library_dir")
            env = os.environ.copy()
            # Isolate the tested path from inherited exploratory settings.
            for key in list(env):
                if (key.startswith(("GGML_OPENCL_", "HENYO_OPENCL_"))
                        and key != "GGML_OPENCL_KERNEL_CACHE_DIR") or key in ("GGML_SCHED_DEBUG", "GGML_BACKEND_PATH"):
                    env.pop(key)
            env["GGML_OPENCL_Q4K_DENSE_DP4A"] = str(args.dp4a)
            if library:
                env["LD_LIBRARY_PATH"] = str(library.resolve()) + ":" + env.get("LD_LIBRARY_PATH", "")
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            row = {"variant": variant, "rows": [], "warmup": [], "workerStopped": False}
            report["runs"].append(row)
            log_path = output.with_suffix(f".{index}-{variant}.worker.log")
            with log_path.open("xb") as log:
                started = time.perf_counter()
                worker = subprocess.Popen([
                    str(binary), "-m", str(args.model.resolve()), "-c", "2048", "-b", "2048", "-ub", "2048",
                    "-t", "4", "-tb", "4", "-np", "1", "--no-warmup", "--host", "127.0.0.1",
                    "--port", str(port), "--no-webui", "--timeout", str(args.request_timeout + 3),
                    "-lv", "4", "--device", args.device, "--gpu-layers", "999"],
                    cwd=output.parent, env=env, stdout=log, stderr=subprocess.STDOUT)
                row["workerPid"] = worker.pid
                save()
                try:
                    while True:
                        if worker.poll() is not None:
                            raise RuntimeError("worker exited before readiness")
                        try:
                            with ev.OPENER.open(f"http://127.0.0.1:{port}/health", timeout=.3) as response:
                                if response.status == 200:
                                    break
                        except OSError:
                            if time.perf_counter() - started > args.startup_timeout:
                                raise TimeoutError("worker startup timed out")
                            time.sleep(.1)
                    row["startupMs"] = (time.perf_counter() - started) * 1000
                    row["placement"] = ev.placement(log_path.read_text(errors="replace"), "opencl")
                    row["loadedBackend"] = loaded_backend(worker.pid, library)
                    row["before"] = ev.resource(worker.pid)
                    for case in (work[0], work[-1]):
                        _, ms = request(port, case["body"], args.request_timeout)
                        row["warmup"].append({"task": case["task"], "latencyMs": ms})
                    ordered = work if index < 2 else list(reversed(work))
                    for repeat in range(args.repeats):
                        for case in ordered:
                            answer, ms = request(port, case["body"], args.request_timeout)
                            row["rows"].append({"id": case["id"], "task": case["task"],
                                                "expected": case["expected"], "repeat": repeat,
                                                "latencyMs": ms, "response": answer})
                            save()
                    row["after"] = ev.resource(worker.pid)
                finally:
                    stopped = time.perf_counter()
                    try:
                        ev.stop(worker)
                    finally:
                        row["shutdownMs"] = (time.perf_counter() - stopped) * 1000
                        row["workerStopped"] = worker.poll() is not None
                        save()
            print(f"Finished {index + 1}/4: {variant}", flush=True)
        report["summary"] = summarize(report["runs"])
    except KeyboardInterrupt:
        report["error"] = "cancelled"
        raise
    except Exception as error:
        report["error"] = type(error).__name__
        raise
    finally:
        save()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for variant in ("baseline", "candidate"):
        parser.add_argument(f"--{variant}-bin", type=Path, required=True)
        parser.add_argument(f"--{variant}-library-dir", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=2)
    parser.add_argument("--dp4a", type=int, choices=(0, 1), default=1)
    parser.add_argument("--startup-timeout", type=int, choices=range(1, 181), default=180)
    parser.add_argument("--request-timeout", type=int, choices=range(1, 121), default=30)
    args = parser.parse_args()
    output = args.output.resolve()
    root = Path(__file__).resolve().parents[2]
    if not output.is_relative_to((root / "artifacts").resolve()) or output.exists():
        parser.error("choose a new output file under ignored artifacts/")
    if not re.fullmatch(r"(?:GPU)?OpenCL\d*", args.device):
        parser.error("select an explicit OpenCL device from --list-devices")
    if args.model.stat().st_size != ev.MODEL_BYTES:
        parser.error("unexpected model size")
    with args.model.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != ev.MODEL_SHA256:
            parser.error("unexpected model hash")
    output.parent.mkdir(parents=True, exist_ok=True)
    previous = signal.signal(signal.SIGTERM, ev.interrupt)
    try:
        result = run(args, output)
    finally:
        signal.signal(signal.SIGTERM, previous)
    print(json.dumps(result["summary"]))


if __name__ == "__main__":
    main()
