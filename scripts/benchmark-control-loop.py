#!/usr/bin/env python3
"""Measure inert operations on Henyo's own foreground settings screen."""
import argparse
import json
import math
import statistics
import sys
import time
import urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'python'))
from henyo.helper import WsClient

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ws-url', required=True)
parser.add_argument('--http-url', required=True, help='Local HTTP base for content-free phase metrics')
parser.add_argument('--samples', type=int, default=24)
parser.add_argument('--output', type=Path, required=True, help='Ignored artifacts path for raw measurements')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = args.output.resolve()
if not output.is_relative_to((root / 'artifacts').resolve()):
    parser.error('raw measurements must stay under ignored artifacts/')
if not 5 <= args.samples <= 100:
    parser.error('samples must be between 5 and 100')

def http(path, reset=False):
    request = urllib.request.Request(args.http_url.rstrip('/') + path, method='POST' if reset else 'GET')
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)

def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values)*fraction)-1]

assert http('/v1/health')['controlState'] == 'available', 'control must initially be available'
client = WsClient(args.ws_url, '', lambda event: None)
results = {}
try:
    client.connect()
    points = []
    for text in ('Henyo Accessibility Bridge', 'Targeting'):
        response = client.call('ui.find', {'package':'link.liaru.henyo', 'text':text, 'field':'text', 'exact':True})
        assert response.get('ok'), 'open the Henyo settings screen before running'
        node = response['result']['node']
        assert not node['clickable'] and not node['editable'], 'benchmark requires inert headings'
        target = node['target']; assert target['package'] == 'link.liaru.henyo'
        points.append({'package':'link.liaru.henyo', 'windowId':target['windowId'], 'displayId':target['displayId'],
                       'x':node['centerX'], 'y':node['centerY']})
    workloads = {
        'tree': lambda i: client.call('ui.tree', {'package':'link.liaru.henyo', 'maxDepth':8, 'maxNodes':500}),
        'find': lambda i: client.call('ui.find', {'package':'link.liaru.henyo','text':'Targeting','field':'text','exact':True}),
        'tap': lambda i: client.call('ui.tap', points[i % 2]),
        'capture': lambda i: client.call('screen.screenshot', {'package':'link.liaru.henyo','captureMode':'display'}),
    }
    for name, operation in workloads.items():
        http('/v1/debug/performance/reset', reset=True)
        times, failures, failure_codes = [], 0, {}
        for i in range(args.samples + 4):
            started = time.perf_counter_ns()
            response = operation(i)
            elapsed = (time.perf_counter_ns()-started)/1e6
            success = response.get('ok') and response.get('result',{}).get('ok',True)
            if i >= 4:
                times.append(elapsed); failures += not success
                if not success:
                    code=response.get('code',response.get('result',{}).get('code','action_rejected'))
                    failure_codes[code]=failure_codes.get(code,0)+1
            if name == 'capture': time.sleep(.45)  # Space captures; pacing is excluded from measured duration.
        results[name] = {'p50Ms':statistics.median(times), 'p95Ms':percentile(times,.95),
                         'samples':times, 'failures':failures, 'failureCodes':failure_codes,
                         'phases':http('/v1/debug/performance')['performance'].get('phases',{})}
        assert client.call('app.current',{})['result']['package'] == 'link.liaru.henyo', 'inert input changed application'
    assert client.finish_progress().get('ok'), 'completion must release control'
    assert http('/v1/health')['controlState'] == 'available'
finally:
    client.close()
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(results))
print(json.dumps({name:{key:value[key] for key in ('p50Ms','p95Ms','failures')} for name,value in results.items()}))
