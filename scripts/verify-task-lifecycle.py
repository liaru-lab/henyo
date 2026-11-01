#!/usr/bin/env python3
"""Exercise task ownership, stale completion, and presentation generations."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="henyo-task-lifecycle-") as classes:
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/TaskLifecycle.java"),
                    str(root / "src/main/java/link/liaru/henyo/ControlOwnership.java"),
                    str(root / "src/test/java/link/liaru/henyo/TaskLifecycleTest.java")], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.TaskLifecycleTest"], check=True)
