#!/usr/bin/env python3
"""Exercise capture completion races without Android or private image data."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-capture-") as classes:
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/CaptureCompletion.java"),
                    str(root / "src/test/java/link/liaru/henyo/CaptureCompletionTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.CaptureCompletionTest"], check=True)
