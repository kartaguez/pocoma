#!/usr/bin/env python3
"""Extract observed test costs without executing tests or inferring normative coverage."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def inventory(archive):
    receipt_path = archive.with_name('canonical-final.json')
    receipt = json.loads(receipt_path.read_text())
    archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
    if archive_hash != receipt['archive']['sha256']:
        raise ValueError('Historical archive does not match its preserved receipt')
    sources = {}
    for path in sorted((ROOT / 'app').glob('*/src/test/**/*.java')):
        content = path.read_text()
        # Technical dependencies are source observations, never a coverage verdict.
        dependencies = [label for marker, label in [
            ('org.testcontainers', 'Testcontainers'), ('org.flywaydb', 'Flyway'),
            ('org.springframework', 'Spring'), ('java.sql', 'JDBC'),
            ('java.util.concurrent', 'concurrency primitives'), ('archunit', 'ArchUnit')]
            if marker in content]
        sources[path.stem] = dict(source=str(path.relative_to(ROOT)),
            source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(), dependencies=dependencies)
    suites = []
    with tarfile.open(archive) as bundle:
        for member in bundle:
            if '/surefire-reports/TEST-' not in member.name or not member.name.endswith('.xml'):
                continue
            raw = bundle.extractfile(member).read()
            if hashlib.sha256(raw).hexdigest() != receipt['members'][member.name]['sha256']:
                raise ValueError(f'Historical XML mismatch: {member.name}')
            suite = ET.fromstring(raw)
            cases = []
            for case in suite.findall('testcase'):
                problem = next((case.find(tag) for tag in ['failure', 'error', 'skipped']
                                if case.find(tag) is not None), None)
                cases.append(dict(method=case.get('name'), seconds=float(case.get('time', '0')),
                    outcome='PASS' if problem is None else {'failure':'FAIL','error':'ERROR','skipped':'SKIP'}[problem.tag],
                    **({} if problem is None else {'diagnostic': problem.get('message', '')})))
            name = suite.get('name')
            suites.append(dict(module=member.name.split('/target/')[0].split('/')[-1],
                classname=name, seconds=float(suite.get('time', '0')), methods=cases,
                evidence_member=member.name, evidence_sha256=hashlib.sha256(raw).hexdigest(),
                current_source=sources.get(name.split('.')[-1], {})))
    observed = {s['classname'].split('.')[-1] for s in suites}
    return dict(archive=str(archive.relative_to(ROOT)), archive_sha256=archive_hash,
        receipt_sha256=hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
        tested_sha=receipt['head_at_capture'],
        provenance='Historical L3-I2 canonical-final; current sources listed separately, no equivalence claimed',
        suite_seconds=sum(s['seconds'] for s in suites),
        suites=sorted(suites, key=lambda s: -s['seconds']),
        sources_without_observation=[v for k,v in sources.items() if k not in observed])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = inventory(ROOT / 'docs/testing/evidence/L3-I2/canonical-final.tar.gz')
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(suites=len(data['suites']), tests=sum(len(s['methods']) for s in data['suites']),
        suite_seconds=data['suite_seconds'], sources_without_observation=len(data['sources_without_observation']))))


if __name__ == '__main__':
    main()
