#!/usr/bin/env python3
"""Small Maven command selector. Filtered feedback never replaces required unfiltered gates."""
import argparse
import collections
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path(__file__).with_name('verification-campaigns.json')
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
TEST_ANNOTATION = r'@(?:[\w.]+\.)?(Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\b'


def java_structure(content):
    # Preserve line structure; strings/comments cannot introduce fake annotations
    # or braces. This is a restricted source inventory, not a Java parser.
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: re.sub(r'[^\n]', ' ', m[0]), content)


def validate_annotations(fqcn, content):
    allowed = {'Test', 'ParameterizedTest', 'ValueSource', 'Testcontainers', 'Container',
               'BeforeAll', 'AfterAll', 'BeforeEach', 'AfterEach', 'Override',
               'SuppressWarnings', 'Configuration', 'TestConfiguration', 'Bean',
               'Qualifier', 'Autowired', 'Disabled', 'Tag', 'Timeout',
               'FunctionalInterface', 'SafeVarargs', 'Entity', 'Table', 'Id', 'Column',
               'SpringBootTest', 'SpringBootApplication', 'SpringBootConfiguration', 'DataJpaTest',
               'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import',
               'DynamicPropertySource', 'DirtiesContext', 'Primary', 'ActiveProfiles', 'Profile',
               'Sql', 'Transactional', 'LocalServerPort', 'MockitoBean', 'MockitoSpyBean', 'TestBean',
               'RestController', 'PostMapping', 'Order', 'TestMethodOrder', 'TempDir',
               'EnabledIfSystemProperty', 'RepeatedTest', 'EnumSource', 'Component'}
    unknown = {name.split('.')[-1] for name in re.findall(r'@([\w.]+)', content)} - allowed
    if unknown:
        raise ValueError(f'{fqcn}: unreviewed annotations (possible composed tests): {sorted(unknown)}')


def ancestor_body(name, package, index):
    candidates = []
    for matches in index.values():
        for identity, path in matches:
            if identity.rsplit('.', 1)[0] != package:
                continue
            content = java_structure(path.read_text())
            match = re.search(r'\bclass\s+' + re.escape(name) + r'\b[^\{]*\{', content)
            if not match:
                continue
            depth = 1
            end = match.end()
            while depth and end < len(content):
                depth += (content[end] == '{') - (content[end] == '}')
                end += 1
            if depth:
                raise ValueError(f'{name}: unbalanced test ancestor')
            candidates.append(content[match.start():end])
    if len(candidates) != 1:
        raise ValueError(f'{name}: unresolved/ambiguous test ancestor')
    return candidates[0]


def required_methods(fqcn, path):
    """Exact invocation identities for reviewed JUnit forms, never a total estimate.

    The source is the obligation, not a previous (possibly filtered) XML report.
    Unknown providers, composed annotations and inheritance of tests fail closed.
    """
    content = java_structure(path.read_text()) if isinstance(path, Path) else path
    validate_annotations(fqcn, content)
    if re.search(r'@(?:[\w.]+\.)?(TestFactory|TestTemplate|Nested|Disabled)\b', content):
        raise ValueError(f'{fqcn}: unsupported dynamic/nested/disabled discovery; no coverage proof')

    test_decls = re.findall(r'@(?:[\w.]+\.)?Test\b(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(', content)
    if len(test_decls) != len(re.findall(r'@(?:[\w.]+\.)?Test\b', content)):
        raise ValueError(f'{fqcn}: unreviewed JUnit method declaration')
    methods = list(test_decls)

    repeated = re.findall(r'@(?:[\w.]+\.)?RepeatedTest\s*\(\s*(\d+)\s*\)(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(', content)
    if len(repeated) != len(re.findall(r'@(?:[\w.]+\.)?RepeatedTest\b', content)):
        raise ValueError(f'{fqcn}: unreviewed RepeatedTest declaration')
    for count_str, name in repeated:
        methods += [f'{name}()[{n}]' for n in range(1, int(count_str) + 1)]

    param_tests = re.findall(r'@(?:[\w.]+\.)?ParameterizedTest\b(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(([^)]*)\)', content)
    if len(param_tests) != len(re.findall(r'@(?:[\w.]+\.)?ParameterizedTest\b', content)):
        raise ValueError(f'{fqcn}: unreviewed ParameterizedTest declaration')

    for name, param_decl in param_tests:
        m = re.search(r'(@(?:[\w.]+\.)?ParameterizedTest(?:(?!@(?:[\w.]+\.)?ParameterizedTest)[\s\S])*?void\s+' + re.escape(name) + r'\s*\([^)]*\))', content)
        decl_block = m.group(1) if m else ''
        param_type = param_decl.strip().split()[0].split('.')[-1]

        val_match = re.search(r'@(?:[\w.]+\.)?ValueSource\s*\(\s*(\w+)\s*=\s*\{([^}]*)\}\s*\)', decl_block)
        enum_match = re.search(r'@(?:[\w.]+\.)?EnumSource\s*\(([^)]*)\)', decl_block)
        if val_match:
            kind, vals = val_match.group(1), val_match.group(2)
            count = len(vals.split(','))
            methods += [f'{name}({param_type})[{n}]' for n in range(1, count + 1)]
        elif enum_match:
            args = enum_match.group(1)
            names_match = re.search(r'names\s*=\s*\{([^}]*)\}', args)
            if names_match:
                count = len(names_match.group(1).split(','))
            elif 'Mutation' in args:
                count = 3
            elif 'EventFamily' in args:
                count = 4
            else:
                raise ValueError(f'{fqcn}: unknown EnumSource in {name}: {args}')
            methods += [f'{name}({param_type})[{n}]' for n in range(1, count + 1)]
        else:
            raise ValueError(f'{fqcn}: unreviewed parameterized test provider for {name}')

    if not methods or len(methods) != len(set(methods)):
        raise ValueError(f'{fqcn}: cannot determine unique required invocations')
    return methods


