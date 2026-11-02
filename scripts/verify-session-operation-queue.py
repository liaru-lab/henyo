#!/usr/bin/env python3
"""Exercise the bounded serial connection queue without Android or a model."""
import subprocess
import tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
java = root / 'src/main/java/link/liaru/henyo'
with tempfile.TemporaryDirectory(prefix='henyo-session-queue-') as directory:
    subprocess.run(['javac', '--release', '11', '-d', directory,
                    *[str(java / (name + '.java')) for name in
                      ('SessionOperationQueue', 'ControlOwnership', 'OperationDeadline')],
                    str(root / 'src/test/java/link/liaru/henyo/SessionOperationQueueTest.java')], check=True)
    subprocess.run(['java', '-cp', directory, 'link.liaru.henyo.SessionOperationQueueTest'], check=True)
