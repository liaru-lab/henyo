#!/usr/bin/env python3
"""Run deterministic ownership and queued-work lifecycle tests."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-control-") as classes:
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/ControlOwnership.java"),
                    str(root / "src/test/java/link/liaru/henyo/ControlOwnershipTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.ControlOwnershipTest"], check=True)
