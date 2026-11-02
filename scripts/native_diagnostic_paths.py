"""Reject local build paths; never modify native code or linked metadata."""
from pathlib import Path


def verify_no_build_paths(data, roots):
    for root in roots:
        path = Path(root)
        if not path.is_absolute() or len(str(path)) < 4:
            raise ValueError('expected an absolute build root')
        if str(path).encode('utf-8') in data:
            raise ValueError('native runtime retains a local build path; rebuild with prefix maps')
