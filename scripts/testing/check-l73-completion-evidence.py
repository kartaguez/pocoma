#!/usr/bin/env python3
"""Read-only validation of the L7.3 completion allocation and immutable receipts."""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT/'docs/testing/evidence/L7-I3-completion'
OLD = ROOT/'docs/testing/evidence/L7-I2-I3/full-after-w19'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    initial = json.loads((EVIDENCE/'initial-classification.json').read_text())
    final = json.loads((EVIDENCE/'reconciliation.json').read_text())
    expected = {r['ID'] for r in initial['obligations']}
    assert len(expected) == len(final['obligations']) == 53
    assert expected == {r['ID'] for r in final['obligations']}
    gate = json.loads((OLD/'result.json').read_text())
    assert gate['verdict'] == 'PASS'
    assert digest(OLD/'raw.tar.gz') == gate['raw_sha256'] == final['archiveSha256']
    with tarfile.open(OLD/'raw.tar.gz') as archive:
        for row in final['obligations']:
            for proof in row['existingEvidence']:
                source = subprocess.check_output(['git', 'show', initial['baseline']+':'+proof['source']], cwd=ROOT)
                assert hashlib.sha256(source).hexdigest() == proof['sourceSha256']
                for record in proof['verifiedXml']:
                    xml = archive.extractfile(record['member']).read()
                    assert hashlib.sha256(xml).hexdigest() == record['sha256']
                    suite = ET.fromstring(xml)
                    assert suite.find("./properties/property[@name='pocoma.verification.id']").get('value') == gate['invocation_id']
                    cls, method = proof['selector'].split('.', 1)
                    matches = [c for c in suite.findall('testcase') if c.get('classname').endswith('.'+cls)
                               and (c.get('name') == method or c.get('name').startswith(method+'['))]
                    assert len(matches) == proof['invocations']
                    assert all(not any(c.find(tag) is not None for tag in ('failure','error','skipped')) for c in matches)
                for physical in proof['physicalArtifacts']:
                    assert hashlib.sha256(archive.extractfile(physical['member']).read()).hexdigest() == physical['sha256']
    target = json.loads((EVIDENCE/'discovery-01/receipt.json').read_text())
    assert target['verdict'] == 'PASS' and target['counts'] == dict(tests=3,failures=0,errors=0,skipped=0)
    import gzip
    for name, expected_digest in target['artifacts'].items():
        data=(EVIDENCE/'discovery-01'/name).read_bytes()
        if name.endswith('.gz'):data=gzip.decompress(data)
        assert hashlib.sha256(data).hexdigest()==expected_digest
    admin = json.loads((EVIDENCE/'historical-admin-receipt.json').read_text())
    assert admin['status'] == 'PASS' and admin['cases'] == 11
    for path, expected_digest in admin['sourceSha256'].items():
        assert digest(ROOT/path) == expected_digest
    matrix = list(csv.DictReader((ROOT/'docs/v3/L7_I1_Certification_Coverage_Matrix.csv').open()))
    assert len(matrix) == len({r['ID'] for r in matrix}) == 522
    byid={r['ID']:r for r in matrix}
    for row in final['obligations']:
        assert byid[row['ID']]['Statut L7.2–L7.3'] == row['finalStatus']
    changed = subprocess.check_output(['git','diff','--name-only',initial['baseline'],'--','app'],cwd=ROOT,text=True).splitlines()
    assert changed == ['app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/v3/identity/IdentityDiscoveryPostgresTest.java'], changed
    print('PASS: 53 allocations, archived XML/SQL digests, targeted receipts and 522 matrix IDs verified; no runtime or database mutation.')


if __name__ == '__main__':
    main()
