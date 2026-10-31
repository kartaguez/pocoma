#!/usr/bin/env python3
"""I4 native gates only: preserve unfiltered execution even for unreviewed JUnit forms.

The reviewed selector is reused for model/producer checks and ordinary test attribution.
Discovery limitations remain explicit; this collector cannot turn them into a PASS.
It does not modify verification recipes, Maven configuration, tests or their oracles.
"""
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('verify', ROOT / 'scripts/testing/verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def main():
    gate, destination = sys.argv[1:]
    anchors = {'command': 'runtime-command-consumption-worker,runtime-monolith',
               'architecture': 'architecture-tests'}[gate]
    v.reject_implicit_options()
    lock = ROOT / 'app/.verification.lock'
    lock.mkdir()  # Native Maven shares the same single-owner discipline.
    try:
        output = Path(destination).resolve()
        output.mkdir(parents=True, exist_ok=False)
        invocation = uuid.uuid4().hex
        command = ['./mvnw', '--batch-mode', '--no-transfer-progress', '-pl', anchors, '-am',
                   '-Pverification-provenance', '-Dpocoma.verification.id=' + invocation,
                   '-Dpocoma.verification.reports=' + str(output / 'reports'), 'test']
        plan = v.plan('INTEGRATION', 'COMMAND')
        plan.update(command=command, scope='I4 native ' + gate, invocation_id=invocation,
                    reports_root=str(output / 'reports'))
        inputs, controls = v.app_inputs(), v.control_inputs()
        sql_inputs = {str(p.relative_to(ROOT)): v.digest(p)
                      for p in sorted((ROOT / 'scripts/v3').glob('*.sql'))}
        model, issues = v.effective_inventory(plan, output)
        # Producer/configuration ambiguities still block execution. Source-discovery forms
        # unsupported by the selector are preserved as coverage limitations, never ignored.
        config_issues = [i for i in issues if any(word in i for word in (
            'Surefire', 'Failsafe', 'source root', 'provenance', 'duplicate source'))]
        if config_issues:
            raise ValueError('Unproven producer configuration: ' + '; '.join(config_issues))
        source_inventory = {}
        for project in model:
            for path in sorted(Path(project['test_root']).rglob('*.java')):
                content = v.java_structure(path.read_text())
                package = re.search(r'^package\s+([\w.]+);', content, re.M)
                if package:
                    name = package[1] + '.' + path.stem
                    plan['expected_modules'][name] = project['artifact']
                    source_inventory[name] = dict(path=str(path.relative_to(ROOT)), sha256=v.digest(path))
        plan['coverage_limitations'] = issues
        (output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
        (output / 'source-inventory.json').write_text(json.dumps(source_inventory, indent=2) + '\n')
        provenance = dict(head=v.capture(['git', 'rev-parse', 'HEAD']),
            status=v.capture(['git', 'status', '--short']), app_inputs=inputs, control_inputs=controls,
            external_sql_inputs=sql_inputs, collector_sha256=v.digest(Path(__file__)),
            java=v.capture(['java', '-version']), maven=v.capture(['./mvnw', '-v']),
            docker=v.capture(['docker', 'version', '--format', '{{.Server.Version}}']))
        (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        started = time.time_ns()
        print(' '.join(command), '\nEvidence:', output, flush=True)
        with (output / 'maven.log').open('wb') as log:
            rc = subprocess.run(command, cwd=ROOT / 'app', stdout=log, stderr=subprocess.STDOUT).returncode
        reports = []
        artifacts = {}
        allowed = {output / 'reports' / p['artifact'] / (producer + '-reports')
                   for p in model for producer in ['surefire', 'failsafe']}
        with tarfile.open(output / 'raw.tar.gz', 'w:gz') as archive:
            for filename in ['maven.log', 'effective-pom.xml', 'discovery.log', 'source-inventory.json']:
                archive.add(output / filename, arcname=filename)
                artifacts[filename] = v.digest(output / filename)
            for path in sorted((output / 'reports').rglob('*')):
                if path.is_dir():
                    continue
                if path.is_symlink() or path.parent not in allowed or path.stat().st_nlink != 1:
                    raise ValueError('Unauthorized producer location: ' + str(path))
                relative, raw = str(path.relative_to(output)), path.read_bytes()
                info = tarfile.TarInfo(relative)
                info.size = len(raw)
                archive.addfile(info, io.BytesIO(raw))
                artifacts[relative] = v.digest(path)
                if path.name.startswith('TEST-') and path.suffix == '.xml':
                    reports.append((relative, raw))
            for path in sorted((ROOT / 'app').glob('*/target/**/*')):
                if path.is_file() and path.stat().st_mtime_ns >= started:
                    relative = str(path.relative_to(ROOT))
                    if 'evidence' in relative.lower() and '-reports/' not in relative:
                        archive.add(path, arcname=relative)
                        artifacts[relative] = v.digest(path)
        result = v.assess(reports, plan['expected'], rc, False, plan)
        result['issues'] += issues
        if issues and result['verdict'] == 'PASS':
            result['verdict'] = 'INCONCLUSIVE'
        if inputs != v.app_inputs() or controls != v.control_inputs() or sql_inputs != {
                name: v.digest(ROOT / name) for name in sql_inputs}:
            result['issues'].append('Executable inputs changed during native gate')
            if result['verdict'] != 'FAIL':
                result['verdict'] = 'INCONCLUSIVE'
        result.update(command=command, exit_code=rc, invocation_id=invocation,
                      seconds=(time.time_ns() - started) / 1e9, started_ns=started,
                      scope=plan['scope'], artifacts=artifacts, raw_sha256=v.digest(output / 'raw.tar.gz'),
                      expected_invocations=sum(map(len, plan['expected'].values())),
                      source_discovery_limitations=issues,
                      compilation=v.compilation_metrics((output / 'maven.log').read_text(), model))
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ['verdict', 'counts', 'seconds']}, ensure_ascii=False), flush=True)
        return 0 if result['verdict'] == 'PASS' else 1
    finally:
        lock.rmdir()


if __name__ == '__main__':
    raise SystemExit(main())
