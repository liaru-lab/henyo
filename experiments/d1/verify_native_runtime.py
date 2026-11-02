#!/usr/bin/env python3
"""Model-free checks for bundle boundaries, lazy inference, and cancellation."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

import native_runtime as nr
import prepare_native
from readiness import guarded_request


class NativeRuntimeTest(unittest.TestCase):
    def setUp(self):
        nr.ARTIFACTS.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=nr.ARTIFACTS)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        roles = {key: key for key in ("server", "base", "host", "dsp", "cxx")}
        for name in roles.values():
            (self.bundle / name).write_bytes(b"generated fixture")
        self.manifest = {"schema": 1, "runtime_revision": nr.REVISION,
                         "kernel_contract": nr.CONTRACT, "roles": roles,
                         "files": {name: nr.digest(self.bundle / name) for name in roles.values()}}
        self.manifest_path = self.bundle / "manifest.json"
        self.model = self.root / "model"
        self.model.write_bytes(b"not model weights")
        self.config = {"bundle": "bundle/manifest.json", "model": "model", "profile": "stable"}
        self.config_path = self.root / "config.json"
        self.write()

    def tearDown(self):
        self.temp.cleanup()

    def write(self):
        self.manifest_path.write_text(json.dumps(self.manifest))
        self.config_path.write_text(json.dumps(self.config))

    def runtime(self):
        return nr.NativeRuntime(self.config_path, artifact_dir=self.root / "evidence")

    def test_mismatched_contract_and_escape_are_rejected(self):
        self.manifest["kernel_contract"] = "unknown"
        self.write()
        with self.assertRaises(ValueError):
            self.runtime()
        self.manifest["kernel_contract"] = nr.CONTRACT
        self.manifest["files"]["../outside"] = "0" * 64
        self.write()
        with self.assertRaises(ValueError):
            self.runtime()

    def test_wrong_model_never_launches_a_process(self):
        with self.runtime() as runtime, mock.patch.object(nr.subprocess, "Popen") as launch:
            with self.assertRaises(ValueError):
                runtime.request({"state": {}, "questions": {"match": {"type": "noul"}}})
            launch.assert_not_called()

    def test_tampered_library_never_launches_a_process(self):
        with self.runtime() as runtime, mock.patch.object(nr, "MODEL_BYTES", self.model.stat().st_size):
            with mock.patch.object(nr, "MODEL_SHA256", nr.digest(self.model)):
                (self.bundle / "host").write_bytes(b"changed after manifest")
                with mock.patch.object(nr.subprocess, "Popen") as launch:
                    with self.assertRaisesRegex(ValueError, "bundle artifact digest mismatch"):
                        runtime.start()
                    launch.assert_not_called()

    def test_cancelled_exchange_cannot_return_success(self):
        runtime = self.runtime()
        runtime.port = 1

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read(self, limit):
                runtime.close()
                return b'{"answers":{"match":{"type":"noul","noul":0.5}}}'

        with runtime, mock.patch.object(runtime, "start"):
            with mock.patch.object(nr.OPENER, "open", return_value=Response()):
                with self.assertRaisesRegex(RuntimeError, "closed during inference"):
                    runtime.request({"questions": {"match": {}}})

    def test_assembly_rechecks_copied_component_against_descriptor(self):
        official = self.root / "official"
        (official / "bin").mkdir(parents=True)
        (official / "lib").mkdir()
        (official / "bin/llama-server").write_bytes(b"synthetic server")
        descriptor = self.root / "components.json"
        descriptor.write_text(json.dumps({
            "schema": 1, "runtime_revision": nr.REVISION,
            "kernel_contract": nr.CONTRACT, "source_patch_sha256": "0" * 64,
            "components": {key: {"path": str(self.bundle / key),
                                  "sha256": nr.digest(self.bundle / key)}
                           for key in ("base", "host", "dsp")},
        }))
        cxx_license = self.root / "license"
        cxx_license.write_text("Synthetic license fixture")
        original_copy = prepare_native.shutil.copy2

        def changed_copy(source, target):
            if Path(source) == self.bundle / "host":
                Path(source).write_bytes(b"replacement during assembly")
            return original_copy(source, target)

        output = self.root / "assembled"
        config = self.root / "assembled.json"
        with mock.patch.object(prepare_native.shutil, "copy2", side_effect=changed_copy):
            with self.assertRaisesRegex(ValueError, "component changed during assembly"):
                prepare_native.assemble(
                    official, descriptor, output, config, self.model,
                    cxx_runtime=self.bundle / "cxx", cxx_license=cxx_license)
        self.assertFalse(output.exists())
        self.assertFalse(config.exists())

    def test_rejected_observation_does_not_hash_or_start_model(self):
        with self.runtime() as runtime, mock.patch.object(runtime, "start") as start:
            result = guarded_request({"state": {}}, expected_package="example.fixture",
                                     infer=runtime.request)
            self.assertEqual(result["source"], "precheck")
            start.assert_not_called()
            self.assertIsNone(runtime.process)
            self.assertFalse(runtime.directory.exists())

    def test_profile_removes_inherited_experiments(self):
        inherited = {"LD_PRELOAD": "unused.so", "HENYO_D1_GLU_HALF": "1",
                     "GGML_HEXAGON_PROFILE": "1", "GGML_RPC_SERVERS": "unused"}
        with mock.patch.dict(os.environ, inherited), self.runtime() as runtime:
            env = runtime.environment()
            for name in inherited:
                self.assertNotIn(name, env)
            self.assertEqual(env["HENYO_D1_SELECT_LAST"], "1")
            self.assertEqual(env["GGML_BACKEND_PATH"], str(self.bundle / "host"))

    def test_invalid_or_multiple_questions_do_not_start_worker(self):
        with self.runtime() as runtime, mock.patch.object(runtime, "start") as start:
            for questions in ({}, {"a": {}, "b": {}}):
                with self.assertRaises(ValueError):
                    runtime.request({"state": {}, "questions": questions})
            start.assert_not_called()

    def test_whole_exchange_deadline_stops_worker(self):
        release = threading.Event()

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read(self, limit):
                release.wait(1)
                return b'{"answers":{"match":{"type":"noul","noul":0.5}}}'

        with self.runtime() as runtime:
            runtime.port = 1
            try:
                with mock.patch.object(runtime, "start"), mock.patch.object(runtime, "close") as stop:
                    with mock.patch.object(nr.OPENER, "open", return_value=Response()):
                        with self.assertRaises(TimeoutError):
                            runtime.request({"questions": {"match": {}}}, timeout=.02)
                    stop.assert_called_once()
            finally:
                release.set()

    def test_close_interrupts_startup_without_waiting_for_startup_deadline(self):
        spawned = threading.Event()
        children, failures = [], []
        real_popen = subprocess.Popen
        runtime = self.runtime()

        def launch(*args, **kwargs):
            process = real_popen([sys.executable, "-c", "import time; time.sleep(5)"],
                                 cwd=kwargs["cwd"], stdout=kwargs["stdout"],
                                 stderr=subprocess.STDOUT)
            children.append(process)
            spawned.set()
            return process

        def start():
            try:
                runtime.start()
            except RuntimeError as error:
                failures.append(type(error).__name__)

        thread = threading.Thread(target=start)
        close_thread = threading.Thread(target=runtime.close)
        try:
            with mock.patch.object(runtime, "verify_files"), mock.patch.object(nr.subprocess, "Popen", side_effect=launch):
                with mock.patch.object(nr.OPENER, "open", side_effect=OSError("not ready")):
                    thread.start()
                    self.assertTrue(spawned.wait(2))
                    close_thread.start()
                    close_thread.join(1)
                    self.assertFalse(close_thread.is_alive(), "close blocked behind startup")
                    thread.join(2)
                    self.assertFalse(thread.is_alive())
            self.assertEqual(len(children), 1)
            self.assertIsNotNone(children[0].poll())
            self.assertEqual(failures, ["RuntimeError"])
        finally:
            for process in children:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=2)
            thread.join(2)
            if close_thread.ident is not None:
                close_thread.join(2)
            runtime.close()


if __name__ == "__main__":
    unittest.main()
