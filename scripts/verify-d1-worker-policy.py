#!/usr/bin/env python3
"""Regress preparation/Stop intent and idle-reclamation boundaries."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
evidence = root / "artifacts"
evidence.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="d1-policy-", dir=evidence) as directory:
    subprocess.run(["javac", "--release", "11", "-d", directory,
                    str(root / "src/main/java/link/liaru/henyo/D1WorkerPolicy.java"),
                    str(root / "src/test/java/link/liaru/henyo/D1WorkerPolicyTest.java")], check=True)
    subprocess.run(["java", "-cp", directory, "link.liaru.henyo.D1WorkerPolicyTest"], check=True)
