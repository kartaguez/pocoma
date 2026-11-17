#!/usr/bin/env python3
"""Read-only provenance and allocation check for GO_L7_I3_FINAL_QUALIFICATION."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
P = ROOT / 'docs/testing/evidence/L7-I3-final'
BASE = 'c4f5f0c11114921cdc3a735f1fdaeb32c33537c7'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def method(source, name):
    # Mask strings/comments before balancing braces; retain the exact original bytes.
    mask = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                  lambda m: ' ' * len(m.group()), source, flags=re.S)
    match = re.search(r'\bvoid\s+' + re.escape(name) + r'\s*\(', mask)
    assert match, name
    start = mask.index('{', match.end())
    depth = 1
    end = start + 1
    while depth:
        depth += (mask[end] == '{') - (mask[end] == '}')
        end += 1
    return source[match.start():end]


def main():
    rec = json.loads((P / 'reconciliation.json').read_text())
    plan = json.loads((P / 'execution-plan.json').read_text())
    assert len(rec['obligations']) == len(plan['obligations']) == 31
    assert {r['ID'] for r in rec['obligations']} == {r['ID'] for r in plan['obligations']}
    matrix_path = 'docs/v3/L7_I1_Certification_Coverage_Matrix.csv'
    old = list(csv.DictReader(git('show', BASE + ':' + matrix_path).decode().splitlines()))
    now = list(csv.DictReader((ROOT / matrix_path).open()))
    assert len(now) == len({r['ID'] for r in now}) == 522
    fields = list(now[0])
    obligations = {r['ID']: r for r in rec['obligations']}
    previous = json.loads((P.parent / 'L7-I3-completion/reconciliation.json').read_text())
    previous_rows = {r['ID']: r for r in previous['obligations']}
    for before, after in zip(old, now):
        assert all(before[k] == after[k] for k in fields[:-4]), after['ID']
        if after['ID'] in obligations:
            row = obligations[after['ID']]
            assert before[fields[-4]] == row['initialStatus']
            assert previous_rows[after['ID']]['finalStatus'] == row['initialStatus']
            assert row['source'] == before['Source normative']
            assert after[fields[-4]] == row['finalStatus']
            assert row['blocking'] == (row['finalStatus'] != 'CURRENT_FULL')
            if row['finalStatus'] == 'CURRENT_FULL':
                assert row['newEvidence']
            assert row['previousReconciliation'].endswith('#' + after['ID'])
        else:
            assert before == after, after['ID']
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    seconds = 0
    for directory in sorted(P.iterdir()):
        if not directory.is_dir() or directory.name.endswith('-collection-attempt'):
            continue
        receipt = json.loads((directory / 'receipt.json').read_text())
        assert receipt['verdict'] == 'PASS'
        cases = []
        for name, expected in receipt['artifacts'].items():
            if name.startswith('app/'):
                continue
            data = gzip.decompress((directory / name).read_bytes())
            assert digest(data) == expected, (directory.name, name)
            if name.endswith('.xml.gz'):
                suite = ET.fromstring(data)
                assert suite.find("./properties/property[@name='pocoma.verification.id']").get('value') == 'l7-i3-final-' + directory.name
                for case in suite.findall('testcase'):
                    assert not any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))
                    cases.append((case.get('classname'), case.get('name')))
        assert set(cases) == {(e['classname'], e['method']) for e in receipt['executions']}
        assert len(cases) == len(set(cases)) == receipt['counts']['tests']
        assert all(receipt['counts'][k] == 0 for k in ('failures', 'errors', 'skipped'))
        archive = directory / 'physical.tar.gz'
        assert digest(archive.read_bytes()) == receipt['physicalArchiveSha256']
        with tarfile.open(archive) as tar:
            assert set(tar.getnames()) == {n for n in receipt['artifacts'] if n.startswith('app/')}
            for member in tar.getnames():
                assert digest(tar.extractfile(member).read()) == receipt['artifacts'][member]
        for execution in receipt['executions']:
            name = execution['classname'].split('.')[-1] + '.java'
            sources = [s for s in receipt['testInputSha256'] if s.endswith('/' + name)]
            assert len(sources) == 1, sources
            source = sources[0]
            tested = git('show', receipt['sha'] + ':' + source)
            assert digest(tested) == receipt['testInputSha256'][source]
            if directory.name == 'read-01' and execution['method'] == 'l73_allReadParticipantsRetainUncertainCapacityAndExactEffects':
                # The later read-reacquire-01 adds the independent reacquisition/value oracle.
                assert (P / 'read-reacquire-01/receipt.json').exists()
            else:
                assert method(tested.decode(), execution['method']) == method((ROOT / source).read_text(), execution['method'])
        changed = git('diff', '--name-only', receipt['sha'], '--', 'app', 'scripts/v3').decode().splitlines()
        assert all('/src/test/' in s for s in changed), changed
        for k in totals:
            totals[k] += receipt['counts'][k]
        seconds += receipt['wallSeconds']
    # Revalidate historical archives and exact unchanged sources, without rerunning them.
    reuse = json.loads((P / 'reuse-receipt.json').read_text())
    for item in reuse.values():
        source = git('show', item['sha'] + ':' + item['source'])
        assert digest(source) == item['sourceSha256']
        assert source == (ROOT / item['source']).read_bytes()
    expiry = reuse['reuse-expiry']
    old_receipt = ROOT / expiry['receipt']
    gate = json.loads(old_receipt.read_text())
    archive = old_receipt.parent / 'raw.tar.gz'
    assert digest(archive.read_bytes()) == gate['raw_sha256']
    with tarfile.open(archive) as tar:
        xml = tar.extractfile(expiry['xmlMember']).read()
        assert digest(xml) == expiry['xmlSha256']
        suite = ET.fromstring(xml)
        assert suite.find("./properties/property[@name='pocoma.verification.id']").get('value') == gate['invocation_id']
        for n, d in expiry['physicalArtifacts'].items():
            assert digest(tar.extractfile(n).read()) == d
    discovery = ROOT / reuse['reuse-discovery']['receipt']
    target = json.loads(discovery.read_text())
    assert target['verdict'] == 'PASS' and target['counts']['tests'] == 3
    for n, d in target['artifacts'].items():
        data = (discovery.parent / n).read_bytes()
        assert digest(gzip.decompress(data) if n.endswith('.gz') else data) == d
    productive = git('diff', '--name-only', BASE, '--', 'app', 'scripts/v3').decode().splitlines()
    assert all('/src/test/' in s for s in productive), productive
    assert json.loads((P / 'collection-correction.json').read_text())['admissible'] is False
    print(json.dumps({'verdict': 'PASS', 'scope': 'read-only evidence/allocation verification',
                      'obligations': 31, 'matrixIds': 522, 'counts': totals,
                      'newExecutionWallSeconds': seconds, 'runtimeMutations': 0}, indent=2))


if __name__ == '__main__':
    main()
