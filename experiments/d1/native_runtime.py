#!/usr/bin/env python3
"""Opt-in, owned d1 worker using a coherent native runtime bundle.

This module performs inference only. Readiness prechecks, current-target
validation, cancellation, and any UI action remain the caller's responsibility.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import secrets
import signal
import socket
import subprocess
import threading
import time
import urllib.request
import uuid


REVISION = "8642541b255bfb5dc39cd351a178be09013a55d6"
CONTRACT = "henyo.d1.htp-v79/1"
MODEL_SHA256 = "adca5789ad30ae6027e8e936749bb9f19673097492ab26fa1c5b69643ca50b4b"
MODEL_BYTES = 1099629888
ARTIFACTS = Path(__file__).resolve().parents[2] / "artifacts"
MAX_REQUEST = 32768
MAX_RESPONSE = 65536
STABLE_FLAGS = {
    "NPU_STENCIL": "1", "PAIR_GLU": "1", "PAIR_MAX_TOKENS": "0",
    "SELECT_LAST": "1", "SELECT_FFN": "1", "SELECT_PAD": "1",
    "SELECT_MIN_TOKENS": "256", "RESIDUAL_NORM": "1",
    "HMX_SCOPE": "down", "HMX_M": "0", "HMX_N": "adaptive",
    "GATE_LONG_TUNE": "1",
}
OFF_FLAGS = {k: "0" for k in (
    "NPU_STENCIL", "PAIR_GLU", "SELECT_LAST", "RESIDUAL_NORM")}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def resolve(directory, value):
    if not isinstance(value, str) or not value or "\x00" in value:
        raise ValueError("invalid path")
    return (directory / value).resolve()


class NativeRuntime:
    """One lazy, single-slot worker; failures stop it and are never replayed."""

    def __init__(self, config_path, *, artifact_dir=None):
        config_path = Path(config_path).resolve()
        config = json.loads(config_path.read_text())
        if set(config) - {"bundle", "model", "profile", "system_environment_file"}:
            raise ValueError("unknown configuration field")
        self.profile = config.get("profile")
        if self.profile not in ("stable", "off"):
            raise ValueError("explicit stable/off profile required")
        manifest_path = resolve(config_path.parent, config["bundle"])
        self.bundle = manifest_path.parent
        manifest = json.loads(manifest_path.read_text())
        if (manifest.get("schema") != 1 or manifest.get("runtime_revision") != REVISION
                or manifest.get("kernel_contract") != CONTRACT):
            raise ValueError("unsupported native runtime contract")
        self.files = manifest.get("files")
        roles = manifest.get("roles")
        if (not isinstance(self.files, dict) or not self.files
                or not isinstance(roles, dict)
                or set(roles) != {"server", "base", "host", "dsp", "cxx"}):
            raise ValueError("incomplete bundle manifest")
        for name, checksum in self.files.items():
            path = resolve(self.bundle, name)
            if (not path.is_relative_to(self.bundle) or Path(name).is_absolute()
                    or not isinstance(checksum, str)
                    or re.fullmatch("[0-9a-f]{64}", checksum) is None):
                raise ValueError("invalid bundle artifact")
        if (any(not isinstance(name, str) or name not in self.files
                       for name in roles.values())):
            raise ValueError("bundle role has no verified artifact")
        self.roles = {key: resolve(self.bundle, name) for key, name in roles.items()}
        self.model = resolve(config_path.parent, config["model"])
        self.system_env = {}
        if config.get("system_environment_file"):
            env_path = resolve(config_path.parent, config["system_environment_file"])
            self.system_env = json.loads(env_path.read_text())
            if (not isinstance(self.system_env, dict)
                    or set(self.system_env) - {"LD_LIBRARY_PATH", "ADSP_LIBRARY_PATH"}
                    or any(not isinstance(v, str) or "\x00" in v
                           for v in self.system_env.values())):
                raise ValueError("invalid driver environment")
        parent = Path(artifact_dir or ARTIFACTS / "d1-native").resolve()
        if not parent.is_relative_to(ARTIFACTS.resolve()):
            raise ValueError("worker evidence must remain under artifacts")
        self.directory = parent / uuid.uuid4().hex
        self.process = None
        self._log = None
        self._closed = False
        self._request_lock = threading.Lock()
        self._start_lock = threading.Lock()
        self._state_lock = threading.RLock()
        self._key = secrets.token_hex(32)
        self.startup_ms = None
        self.last_request_ms = None
        self.last_call_ms = None

    def environment(self):
        env = os.environ.copy()
        for key in list(env):
            if (key.startswith(("HENYO_D1_", "GGML_"))
                    or key in ("LD_PRELOAD", "GGML_SCHED_DEBUG")):
                env.pop(key)
        env.update(self.system_env)
        env["LD_LIBRARY_PATH"] = str(self.roles["base"].parent) + ":" + env.get("LD_LIBRARY_PATH", "")
        env["ADSP_LIBRARY_PATH"] = str(self.roles["dsp"].parent) + ";" + env.get("ADSP_LIBRARY_PATH", "")
        env["GGML_BACKEND_PATH"] = str(self.roles["host"])
        env["GGML_HEXAGON_OPPOLL"] = "1"
        env["GGML_HEXAGON_POWER_MODE"] = "sustained_high_performance"
        flags = STABLE_FLAGS if self.profile == "stable" else OFF_FLAGS
        env.update({"HENYO_D1_" + k: v for k, v in flags.items()})
        return env

    def verify_files(self):
        if (self.model.stat().st_size != MODEL_BYTES
                or digest(self.model) != MODEL_SHA256):
            raise ValueError("unexpected model: use the pinned d1-2B Q4_K_M file")
        for name, checksum in self.files.items():
            path = resolve(self.bundle, name)
            if not path.is_file() or digest(path) != checksum:
                raise ValueError("bundle artifact digest mismatch: " + name)

    def _verify_loaded(self):
        environ = Path(f"/proc/{self.process.pid}/environ").read_bytes().split(b"\0")
        if any(entry.startswith(b"LD_PRELOAD=") for entry in environ):
            raise RuntimeError("preloaded graph shims are not supported")
        maps = Path(f"/proc/{self.process.pid}/maps").read_text()
        paths = {Path(parts[5]).resolve() for line in maps.splitlines()
                 if len(parts := line.split(None, 5)) == 6 and parts[5].startswith("/")}
        bases = {p for p in paths if p.name.startswith("libggml-base.so")}
        if bases != {self.roles["base"]} or self.roles["host"] not in paths:
            raise RuntimeError("unexpected native backend/base mapping")
        runtimes = {p for p in paths if p.name == "libc++_shared.so"}
        if runtimes != {self.roles["cxx"]}:
            raise RuntimeError("unexpected app C++ runtime mapping")
        self.loaded_cxx_sha256 = digest(next(iter(runtimes)))
        text = (self.directory / "worker.log").read_text(errors="replace")
        architectures = set(re.findall(r"Hexagon Arch version v(\d+)", text))
        if (architectures != {"79"} or "offloaded 25/25 layers" not in text
                or "HTPT0 model buffer size" not in text
                or re.search(r"build \d+ \(" + REVISION[:9] + r"\)", text) is None):
            raise RuntimeError("intended model placement was not verified")

    def start(self):
        with self._start_lock:
            with self._state_lock:
                if self._closed:
                    raise RuntimeError("worker is closed")
                if self.process is not None:
                    if self.process.poll() is not None:
                        self.close()
                        raise RuntimeError("owned worker exited")
                    return self
            # Hashing warms file cache. Startup below is process-cold, not storage-cold.
            self.verify_files()
            try:
                with self._state_lock:
                    if self._closed:
                        raise RuntimeError("worker is closed")
                    self.directory.mkdir(parents=True, mode=0o700)
                    log_path = self.directory / "worker.log"
                    fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    self._log = os.fdopen(fd, "wb")
                    with socket.socket() as sock:
                        sock.bind(("127.0.0.1", 0))
                        self.port = sock.getsockname()[1]
                    args = [str(self.roles["server"]), "-m", str(self.model),
                            "-c", "2048", "-b", "2048", "-ub", "2048",
                            "-t", "4", "-tb", "4", "-np", "1", "--no-warmup",
                            "-lv", "4",
                            "--device", "HTPT0", "--gpu-layers", "999",
                            "--host", "127.0.0.1", "--port", str(self.port),
                            "--no-webui", "--timeout", "33",
                            "--api-key", self._key]
                    started = time.perf_counter()
                    self.process = subprocess.Popen(args, cwd=self.directory,
                                                    env=self.environment(), stdout=self._log,
                                                    stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 120
                while True:
                    if self.process.poll() is not None:
                        raise RuntimeError("worker exited before readiness; inspect worker.log")
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError("native worker startup deadline")
                    request = self._http_request("/health")
                    try:
                        with OPENER.open(request, timeout=min(.3, remaining)) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        pass
                    time.sleep(min(.1, remaining))
                self._verify_loaded()
                if self._closed:
                    raise RuntimeError("worker was closed during startup")
                self.startup_ms = (time.perf_counter() - started) * 1000
                return self
            except BaseException:
                self.close()
                raise

    def _http_request(self, path, data=None):
        headers = {"Authorization": "Bearer " + self._key}
        if data is not None:
            headers["Content-Type"] = "application/json"
        return urllib.request.Request(f"http://127.0.0.1:{self.port}" + path,
                                      data=data, headers=headers)

    def request(self, body, *, timeout=30):
        if not 0 < timeout <= 30:
            raise ValueError("request timeout must be in (0,30]")
        if not isinstance(body, dict) or not isinstance(body.get("questions"), dict) or len(body["questions"]) != 1:
            raise ValueError("this validated profile accepts one question per request")
        payload = json.dumps(body, ensure_ascii=False, allow_nan=False,
                             separators=(",", ":")).encode()
        if len(payload) > MAX_REQUEST:
            raise ValueError("native request exceeds byte budget")
        if not self._request_lock.acquire(blocking=False):
            raise RuntimeError("one active request is supported")
        call_started = time.perf_counter()
        self.last_request_ms = self.last_call_ms = None
        try:
            self.start()
            result = queue.Queue(maxsize=1)
            request = self._http_request("/v1/systemone", payload)
            started = time.perf_counter()

            def exchange():
                try:
                    with OPENER.open(request, timeout=timeout) as response:
                        raw = response.read(MAX_RESPONSE + 1)
                    if len(raw) > MAX_RESPONSE:
                        raise ValueError("native response exceeds byte budget")
                    parsed = json.loads(raw)
                    if (not isinstance(parsed, dict) or not isinstance(parsed.get("answers"), dict)
                            or parsed["answers"].keys() != body["questions"].keys()):
                        raise ValueError("invalid decision response")
                    result.put((True, parsed))
                except Exception as error:
                    result.put((False, error))

            threading.Thread(target=exchange, daemon=True).start()
            try:
                ok, value = result.get(timeout=timeout)
            except queue.Empty:
                raise TimeoutError("native whole-exchange deadline") from None
            if not ok:
                raise value
            with self._state_lock:
                if self._closed:
                    raise RuntimeError("worker was closed during inference")
                self.last_request_ms = (time.perf_counter() - started) * 1000
                self.last_call_ms = (time.perf_counter() - call_started) * 1000
                return value
        except BaseException:
            self.close()
            raise
        finally:
            self._request_lock.release()

    def close(self):
        with self._state_lock:
            self._closed = True
            process = self.process
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            if self._log is not None:
                self._log.close()
                self._log = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True, help="single-question JSONL requests")
    args = parser.parse_args()
    with NativeRuntime(args.config) as runtime, args.input.open("rb") as source:
        while line := source.readline(MAX_REQUEST + 1):
            if len(line) > MAX_REQUEST:
                raise ValueError("input line exceeds byte budget")
            if not line.strip():
                continue
            response = runtime.request(json.loads(line))
            print(json.dumps({"response": response, "apiMs": runtime.last_request_ms,
                              "callMs": runtime.last_call_ms, "startupMs": runtime.startup_ms},
                             ensure_ascii=False), flush=True)


def interrupt(*_):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupt)
    main()
