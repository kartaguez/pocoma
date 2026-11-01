#!/usr/bin/env python3
"""Read-only byte/member/SQL-receipt check; no Maven, DB, publication or human verdict."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def check_bytes(raw, record):
    if len(raw) != record['bytes'] or digest(raw) != record['sha256']:
        raise ValueError('byte integrity mismatch')


def read_json(path):
    raw = path.read_bytes()
    return json.loads(gzip.decompress(raw) if path.suffix == '.gz' else raw)


def main():
    manifest = read_json(ROOT / 'docs/v3/L3_Identity_Proof_Manifest.json')
    assert manifest['status'] == 'QUALIFICATION IN PROGRESS — CLOSURE PENDING HUMAN ACCEPTANCE'
    assert manifest['human_acceptance']['status'] == 'PENDING'
    assert manifest['human_acceptance']['decision'] is None
    assert not manifest['human_acceptance']['l4_go']
    assert set(manifest['obligations']) == {f'O{i:02}' for i in range(1, 28)}
    assert set(manifest['variants_id01']) == {'I01-' + chr(i) for i in range(ord('A'), ord('Q') + 1)}
    statuses = {'SATISFIED', 'SATISFIED WITH RESERVATIONS', 'NOT DEMONSTRATED',
                'DEFECT CONFIRMED', 'OUT OF SCOPE'}
    for group in ['obligations', 'variants_id01']:
        assert all(o['status'] in statuses for o in manifest[group].values())
    assert all(manifest['obligations'][o]['status'] == 'NOT DEMONSTRATED'
               for o in ['O20', 'O21', 'O25'])
    assert manifest['variants_id01']['I01-Q']['status'] == 'OUT OF SCOPE'
    assert manifest['proof_artifacts'], 'frozen nonempty inventory required'
    for path, record in manifest['proof_artifacts'].items():
        assert path != 'docs/v3/L3_Identity_Proof_Manifest.json', 'no self hash'
        target = (ROOT / path).resolve()
        assert target.is_relative_to(ROOT)
        check_bytes(target.read_bytes(), record)

    index = read_json(HERE / 'Archive_Index.json')
    archives = {}
    for label, record in index.items():
        path = ROOT / record['archive']['path']
        check_bytes(path.read_bytes(), record['archive'])
        actual = {}
        with tarfile.open(path) as archive:
            for entry in archive:
                assert entry.isfile() and entry.name not in actual
                assert not entry.name.startswith('/') and '..' not in Path(entry.name).parts
                actual[entry.name] = archive.extractfile(entry).read()
        assert set(actual) == {m['member'] for m in record['members']}
        for member in record['members']:
            raw = actual[member['member']]
            check_bytes(raw, member)
            for case in member.get('testcases', []):
                assert case['line'] and '<testcase ' in raw.decode().splitlines()[case['line'] - 1]
        archives[label] = actual

    spec = importlib.util.spec_from_file_location('verification', ROOT / 'scripts/testing/verify.py')
    verification = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verification)
    for label, record in manifest['recovered_historical_campaigns'].items():
        plan = read_json(ROOT / record['plan']['path'])
        result = read_json(ROOT / record['result']['path'])
        provenance = read_json(ROOT / record['provenance']['path'])
        raw = archives[label]
        assert {n: digest(b) for n, b in raw.items()} == result['artifacts']
        reports = [(n, b) for n, b in raw.items() if n.startswith('reports/')
                   and Path(n).name.startswith('TEST-') and n.endswith('.xml')]
        outcome = verification.assess(reports, plan['expected'], result['exit_code'], plan['filtered'],
                                      dict(invocation_id=result['invocation_id'],
                                           expected_modules=plan['expected_modules']))
        assert outcome['verdict'] == result['verdict'] == 'PASS' and not outcome['issues']
        assert outcome['counts'] == result['counts'] == record['counts']
        # Reuse is compared to the frozen tested commit, not to later edited documentation.
        for name, expected in provenance['app_inputs'].items():
            current = ROOT / name
            assert digest(current.read_bytes()) == expected, name
        for name, expected in provenance.get('external_sql_inputs', {}).items():
            assert digest((ROOT / name).read_bytes()) == expected, name
        for name, expected in provenance.get('control_inputs', {}).items():
            if expected is not None:
                # Original provenance has absolute paths; resolve repo controls portably.
                target = ROOT / ('scripts/' + name.split('/scripts/', 1)[1]) if '/scripts/' in name else Path(name)
                assert digest(target.read_bytes()) == expected, name

    public = archives['Q-ID']
    campaign = json.loads(public['campaign.json'])
    assert campaign == read_json(HERE / 'campaign.json')
    assert campaign['verdict'] == 'PASS' and len(campaign['requests']) == 7
    assert len({r['request_id'] for r in campaign['requests']}) == 7
    assert [r['order'] for r in campaign['requests']] == list(range(1, 8))
    assert [r['result']['outcome'] for r in campaign['requests']] == ['CHANGED'] * 4 + ['REJECTED'] * 3
    assert public['final-oracle.tsv'].decode().strip().split('\t') == ['t'] * 11
    business = ['v3_pocoma_user', 'v3_eui_authority', 'v3_eui_version', 'v3_identity_event']
    for label, expected in [('Q-UFREE', 'TARGET_NOT_BOUND'),
                            ('Q-UOTHER', 'TARGET_BOUND_TO_ANOTHER_USER'),
                            ('Q-REGNEW', 'TARGET_ALREADY_BOUND')]:
        receipt = json.loads(public[label + '-receipt.json'])
        assert receipt == read_json(HERE / (label + '-receipt.json'))
        assert receipt['verdict'] == 'PASS' and receipt['fresh']
        assert receipt['result']['rejection'] == expected
        assert receipt['result']['target_eui_id'] is None and receipt['result']['target_version'] is None
        before = json.loads(public[label + '-before.json'])
        after = json.loads(public[label + '-after.json'])
        assert all(before[t] == after[t] for t in business)
        for suffix in ['before', 'after']:
            raw = public[label + '-' + suffix + '-fingerprints.tsv'].decode()
            observed = dict((line.split('\t')[0], line.split('\t')[1:]) for line in raw.splitlines())
            assert observed == receipt[suffix + '_fingerprints']
        b, a = receipt['before_fingerprints'], receipt['after_fingerprints']
        assert all(b[t] == a[t] for t in ['users', 'anchors', 'history', 'mutations', 'IDENTITY'])
        assert int(a['IDENTITY_COMPLETION'][0]) == int(b['IDENTITY_COMPLETION'][0]) + 1
        assert public[label + '-oracle.tsv'].decode().splitlines() == [
            't\tt', 'IDENTITY\tt', 'IDENTITY_COMPLETION\tt']
    final = json.loads(public['final-before-stop.json'])
    for name in ['after-first-stop', 'after-restart', 'after-final-stop']:
        assert json.loads(public[name + '.json']) == final
    assert len(final['v3_pocoma_user']) == 2 and len(final['v3_eui_authority']) == 3
    assert len(final['v3_eui_version']) == 4 and len(final['v3_identity_event']) == 6
    assert len(final['v3_identity_completion_event']) == 7
    assert all(campaign[k]['helper_exit'] == 0 and campaign[k]['callback']
               for k in ['first_stop', 'second_stop'])
    assert all(campaign['confidentiality_checks'].values())
    operator = public['operator/run_qid.py.txt']
    assert digest(operator) == campaign['operator_sha256']
    assert public['oracle-fingerprints.sql'].decode() and public['oracle-rejected.sql'].decode()
    assert manifest['i4_receipts']['count'] == 64 and not manifest['i4_receipts']['duplication']
    subprocess.run([sys.executable, '-B', str(ROOT / 'docs/testing/evidence/L3-I4/verify_evidence.py')],
                   check=True)
    if '--self-test' in sys.argv:
        record = dict(bytes=3, sha256=digest(b'abc'))
        check_bytes(b'abc', record)
        for changed in [b'xbc', b'ab', b'abcd']:
            try:
                check_bytes(changed, record)
            except ValueError:
                pass
            else:
                raise AssertionError('corrupted bytes accepted')
    print('PASS: frozen files/archive members; six historical producer/inventory checks; '
          '7 requests/3 Q-ID receipts/11 final booleans/2 drains/restart; matrices 27/17; '
          '64 historical receipts; acceptance remains PENDING. No runtime tests executed.')


if __name__ == '__main__':
    main()
