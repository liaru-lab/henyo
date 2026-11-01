#!/usr/bin/env python3
"""Check observation modes, pulse timing, and continuous frame geometry."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-observation-") as classes:
    sources = [root / "src/main/java/link/liaru/henyo" / name for name in (
        "ObservationEffectModel.java", "AgentVisualModel.java", "TargetFrameGeometry.java")]
    subprocess.run(["javac", "--release", "11", "-d", classes, *map(str, sources),
                    str(root / "src/test/java/link/liaru/henyo/ObservationEffectsTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.ObservationEffectsTest"], check=True)
