#!/usr/bin/env python3
"""Serial V3 smoke; exact balance delta oracle, never a sustainable-throughput proof.

Use an isolated, sole-writer fixture. Retain all observed responses with the
receipt. Historical L7/I1 evidence is immutable; every run needs a new output.
"""
import argparse
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
import uuid

from v3_token import OidcTokenManager

ROOT = Path(__file__).resolve().parents[2]
WRITE_BASE_URL = os.environ.get('POCOMA_WRITE_URL', 'http://127.0.0.1:8080')
READ_BASE_URL = os.environ.get('POCOMA_READ_URL', 'http://127.0.0.1:8082')
FIXTURES_FILE = ROOT / 'docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json'


def rational(value):
    if not isinstance(value, dict) or set(value) != {'n', 'd'}:
        raise ValueError('Missing exact rational')
    n, d = value['n'], value['d']
    if not isinstance(n, str) or not isinstance(d, str) or int(d) <= 0:
        raise ValueError('Invalid rational encoding')
    return Fraction(int(n), int(d))


def balance_values(data, ids):
    values = data.get('balances')
    if not isinstance(values, dict) or set(values) != set(ids):
        raise ValueError('Balance shareholder IDs differ')
    exact = {k: rational(v) for k, v in values.items()}
    if sum(exact.values(), Fraction()) != 0:
        raise ValueError('Balance sum is not zero')
    return exact


def initial_state(data, fixture):
    ids = fixture['shareholders']
    if data.get('id') != fixture['potId'] or type(data.get('potVersion')) is not int or data['potVersion'] < 1:
        raise ValueError('Invalid current Pot identity/version')
    shareholders = data.get('shareholders', [])
    if len(shareholders) != len(ids) or {s['id'] for s in shareholders} != set(ids):
        raise ValueError('Incomplete Pot shareholders')
    if not isinstance(data.get('expenses'), list) or len(data['expenses']) < fixture['expenses']:
        raise ValueError('Incomplete Pot expenses')
    weights = {s['id']: rational(s['defaultWeight']) for s in shareholders}
    if any(w <= 0 for w in weights.values()):
        raise ValueError('Nonpositive fixture weight')
    return dict(potId=fixture['potId'], shareholders=ids, version=data['potVersion'],
                balances=balance_values(data, ids), weights=weights, expenses=len(data['expenses']))


def applied_version(result, observed):
    version = result.get('resultingPotVersion')
    if result.get('outcome') not in ('COMPLETED', 'CHANGED'):
        raise ValueError('Command not applied: ' + str(result.get('outcome')))
    if type(version) is not int or version != observed + 1:
        raise ValueError('Missing new exact version or concurrent fixture writer')
    return version


def expected_balances(state, payer, amount):
    total = sum(state['weights'].values(), Fraction())
    values = {k: v - amount * state['weights'][k] / total for k, v in state['balances'].items()}
    values[payer] += amount
    return values


def validate_complete(pot, balance, state, version, expense, expected):
    if pot.get('id') != state['potId'] or pot.get('potVersion') != version:
        raise ValueError('Pot is not the exact sole-writer version')
    shareholders = pot.get('shareholders', [])
    if len(shareholders) != len(state['shareholders']) or {s['id'] for s in shareholders} != set(state['shareholders']):
        raise ValueError('Pot shareholder IDs differ')
    refs = pot.get('expenses', [])
    if len(refs) != state['expenses'] + 1 or expense not in {r['id'] for r in refs}:
        raise ValueError('Created expense missing from complete Pot')
    if balance.get('potId') != state['potId'] or balance.get('potVersion') != version:
        raise ValueError('Balance is not the exact requested version')
    if balance_values(balance, state['shareholders']) != expected:
        raise ValueError('Exact independent balance delta differs')
    if balance_values(pot, state['shareholders']) != expected:
        raise ValueError('Complete Pot balance differs')


def percentile(data, p):
    if not data:
        return None
    values = sorted(data)
    k = (len(values) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def request(method, url, token, payload=None, binding=None, key=None):
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}
    if binding:
        headers['X-Pocoma-Binding-Token'] = binding
    if key:
        headers['Idempotency-Key'] = key
    body = None
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors='replace')
        return error.code, {'httpError': raw}


