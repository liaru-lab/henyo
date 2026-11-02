#!/usr/bin/env python3
"""Build the patched Android host from project source and fresh QAIC output.

The compiler must target Android arm64. This is the native Termux build path;
the Hexagon SDK and generated interface files remain external prerequisites.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess

from native_runtime import ARTIFACTS


def build(source, sdk, generated, base_lib, output, jobs=2):
    source, sdk, generated, base_lib, output = (
        Path(p).resolve() for p in (source, sdk, generated, base_lib, output))
    if not output.is_relative_to(ARTIFACTS.resolve()):
        raise ValueError("build outputs and command logs must remain under artifacts")
    if not (generated / "htp_iface_stub.c").is_file():
        raise ValueError("fresh QAIC output is required")
    if not (source / "ggml/src/ggml-henyo-d1.inc").is_file():
        raise ValueError("apply the complete native runtime patch first")
    if not (base_lib / "libggml-base.so").is_file():
        raise ValueError("the matching native base library is required")
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    host = source / "ggml/src/ggml-hexagon"
    includes = [source / "ggml/include", source / "ggml/src", host, host / "htp",
                generated, sdk / "incs", sdk / "incs/stddef", sdk / "ipc/fastrpc/incs"]
    sources = [host / name for name in (
        "ggml-hexagon.cpp", "htp-kparams.cpp", "htp-opnode.cpp",
        "htp-fusion.cpp", "htp-repack.cpp", "htp-drv.cpp")]
    sources.append(generated / "htp_iface_stub.c")
    commands = []
    for path in sources:
        obj = output / (path.name + ".o")
        command = ["clang++" if path.suffix == ".cpp" else "clang", "-O2", "-fPIC",
                   "-fvisibility=hidden", "-DGGML_BACKEND_BUILD", "-DGGML_BACKEND_SHARED",
                   "-DGGML_BACKEND_DL", "-DNDEBUG"]
        if path.suffix == ".cpp":
            command.append("-std=c++17")
        for directory in includes:
            command += ["-I", str(directory)]
        command += ["-c", str(path), "-o", str(obj)]
        commands.append({"source": str(path), "object": str(obj), "command": command})
    (output / "compile-commands.json").write_text(json.dumps(commands, indent=2))

    def compile_one(entry):
        with (output / (Path(entry["source"]).name + ".log")).open("wb") as log:
            result = subprocess.run(entry["command"], stdout=log, stderr=subprocess.STDOUT)
        return result.returncode

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        results = list(pool.map(compile_one, commands))
    if any(results):
        raise RuntimeError("native host compilation failed; inspect the output logs")
    command = ["clang++", "-shared", "-Wl,--no-undefined", "-o",
               str(output / "libggml-hexagon-tuned.so")]
    command += [entry["object"] for entry in commands]
    command += ["-L", str(base_lib), "-lggml-base", "-ldl"]
    (output / "link-command.json").write_text(json.dumps(command, indent=2))
    with (output / "link.log").open("wb") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError("native host linking failed; inspect link.log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "sdk", "generated-dir", "base-lib-dir", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2, choices=range(1, 9))
    args = parser.parse_args()
    build(args.source, args.sdk, args.generated_dir, args.base_lib_dir, args.output, args.jobs)
    print("Native host build completed; model/device verification is still required.")


if __name__ == "__main__":
    main()
