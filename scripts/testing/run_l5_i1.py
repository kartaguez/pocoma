#!/usr/bin/env python3
"""One prepared T1+B1 campaign. Planning forecast never interrupts a healthy process."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]

def main():
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    collection = ROOT / 'scripts/bruno-v3'
    inputs = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(collection.rglob('*')) if p.is_file() and 'node_modules' not in p.parts}
    receipt = dict(start_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                   forecast_seconds=900, forecast_is_interruptive=False, collection_sha256=inputs, phases={}, verdict='INCOMPLETE')
    env = os.environ.copy()
    env['POCOMA_I1_PHYSICAL'] = str(output / 'physical')
    def phase(name, command, cwd):
        beginning = time.monotonic()
        with (output / (name + '.log')).open('wb') as log:
            child = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
            # No global campaign timeout. Scenario/safety oracles remain in tests.
            code = child.wait()
        receipt['phases'][name] = dict(command=command, exit_code=code, seconds=time.monotonic()-beginning)
        return code
    try:
        code = phase('T1', [sys.executable, '-B', 'scripts/testing/verify.py', 'INTEGRATION', sys.argv[2] if len(sys.argv) > 2 else 'l5-i1', '--output', str(output/'T1')], ROOT)
        if code != 0:
            receipt['verdict'] = 'T1_NON_PASS_B1_NOT_RUN'
            return code
        classpath = (ROOT/'app/runtime-web-api/target/i1-classpath.txt').read_text()
        code = phase('B1', ['java', '-Xmx512m', '-cp', classpath, 'com.kartaguez.pocoma.V3ReadHttpFixture', str(ROOT), str(output/'B1')], ROOT)
        if code != 0:
            receipt['verdict'] = 'B1_NON_PASS'
            return code
        requests = [json.loads(line) for line in (output/'B1/requests.jsonl').read_text().splitlines()]
        expense_only = env.get('POCOMA_I1_BRUNO_EXPENSES_ONLY') == 'true'
        expected = [f'B{n:02d}' for n in range(15,21)] if expense_only else [f'B{n:02d}' for n in range(1,33)]
        if sorted(r['scenario'] for r in requests) != expected:
            raise ValueError('B1 missing or duplicate scenario receipts')
        receipt['bruno_scope'] = 'EXPENSE_DELTA_B15_B20' if expense_only else 'FULL_B1_B01_B32'
        receipt['bruno_scenarios'] = len(requests)
        receipt['bruno_http_requests'] = sum(r['requests'] for r in requests)
        receipt['verdict'] = 'LOCAL_CONTRIBUTION_PASS'
        return 0
    finally:
        receipt['seconds'] = time.monotonic()-started
        receipt['forecast_exceeded'] = receipt['seconds'] > 900
        receipt['input_changes'] = [name for name, digest in inputs.items() if hashlib.sha256((ROOT/name).read_bytes()).hexdigest() != digest]
        if receipt['input_changes']:
            receipt['verdict'] = 'INCONCLUSIVE_INPUT_CHANGE'
        (output/'campaign.json').write_text(json.dumps(receipt, indent=2)+'\n')

if __name__ == '__main__':
    sys.exit(main())
