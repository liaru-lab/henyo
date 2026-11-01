#!/usr/bin/env python3
"""Check measured panel geometry, font-scaled rows, and safe overflow."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-panel-layout-") as classes:
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/ActivityPanelLayout.java"),
                    str(root / "src/test/java/link/liaru/henyo/ActivityPanelLayoutTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.ActivityPanelLayoutTest"], check=True)
