#!/usr/bin/env python3
"""Fresh L1-F2 bounded COMMAND gate. Reuse existing assessment/external-input checks, preserve L1 history."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/testing' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


L1 = module('l1', 'verify-v3-l1.py')
ASSESS = module('assessment', 'summarize-v3.py')


def inputs():
    # Git checks content and modes against the actual immutable HEAD, not a declared digest alone.
    subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', '.', ':!docs/testing/evidence/L1-F2'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    tracked = set(subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', 'HEAD'], cwd=ROOT).decode().splitlines())
    result = {}
    for name in sorted(set(names)):
        if not name or name.startswith('docs/testing/evidence/L1-F2/'):
            continue
        if name not in tracked:
            raise RuntimeError('Untracked input: ' + name)
        path = ROOT / name
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def run(gate):
    expected_manifest = json.loads((ROOT / 'docs/v3/L1_F2_Proof_Manifest.json').read_text())
    definition = expected_manifest['gates'][gate]
    historic = json.loads((ROOT / 'docs/v3/L1_Proof_Manifest.json').read_text())
    old_gate = historic['gates'][definition['regression_gate']]
    expected = {(historic['suites'][key]['class'], method) for key in old_gate['suites'] for method in historic['suites'][key]['methods']}
    expected |= {(suite['class'], method) for suite in expected_manifest['corrections'] for method in suite['methods']}
    sha = L1.git('rev-parse', 'HEAD').decode().strip()
    before = inputs()
    output = ROOT / 'docs/testing/evidence/L1-F2' / (gate + '-' + str(time.time_ns()))
    output.mkdir(parents=True)
    (output / 'inputs-before.json').write_text(json.dumps(before, indent=2))
    external = L1.external_inputs()
    (output / 'external-before.json').write_text(json.dumps(external, indent=2))
    environment = {key: L1.capture(cmd) for key, cmd in {
        'java': ['java', '-version'], 'maven': ['./app/mvnw', '-version'],
        'postgres_image': ['docker', 'image', 'inspect', 'postgres:17-alpine', '--format', '{{json .RepoDigests}}'],
        'docker': ['docker', 'version', '--format', '{{.Server.Version}}']}.items()}
    (output / 'environment.json').write_text(json.dumps(environment, indent=2))
    for target in (ROOT / 'app').glob('*/target'):
        shutil.rmtree(target)
    started = time.time_ns()
    with (output / 'maven.log').open('wb') as log:
        execution = subprocess.run(definition['command'], cwd=ROOT / 'app', stdout=log, stderr=subprocess.STDOUT)
    ended = time.time_ns()
    after = inputs()
    if after != before or L1.git('rev-parse', 'HEAD').decode().strip() != sha:
        raise RuntimeError('Inputs/HEAD changed during gate')
    (output / 'inputs-after.json').write_text(json.dumps(after, indent=2))
    reports, jars, classpaths = [], {}, set()
    for path in (ROOT / 'app').glob('*/target/surefire-reports/TEST-*.xml'):
        content = path.read_bytes()
        relative = path.relative_to(ROOT)
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        reports.append((str(relative), content, path.stat().st_mtime_ns))
        for prop in ET.fromstring(content).findall('./properties/property'):
            if prop.get('name') not in ('java.class.path', 'surefire.test.class.path'):
                continue
            for entry in prop.get('value', '').split(os.pathsep):
                dependency = Path(entry)
                if dependency.suffix == '.jar' and dependency.is_file():
                    jars[entry] = L1.digest(dependency.read_bytes())
                elif entry:
                    resolved = dependency.resolve()
                    if not resolved.is_relative_to(ROOT / 'app') or '/target/' not in str(resolved) or not str(resolved).endswith(('/classes', '/test-classes')):
                        raise RuntimeError('Uninventoried classpath: ' + entry)
                    classpaths.add(entry)
    external_after = L1.external_inputs()
    external_verdict = L1.verify_external(external, external_after, jars)
    for name, value in [('external-after', external_after), ('external-verdict', external_verdict), ('dependencies', jars), ('classpaths', sorted(classpaths))]:
        (output / (name + '.json')).write_text(json.dumps(value, indent=2))
    generated = {str(path.relative_to(ROOT)): L1.digest(path.read_bytes()) for pattern in ('*/target/classes/**/*', '*/target/test-classes/**/*') for path in (ROOT / 'app').glob(pattern) if path.is_file()}
    (output / 'generated-inputs.json').write_text(json.dumps(generated, indent=2))
    for path in (ROOT / 'app').glob('*/target/v3-pot-evidence'):
        shutil.copytree(path, output / path.relative_to(ROOT))
    summary = ASSESS.assess_reports(reports, expected, started, execution.returncode)
    actual = {(item['classname'], item['method']) for item in summary['executions'] if item['outcome'] == 'PASS'}
    summary.update(gate=gate, tested_sha=sha, command=definition['command'], started_ns=started, ended_ns=ended,
                   maven_exit_code=execution.returncode, mandatory_count=len(expected),
                   missing_mandatory=sorted(expected - actual),
                   inputs_sha256=L1.digest(json.dumps(before, sort_keys=True).encode()))
    performance = json.loads((output / 'app/infra-persistence-primary-jpa/target/v3-pot-evidence/F2-write-performance.json').read_text())
    bounded = all(sample['java_rows'] == expected_manifest['proofs']['expected_java_rows_per_equivalent_mutation']
                  for profile in performance['profiles'] for sample in profile['samples'])
    indexed = True
    for profile in performance['profiles']:
        if profile['depth'] < 1000:
            continue
        checks = [q for q in profile['plans'] if q['sql'].startswith('select c.*') and 'v3_pot_details c' in q['sql']]
        indexed &= len(checks) == 1
        for query in checks:
            plan = query['plan'][0]['Plan']
            indexed &= plan['Actual Rows'] == 1 and plan.get('Shared Hit Blocks', 0) + plan.get('Shared Read Blocks', 0) <= 32
            encoded = json.dumps(plan)
            indexed &= 'v3_pot_details_open' in encoded and 'v3_pot_details_interval' in encoded
    summary['bounded_java_history_transfer'] = 'PASS' if bounded else 'FAIL'
    summary['indexed_local_write_plans'] = 'PASS' if indexed else 'FAIL'
    summary['mandatory_verdict'] = 'PASS' if execution.returncode == 0 and not expected - actual and bounded and indexed else 'FAIL'
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({key: summary[key] for key in ('gate', 'tested_sha', 'counts', 'verdict', 'mandatory_count', 'mandatory_verdict', 'missing_mandatory')}), flush=True)
    print(output, flush=True)
    return 0 if summary['mandatory_verdict'] == 'PASS' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gate', choices=['command'])
    sys.exit(run(parser.parse_args().gate))