def run_smoke(total_commands, target_rate, fixtures_file, output):
    if total_commands < 1 or target_rate <= 0:
        raise ValueError('Positive command count/rate required')
    for url in (WRITE_BASE_URL, READ_BASE_URL):
        if urlparse(url).hostname not in ('localhost', '127.0.0.1', '::1'):
            raise ValueError('Only local isolated targets allowed')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Reserve before any mutation. Existing receipts cannot be overwritten.
    with output.open('x') as receipt:
        receipt.write('{}\n')
    token_mgr = OidcTokenManager()
    traces, measurements = [], []
    started = time.monotonic()
    summary = dict(benchmark='V3 serial five-runtime smoke', status='FAIL',
                   sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                   dirty=subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True),
                   fixtureSha256=hashlib.sha256(fixtures_file.read_bytes()).hexdigest(),
                   scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   offered=total_commands, targetRate=target_rate, measurements=measurements, traces=traces,
                   limitation='Serial closed-loop smoke; does not certify offered-load capacity or sustainable throughput')

    def call(method, url, payload=None, binding=None, key=None):
        status, data = request(method, url, token_mgr.get_token(), payload, binding, key)
        traces.append(dict(elapsedMs=(time.monotonic() - started) * 1000,
                           method=method, url=url, status=status, request=payload, response=data))
        return status, data

    def poll(url, binding=None):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            status, data = call('GET', url, binding=binding)
            if status == 200:
                return data
            if status not in (404, 503):
                raise ValueError('Unexpected polling HTTP status ' + str(status))
            time.sleep(.02)
        raise TimeoutError('Availability deadline exceeded: ' + url)

    try:
        status, binding = call('GET', READ_BASE_URL + '/api/v3/me/binding')
        if status != 200 or binding.get('active') is not True or not binding.get('bindingToken'):
            raise ValueError('Active authenticated binding required')
        binding = binding['bindingToken']
        states = []
        for fixture in json.loads(fixtures_file.read_text())['pots']:
            status, pot = call('GET', READ_BASE_URL + '/api/v3/pots/' + fixture['potId'], binding=binding)
            if status != 200:
                raise ValueError('Fixture synchronization failed')
            states.append(initial_state(pot, fixture))
        if not states:
            raise ValueError('No fixtures')
        for index in range(total_commands):
            state = states[index % len(states)]
            began = time.monotonic()
            expense = str(uuid.uuid4())
            payer = state['shareholders'][index % len(state['shareholders'])]
            amount = Fraction(1000 + index * 50, 100)
            expected = expected_balances(state, payer, amount)
            payload = dict(operation='CreateExpense', potId=state['potId'], observedVersion=state['version'],
                           bindingToken=binding, payload=dict(id=expense, label=f'Smoke Expense #{index+1}',
                           date='2026-11-08', amount=dict(numerator=str(1000+index*50), denominator='100'),
                           payerId=payer, participants=[dict(id=s, weight=None) for s in state['shareholders']]))
            record = dict(index=index+1, c03Valid=False)
            measurements.append(record)
            status, admitted = call('POST', WRITE_BASE_URL + '/api/v3/commands', payload=payload, key='l7-smoke-' + expense)
            record['admissionMs'] = (time.monotonic() - began) * 1000
            if status != 202 or not admitted.get('requestId'):
                raise ValueError('Command admission failed: HTTP ' + str(status))
            record['requestId'] = admitted['requestId']
            result = poll(READ_BASE_URL + '/api/v3/commands/' + admitted['requestId'] + '/result')
            record['resultMs'] = (time.monotonic() - began) * 1000
            record['outcome'] = result.get('outcome')
            version = applied_version(result, state['version'])
            base = READ_BASE_URL + '/api/v3/pots/' + state['potId']
            pot = poll(base + '?minVersion=' + str(version), binding)
            record['versionMs'] = (time.monotonic() - began) * 1000
            balance = poll(base + '/versions/' + str(version) + '/balance', binding)
            validate_complete(pot, balance, state, version, expense, expected)
            record.update(c03Valid=True, targetVersion=version, e2eMs=(time.monotonic() - began) * 1000)
            state.update(version=version, balances=expected, expenses=state['expenses']+1)
            time.sleep(max(0, 1 / target_rate - (time.monotonic() - began)))
        summary['status'] = 'PASS'
    except Exception as error:
        summary['error'] = f'{type(error).__name__}: {error}'
    finally:
        summary['elapsedSeconds'] = time.monotonic() - started
        summary['oracleValid'] = sum(m['c03Valid'] for m in measurements)
        summary['successPct'] = 100 * summary['oracleValid'] / total_commands
        summary['latenciesMs'] = {name: {f'p{p}': percentile([m[name] for m in measurements if m['c03Valid']], p/100)
                                       for p in (50, 95, 99)}
                                  for name in ('admissionMs', 'resultMs', 'versionMs', 'e2eMs')}
        output.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if k not in ('traces', 'measurements', 'dirty')}, indent=2))
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commands', type=int, default=30)
    parser.add_argument('--rate', type=float, default=1)
    parser.add_argument('--fixtures', type=Path, default=FIXTURES_FILE)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run_smoke(args.commands, args.rate, args.fixtures, args.output)
    raise SystemExit(0 if result['status'] == 'PASS' else 1)
