#!/usr/bin/env python3
"""Model-free checks for the experiment's safety and resource boundaries."""
import io
import json
import subprocess
import sys
import time
from unittest.mock import patch
import evaluate
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
print('d1 experiment model-free verifier passed')
