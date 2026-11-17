#!/usr/bin/env python3
"""Validate and render the L7 SPEC-09 manifest without starting any process or request."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex


def render(manifest):
    cases = {case['id']: case for case in manifest['campaigns']}
    expected = {f'F01-{key}' for key in 'ABCDEFGHIJ'} | {'A11-L/M', 'ENDURANCE', 'SATURATION'}
    if len(cases) != len(manifest['campaigns']) or cases.keys() != expected:
        raise ValueError('SPEC-09 campaign inventory mismatch')
    budgets = {'F01-A': (1, 500), 'F01-B': (10, 1000), 'F01-C': (50, 3000), 'F01-D': (1, 2000)}
    for case, budget in budgets.items():
        if (cases[case]['rate'], cases[case]['c03P95Ms']) != budget:
            raise ValueError('SPEC-09 C-03 rate or threshold changed: ' + case)
    if (cases['F01-A']['exactBalanceP95Ms'], cases['F01-E']['usableBindingP95Ms']) != (500, 500):
        raise ValueError('SPEC-09 Balance or Identity threshold changed')
    catchup = cases['F01-G']
    if (catchup['backlog'], catchup['recoveryRate'], catchup['convergenceSeconds']) != (10000, 1, 300):
        raise ValueError('SPEC-09 catch-up profile changed')
    if manifest['repeat'] < 3 or not 0 < manifest['pollIntervalMs'] <= 25:
        raise ValueError('Insufficient repeat count or polling resolution')
    result = []
    for case in manifest['campaigns']:
        entry = dict(case)
        stimulus = case.get('stimulus')
        if stimulus in {'nominal', 'sustained', 'peak', 'contention', 'idle', 'ramp'}:
            dataset = manifest['datasets'][case['dataset']]
            env = {
                'SCENARIO': stimulus,
                'RATE': str(case.get('rate', 1)),
                'DURATION': str(case.get('sampleSeconds', 1)) + 's',
                'POTS': str(case['potCount']),
                'SHAREHOLDERS': str(dataset['shareholders']),
                'SEED_EXPENSES': str(dataset['expenses']),
                'POLL_INTERVAL_MS': str(manifest['pollIntervalMs']),
                'DECOUPLED': 'false',
                'MAX_ADMISSION_RETRIES': '0',
            }
            if 'idleSeconds' in case:
                env['IDLE_SECONDS'] = str(case['idleSeconds'])
            if stimulus in {'nominal', 'sustained', 'peak', 'ramp'}:
                env['ISOLATE_POTS_BY_VU'] = 'true'
                entry['requiredEnvironment'] = ['POCOMA_READ_URL', 'POCOMA_WRITE_URL', 'POCOMA_TOKEN',
                                                'POCOMA_BINDING_TOKEN', 'PRE_SEEDED_POTS_FILE']
                entry['fixtureCommand'] = shlex.join([
                    'python3', '-B', 'scripts/performance/v3-seed.py',
                    '--profile', case['dataset'], '--pots', str(case['potCount']),
                    '--seed-id', f"l7-spec09-{case['dataset']}-{case['potCount']}",
                    '--materialize', '--output', 'seed-receipt.json',
                    '--fixture-output', 'stimulus-fixtures.json',
                ])
            if stimulus == 'ramp':
                parameters = [{**env, 'RATE': str(rate),
                               'DURATION': str(case['sampleSecondsPerRate']) + 's'}
                              for rate in case['rates']]
            else:
                parameters = [env]
            entry['stimulusCommands'] = [
                shlex.join(['env', *(f'{key}={value}' for key, value in values.items()),
                            'k6', 'run', 'scripts/k6/v3-performance.js'])
                for values in parameters
            ]
        result.append(entry)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path,
                        default=Path(__file__).with_name('l7-spec09-campaigns.json'))
    args = parser.parse_args()
    data = args.manifest.read_bytes()
    manifest = json.loads(data)
    print(json.dumps({'mode': 'STATIC_PLAN_ONLY', 'manifestSha256': hashlib.sha256(data).hexdigest(),
                      'repetitions': manifest['repeat'], 'campaigns': render(manifest)}, indent=2))
