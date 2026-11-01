#!/usr/bin/env python3
"""Check bounded request parsing and absolute loopback read deadlines."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-http-reader-") as classes:
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/HttpRequestReader.java"),
                    str(root / "src/test/java/link/liaru/henyo/HttpRequestReaderTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.HttpRequestReaderTest"], check=True)
