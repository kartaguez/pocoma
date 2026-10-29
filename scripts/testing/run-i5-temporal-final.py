#!/usr/bin/env python3
"""One frozen campaign, stop on first failure; single unfiltered composed gate after PASS."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app'
OUT = Path(sys.argv[1]).resolve()
OUT.mkdir(parents=True, exist_ok=False)

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def inputs():
    paths = git('ls-files', 'app', 'scripts/testing/run-i5-temporal-final.py',
                'docs/testing/evidence/L2-I5-temporal-final/temporal-contract-v3.properties', 'docs/testing/evidence/L2-I5-temporal-v2/temporal-contract-v2.properties').splitlines()
    return {p: sha(ROOT / p) for p in paths if (ROOT / p).is_file()}

frozen = git('rev-parse', 'HEAD')
assert not git('status', '--porcelain'), 'Freeze must be committed before campaign'
before = inputs()
receipt = {'freeze': frozen, 'startedUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
           'inputs': before, 'stages': [], 'verdict': 'INCONCLUSIVE',
           'globalArchitectureGate': 'NOT REQUIRED', 'fullReactor': 'NOT RUN',
           'unexpectedSliceCrossing': 'NONE', 'declaredSlices': ['COMMAND', 'MONOLITH']}
base = ['./mvnw', '-pl', 'runtime-command-consumption-worker', '-am']
stages = [
    ('W2-B32-MW4', base + ['-Dtest=V3WorkerResourcesPostgresTest#i5_r12', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], 'i5_r12'),
    ('COMMAND-MONOLITH-gate', ['./mvnw', '-pl', 'runtime-command-consumption-worker,runtime-monolith', '-am', 'test'], None),
]
for name, command, required in stages:
    folder = OUT / name
    folder.mkdir()
    start_wall = time.time()
    start = time.monotonic()
    print(f'START {name} freeze={frozen}', flush=True)
    with (folder / 'maven.log').open('w') as log:
        result = subprocess.run(command, cwd=APP, stdout=log, stderr=subprocess.STDOUT)
    elapsed = time.monotonic() - start
    suites = []
    for p in APP.glob('*/target/surefire-reports/TEST-*.xml'):
        if p.stat().st_mtime < start_wall:
            continue
        target = folder / p.relative_to(APP)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, target)
        tree = ET.parse(p).getroot()
        for case in tree.iter('testcase'):
            suites.append({'class': case.get('classname'), 'method': case.get('name'),
                           'seconds': case.get('time'),
                           'status': 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'})
    for p in (APP / 'runtime-command-consumption-worker/target/v3-i5-evidence').glob('temporal-v3-*'):
        if p.stat().st_mtime >= start_wall:
            shutil.copy2(p, folder / p.name)
    selected = [s for s in suites if s['method'] == required] if required else suites
    accepted = result.returncode == 0 and bool(selected) and all(s['status'] == 'PASS' for s in selected)
    # Historical exploratory skips in the unfiltered gate remain visible and are not new required skips.
    if required is None:
        accepted = result.returncode == 0 and bool(selected) and all(s['status'] != 'FAIL' for s in selected)
    if required:
        traces = list(folder.glob('temporal-v3-*-final*'))
        proof = '\n'.join(p.read_text() for p in traces if p.is_file())
        required_markers = ['V3 QUALIFICATION PASS', 'NOWAIT PROBE', 'BARRIER EXCLUSIVE', 'C-EXCESS', 'CLAIMS AFTER LOCAL LEASE RETIREMENT', 'ALL_TRANSACTIONS', 'ALL_ATTEMPTS', 'JOURNAL SEALED']
        accepted = accepted and len(selected) == 1 and all(m in proof for m in required_markers)
    unchanged = inputs() == before
    stage = {'name': name, 'command': command, 'elapsedSeconds': elapsed, 'exitCode': result.returncode,
             'freshCases': suites, 'accepted': accepted, 'inputsUnchanged': unchanged,
             'artifacts': {str(p.relative_to(folder)): sha(p) for p in sorted(folder.rglob('*')) if p.is_file()}}
    receipt['stages'].append(stage)
    print(f'END {name} exit={result.returncode} seconds={elapsed:.3f} accepted={accepted} unchanged={unchanged}', flush=True)
    if not accepted or not unchanged:
        receipt['verdict'] = 'FAIL' if result.returncode or any(s['status'] == 'FAIL' for s in selected) else 'INCONCLUSIVE'
        break
else:
    receipt['verdict'] = 'PASS'
receipt['elapsedSeconds'] = sum(s['elapsedSeconds'] for s in receipt['stages'])
receipt['notRun'] = [name for name, _, _ in stages[len(receipt['stages']):]]
receipt['compiled'] = {str(p.relative_to(ROOT)): sha(p) for p in APP.glob('*/target/test-classes/**/*.class') if p.is_file()}
(OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(f'VERDICT {receipt["verdict"]} receipt={OUT / "receipt.json"}', flush=True)
sys.exit(0 if receipt['verdict'] == 'PASS' else 1)
