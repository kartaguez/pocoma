#!/usr/bin/env python3
"""Read archived I1 evidence only; never starts Maven, Docker or PostgreSQL."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / 'docs/testing/evidence/L2-I1'
DELIVERY = 'a2e6814f6ebf8d2ee87d88d16ad2dfa4fe07f512'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def digest(content):
    return hashlib.sha256(content).hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def check():
    index = read_json(EVIDENCE / 'Gate-index.json')
    maps, gates = {}, {}
    for gate, entry in index['gates'].items():
        archive = EVIDENCE / entry['archive']['file']
        assert archive.stat().st_size == entry['archive']['bytes']
        assert digest(archive.read_bytes()) == entry['archive']['sha256']
        summary = read_json(EVIDENCE / entry['summary'])
        assert summary['code_sha'] == entry['tested_sha']
        assert summary['command'] == entry['command']
        assert summary['exit_code'] == 0
        assert summary['totals'] == entry['totals']
        methods, skipped = {}, []
        totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                if not member.name.endswith('.xml'):
                    continue
                suite = ET.fromstring(tar.extractfile(member).read())
                for name in totals:
                    totals[name] += int(suite.get(name, 0))
                for case in suite.iter('testcase'):
                    key = (case.get('classname'), case.get('name'))
                    status = ('SKIP' if case.find('skipped') is not None else
                              'FAIL' if case.find('failure') is not None or
                              case.find('error') is not None else 'PASS')
                    methods.setdefault(key, []).append(status)
                    if status == 'SKIP':
                        skipped.append('.'.join(key))
        assert totals == entry['totals'], (gate, totals)
        for suite in summary['suites']:
            for method in suite['methods']:
                assert methods[(method['class'], method['method'])] == [method['status']]
        maps[gate] = methods
        gates[gate] = {'tested_sha': entry['tested_sha'], 'command': entry['command'],
                       'archive_sha256': entry['archive']['sha256'],
                       'xml_totals': totals, 'skips': skipped}
    local = read_json(EVIDENCE / 'I1-methods.json')['methods']
    assert len(local) == 33 and len({(m['class'], m['method']) for m in local}) == 33
    java_files = [p for p in git('ls-tree', '-r', '--name-only', DELIVERY, 'app').decode().splitlines()
                  if p.endswith('.java')]
    for method in local:
        candidates = [p for p in java_files if p.endswith('/' + method['class'].split('.')[-1] + '.java')]
        assert len(candidates) == 1, method
        assert (method['method'] + '(').encode() in git('show', DELIVERY + ':' + candidates[0])
        for observation in method['observations']:
            assert observation['status'] == 'PASS'
            assert maps[observation['gate']][(method['class'], method['method'])] == ['PASS']
    regressions = read_json(EVIDENCE / 'L1-regression.json')['manifests']
    for name, manifest in regressions.items():
        assert digest((ROOT / 'docs/v3' / name).read_bytes()) == manifest['manifest_sha256']
        for method in manifest['methods']:
            for observation in method['observations']:
                assert observation['status'] == 'PASS'
                assert maps[observation['gate']][(method['class'], method['method'])] == ['PASS']
    inputs = read_json(EVIDENCE / 'environment-and-inputs.json')
    conversions = []
    for name, expected in inputs['tested_inputs_sha256'].items():
        content = git('show', inputs['tested_code_sha'] + ':' + name)
        if digest(content) != expected:
            # This exact file has a checked-in eol=crlf attribute; no generic normalization.
            assert name == 'app/mvnw.cmd'
            assert b'eol: crlf' in git('check-attr', 'eol', '--', name)
            assert digest(content.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')) == expected
            conversions.append({'path': name, 'reason': 'Git eol=crlf checkout', 'sha256': expected})
    arch = read_json(EVIDENCE / 'architecture-inventory-inputs.json')
    delta = git('diff', '--name-only', inputs['tested_code_sha'], DELIVERY, '--', 'app', 'scripts').decode().splitlines()
    assert set(delta) == set(arch['changed_files'])
    for name, expected in arch['changed_files'].items():
        assert digest(git('show', arch['test_sha'] + ':' + name)) == expected
        assert git('show', arch['test_sha'] + ':' + name) == git('show', DELIVERY + ':' + name)
    assert git('diff', '--binary', inputs['tested_code_sha'], arch['test_sha'], '--', 'app').decode() == arch['diff']
    return {'checked_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'scope': 'Archived I1 evidence/provenance recheck only; no new application test PASS',
            'delivery_I1_sha': DELIVERY, 'gates': gates,
            'I1_unique_methods_present_in_source_and_PASS_in_actual_XML': len(local),
            'L1_manifest_methods_PASS_in_actual_XML': {k: len(v['methods']) for k, v in regressions.items()},
            'tested_input_hashes_verified': len(inputs['tested_inputs_sha256']),
            'checkout_conversions': conversions, 'only_app_scripts_delta_to_delivery': delta,
            'maven_postgres_campaigns_rerun': False,
            'G1_G2_G3': 'FUTURE', 'L2_global_acceptance': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(check(), ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(result)
        print('Archived I1 recheck OK; report:', args.output)
    else:
        print(result, end='')
