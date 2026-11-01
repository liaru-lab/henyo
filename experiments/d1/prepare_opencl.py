#!/usr/bin/env python3
"""Prepare local vendor-driver copies without changing system or package libraries."""
import argparse
import re
import shutil
import subprocess
from pathlib import Path


def prepare(libraries, output, repository):
    output = output.resolve()
    if not output.is_relative_to((repository / 'artifacts').resolve()):
        raise ValueError('driver copies must remain under ignored artifacts/')
    if output.exists() and any(output.iterdir()):
        raise ValueError('output directory must be empty')
    if not libraries or any(not library.is_file() for library in libraries):
        raise ValueError('vendor library is missing')
    output.mkdir(parents=True, exist_ok=True)
    vendor = libraries[0].resolve().parent
    pending = list(libraries)
    copied = set()
    while pending:
        source = pending.pop()
        if source.name in copied:
            continue
        copied.add(source.name)
        target = output / source.name
        shutil.copy2(source, target)
        # Android linker namespaces require copies and their dependencies to resolve
        # inside app-accessible storage. Only copied files are patched.
        subprocess.run(['patchelf', '--set-rpath', '$ORIGIN', str(target)], check=True)
        dynamic = subprocess.check_output(['llvm-readelf', '--dynamic', str(target)], text=True)
        for name in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
            if Path(name).name != name:
                raise ValueError('unexpected dependency path')
            if (Path('/system/lib64') / name).exists() or name in copied:
                continue
            dependency = vendor / name
            if not dependency.is_file():
                raise ValueError('non-system vendor dependency is missing')
            pending.append(dependency)
    return len(copied)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('/vendor/lib64/libOpenCL.so'))
    parser.add_argument('--implementation', type=Path, action='append', default=[],
                        help='Additional implementation loaded by name rather than DT_NEEDED')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    count = prepare([args.library, *args.implementation], args.output, Path(__file__).resolve().parents[2])
    print(f'local_driver_files_prepared={count}')


if __name__ == '__main__':
    main()
