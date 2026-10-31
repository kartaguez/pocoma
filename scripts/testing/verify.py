#!/usr/bin/env python3
"""Small Maven command selector. Filtered feedback never replaces required unfiltered gates."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path(__file__).with_name('verification-campaigns.json')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources(root=ROOT):
    result = {}
    for path in (root / 'app').glob('*/src/test/**/*.java'):
        content = path.read_text()
        package = re.search(r'^package\s+([\w.]+);', content, re.M)
        if package:
            result.setdefault(path.stem, []).append((package[1] + '.' + path.stem, path))
    return result


def resolve(names, index):
    resolved = {}
    for name in names:
        matches = index.get(name, [])
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one source for {name}, found {len(matches)}')
        fqcn, path = matches[0]
        resolved[fqcn] = path
    return resolved


def plan(level, scope, config=None, root=ROOT):
    config = config or json.loads(CONFIG.read_text())
    base = ['./mvnw', '--batch-mode', '--no-transfer-progress']
    expected = {}
    selectors = []
    modules = []
    index = sources(root)
    if level == 'CERTIFICATION':
        if scope:
            raise ValueError('CERTIFICATION is unfiltered; no scope allowed')
        for scenario in config['quarantine'].values():
            resolved = resolve([scenario['class'].split('.')[-1]], index)
            content = resolved[scenario['class']].read_text()
            if not re.search(r'@Test\s+void\s+' + re.escape(scenario['method']) + r'\s*\(', content):
                raise ValueError('Required quarantined scenario is no longer an executable JUnit test')
            expected.setdefault(scenario['class'], []).append(scenario['method'])
    elif level == 'QUARANTINE':
        if scope not in config['quarantine']:
            raise ValueError('Choose a registered nominative quarantine scenario')
        scenario = config['quarantine'][scope]
        modules = [config['anchors'][scenario['anchor']]]
        matches = resolve([scenario['class'].split('.')[-1]], index)
        if scenario['class'] not in matches:
            raise ValueError('Quarantine class identity differs from source')
        expected = {scenario['class']: [scenario['method']]}
        selectors = [scenario['class'] + '#' + scenario['method']]
        if not re.search(r'\bvoid\s+' + re.escape(scenario['method']) + r'\s*\(', matches[scenario['class']].read_text()):
            raise ValueError('Quarantine method missing')
    elif scope == 'identity':
        definition = config['identity']
        modules = definition['fast_modules'] if level == 'FAST' else [config['anchors'][definition['anchor']]]
        names = []
        for step in ['FAST', 'CHANGE', 'INTEGRATION']:
            names += definition[step]
            if step == level:
                break
        if level == 'INTEGRATION':
            modules += definition['integration_extra_modules']
        for fqcn, path in resolve(names, index).items():
            # Review new test forms explicitly; boolean ValueSource is used by Identity.
            content = path.read_text()
            if re.search(r'@(TestFactory|RepeatedTest|TestTemplate|Nested)\b', content):
                raise ValueError(f'{fqcn}: extend collector for dynamic/parameterized/nested tests before selection')
            methods = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', content)
            if len(methods) != len(re.findall(r'@Test\b', content)):
                raise ValueError(f'{fqcn}: unreviewed JUnit method declaration')
            parameters = re.findall(r'@ParameterizedTest\s+@ValueSource\(booleans\s*=\s*\{true,\s*false\}\)\s+void\s+(\w+)\s*\(boolean\s+\w+\)', content)
            if len(parameters) != len(re.findall(r'@ParameterizedTest\b', content)):
                raise ValueError(f'{fqcn}: unreviewed parameterized test provider')
            methods += [f'{method}(boolean)[{n}]' for method in parameters for n in [1, 2]]
            if not methods:
                raise ValueError(f'{fqcn}: cannot determine required methods')
            expected[fqcn] = methods
        selectors = sorted(expected)
    else:
        if level == 'FAST':
            raise ValueError('FAST currently has one source-reviewed recipe: identity')
        scopes = (scope or '').split(',')
        if any(s not in config['anchors'] for s in scopes):
            raise ValueError('Use identity or comma-separated canonical slice identifiers')
        modules = [config['anchors'][s] for s in scopes]
    command = base + (['-pl', ','.join(modules), '-am'] if modules else [])
    if selectors:
        command += ['-Dtest=' + ','.join(selectors), '-Dsurefire.failIfNoSpecifiedTests=false']
    command += ['test']
    unselected_sources = []
    unselected_methods = {}
    if selectors:
        for matches in index.values():
            for fqcn, path in matches:
                content = path.read_text()
                if fqcn not in expected and re.search(r'@(Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\b', content):
                    unselected_sources.append(str(path.relative_to(root)))
                elif fqcn in expected:
                    methods = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', content)
                    omitted = sorted(set(methods) - set(expected[fqcn]))
                    if omitted:
                        unselected_methods[fqcn] = omitted
    return dict(level=level, scope=scope, command=command, expected=expected,
                filtered=bool(selectors), normative_gate=not selectors,
                not_selected_test_sources=sorted(unselected_sources),
                not_selected_methods_in_selected_classes=unselected_methods,
                added_permanent_exclusions=[],
                quarantine=config['quarantine'], config_sha256=digest(CONFIG))


def assess(reports, expected, exit_code, filtered):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    executions = []
    issues = []
    seen = collections.Counter()
    for name, raw in reports:
        try:
            suite = ET.fromstring(raw)
            declared = {k: int(suite.get(k, '0')) for k in counts}
            if suite.tag != 'testsuite':
                raise ValueError('Expected Surefire testsuite')
        except (ET.ParseError, ValueError) as error:
            issues.append(f'Invalid report {name}: {error}')
            continue
        cases = suite.findall('testcase')
        actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                      errors=sum(c.find('error') is not None for c in cases),
                      skipped=sum(c.find('skipped') is not None for c in cases))
        if actual != declared:
            issues.append(f'Inconsistent counters: {name}')
        for key in counts:
            counts[key] += actual[key]
        for case in cases:
            fqcn = case.get('classname', suite.get('name'))
            method = case.get('name')
            seen[(fqcn, method)] += 1
            problem = next((case.find(tag) for tag in ['failure', 'error', 'skipped'] if case.find(tag) is not None), None)
            executions.append(dict(classname=fqcn, method=method, seconds=float(case.get('time', '0')),
                outcome='PASS' if problem is None else {'failure':'FAIL', 'error':'ERROR', 'skipped':'SKIP'}[problem.tag],
                **({} if problem is None else {'diagnostic':problem.get('message', '')})))
            if filtered and (fqcn not in expected or method not in expected[fqcn]):
                issues.append(f'Unexpected execution: {fqcn}#{method}')
    for fqcn, methods in expected.items():
        for method in methods:
            if seen[(fqcn, method)] != 1:
                issues.append(f'Expected one fresh execution: {fqcn}#{method}, found {seen[(fqcn, method)]}')
    if any(count > 1 for count in seen.values()):
        issues.append('Duplicate method execution')
    if not counts['tests'] or counts['skipped']:
        issues.append('Absent or skipped tests: coverage incomplete')
    verdict = 'FAIL' if exit_code or counts['failures'] or counts['errors'] else ('INCONCLUSIVE' if issues else 'PASS')
    return dict(verdict=verdict, counts=counts, issues=issues, executions=executions)


def app_inputs():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', 'app'], cwd=ROOT).decode().split('\0')
    return {name: digest(ROOT / name) if (ROOT / name).is_file() else 'DELETED' for name in names if name}


def capture(command):
    proc = subprocess.run(command, cwd=ROOT / 'app', capture_output=True, text=True)
    return dict(command=command, exit_code=proc.returncode, output=proc.stdout + proc.stderr)


def run(definition, output):
    # One checkout's targets and physical PG campaigns must never be shared concurrently.
    lock = ROOT / 'app/.verification.lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise ValueError('Another verification owns app targets (or stale app/.verification.lock; inspect before removal)')
    try:
        output.mkdir(parents=True, exist_ok=False)
        (output / 'plan.json').write_text(json.dumps(definition, indent=2) + '\n')
        inputs = app_inputs()
        provenance = dict(head=capture(['git', 'rev-parse', 'HEAD']), status=capture(['git', 'status', '--short']),
            java=capture(['java', '-version']), maven=capture(['./mvnw', '-v']),
            docker=capture(['docker', 'version', '--format', '{{.Server.Version}}']),
            app_inputs=inputs, selector_sha256=digest(Path(__file__)),
            maven_settings_sha256=digest(Path.home()/'.m2/settings.xml') if (Path.home()/'.m2/settings.xml').is_file() else None)
        for key in ['MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS']:
            if os.environ.get(key):
                raise ValueError(f'{key} is set: remove implicit command configuration before reproducible verification')
        (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        # Move previous XML aside: wall-clock freshness alone cannot establish provenance.
        prior = []
        for path in (ROOT / 'app').glob('*/target/*-reports/TEST-*.xml'):
            destination = output / 'previous-reports' / path.relative_to(ROOT)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(path, destination)
            prior.append(str(path.relative_to(ROOT)))
        started = time.time_ns()
        print(' '.join(definition['command']), '\nEvidence:', output, flush=True)
        with (output / 'maven.log').open('wb') as log:
            rc = subprocess.run(definition['command'], cwd=ROOT / 'app', stdout=log, stderr=subprocess.STDOUT).returncode
        seconds = (time.time_ns() - started)/1e9
        reports = []
        artifacts = {}
        with tarfile.open(output / 'raw.tar.gz', 'w:gz') as bundle:
            bundle.add(output / 'maven.log', arcname='maven.log')
            for path in sorted((ROOT / 'app').glob('*/target/**/*')):
                if not path.is_file() or path.stat().st_mtime_ns < started:
                    continue
                relative = str(path.relative_to(ROOT))
                if '/surefire-reports/' in relative or '/failsafe-reports/' in relative or 'evidence' in relative.lower():
                    bundle.add(path, arcname=relative)
                    artifacts[relative] = digest(path)
                    if '/surefire-reports/TEST-' in relative and relative.endswith('.xml'):
                        reports.append((relative, path.read_bytes()))
        result = assess(reports, definition['expected'], rc, definition['filtered'])
        if app_inputs() != inputs:
            if result['verdict'] != 'FAIL':
                result['verdict'] = 'INCONCLUSIVE'
            result['issues'].append('Application inputs changed during execution')
        result.update(command=definition['command'], level=definition['level'], scope=definition['scope'],
            seconds=seconds, started_ns=started, exit_code=rc, artifacts=artifacts,
            raw_sha256=digest(output / 'raw.tar.gz'), previous_reports_not_counted=prior,
            not_selected='All methods outside plan.expected for filtered feedback; NOT RUN, never PASS',
            quarantined_scenarios=definition['quarantine'])
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ['verdict', 'counts', 'seconds', 'issues']}, ensure_ascii=False), flush=True)
        return 0 if result['verdict'] == 'PASS' else 1
    finally:
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('level', choices=['FAST', 'CHANGE', 'INTEGRATION', 'CERTIFICATION', 'QUARANTINE'])
    parser.add_argument('scope', nargs='?')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        definition = plan(args.level, args.scope)
        if args.dry_run:
            print(json.dumps(definition, indent=2))
            return 0
        output = args.output or Path(tempfile.gettempdir()) / ('pocoma-verify-' + str(time.time_ns()))
        return run(definition, output.resolve())
    except (ValueError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
