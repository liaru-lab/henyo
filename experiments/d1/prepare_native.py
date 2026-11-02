#!/usr/bin/env python3
"""Assemble verified native components into one local, immutable bundle."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from native_runtime import ARTIFACTS, CONTRACT, REVISION, NativeRuntime, digest


def private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def assemble(runtime_root, components_path, output, config, model, *,
             system_environment_file=None, cxx_runtime=None, cxx_license=None):
    runtime_root, components_path = Path(runtime_root).resolve(), Path(components_path).resolve()
    output, config, model = Path(output).resolve(), Path(config).resolve(), Path(model).resolve()
    if any(not p.is_relative_to(ARTIFACTS.resolve()) for p in (output, config)):
        raise ValueError("bundle and configuration must remain under artifacts")
    if output.exists() or config.exists():
        raise FileExistsError("use new bundle/configuration paths; existing files are preserved")
    if not cxx_runtime or not cxx_license:
        raise ValueError("the matched C++ runtime and its corresponding license are required")
    descriptor = json.loads(components_path.read_text())
    if (descriptor.get("schema") != 1 or descriptor.get("runtime_revision") != REVISION
            or descriptor.get("kernel_contract") != CONTRACT
            or set(descriptor.get("components", {})) != {"base", "host", "dsp"}
            or re.fullmatch("[0-9a-f]{64}", descriptor.get("source_patch_sha256", "")) is None):
        raise ValueError("unsupported native component descriptor")
    components = {}
    for role, item in descriptor["components"].items():
        path = (components_path.parent / item["path"]).resolve()
        if not path.is_file() or digest(path) != item["sha256"]:
            raise ValueError("native component digest mismatch: " + role)
        components[role] = path
    server = runtime_root / "bin/llama-server"
    library_root = runtime_root / "lib"
    if not server.is_file() or not library_root.is_dir():
        raise ValueError("expected the pinned Android runtime's bin/lib layout")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".d1-native-", dir=output.parent))
    try:
        (stage / "bin").mkdir()
        (stage / "lib").mkdir()
        (stage / "dsp").mkdir()
        shutil.copy2(Path(__file__).with_name("patches") / "LICENSE.ggml", stage / "LICENSE.ggml")
        shutil.copy2(Path(__file__).with_name("NATIVE.md"), stage / "NOTICE.native-runtime.md")
        for name in ("LICENSE", "LICENSE.txt", "NOTICE", "NOTICE.txt"):
            if (runtime_root / name).is_file():
                shutil.copy2(runtime_root / name, stage / ("runtime-" + name))
        shutil.copy2(server, stage / "bin/llama-server")
        # Retain the known runtime/plugin set; do not change registration and
        # graph integration in the same comparison.
        for path in library_root.glob("*.so*"):
            if path.is_file():
                shutil.copy2(path, stage / "lib" / path.name)
        roles = {"server": "bin/llama-server", "base": "lib/libggml-base.so",
                 "host": "lib/libggml-hexagon-tuned.so", "dsp": "dsp/libggml-htp-v79.so"}
        for role, source in components.items():
            shutil.copy2(source, stage / roles[role])
            if digest(stage / roles[role]) != descriptor["components"][role]["sha256"]:
                raise ValueError("native component changed during assembly: " + role)
        if cxx_runtime is not None:
            roles["cxx"] = "lib/libc++_shared.so"
            shutil.copy2(Path(cxx_runtime).resolve(), stage / roles["cxx"])
            shutil.copy2(Path(cxx_license).resolve(), stage / "LICENSE.cxx")
        files = {str(p.relative_to(stage)): digest(p) for p in stage.rglob("*") if p.is_file()}
        private_json(stage / "manifest.json", {
            "schema": 1, "runtime_revision": REVISION, "kernel_contract": CONTRACT,
            "source_patch_sha256": descriptor["source_patch_sha256"],
            "files": files, "roles": roles,
        })
        stage.rename(output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    value = {"bundle": str(output / "manifest.json"), "model": str(model), "profile": "stable"}
    if system_environment_file is not None:
        value["system_environment_file"] = str(Path(system_environment_file).resolve())
    private_json(config, value)
    # No process is started. Model/bundle hashes and configuration syntax are
    # checked here; actual driver availability is checked at worker startup.
    runtime = NativeRuntime(config)
    runtime.verify_files()
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--components", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--system-environment-file", type=Path)
    parser.add_argument("--cxx-runtime", type=Path, required=True)
    parser.add_argument("--cxx-license", type=Path, required=True)
    args = parser.parse_args()
    assemble(args.runtime_root, args.components, args.output, args.config, args.model,
             system_environment_file=args.system_environment_file, cxx_runtime=args.cxx_runtime,
             cxx_license=args.cxx_license)
    print("Native bundle and configuration verified; no worker started.")


if __name__ == "__main__":
    main()
