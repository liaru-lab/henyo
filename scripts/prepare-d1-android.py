#!/usr/bin/env python3
"""Package a validated native bundle for Android's extracted native-library dir.

No model or SDK payload is copied. Runtime files remain under ignored artifacts.
The official server carries optional backend DT_NEEDED entries even when unused;
remove those three dependencies and strip debug information on fresh copies only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from native_diagnostic_paths import verify_no_build_paths


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True,
                        help='pinned patched project source, used to build the backend registry')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    bundle, output = args.bundle.resolve(), args.output.resolve()
    if not output.is_relative_to(root / 'artifacts'):
        raise ValueError('runtime packaging output must remain under artifacts')
    if output.exists():
        raise FileExistsError('use a new output directory')
    manifest = json.loads((bundle / 'manifest.json').read_text())
    if (manifest.get('schema') != 1 or manifest.get('kernel_contract') != 'henyo.d1.htp-v79/1'
            or manifest.get('runtime_revision') != '8642541b255bfb5dc39cd351a178be09013a55d6'):
        raise ValueError('unsupported runtime bundle')
    source_root = args.source.resolve()
    revision = subprocess.check_output(['git', '-C', str(source_root), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != manifest['runtime_revision']:
        raise ValueError('unexpected source revision')
    libraries = ['libggml-cpu.so', 'libggml.so', 'libllama.so', 'libllama-common.so',
                 'libllama-server-impl.so', 'libmtmd.so']
    sources = {'libhenyo-d1.so': manifest['roles']['server']}
    sources.update({name: 'lib/' + name for name in libraries})
    for role in ['base', 'host', 'dsp', 'cxx']:
        name = Path(manifest['roles'][role]).name
        sources[name] = manifest['roles'][role]
    for relative in sources.values():
        source = (bundle / relative).resolve()
        if not source.is_relative_to(bundle) or sha(source) != manifest['files'][relative]:
            raise ValueError('source bundle digest mismatch')
    native = output / 'lib/arm64-v8a'
    native.mkdir(parents=True, mode=0o700)
    for name, relative in sources.items():
        path = native / name
        shutil.copy2(bundle / relative, path)
    registry_sources = [source_root / 'ggml/src' / name
                        for name in ['ggml-backend-dl.cpp', 'ggml-backend-reg.cpp']]
    for path in registry_sources:
        pinned = subprocess.check_output(['git', '-C', str(source_root), 'show',
                                          revision + ':' + str(path.relative_to(source_root))])
        if pinned != path.read_bytes():
            raise ValueError('backend registry source differs from the pin')
    # Build the real upstream registry with CPU + dynamic backend loading only.
    # This removes references to unused OpenCL/RPC/stock-Hexagon registration;
    # it does not substitute fake backend functions.
    command = ['clang++', '-shared', '-fPIC', '-O3', '-DNDEBUG', '-std=c++17',
               '-ffile-prefix-map=' + str(source_root) + '=/src/llama.cpp',
               '-fvisibility=hidden', '-DGGML_BACKEND_DL', '-DGGML_BUILD',
               '-DGGML_SHARED', '-DGGML_USE_CPU', '-D_GNU_SOURCE',
               '-I', str(source_root / 'ggml/include'),
               *map(str, registry_sources), '-L', str(native),
               '-lggml-base', '-lggml-cpu', '-ldl', '-Wl,--no-undefined',
               '-Wl,-soname,libggml.so', '-o', str(native / 'libggml.so')]
    (output / 'registry-build.json').write_text(json.dumps(command, indent=2))
    with (output / 'registry-build.log').open('wb') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    # The launcher retains the pinned server implementation and dies with its
    # owning app, including when Android terminates the VM without onDestroy.
    launcher = root / 'src/main/native/d1-main.cpp'
    command = ['clang++', '-O2', '-fPIE', '-pie', str(launcher), '-L', str(native),
               '-ffile-prefix-map=' + str(root) + '=/src/henyo',
               '-l:libllama-server-impl.so', '-Wl,--allow-shlib-undefined',
               '-o', str(native / 'libhenyo-d1.so')]
    (output / 'launcher-build.json').write_text(json.dumps(command, indent=2))
    with (output / 'launcher-build.log').open('wb') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    for path in native.iterdir():
        name = path.name
        if name != 'libggml-htp-v79.so':
            for dependency in ['libggml-opencl.so', 'libggml-rpc.so', 'libggml-hexagon.so']:
                subprocess.run(['patchelf', '--remove-needed', dependency, str(path)], check=True)
            subprocess.run(['patchelf', '--set-rpath', '$ORIGIN', str(path)], check=True)
            subprocess.run(['llvm-strip', '--strip-debug', str(path)], check=True)
        verify_no_build_paths(path.read_bytes(), [source_root, root])
        path.chmod(0o755)
    marker = root / 'src/main/native/d1-unavailable.c'
    for abi, target in [('armeabi-v7a', 'armv7a-linux-androideabi26'),
                        ('x86', 'i686-linux-android26'), ('x86_64', 'x86_64-linux-android26')]:
        destination = output / 'lib' / abi / 'libhenyo-d1-unavailable.so'
        destination.parent.mkdir()
        subprocess.run(['clang', '--target=' + target, '-fuse-ld=lld', '-shared', '-fPIC',
                        '-nostdlib', '-Wl,--no-undefined', str(marker), '-o', str(destination)], check=True)
    assets = output / 'assets'
    assets.mkdir()
    data = {'schema': 1, 'contract': manifest['kernel_contract'],
            'revision': manifest['runtime_revision'],
            'sourcePatchSha256': manifest['source_patch_sha256'],
            'launcherSourceSha256': sha(launcher),
            'files': {p.name: sha(p) for p in native.iterdir()}}
    (assets / 'd1-runtime.json').write_text(json.dumps(data, indent=2) + '\n')
    notices = assets / 'd1-notices'
    notices.mkdir()
    for name in ['LICENSE.ggml', 'LICENSE.cxx', 'NOTICE.native-runtime.md']:
        shutil.copy2(bundle / name, notices / name)
    for path in sorted((source_root / 'vendor').rglob('*')):
        if path.is_file() and path.name.lower().startswith(('license', 'copying')):
            target = notices / ('upstream-' + str(path.relative_to(source_root)).replace('/', '-'))
            shutil.copy2(path, target)
    print('Android native runtime packaged; APK/device validation is still required.')


if __name__ == '__main__':
    main()