def reject_implicit_options():
    for key in ['MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                'MAVEN_BASEDIR', 'MAVEN_PROJECTBASEDIR']:
        if os.environ.get(key):
            raise ValueError(f'{key} is set: remove implicit command configuration before reproducible verification')
    # These files can inject -Dtest, profiles, skips or JVM options as well.
    for name in ['maven.config', 'jvm.config', 'extensions.xml']:
        path = ROOT / 'app/.mvn' / name
        if path.is_file() and path.read_text().strip():
            raise ValueError(f'{path}: implicit Maven configuration requires review before verification')


def control_inputs():
    paths = [Path(__file__), CONFIG, Path.home() / '.m2/settings.xml']
    return {str(p): digest(p) if p.is_file() else None for p in paths}


class VerificationInterrupted(Exception):
    pass


class Measurements:
    """Incremental monotonic receipts; forecasts never control process lifetime."""
    def __init__(self, output, forecast=None):
        self.output = output
        self.started = time.monotonic()
        self.phases = {}
        self.forecast = forecast or dict(seconds=None, basis='UNKNOWN', uncertainty='UNKNOWN')

    def receipt(self):
        seconds = time.monotonic() - self.started
        predicted = self.forecast.get('seconds')
        return dict(phases=self.phases, campaign_seconds=seconds, forecast=self.forecast,
                    forecast_delta_seconds=None if predicted is None else seconds - predicted,
                    forecast_exceeded=None if predicted is None else seconds > predicted,
                    forecast_is_blocking=False)

    def save(self):
        receipt = self.receipt()
        (self.output / 'timings.json').write_text(json.dumps(receipt, indent=2) + '\n')
        return receipt

    @contextmanager
    def phase(self, name):
        started = time.monotonic()
        self.phases[name] = dict(status='RUNNING', seconds=0)
        self.save()
        try:
            yield
        except BaseException:
            self.phases[name]['status'] = 'INTERRUPTED_OR_FAILED'
            raise
        else:
            self.phases[name]['status'] = 'FINISHED'
        finally:
            self.phases[name]['seconds'] = time.monotonic() - started
            self.save()


def partial_reports(output, definition):
    """Retain observed counts after interruption; incomplete XML cannot prove PASS."""
    reports = []
    artifacts = {}
    root = output / 'reports'
    directories = [root / artifact / 'surefire-reports'
                   for artifact in set(definition.get('expected_modules', {}).values())]
    paths = []
    for directory in directories:
        if any(p.is_symlink() for p in [root, directory.parent, directory]):
            continue
        paths += sorted(directory.glob('*'))
    for path in paths:
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1:
            continue
        relative = str(path.relative_to(output))
        raw = path.read_bytes()
        artifacts[relative] = hashlib.sha256(raw).hexdigest()
        if path.name.startswith('TEST-') and path.suffix == '.xml':
            reports.append((relative, raw))
    result = assess(reports, definition['expected'], 1, definition['filtered'], definition)
    result.update(partial=True, artifacts=artifacts)
    return result


def interrupt(signum, frame):
    raise VerificationInterrupted(f'Interrupted by signal {signum}; Maven execution not completed')


def execute_maven(command, log):
    # Keep the checkout locked until Maven and its forked JVM process group stop.
    with subprocess.Popen(command, cwd=ROOT / 'app', stdout=log,
                          stderr=subprocess.STDOUT, start_new_session=True) as process:
        process_path = Path(log.name).with_suffix('.process.json')
        metadata = dict(pid=process.pid, process_group=process.pid, command=command)
        process_path.write_text(json.dumps(metadata, indent=2) + '\n')
        try:
            return process.wait()
        except BaseException:
            # Capture process identities before termination; no inferred stall timeout.
            try:
                metadata['interrupted_processes'] = subprocess.run(
                    ['ps', '-axo', 'pid,ppid,pgid,stat,etime,comm'],
                    capture_output=True, text=True, timeout=5).stdout
                process_path.write_text(json.dumps(metadata, indent=2) + '\n')
            except (OSError, subprocess.TimeoutExpired):
                pass
            for sig in [signal.SIGTERM, signal.SIGKILL]:
                try:
                    os.killpg(process.pid, sig)
                except ProcessLookupError:
                    break
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    continue
                # Also terminate any surviving forks before releasing the lock.
                if sig == signal.SIGTERM:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                break
            raise


def producer_config(artifact, producer, definition):
    """Exact effective producer contract, including per-module attribution."""
    directory = Path(definition['reports_root']) / artifact / (producer + '-reports')
    return dict(reportsDirectory=str(directory), enablePropertiesElement='true',
                systemPropertyVariables={
                    'pocoma.verification.id': definition['invocation_id'],
                    'pocoma.verification.module': artifact,
                    'pocoma.verification.producer': producer})


def config_value(item):
    return {child.tag.split('}')[-1]: child.text for child in item} if len(item) else item.text


def effective_inventory(definition, output):
    """Let Maven select the reactor; independently enumerate its source obligations.

    Keep the effective model as proof. Unreviewed discovery configuration never
    certifies a gate, even if Maven returns zero. Dry-run intentionally runs no Maven.
    """
    command = definition['command'][:-1] + ['help:effective-pom', '-Doutput=' + str(output / 'effective-pom.xml')]
    with (output / 'discovery.log').open('wb') as log:
        rc = execute_maven(command, log)
    if rc:
        raise ValueError(f'Maven effective-model discovery failed ({rc}); see {output}/discovery.log; no PASS')
    document = ET.parse(output / 'effective-pom.xml').getroot()
    projects = list(document) if document.tag == 'projects' else [document]
    model = []
    issues = []
    module_paths = {ET.parse(p).getroot().findtext('m:artifactId', '', NS): p.parent for p in (ROOT / 'app').glob('*/pom.xml')}
    found = {}
    expected_modules = {}
    index = sources()
    for project in projects:
        if project.findtext('m:packaging', 'jar', NS) == 'pom':
            continue
        test_root = Path(project.findtext('m:build/m:testSourceDirectory', '', NS))
        module = module_paths.get(project.findtext('m:artifactId', '', NS))
        if module is None or test_root != module / 'src/test/java':
            issues.append(f'Unreviewed test source root: {test_root}')
            continue
        artifact_id = project.findtext('m:artifactId', '', NS)
        model.append(dict(module=module.name, artifact=artifact_id,
            main_root=project.findtext('m:build/m:sourceDirectory', '', NS), test_root=str(test_root)))
        for plugin in project.findall('m:build/m:plugins/m:plugin', NS):
            artifact = plugin.findtext('m:artifactId', '', NS)
            if artifact == 'maven-failsafe-plugin':
                issues.append(f'{module.name}: Failsafe lifecycle discovery requires review; CLI currently executes test only')
            if artifact == 'maven-surefire-plugin':
                configs = [plugin.find('m:configuration', NS)] + plugin.findall('m:executions/m:execution/m:configuration', NS)
                # POM's existing standard patterns + L2ManifestProof are reviewed.
                for config in configs:
                    for item in [] if config is None else config:
                        tag = item.tag.split('}')[-1]
                        patterns = [p.text for p in item]
                        contract = producer_config(artifact_id, 'surefire', definition) if 'invocation_id' in definition else {}
                        if tag in contract and config_value(item) == contract[tag]:
                            continue
                        if tag != 'includes' or patterns != ['**/Test*.java', '**/*Test.java', '**/*Tests.java', '**/*TestCase.java', '**/L2ManifestProof.java']:
                            issues.append(f'{module.name}: unreviewed effective Surefire configuration: {tag}')
                executions = plugin.findall('m:executions/m:execution', NS)
                if len(executions) != 1 or executions[0].findtext('m:id', '', NS) != 'default-test':
                    issues.append(f'{module.name}: unreviewed Surefire executions')
                if 'invocation_id' in definition:
                    contract = producer_config(artifact_id, 'surefire', definition)
                    for config in configs:
                        actual = {} if config is None else {c.tag.split('}')[-1]: config_value(c) for c in config}
                        if any(actual.get(key) != value for key, value in contract.items()):
                            issues.append(f'{module.name}: producer provenance configuration missing or overridden')
        for path in sorted(test_root.rglob('*.java')):
            content = java_structure(path.read_text())
            if not re.search(TEST_ANNOTATION, content):
                # Composed/inherited test classes must not be silently treated as helpers.
                if re.fullmatch(r'Test.*|.*Tests?|.*TestCase', path.stem) and not re.search(r'\babstract\s+class\b', content):
                    issues.append(f'{path}: cannot establish JUnit discovery')
                continue
            package = re.search(r'^package\s+([\w.]+);', content, re.M)
            if not package:
                issues.append(f'{path}: missing package identity')
                continue
            class_matches = list(re.finditer(r'(?:^|\n)(?:@[\w.()]+\s+)*(?:public\s+)?class\s+(\w+)[^{]*\{', content))
            classes = []
            for cm in class_matches:
                cls_name = cm.group(1)
                start = cm.end()
                depth, end = 1, start
                while depth and end < len(content):
                    if content[end] == '{': depth += 1
                    elif content[end] == '}': depth -= 1
                    end += 1
                cls_body = content[cm.start():end]
                if re.search(TEST_ANNOTATION, cls_body):
                    classes.append((cls_name, cls_body))
            if not classes:
                classes = [(path.stem, content)]
            for cls_name, cls_content in classes:
                if definition['filtered'] and cls_name not in {name.split('.')[-1] for name in definition['expected']}:
                    continue
                fqcn = package[1] + '.' + cls_name
                if definition['filtered'] and fqcn not in definition['expected']:
                    continue
                try:
                    methods = required_methods(fqcn, cls_content)
                    # Ancestor helpers may contain fixtures, but not hidden test obligations.
                    parent = re.search(r'\bclass\s+' + re.escape(cls_name) + r'\s+extends\s+(\w+)', cls_content)
                    visited = set()
                    while parent:
                        name = parent[1]
                        if name in visited:
                            raise ValueError(f'{fqcn}: cyclic test inheritance')
                        visited.add(name)
                        ancestor = ancestor_body(name, fqcn.rsplit('.', 1)[0], index)
                        validate_annotations(fqcn, ancestor)
                        if re.search(TEST_ANNOTATION, ancestor):
                            raise ValueError(f'{fqcn}: inherited test discovery requires review')
                        parent = re.search(r'\bclass\s+' + re.escape(name) + r'\s+extends\s+(\w+)', ancestor)
                    if fqcn in found:
                        raise ValueError(f'{fqcn}: duplicate source identity')
                    found[fqcn] = methods
                    expected_modules[fqcn] = artifact_id
                except ValueError as error:
                    issues.append(str(error))
    if definition['filtered']:
        for fqcn, methods in definition['expected'].items():
            if fqcn not in found or not set(methods) <= set(found[fqcn]):
                issues.append(f'{fqcn}: recipe obligations absent from effective reactor sources')
    else:
        # CERTIFICATION's required quarantine obligations also survive discovery.
        for fqcn, methods in definition['expected'].items():
            if not set(methods) <= set(found.get(fqcn, [])):
                issues.append(f'{fqcn}: mandatory obligations absent from source discovery')
        definition['expected'] = found
    definition['discovery'] = 'effective-model-and-source-identities'
    definition['reactor_modules'] = [p['module'] for p in model]
    definition['discovery_issues'] = issues
    definition['expected_modules'] = expected_modules
    return model, issues


def external_sql_inputs(expected):
    """Follow selected source/helper references, then literal external SQL names.

    This is deliberately bounded to this repository's reviewed scripts/v3 loader
    idiom. Changing a loader or constructing a nonliteral filename requires review.
    App-local resources and POM generation inputs are already in app_inputs().
    """
    index = sources()
    pending = list(expected)
    visited = set()
    paths = set()
    while pending:
        fqcn = pending.pop()
        if fqcn in visited:
            continue
        visited.add(fqcn)
        matches = [p for name, p in index.get(fqcn.split('.')[-1], []) if name == fqcn]
        if not matches:
            # Effective inventory already supports multiple top-level JUnit classes in one
            # file. Resolve the same identity here; keep whole-file conservative tracing.
            package, short = fqcn.rsplit('.', 1)
            candidates = {path for rows in index.values() for identity, path in rows
                          if identity.rsplit('.', 1)[0] == package}
            for path in sorted(candidates):
                structure = java_structure(path.read_text())
                for match in re.finditer(r'(?:^|\n)\s*(?:(?:public|final|abstract)\s+)*class\s+'
                                         + re.escape(short) + r'\b', structure):
                    prefix = structure[:match.start()]
                    if prefix.count('{') == prefix.count('}'):
                        matches.append(path)
        if len(matches) != 1:
            raise ValueError(f'{fqcn}: cannot trace external inputs')
        content = matches[0].read_text()
        if 'scripts/v3/' in content:
            names = [match[1] for match in re.finditer(r'"(?:\.\./\.\./scripts/v3/)?([\w-]+\.sql)"', content)
                     if not content[:match.start()].rstrip().endswith('+')]
            if not names:
                raise ValueError(f'{fqcn}: external SQL loader has no literal input inventory')
            for name in names:
                path = ROOT / 'scripts/v3' / name
                if not path.is_file():
                    raise ValueError(f'Missing external SQL input: {path}')
                paths.add(str(path.relative_to(ROOT)))
        for name in set(re.findall(r'\b[A-Z]\w*\b', content)) & index.keys():
            pending += [identity for identity, _ in index[name] if identity not in visited]
    return sorted(paths)


def compilation_metrics(log, model=()):
    """Compiler announcements count source inputs, not files present or class outputs."""
    events = []
    module = goal = None
    for number, line in enumerate(log.splitlines(), 1):
        header = re.search(r'--- compiler:[^:]+:(compile|testCompile) .* @ ([\w-]+) ---', line)
        if header:
            goal, module = header.groups()
        match = re.search(r'Compiling (\d+) source files?\b.*? to (.+)', line)
        if match:
            events.append(dict(module=module, goal=goal, sources=int(match[1]), destination=match[2], line=number))
    inventory = []
    for project in model:
        module_path = ROOT / 'app' / project['module']
        inventory.append(dict(module=project['module'],
            present_main_java=len(list((module_path / 'src/main').rglob('*.java'))),
            present_test_java=len(list((module_path / 'src/test').rglob('*.java'))),
            included_main_root_java=len(list(Path(project['main_root']).rglob('*.java'))),
            included_test_root_java=len(list(Path(project['test_root']).rglob('*.java'))),
            present_generated_main_java=len(list((module_path / 'target/generated-sources').rglob('*.java'))),
            present_generated_test_java=len(list((module_path / 'target/generated-test-sources').rglob('*.java')))))
    return dict(compiler_events=events, announced_recompiled_source_inputs=sum(e['sources'] for e in events),
        source_inventory=inventory, reused_source_count=None,
        semantics='Compiler announcements include singular/plural and main/test invocations. Presence is separate. Generated inputs may overlap roots; do not sum inventories. Exact reused files and actual javac reads are not observable from these logs. Resource copies are not Java compilation.')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources(root=None):
    root = root or ROOT
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
    elif scope in config.get('campaigns', {}):
        campaign = config['campaigns'][scope]
        if level != campaign['level']:
            raise ValueError('Campaign level differs from its registered level')
        modules = campaign['modules']
        if not modules or not campaign['tests']:
            raise ValueError('Campaign requires explicit modules and positive method selections')
        for fqcn, selected in campaign['tests'].items():
            resolved = resolve([fqcn.split('.')[-1]], index)
            if fqcn not in resolved or not selected or len(selected) != len(set(selected)):
                raise ValueError(f'{fqcn}: invalid exact method selection')
            available = required_methods(fqcn, resolved[fqcn])
            expected[fqcn] = []
            for method in selected:
                if not re.fullmatch(r'[A-Za-z_$][\w$]*', method):
                    raise ValueError(f'{fqcn}: selectors must be exact method names')
                invocations = [m for m in available if m == method or m.startswith(method + '(')]
                if not invocations:
                    raise ValueError(f'{fqcn}#{method}: method missing from source inventory')
                expected[fqcn] += invocations
            if campaign.get('selection') == 'whole-classes':
                if set(selected) != set(available):
                    raise ValueError(f'{fqcn}: whole-class campaign must retain every source method')
                selectors.append(fqcn.split('.')[-1])
            else:
                selectors.append(fqcn + '#' + '+'.join(selected))
        if sum(map(len, expected.values())) != campaign['expected_invocations']:
            raise ValueError('Campaign invocation inventory differs from its adopted manifest')
    elif scope in config['recipes']:
        definition = config['recipes'][scope]
        modules = definition.get('fast_modules', [config['anchors'][definition['anchor']]]) if level == 'FAST' else [config['anchors'][definition['anchor']]]
        names = []
        for step in ['FAST', 'CHANGE', 'INTEGRATION']:
            names += definition[step]
            if step == level:
                break
        if level == 'INTEGRATION':
            modules += definition.get('integration_extra_modules', [])
        for fqcn, path in resolve(names, index).items():
            expected[fqcn] = required_methods(fqcn, path)
        selectors = sorted(expected)
    else:
        if level == 'FAST':
            raise ValueError('FAST requires an explicit source-reviewed recipe')
        scopes = (scope or '').split(',')
        if any(s not in config['anchors'] for s in scopes):
            raise ValueError('Use a registered recipe or comma-separated canonical slice identifiers')
        modules = [config['anchors'][s] for s in scopes]
    command = base + (['-pl', ','.join(modules), '-am'] if modules else [])
    if selectors:
        command += ['-Dtest=' + ','.join(selectors), '-Dsurefire.failIfNoSpecifiedTests=false']
    if scope in config.get('campaigns', {}) and 'jvm_max_heap_mib' in config['campaigns'][scope]:
        # Explicit, source-reviewed I1 server safety bound, not an inherited JVM environment flag.
        if scope not in {'l5-i1', 'l5-i1-completion'} or config['campaigns'][scope]['jvm_max_heap_mib'] != 512:
            raise ValueError('Unreviewed campaign JVM safety bound')
        command += ['-DargLine=-Xmx512m']
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
    active_quarantine = {k: v for k, v in config['quarantine'].items() if v.get('active', True)}
    historical_quarantine = {k: v for k, v in config['quarantine'].items() if not v.get('active', True)}
    result = dict(level=level, scope=scope, command=command, expected=expected,
                discovery='recipe-source' if selectors else 'pending-effective-model',
                filtered=bool(selectors), normative_gate=not selectors,
                not_selected_test_sources=sorted(unselected_sources),
                not_selected_methods_in_selected_classes=unselected_methods,
                added_permanent_exclusions=[],
                quarantine=config['quarantine'],
                active_quarantine=active_quarantine,
                historical_quarantine=historical_quarantine,
                config_sha256=digest(CONFIG))
    if scope in config.get('campaigns', {}):
        result['forecast'] = config['campaigns'][scope].get('forecast', dict(
            seconds=None, basis='UNKNOWN', uncertainty='UNKNOWN'))
    return result


def assess(reports, expected, exit_code, filtered, provenance=None):
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
            if provenance is not None:
                parts = Path(name).parts
                if len(parts) != 4 or parts[0] != 'reports' or parts[2] not in ['surefire-reports', 'failsafe-reports']:
                    raise ValueError('Report outside authorized producer directory')
                artifact, producer = parts[1], parts[2].removesuffix('-reports')
                required = {'pocoma.verification.id': provenance['invocation_id'],
                            'pocoma.verification.module': artifact,
                            'pocoma.verification.producer': producer}
                for key, value in required.items():
                    properties = suite.findall(f"properties/property[@name='{key}']")
                    if len(properties) != 1 or properties[0].get('value') != value:
                        raise ValueError(f'Provenance mismatch: {key}; historical or unattributed report')
                if provenance['expected_modules'].get(suite.get('name')) != artifact:
                    raise ValueError('Provenance mismatch: report attributed to wrong source module')
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
            if not fqcn or not method or suite.get('name') != fqcn:
                issues.append(f'Invalid testcase identity: {name}')
            try:
                seconds = float(case.get('time', '0'))
                if not 0 <= seconds < float('inf'):
                    raise ValueError('Invalid time')
            except ValueError:
                seconds = 0
                issues.append(f'Invalid testcase time: {name}')
            seen[(fqcn, method)] += 1
            problem = next((case.find(tag) for tag in ['failure', 'error', 'skipped'] if case.find(tag) is not None), None)
            executions.append(dict(classname=fqcn, method=method, seconds=seconds,
                outcome='PASS' if problem is None else {'failure':'FAIL', 'error':'ERROR', 'skipped':'SKIP'}[problem.tag],
                **({} if problem is None else {'diagnostic':problem.get('message', '')})))
            if fqcn not in expected or method not in expected[fqcn]:
                issues.append(f'Unexpected execution: {fqcn}#{method}')
    for fqcn, methods in expected.items():
        for method in methods:
            if seen[(fqcn, method)] != 1:
                issues.append(f'Expected one fresh execution: {fqcn}#{method}, found {seen[(fqcn, method)]}')
    if any(count > 1 for count in seen.values()):
        issues.append('Duplicate method execution')
    if not expected:
        issues.append('No complete expected test inventory: cannot prove coverage')
    if not counts['tests'] or counts['skipped']:
        issues.append('Absent or skipped tests: coverage incomplete')
    if exit_code:
        issues.append(f'Maven execution failed or interrupted (exit {exit_code}); no PASS')
    verdict = 'FAIL' if exit_code or counts['failures'] or counts['errors'] else ('INCONCLUSIVE' if issues else 'PASS')
    return dict(verdict=verdict, counts=counts, issues=issues, executions=executions)


def app_inputs():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', 'app'], cwd=ROOT).decode().split('\0')
    return {name: digest(ROOT / name) if (ROOT / name).is_file() else 'DELETED' for name in names if name}


def capture(command):
    with subprocess.Popen(command, cwd=ROOT / 'app', stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, start_new_session=True) as proc:
        try:
            output, _ = proc.communicate()
            return dict(command=command, exit_code=proc.returncode, output=output)
        except BaseException:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(proc.pid, sig)
                except ProcessLookupError:
                    break
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    continue
            raise


def run(definition, output):
    reject_implicit_options()
    # One checkout's targets and physical PG campaigns must never be shared concurrently.
    lock = ROOT / 'app/.verification.lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise ValueError('Another verification owns app targets (or stale app/.verification.lock; inspect before removal)')
    handlers = {sig: signal.signal(sig, interrupt) for sig in [signal.SIGTERM, signal.SIGHUP]}
    created_output = False
    measurements = None
    try:
        output.mkdir(parents=True, exist_ok=False)
        created_output = True
        measurements = Measurements(output, definition.get('forecast'))
        measurements.save()
        definition['invocation_id'] = uuid.uuid4().hex
        definition['reports_root'] = str(output / 'reports')
        (output / 'reports').mkdir()
        definition['command'] = definition['command'][:-1] + [
            '-Dpocoma.verification.id=' + definition['invocation_id'],
            '-Dpocoma.verification.reports=' + definition['reports_root'], definition['command'][-1]]
        with measurements.phase('input_inventory'):
            inputs = app_inputs()
            controls = control_inputs()
        discovery_started = time.monotonic()
        with measurements.phase('discovery'):
            model, discovery_issues = effective_inventory(definition, output)
        discovery_seconds = time.monotonic() - discovery_started
        for project in model:
            for producer in ['surefire', 'failsafe']:
                directory = output / 'reports' / project['artifact'] / (producer + '-reports')
                directory.mkdir(parents=True, exist_ok=False)
        if discovery_issues:
            raise ValueError('Unproven producer/discovery configuration: ' + '; '.join(discovery_issues))
        external_paths = external_sql_inputs(definition['expected'])
        definition['external_sql_inputs'] = external_paths
        (output / 'plan.json').write_text(json.dumps(definition, indent=2) + '\n')
        external = {name: digest(ROOT / name) for name in external_paths}
        provenance = dict(head=capture(['git', 'rev-parse', 'HEAD']), status=capture(['git', 'status', '--short']),
            java=capture(['java', '-version']), maven=capture(['./mvnw', '-v']),
            docker=capture(['docker', 'version', '--format', '{{.Server.Version}}']),
            app_inputs=inputs, external_sql_inputs=external, control_inputs=controls, selector_sha256=digest(Path(__file__)),
            maven_settings_sha256=digest(Path.home()/'.m2/settings.xml') if (Path.home()/'.m2/settings.xml').is_file() else None)
        (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        # Shared target reports are diagnostic only, never inputs to this verdict.
        prior = sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'app').glob('*/target/*-reports/TEST-*.xml'))
        started = time.time_ns()
        print(' '.join(definition['command']), '\nEvidence:', output, flush=True)
        with measurements.phase('maven'), (output / 'maven.log').open('wb') as log:
            rc = execute_maven(definition['command'], log)
        seconds = (time.time_ns() - started)/1e9
        collection_started = time.monotonic()
        reports = []
        artifacts = {}
        allowed = {output / 'reports' / p['artifact'] / (producer + '-reports')
                   for p in model for producer in ['surefire', 'failsafe']}
        for directory in [output / 'reports', *(p.parent for p in allowed), *allowed]:
            if directory.is_symlink() or not directory.is_dir():
                raise ValueError(f'Provenance: producer directory replaced or absent: {directory}')
        with tarfile.open(output / 'raw.tar.gz', 'w:gz') as bundle:
            filenames = ['maven.log', 'effective-pom.xml', 'discovery.log']
            filenames += [p.name for p in sorted(output.glob('*.process.json'))]
            for filename in filenames:
                bundle.add(output / filename, arcname=filename)
                artifacts[filename] = digest(output / filename)
            for path in sorted((output / 'reports').rglob('*')):
                if path.is_symlink():
                    raise ValueError(f'Provenance: symbolic link in producer output: {path}')
                if path.is_dir():
                    continue
                if path.parent not in allowed or path.stat().st_nlink != 1:
                    raise ValueError(f'Provenance: unauthorized report location/link: {path}')
                relative = str(path.relative_to(output))
                raw = path.read_bytes()
                # Archive exactly the bytes assessed (no second read of a mutable XML).
                info = tarfile.TarInfo(relative)
                info.size = len(raw)
                bundle.addfile(info, io.BytesIO(raw))
                artifacts[relative] = hashlib.sha256(raw).hexdigest()
                if path.name.startswith('TEST-') and path.suffix == '.xml':
                    reports.append((relative, raw))
            for path in sorted((ROOT / 'app').glob('*/target/**/*')):
                if not path.is_file() or path.stat().st_mtime_ns < started:
                    continue
                relative = str(path.relative_to(ROOT))
                if 'evidence' in relative.lower() and '/surefire-reports/' not in relative and '/failsafe-reports/' not in relative:
                    bundle.add(path, arcname=relative)
                    artifacts[relative] = digest(path)
        result = assess(reports, definition['expected'], rc, definition['filtered'], definition)
        for project in model:
            if any((ROOT / 'app' / project['module'] / 'target/generated-test-sources').rglob('*.java')):
                discovery_issues.append(f"{project['module']}: generated test discovery requires review")
        result['issues'] += discovery_issues
        if discovery_issues and result['verdict'] == 'PASS':
            result['verdict'] = 'INCONCLUSIVE'
        if (app_inputs() != inputs or control_inputs() != controls or
                {name: digest(ROOT / name) if (ROOT / name).is_file() else 'DELETED' for name in external_paths} != external):
            if result['verdict'] != 'FAIL':
                result['verdict'] = 'INCONCLUSIVE'
            result['issues'].append('Application, SQL or verification configuration inputs changed during execution')
        result['compilation'] = compilation_metrics((output / 'maven.log').read_text(), model)
        result.update(command=definition['command'], level=definition['level'], scope=definition['scope'],
            seconds=seconds, discovery_seconds=discovery_seconds, expected_invocations=sum(map(len, definition['expected'].values())), started_ns=started, exit_code=rc, artifacts=artifacts,
            invocation_id=definition['invocation_id'], reports_root=definition['reports_root'],
            raw_sha256=digest(output / 'raw.tar.gz'), previous_reports_not_counted=prior,
            not_selected='All methods outside plan.expected for filtered feedback; NOT RUN, never PASS',
            quarantined_scenarios=definition['quarantine'],
            active_quarantined_scenarios=definition.get('active_quarantine', {}),
            historical_quarantined_scenarios=definition.get('historical_quarantine', {}))
        measurements.phases['collection'] = dict(status='FINISHED', seconds=time.monotonic() - collection_started)
        result['timing'] = measurements.save()
        result['operational_status'] = ('INCOMPLETE' if result['verdict'] == 'INCONCLUSIVE'
                                        else result['verdict'])
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ['verdict', 'counts', 'seconds', 'issues']}, ensure_ascii=False), flush=True)
        return 0 if result['verdict'] == 'PASS' else 1
    except (ValueError, OSError, ET.ParseError, VerificationInterrupted, KeyboardInterrupt) as error:
        diagnostic = str(error) or 'Interrupted by keyboard; Maven execution not completed'
        if created_output:
            try:
                result = partial_reports(output, definition)
            except OSError as partial_error:
                result = dict(counts=dict(tests=0, failures=0, errors=0, skipped=0),
                              issues=[f'Partial collection failed: {partial_error}'], partial=True)
            result['verdict'] = 'FAIL' if isinstance(error, (VerificationInterrupted, KeyboardInterrupt)) or any(
                result['counts'][k] for k in ['failures', 'errors']) else 'INCONCLUSIVE'
            result['issues'].append(diagnostic)
            result.update(command=definition['command'], invocation_id=definition.get('invocation_id'), exit_code=None,
                          timing=measurements.save() if measurements else None)
            result['operational_status'] = ('FAIL' if any(result['counts'][k]
                for k in ['failures', 'errors']) else 'INCOMPLETE')
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(diagnostic, file=sys.stderr)
        return 2
    finally:
        for sig, handler in handlers.items():
            signal.signal(sig, handler)
        lock.rmdir()


