#!/usr/bin/env python3
"""Read-only integrity, producer attribution and counter check for the I4 dossier."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read_json(path):
    return json.loads(gzip.decompress(path.read_bytes()))


def main():
    manifest = json.loads((ROOT / 'docs/v3/L3_I4_Proof_Manifest.json').read_text())
    for name, expected in manifest['proof_artifacts'].items():
        raw = (ROOT / name).read_bytes()
        assert len(raw) == expected['bytes'] and sha(raw) == expected['sha256'], name
    # Read raw Git blobs: git archive may apply the declared CRLF checkout form
    # of mvnw.cmd, while these Unix executions used its LF blob bytes.
    entries = subprocess.check_output(['git', 'ls-tree', '-r', '-z', manifest['tested_code_sha'], 'app', 'scripts'])
    objects = []
    for entry in entries.split(b'\0'):
        if entry:
            metadata, name = entry.split(b'\t', 1)
            objects.append((metadata.split()[2].decode(), name.decode()))
    payload = subprocess.check_output(['git', 'cat-file', '--batch'],
                                     input=('\n'.join(oid for oid, name in objects) + '\n').encode())
    code = {}
    offset = 0
    for oid, name in objects:
        end = payload.index(b'\n', offset)
        returned, kind, length = payload[offset:end].split()
        assert returned.decode() == oid and kind == b'blob'
        start = end + 1
        code[name] = sha(payload[start:start + int(length)])
        offset = start + int(length) + 1
    assert offset == len(payload)
    for name, expected in manifest['files'].items():
        assert code[name] == expected['sha256'], name
    total_reports = 0
    for label, summary in manifest['campaigns'].items():
        directory = HERE / 'campaigns' / label
        result = read_json(directory / summary.get('qualification_receipt', 'result.json.gz'))
        plan = read_json(directory / 'plan.json.gz')
        provenance = read_json(directory / 'provenance.json.gz')
        review_path = directory / 'source-attribution-review.json.gz'
        if review_path.exists():
            review = read_json(review_path)
            assert code[review['source']] == review['source_sha256']
            assert review['test_class'] == 'com.kartaguez.pocoma.runtime.command.consumption.sequencing.CurrentTransactionManagerPostgresTest'
            assert review['method'] == 'effectiveManagerAndSharedJdbcConnection'
            assert review['module'] == 'pocoma-runtime-command-consumption-worker'
            plan['expected_modules'][review['test_class']] = review['module']
        if label.endswith('-final') or label in ['architecture-bounded', 'native-command', 'native-architecture']:
            assert provenance['head']['output'].strip() == manifest['tested_code_sha'], label
            assert provenance['app_inputs'] == {n: h for n, h in code.items() if n.startswith('app/')}, label
        assert summary['counts'] == result['counts'] and summary['verdict'] == result['verdict'], label
        raw_archive = (directory / 'raw.tar.gz').read_bytes()
        assert sha(raw_archive) == result['raw_sha256'], label
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        members = {}
        with tarfile.open(fileobj=io.BytesIO(raw_archive)) as archive:
            for entry in archive:
                if not entry.isfile():
                    continue
                assert entry.name not in members, entry.name
                raw = archive.extractfile(entry).read()
                members[entry.name] = sha(raw)
                if not (entry.name.startswith('reports/') and entry.name.endswith('.xml') and Path(entry.name).name.startswith('TEST-')):
                    continue
                total_reports += 1
                suite = ET.fromstring(raw)
                parts = Path(entry.name).parts
                assert len(parts) == 4, entry.name
                properties = {
                    'pocoma.verification.id': result['invocation_id'],
                    'pocoma.verification.module': parts[1],
                    'pocoma.verification.producer': parts[2].removesuffix('-reports')}
                for key, value in properties.items():
                    found = suite.findall(f"properties/property[@name='{key}']")
                    assert len(found) == 1 and found[0].get('value') == value, (label, key)
                assert plan['expected_modules'][suite.get('name')] == parts[1], entry.name
                cases = suite.findall('testcase')
                actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                              errors=sum(c.find('error') is not None for c in cases),
                              skipped=sum(c.find('skipped') is not None for c in cases))
                assert actual == {k: int(suite.get(k, 0)) for k in counts}, entry.name
                for key in counts:
                    counts[key] += actual[key]
        assert members == result['artifacts'], label
        assert counts == result['counts'], (label, counts, result['counts'])
    variants = json.loads((HERE / 'Variant_Receipts.json').read_text())
    with tarfile.open(ROOT / variants['archive']) as archive:
        for receipt in variants['receipts']:
            line = archive.extractfile(receipt['member']).read().decode('utf-8').splitlines()[receipt['line'] - 1]
            assert line == receipt['observed_line'] and sha(line.encode()) == receipt['line_sha256'], receipt['case']
    for group in manifest['oracles']:
        assert group['status'] == 'PASS' and all(v == 'PASS' for v in group['tests'].values()), group['id']
    print('PASS: artifact hashes, exact archive members, fresh producer/module IDs, source commit and XML counters;',
          total_reports, 'XML reports checked')


if __name__ == '__main__':
    main()
