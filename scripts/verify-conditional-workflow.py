#!/usr/bin/env python3
"""Run bounded workflow and predicate state-machine regression checks."""
import subprocess
import tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
java=root/'src/main/java/link/liaru/henyo'
with tempfile.TemporaryDirectory(prefix='henyo-workflow-') as directory:
    subprocess.run(['javac','--release','11','-d',directory,*[str(java/(name+'.java')) for name in (
        'ConditionalWorkflow','WorkflowPredicateProbe','OperationFailure','ControlOwnership')],
        str(root/'src/test/java/link/liaru/henyo/ConditionalWorkflowTest.java')],check=True)
    subprocess.run(['java','-cp',directory,'link.liaru.henyo.ConditionalWorkflowTest'],check=True)