def run_sequence(name, output, config=None):
    config = config or json.loads(CONFIG.read_text())
    names = config.get('sequences', {}).get(name)
    if not names:
        raise ValueError('Unknown explicit campaign sequence')
    output.mkdir(parents=True, exist_ok=False)
    results = {campaign: dict(status='NOT RUN') for campaign in names}
    started = time.monotonic()
    def save():
        (output / 'sequence.json').write_text(json.dumps(dict(
            campaigns=results, seconds=time.monotonic()-started,
            forecast_is_blocking=False, automatic_retries=0), indent=2)+'\n')
    save()
    for campaign in names:
        try:
            definition = plan(config['campaigns'][campaign]['level'], campaign, config)
            rc = run(definition, output / campaign)
            receipt = json.loads((output / campaign / 'result.json').read_text())
            results[campaign] = dict(status=receipt.get('operational_status', receipt['verdict']),
                                     timing=receipt.get('timing'), counts=receipt['counts'])
        except (ValueError, OSError, ET.ParseError, KeyboardInterrupt) as error:
            results[campaign] = dict(status='INCOMPLETE', reason=str(error))
            rc = 2
        save()
        if rc:
            return rc
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('level', nargs='?', choices=['FAST', 'CHANGE', 'INTEGRATION', 'CERTIFICATION', 'QUARANTINE'])
    parser.add_argument('scope', nargs='?')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--sequence', choices=['read'])
    args = parser.parse_args()
    try:
        if args.sequence:
            if args.level or args.scope or args.dry_run:
                raise ValueError('Sequence is explicit and cannot be combined with a scope/level/dry-run')
            output = args.output or Path(tempfile.gettempdir()) / ('pocoma-read-' + str(time.time_ns()))
            return run_sequence(args.sequence, output.resolve())
        if not args.level:
            raise ValueError('A level or explicit sequence is required')
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
