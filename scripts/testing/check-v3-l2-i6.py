#!/usr/bin/env python3
"""Check the I6 documentary overlay; --archives rereads old bytes, never runs Maven.

The TSV contains review judgments. This checker checks attribution and integrity,
not semantic sufficiency of an oracle and not independent I7 acceptance.
"""
import argparse
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / 'docs/testing/evidence/L2-I6'
BASELINE = '5ab89dd3e58a7bfd617cdd6526f730e6d6854c80'
STATES = {'PASS', 'FAIL', 'INCONCLUSIVE', 'NOT RUN', 'NOT APPLICABLE'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def allocations():
    rows = {}
    for line in (ROOT / 'docs/v3/L2_Certification_Coverage_Manifest.md').read_text().splitlines():
        if line.startswith('| ') and '| L2M-' in line:
            cells = [c.strip() for c in line.strip('|').split('|')]
            if len(cells) != 14 or cells[1] in rows:
                raise ValueError('Malformed/duplicate source allocation')
            rows[cells[1]] = cells
    return rows


def validate_rows(rows, source, evidence):
    if len(rows) != len(source) or {r['proof_id'] for r in rows} != set(source):
        raise ValueError('Missing, extra or duplicate allocation')
    for row in rows:
        original = source[row['proof_id']]
        for key, index in [('normative_id', 0), ('invariant', 2), ('scenario', 3),
                           ('oracle', 4), ('authority', 5), ('subject', 6),
                           ('allocation', 7), ('gate', 10), ('deferred_to', 11)]:
            if row[key] != original[index]:
                raise ValueError('Changed normative allocation: ' + row['proof_id'])
        if row['verdict'] not in STATES or not row['reserve']:
            raise ValueError('Invalid verdict or missing limit')
        refs = row['evidence'].split(';') if row['evidence'] else []
        if any(ref not in evidence for ref in refs):
            raise ValueError('Unresolved evidence')
        if row['allocation'] == 'reporté' and row['verdict'] != 'NOT APPLICABLE':
            raise ValueError('Deferred business obligation promoted')
        if row['verdict'] == 'PASS':
            if not refs or not any(evidence[r]['status'] == 'PASS' for r in refs):
                raise ValueError('PASS without a successful bounded observation')
            if row['basis'] != 'EXACT_COMMAND_ALLOCATION':
                raise ValueError('Unsupported full-allocation PASS')
            if row['subject'] not in ('Command réel', 'Command Pot réel', 'Command Pot réel adapté'):
                raise ValueError('Mixed or fixture subject cannot receive this Command verdict')
        if row['normative_id'] in ('R10-08', 'R6-08') and row['verdict'] == 'PASS':
            raise ValueError('Availability/closure reserve promoted')
    return len(rows)


def scan_archive(path):
    """Read only; no extractall and no executable member is evaluated."""
    cases, members, receipts = {}, {}, []
    with tarfile.open(path, 'r|*') as archive:
        for member in archive:
            if not member.isfile():
                continue
            stream = archive.extractfile(member)
            # JSON/XML are small; stream other members without materializing logs.
            if member.name.endswith('.json') or (
                    member.name.endswith('.xml') and '/TEST-' in member.name):
                data = stream.read()
                members[member.name] = sha(data)
                if '/TEST-' in member.name and member.name.endswith('.xml'):
                    suite = ET.fromstring(data)
                    for case in suite.iter('testcase'):
                        key = case.get('classname', suite.get('name')) + '#' + case.get('name')
                        state = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'
                        cases.setdefault(key, []).append(state)
                elif member.name.endswith(('result.json', 'gate-receipt.json')):
                    receipts.append((member.name, json.loads(data)))
            else:
                members[member.name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    # Verify all artifact members listed by result receipts where this format is used.
    verified = 0
    for name, receipt in receipts:
        prefix = name.removesuffix('result.json')
        for artifact in receipt.get('artifacts', []):
            target = prefix + artifact['path']
            if members.get(target) != artifact['sha256']:
                raise ValueError('Changed/missing archive member: ' + target)
            verified += 1
    return cases, members, receipts, verified


def source_encoding(path, data, expected):
    if sha(data) == expected:
        return 'RAW'
    # Existing Windows checkout encoding, never accepted for Java/SQL/oracles.
    if path == 'app/mvnw.cmd' and b'\r' not in data and sha(data.replace(b'\n', b'\r\n')) == expected:
        return 'CRLF_WINDOWS_WRAPPER'
    return None


def verify_git_inputs(tested_sha, inputs):
    """Check raw Git bytes in one process, without checkout or execution."""
    process = subprocess.Popen(['git', 'cat-file', '--batch'], cwd=ROOT,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    checked = 0
    encodings = []
    try:
        for path, expected in inputs.items():
            process.stdin.write((tested_sha + ':' + path + '\n').encode())
            process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3 or header[1] != b'blob':
                raise ValueError('Missing historical source: ' + path)
            data = process.stdout.read(int(header[2]))
            encoding = source_encoding(path, data, expected)
            if process.stdout.read(1) != b'\n' or encoding is None:
                raise ValueError('Historical source/receipt mismatch: ' + path)
            if encoding != 'RAW':
                encodings.append({'path': path, 'representation': encoding,
                                  'git_raw_sha256': sha(data), 'receipt_sha256': expected})
            checked += 1
    finally:
        process.stdin.close()
        process.stdout.close()
        process.wait()
    return checked, encodings


def audit_archives(index):
    spec = importlib.util.spec_from_file_location('i6_storage', ROOT / 'scripts/testing/evidence-storage.py')
    storage = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(storage)
    sources = storage.retained_sources(json.loads((ROOT / 'docs/testing/Evidence_Retention.json').read_text()))
    results = []
    with tempfile.TemporaryDirectory(prefix='pocoma-i6-readonly-') as directory:
        destination = Path(directory) / 'archive'
        for key, entry in index['archives'].items():
            path = ROOT / entry['path']
            if not path.exists():
                source = sources[entry['path']]
                if source['sha256'] != entry['sha256']:
                    raise ValueError('Archive locator/receipt mismatch')
                storage.restore_historical(source, destination)
                path = destination
            if storage.digest(path) != entry['sha256']:
                raise ValueError('Archive hash mismatch: ' + key)
            cases, members, receipts, checked = scan_archive(path)
            for method, state in entry['required_cases'].items():
                if cases.get(method) != [state]:
                    raise ValueError('Missing/duplicate/wrong-state historical XML: ' + key + ':' + method)
            matched_sha = []
            input_count = 0
            encodings = []
            for _, receipt in receipts:
                tested = receipt.get('sha', receipt.get('tested_sha'))
                if tested:
                    if tested != entry['tested_sha']:
                        raise ValueError('Archive tested SHA mismatch')
                    matched_sha.append(tested)
                    inputs = receipt.get('input_sha256', receipt.get('inputs_sha256', {}))
                    count, representations = verify_git_inputs(tested, inputs)
                    input_count += count
                    encodings.extend(representations)
            results.append({'archive': key, 'sha256': entry['sha256'],
                            'members': len(members), 'xml_cases': sum(map(len, cases.values())),
                            'required_cases_checked': len(entry['required_cases']),
                            'member_hashes_checked': checked, 'receipt_shas': matched_sha,
                            'historical_source_hashes_checked': input_count,
                            'explicit_checkout_encodings': encodings,
                            'xml_states': {s: sum(v.count(s) for v in cases.values()) for s in ('PASS', 'FAIL', 'SKIP')},
                            'meaning': 'Historical bytes/XML checked; no application execution'})
            if destination.exists():
                destination.unlink()
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archives', action='store_true')
    args = parser.parse_args()
    index = json.loads((FOLDER / 'Proof-index.json').read_text())
    for record in index['evidence'].values():
        archive = index['archives'][record['archive']]
        case = record['class'] + '#' + record['method']
        if record['tested_sha'] != archive['tested_sha'] or archive['required_cases'].get(case) != record['status']:
            raise ValueError('Observation disagrees with archive attribution: ' + case)
        if record.get('test_source') and not (ROOT / record['test_source']).is_file():
            raise ValueError('Missing implementation of test: ' + case)
    with (FOLDER / 'Coverage.tsv').open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    count = validate_rows(rows, allocations(), index['evidence'])
    for path, expected in index['inputs_sha256'].items():
        if sha((ROOT / path).read_bytes()) != expected:
            raise ValueError('Changed documentary input: ' + path)
    for path, expected in index['application_tree'].items():
        if git('rev-parse', 'HEAD:' + path).decode().strip() != expected:
            raise ValueError('Qualification/publication tree changed: ' + path)
        if git('diff', BASELINE, '--', path):
            raise ValueError('Uncommitted application/script change: ' + path)
    result = {'kind': 'DOCUMENTARY VERIFICATION ONLY', 'checked_sha': git('rev-parse', 'HEAD').decode().strip(),
              'rows': count, 'L2': sum(r['allocation'] == 'obligatoire L2' for r in rows),
              'deferred': sum(r['allocation'] == 'reporté' for r in rows),
              'verdicts': {s: sum(r['verdict'] == s for r in rows) for s in sorted(STATES)},
              'input_hashes': len(index['inputs_sha256']), 'application_and_campaign_inputs': 'UNCHANGED',
              'independent_I7_audit': 'NOT PERFORMED', 'Maven': 'NOT RUN'}
    if args.archives:
        result['archive_review'] = audit_archives(index)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
