#!/usr/bin/env python3
"""Model-free checks for the experiment's safety and resource boundaries."""
import io
import json
import subprocess
import sys
import time
from unittest.mock import patch
import evaluate
import prepare_opencl
import tempfile
from pathlib import Path
from cases import cases

assert len(cases) == 48 and {case['language'] for case in cases} == {'en', 'ja'}
assert len({case['id'] for case in cases}) == 48
assert all(evaluate.rules(case['state']) == case['expected'] for case in cases)
for case in cases:
    data = json.loads(evaluate.payload(case['state']))
    assert 'expected' not in data and 'id' not in data
    assert set(data['questions']['next']['criteria']) == set(evaluate.CRITERIA)
try:
    evaluate.payload({'text': 'x' * 32768})
except ValueError:
    pass
else:
    raise AssertionError('oversized request accepted')
assert evaluate.accepted({'confidence': .95, 'probabilities': {'search': .95, 'wait': .05}})
assert not evaluate.accepted({'confidence': .6, 'probabilities': {'search': .6, 'wait': .4}})
assert not evaluate.accepted({'confidence': .86, 'probabilities': {'search': .86, 'wait': .8}})
with patch.object(evaluate.OPENER, 'open', return_value=io.BytesIO(b'x' * 65537)):
    try:
        evaluate.request(1, {})
    except ValueError:
        pass
    else:
        raise AssertionError('oversized response accepted')
with patch.object(evaluate, 'REQUEST_TIMEOUT_SECONDS', .02), patch.object(
        evaluate, '_request', side_effect=lambda *args: time.sleep(.1)):
    started = time.monotonic()
    try:
        evaluate.request(1, {})
    except TimeoutError:
        assert time.monotonic() - started < .2
    else:
        raise AssertionError('whole-exchange deadline ignored')
# Verify the forced-shutdown path against an owned process that ignores SIGTERM.
worker = subprocess.Popen([sys.executable, '-c',
    'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("ready",flush=True);time.sleep(10)'],
    stdout=subprocess.PIPE, text=True)
try:
    assert worker.stdout.readline().strip() == 'ready'
    started = time.monotonic()
    evaluate.stop(worker)
    assert worker.poll() is not None and time.monotonic() - started < 3
finally:
    if worker.poll() is None:
        worker.kill()
        worker.wait()
assert evaluate.placement('offloaded 0/25 layers to GPU', 'cpu')['offloadedLayers'] == 0
assert evaluate.placement('ggml_opencl: device initialized\nOpenCL model buffer size = 1000.00 MiB\noffloaded 25/25 layers to GPU', 'opencl')['offloadedLayers'] == 25
for backend, log in [('opencl', ''), ('opencl', 'ggml_opencl: initialized\noffloaded 25/25 layers to GPU'), ('opencl', 'ggml_opencl: device initialized\noffloaded 0/25 layers to GPU'), ('cpu', 'offloaded 25/25 layers to GPU'), ('cpu', 'OpenCL model buffer size = 1000.00 MiB')]:
    try:
        evaluate.placement(log, backend)
    except RuntimeError:
        pass
    else:
        raise AssertionError('unverified backend accepted')

with tempfile.TemporaryDirectory(prefix='d1-driver-') as outside:
    try:
        prepare_opencl.prepare([], Path(outside), Path(__file__).resolve().parents[2])
    except ValueError:
        pass
    else:
        raise AssertionError('driver output outside artifacts accepted')
artifact_directory = Path(__file__).resolve().parents[2] / 'artifacts'
artifact_directory.mkdir(exist_ok=True)
bad_device = subprocess.run([sys.executable, str(Path(__file__).with_name('evaluate.py')),
    '--server-bin', 'unused-binary', '--model', 'unused-model', '--backend', 'opencl',
    '--device', 'CPU', '--output', str(artifact_directory / 'unused-gpu-output.json')],
    capture_output=True, text=True)
assert bad_device.returncode == 2 and 'explicit OpenCL device ID' in bad_device.stderr
with tempfile.TemporaryDirectory(prefix='d1-driver-', dir=artifact_directory) as inside:
    keep = Path(inside) / 'keep.txt'
    keep.write_text('preserve')
    try:
        prepare_opencl.prepare([], Path(inside), Path(__file__).resolve().parents[2])
    except ValueError:
        pass
    else:
        raise AssertionError('nonempty driver output accepted')
    assert keep.read_text() == 'preserve'
print('d1 experiment model-free verifier passed')
