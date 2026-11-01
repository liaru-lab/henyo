#!/usr/bin/env python3
"""Compare control calls on the dedicated English synthetic workflow fixture."""
import argparse
import json
import math
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'python'))
from henyo.helper import WsClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ws-url', required=True)
    parser.add_argument('--package', required=True, help='Browser containing the foreground fixture')
    parser.add_argument('--samples', type=int, default=12)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / 'artifacts').resolve()):
        parser.error('raw results must remain under ignored artifacts/')
    if not 5 <= args.samples <= 50:
        parser.error('samples must be between 5 and 50')
    client = WsClient(args.ws_url, '', lambda event: None)
    calls = 0

    def call(op, params):
        nonlocal calls
        calls += 1
        return client.call(op, params, timeout=10)

    def find(text):
        return call('ui.find', {'package': args.package, 'text': text, 'field': 'text', 'exact': True})

    def wait(text):
        end = time.monotonic() + 3
        while time.monotonic() < end:
            response = find(text)
            if response.get('ok'):
                return response
            time.sleep(.05)
        raise RuntimeError('fixture observation unavailable')

    def click(text):
        if not call('ui.click', {'package': args.package, 'text': text, 'field': 'text', 'exact': True}).get('ok'):
            raise RuntimeError('fixture click failed; do not replay automatically')

    results = {}
    try:
        client.connect()
        assert find('Henyo workflow fixture').get('ok') and find('Goal: Open Example Notes').get('ok')
        for mode in ('individual', 'workflow'):
            rows = []
            for sample in range(args.samples + 2):
                click('Reset fixture')
                target = wait('Search')['result']['node']['target']
                time.sleep(.15)
                calls = 0
                started = time.perf_counter()
                if mode == 'individual':
                    assert find('Search').get('ok')
                    click('Search')
                    wait('Example Notes')
                    click('Example Notes')
                    wait('Opened Example Notes')
                else:
                    response = call('workflow.run', {
                        'target': {key: target[key] for key in ('package', 'windowId', 'displayId')},
                        'steps': [
                            {'if': {'text': 'Search'}, 'then': [{'op': 'ui.click', 'params': {'text': 'Search'}}]},
                            {'wait': {'text': 'Example Notes'}, 'timeoutMs': 1800},
                            {'op': 'ui.click', 'params': {'text': 'Example Notes'}},
                            {'wait': {'text': 'Opened Example Notes'}, 'timeoutMs': 1000},
                        ],
                        'verify': {'text': 'Opened Example Notes'},
                        'budgetMs': 5000,
                    })
                    assert response.get('ok') and response['result']['actionsAccepted'] == 2
                elapsed = (time.perf_counter() - started) * 1000
                request_count = calls
                wait('Search count: 1')
                wait('Open count: 1')
                if sample >= 2:
                    rows.append({'latencyMs': elapsed, 'requests': request_count})
            durations = sorted(row['latencyMs'] for row in rows)
            results[mode] = {'samples': rows, 'p50Ms': statistics.median(durations),
                             'p95Ms': durations[math.ceil(.95 * len(durations)) - 1], 'failures': 0}
        assert client.finish_progress().get('ok')
    finally:
        client.close()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(results))
    print(json.dumps({mode: {key: value for key, value in result.items() if key != 'samples'}
                      for mode, result in results.items()}))


if __name__ == '__main__':
    main()
