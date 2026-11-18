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


def _brace_block_end(content, start_after_open_brace):
    depth, end = 1, start_after_open_brace
    while depth and end < len(content):
        depth += (content[end] == '{') - (content[end] == '}')
        end += 1
    if depth:
        return None
    return end


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
            end = _brace_block_end(content, match.end())
            if end is None:
                raise ValueError(f'{name}: unbalanced test ancestor')
            candidates.append(content[match.start():end])
    if len(candidates) != 1:
        raise ValueError(f'{name}: unresolved/ambiguous test ancestor')
    return candidates[0]


def _enum_source_count(fqcn, name, args):
    names_match = re.search(r'names\s*=\s*\{([^}]*)\}', args)
    if names_match:
        return len(names_match.group(1).split(','))
    if 'Mutation' in args:
        return 3
    if 'EventFamily' in args:
        return 4
    raise ValueError(f'{fqcn}: unknown EnumSource in {name}: {args}')


def _parameterized_invocations(fqcn, content, name, param_decl):
    m = re.search(r'(@(?:[\w.]+\.)?ParameterizedTest(?:(?!@(?:[\w.]+\.)?ParameterizedTest)[\s\S])*?void\s+' + re.escape(name) + r'\s*\([^)]*\))', content)
    decl_block = m.group(1) if m else ''
    param_type = param_decl.strip().split()[0].split('.')[-1]
    val_match = re.search(r'@(?:[\w.]+\.)?ValueSource\s*\(\s*(\w+)\s*=\s*\{([^}]*)\}\s*\)', decl_block)
    if val_match:
        count = len(val_match.group(2).split(','))
        return [f'{name}({param_type})[{n}]' for n in range(1, count + 1)]
    enum_match = re.search(r'@(?:[\w.]+\.)?EnumSource\s*\(([^)]*)\)', decl_block)
    if enum_match:
        count = _enum_source_count(fqcn, name, enum_match.group(1))
        return [f'{name}({param_type})[{n}]' for n in range(1, count + 1)]
    raise ValueError(f'{fqcn}: unreviewed parameterized test provider for {name}')


def _extract_declared_tests(fqcn, content):
    test_decls = re.findall(r'@(?:[\w.]+\.)?Test\b(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(', content)
    if len(test_decls) != len(re.findall(r'@(?:[\w.]+\.)?Test\b', content)):
        raise ValueError(f'{fqcn}: unreviewed JUnit method declaration')
    methods = list(test_decls)
    repeated = re.findall(r'@(?:[\w.]+\.)?RepeatedTest\s*\(\s*(\d+)\s*\)(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(', content)
    if len(repeated) != len(re.findall(r'@(?:[\w.]+\.)?RepeatedTest\b', content)):
        raise ValueError(f'{fqcn}: unreviewed RepeatedTest declaration')
    for count_str, name in repeated:
        methods += [f'{name}()[{n}]' for n in range(1, int(count_str) + 1)]
    return methods


def required_methods(fqcn, path):
    """Exact invocation identities for reviewed JUnit forms, never a total estimate."""
    content = java_structure(path.read_text()) if isinstance(path, Path) else path
    validate_annotations(fqcn, content)
    if re.search(r'@(?:[\w.]+\.)?(TestFactory|TestTemplate|Nested|Disabled)\b', content):
        raise ValueError(f'{fqcn}: unsupported dynamic/nested/disabled discovery; no coverage proof')
    methods = _extract_declared_tests(fqcn, content)
    param_tests = re.findall(r'@(?:[\w.]+\.)?ParameterizedTest\b(?:(?!void\b)[\s\S])*?void\s+(\w+)\s*\(([^)]*)\)', content)
    if len(param_tests) != len(re.findall(r'@(?:[\w.]+\.)?ParameterizedTest\b', content)):
        raise ValueError(f'{fqcn}: unreviewed ParameterizedTest declaration')
    for name, param_decl in param_tests:
        methods += _parameterized_invocations(fqcn, content, name, param_decl)
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


def _is_regular_non_symlink(path):
    return not path.is_symlink() and path.is_file() and path.stat().st_nlink == 1


def partial_reports(output, definition):
    """Retain observed counts after interruption; incomplete XML cannot prove PASS."""
    reports, artifacts, paths = [], {}, []
    root = output / 'reports'
    for artifact in set(definition.get('expected_modules', {}).values()):
        directory = root / artifact / 'surefire-reports'
        if not any(p.is_symlink() for p in [root, directory.parent, directory]):
            paths += sorted(directory.glob('*'))
    for path in paths:
        if not _is_regular_non_symlink(path):
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


def _kill_process_group(proc):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            break
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            continue
        if sig == signal.SIGTERM:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        break


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
            try:
                metadata['interrupted_processes'] = subprocess.run(
                    ['ps', '-axo', 'pid,ppid,pgid,stat,etime,comm'],
                    capture_output=True, text=True, timeout=5).stdout
                process_path.write_text(json.dumps(metadata, indent=2) + '\n')
            except (OSError, subprocess.TimeoutExpired):
                pass
            _kill_process_group(process)
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


def _check_surefire_config_item(module_name, item, contract, issues):
    standard_includes = ['**/Test*.java', '**/*Test.java', '**/*Tests.java', '**/*TestCase.java', '**/L2ManifestProof.java']
    tag = item.tag.split('}')[-1]
    if tag in contract and config_value(item) == contract[tag]:
        return
    if tag != 'includes' or [p.text for p in item] != standard_includes:
        issues.append(f'{module_name}: unreviewed effective Surefire configuration: {tag}')


def _check_surefire_plugin(module_name, plugin, artifact_id, definition):
    issues = []
    configs = [plugin.find('m:configuration', NS)] + plugin.findall('m:executions/m:execution/m:configuration', NS)
    contract = producer_config(artifact_id, 'surefire', definition) if 'invocation_id' in definition else {}
    for config in configs:
        for item in [] if config is None else config:
            _check_surefire_config_item(module_name, item, contract, issues)
    executions = plugin.findall('m:executions/m:execution', NS)
    if len(executions) != 1 or executions[0].findtext('m:id', '', NS) != 'default-test':
        issues.append(f'{module_name}: unreviewed Surefire executions')
    if 'invocation_id' in definition:
        for config in configs:
            actual = {} if config is None else {c.tag.split('}')[-1]: config_value(c) for c in config}
            if any(actual.get(key) != value for key, value in contract.items()):
                issues.append(f'{module_name}: producer provenance configuration missing or overridden')
    return issues


def _extract_test_classes(content, path):
    classes = []
    for cm in re.finditer(r'(?:^|\n)(?:@[\w.()]+\s+)*(?:public\s+)?class\s+(\w+)[^{]*\{', content):
        end = _brace_block_end(content, cm.end())
        cls_body = content[cm.start():end] if end is not None else content[cm.start():]
        if re.search(TEST_ANNOTATION, cls_body):
            classes.append((cm.group(1), cls_body))
    return classes or [(path.stem, content)]


def _verify_ancestor_chain(fqcn, cls_name, cls_content, index):
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


def _register_test_class(fqcn, cls_content, artifact_id, state):
    definition, index, found, expected_modules, issues = state
    if definition['filtered'] and fqcn not in definition['expected']:
        return
    cls_name = fqcn.rsplit('.', 1)[-1]
    try:
        methods = required_methods(fqcn, cls_content)
        _verify_ancestor_chain(fqcn, cls_name, cls_content, index)
        if fqcn in found:
            raise ValueError(f'{fqcn}: duplicate source identity')
        found[fqcn] = methods
        expected_modules[fqcn] = artifact_id
    except ValueError as error:
        issues.append(str(error))


def _discover_test_file(path, artifact_id, state):
    definition, _, _, _, issues = state
    content = java_structure(path.read_text())
    if not re.search(TEST_ANNOTATION, content):
        is_test_name = re.fullmatch(r'Test.*|.*Tests?|.*TestCase', path.stem)
        if is_test_name and not re.search(r'\babstract\s+class\b', content):
            issues.append(f'{path}: cannot establish JUnit discovery')
        return
    package = re.search(r'^package\s+([\w.]+);', content, re.M)
    if not package:
        issues.append(f'{path}: missing package identity')
        return
    short_names = {name.split('.')[-1] for name in definition['expected']}
    for cls_name, cls_content in _extract_test_classes(content, path):
        if definition['filtered'] and cls_name not in short_names:
            continue
        _register_test_class(package[1] + '.' + cls_name, cls_content, artifact_id, state)


def _scan_project(project, module_paths, state, model):
    definition, _, _, _, issues = state
    if project.findtext('m:packaging', 'jar', NS) == 'pom':
        return
    test_root = Path(project.findtext('m:build/m:testSourceDirectory', '', NS))
    artifact_id = project.findtext('m:artifactId', '', NS)
    module = module_paths.get(artifact_id)
    if module is None or test_root != module / 'src/test/java':
        issues.append(f'Unreviewed test source root: {test_root}')
        return
    model.append(dict(module=module.name, artifact=artifact_id,
        main_root=project.findtext('m:build/m:sourceDirectory', '', NS), test_root=str(test_root)))
    for plugin in project.findall('m:build/m:plugins/m:plugin', NS):
        artifact = plugin.findtext('m:artifactId', '', NS)
        if artifact == 'maven-failsafe-plugin':
            issues.append(f'{module.name}: Failsafe lifecycle discovery requires review; CLI currently executes test only')
        elif artifact == 'maven-surefire-plugin':
            issues.extend(_check_surefire_plugin(module.name, plugin, artifact_id, definition))
    for path in sorted(test_root.rglob('*.java')):
        _discover_test_file(path, artifact_id, state)


def _reconcile_expected(definition, found, issues):
    for fqcn, methods in definition['expected'].items():
        if not set(methods) <= set(found.get(fqcn, [])):
            kind = 'recipe' if definition['filtered'] else 'mandatory'
            issues.append(f'{fqcn}: {kind} obligations absent from source discovery')
    if not definition['filtered']:
        definition['expected'] = found


def effective_inventory(definition, output):
    """Let Maven select the reactor; independently enumerate its source obligations."""
    command = definition['command'][:-1] + ['help:effective-pom', '-Doutput=' + str(output / 'effective-pom.xml')]
    with (output / 'discovery.log').open('wb') as log:
        rc = execute_maven(command, log)
    if rc:
        raise ValueError(f'Maven effective-model discovery failed ({rc}); see {output}/discovery.log; no PASS')
    document = ET.parse(output / 'effective-pom.xml').getroot()
    projects = list(document) if document.tag == 'projects' else [document]
    module_paths = {ET.parse(p).getroot().findtext('m:artifactId', '', NS): p.parent for p in (ROOT / 'app').glob('*/pom.xml')}
    model, issues, found, expected_modules = [], [], {}, {}
    state = (definition, sources(), found, expected_modules, issues)
    for project in projects:
        _scan_project(project, module_paths, state, model)
    _reconcile_expected(definition, found, issues)
    definition['discovery'] = 'effective-model-and-source-identities'
    definition['reactor_modules'] = [p['module'] for p in model]
    definition['discovery_issues'] = issues
    definition['expected_modules'] = expected_modules
    return model, issues


def _has_top_level_class(path, short):
    structure = java_structure(path.read_text())
    pattern = r'(?:^|\n)\s*(?:(?:public|final|abstract)\s+)*class\s+' + re.escape(short) + r'\b'
    return any(structure[:m.start()].count('{') == structure[:m.start()].count('}') for m in re.finditer(pattern, structure))


def _resolve_java_source(fqcn, index):
    matches = [p for name, p in index.get(fqcn.split('.')[-1], []) if name == fqcn]
    if not matches:
        package, short = fqcn.rsplit('.', 1)
        candidates = {path for rows in index.values() for identity, path in rows
                      if identity.rsplit('.', 1)[0] == package}
        matches = [path for path in sorted(candidates) if _has_top_level_class(path, short)]
    if len(matches) != 1:
        raise ValueError(f'{fqcn}: cannot trace external inputs')
    return matches[0].read_text()


def _collect_literal_sql(fqcn, content, spec):
    folder_rel, regex, reject_concat = spec
    names = [m[1] for m in re.finditer(regex, content)
             if not (reject_concat and content[:m.start()].rstrip().endswith('+'))]
    if not names:
        label = 'input inventory' if reject_concat else 'fixture'
        raise ValueError(f'{fqcn}: external SQL {label} must be a literal path')
    found = []
    for name in names:
        path = ROOT / folder_rel / name
        if not path.is_file():
            raise ValueError(f'Missing external SQL input: {path}')
        found.append(str(path.relative_to(ROOT)))
    return found


def external_sql_inputs(expected):
    """Follow selected source/helper references, then literal external SQL names."""
    index = sources()
    pending, visited, paths = list(expected), set(), set()
    while pending:
        fqcn = pending.pop()
        if fqcn in visited:
            continue
        visited.add(fqcn)
        content = _resolve_java_source(fqcn, index)
        if 'scripts/v3/' in content:
            paths.update(_collect_literal_sql(fqcn, content, ('scripts/v3', r'"(?:\.\./\.\./scripts/v3/)?([\w-]+\.sql)"', True)))
        if 'scripts/testing/fixtures/' in content:
            paths.update(_collect_literal_sql(fqcn, content, ('scripts/testing/fixtures', r'"\.\./\.\./scripts/testing/fixtures/([\w-]+\.sql)"', False)))
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


def anchor_modules(config, name):
    """A slice may contain more than one independently packaged Runtime."""
    value = config['anchors'][name]
    return [value] if isinstance(value, str) else list(value)


def _plan_certification(config, index, scope):
    if scope:
        raise ValueError('CERTIFICATION is unfiltered; no scope allowed')
    expected = {}
    for scenario in config['quarantine'].values():
        resolved = resolve([scenario['class'].split('.')[-1]], index)
        content = resolved[scenario['class']].read_text()
        if not re.search(r'@Test\s+void\s+' + re.escape(scenario['method']) + r'\s*\(', content):
            raise ValueError('Required quarantined scenario is no longer an executable JUnit test')
        expected.setdefault(scenario['class'], []).append(scenario['method'])
    return expected, [], []


def _plan_quarantine(config, index, scope):
    if scope not in config['quarantine']:
        raise ValueError('Choose a registered nominative quarantine scenario')
    scenario = config['quarantine'][scope]
    modules = anchor_modules(config, scenario['anchor'])
    matches = resolve([scenario['class'].split('.')[-1]], index)
    if scenario['class'] not in matches:
        raise ValueError('Quarantine class identity differs from source')
    if not re.search(r'\bvoid\s+' + re.escape(scenario['method']) + r'\s*\(', matches[scenario['class']].read_text()):
        raise ValueError('Quarantine method missing')
    return {scenario['class']: [scenario['method']]}, [scenario['class'] + '#' + scenario['method']], modules


def _select_campaign_methods(fqcn, selected, available):
    if not selected or len(selected) != len(set(selected)):
        raise ValueError(f'{fqcn}: invalid exact method selection')
    chosen = []
    for method in selected:
        if not re.fullmatch(r'[A-Za-z_$][\w$]*', method):
            raise ValueError(f'{fqcn}: selectors must be exact method names')
        invocations = [m for m in available if m == method or m.startswith(method + '(')]
        if not invocations:
            raise ValueError(f'{fqcn}#{method}: method missing from source inventory')
        chosen += invocations
    return chosen


def _campaign_selector(fqcn, selected, available, campaign):
    if campaign.get('selection') == 'whole-classes':
        if set(selected) != set(available):
            raise ValueError(f'{fqcn}: whole-class campaign must retain every source method')
        return fqcn.split('.')[-1]
    return fqcn + '#' + '+'.join(selected)


def _plan_campaign(config, index, level, scope):
    campaign = config['campaigns'][scope]
    if level != campaign['level']:
        raise ValueError('Campaign level differs from its registered level')
    modules = campaign['modules']
    if not modules or not campaign['tests']:
        raise ValueError('Campaign requires explicit modules and positive method selections')
    expected, selectors = {}, []
    for fqcn, selected in campaign['tests'].items():
        resolved = resolve([fqcn.split('.')[-1]], index)
        if fqcn not in resolved:
            raise ValueError(f'{fqcn}: invalid exact method selection')
        available = required_methods(fqcn, resolved[fqcn])
        expected[fqcn] = _select_campaign_methods(fqcn, selected, available)
        selectors.append(_campaign_selector(fqcn, selected, available, campaign))
    if sum(map(len, expected.values())) != campaign['expected_invocations']:
        raise ValueError('Campaign invocation inventory differs from its adopted manifest')
    return expected, selectors, modules


def _plan_recipe(config, index, level, scope):
    definition = config['recipes'][scope]
    modules = definition.get('fast_modules', anchor_modules(config, definition['anchor'])) if level == 'FAST' else anchor_modules(config, definition['anchor'])
    names = []
    for step in ['FAST', 'CHANGE', 'INTEGRATION']:
        names += definition[step]
        if step == level:
            break
    if level == 'INTEGRATION':
        modules += definition.get('integration_extra_modules', [])
    expected = {fqcn: required_methods(fqcn, path) for fqcn, path in resolve(names, index).items()}
    return expected, sorted(expected), modules


def _plan_slice_or_gate(config, level, scope):
    if level == 'FAST':
        raise ValueError('FAST requires an explicit source-reviewed recipe')
    scopes = (scope or '').split(',')
    gates = config.get('gates', {})
    if any(s not in config['anchors'] and s not in gates for s in scopes):
        raise ValueError('Use a registered recipe, canonical slice or explicit gate identifier')
    modules = list(dict.fromkeys(module for s in scopes for module in
                                (anchor_modules(config, s) if s in config['anchors'] else gates[s])))
    return {}, [], modules


def _collect_unselected(index, expected, root):
    unselected_sources, unselected_methods = [], {}
    for fqcn, path in (pair for matches in index.values() for pair in matches):
        content = path.read_text()
        if fqcn not in expected and re.search(r'@(Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\b', content):
            unselected_sources.append(str(path.relative_to(root)))
        elif fqcn in expected:
            omitted = sorted(set(re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', content)) - set(expected[fqcn]))
            if omitted:
                unselected_methods[fqcn] = omitted
    return sorted(unselected_sources), unselected_methods


def _build_maven_command(config, scope, modules, selectors):
    command = ['./mvnw', '--batch-mode', '--no-transfer-progress']
    if modules:
        command += ['-pl', ','.join(modules), '-am']
    if selectors:
        command += ['-Dtest=' + ','.join(selectors), '-Dsurefire.failIfNoSpecifiedTests=false']
    campaigns = config.get('campaigns', {})
    if scope in campaigns and 'jvm_max_heap_mib' in campaigns[scope]:
        if scope not in {'l5-i1', 'l5-i1-completion', 'l5-i2-d4'} or campaigns[scope]['jvm_max_heap_mib'] != 512:
            raise ValueError('Unreviewed campaign JVM safety bound')
        command += ['-DargLine=-Xmx512m']
    return command + ['test']


def plan(level, scope, config=None, root=ROOT):
    config = config or json.loads(CONFIG.read_text())
    index = sources(root)
    if level == 'CERTIFICATION':
        expected, selectors, modules = _plan_certification(config, index, scope)
    elif level == 'QUARANTINE':
        expected, selectors, modules = _plan_quarantine(config, index, scope)
    elif scope in config.get('campaigns', {}):
        expected, selectors, modules = _plan_campaign(config, index, level, scope)
    elif scope in config['recipes']:
        expected, selectors, modules = _plan_recipe(config, index, level, scope)
    else:
        expected, selectors, modules = _plan_slice_or_gate(config, level, scope)

    command = _build_maven_command(config, scope, modules, selectors)
    unselected_sources, unselected_methods = _collect_unselected(index, expected, root) if selectors else ([], {})
    result = dict(level=level, scope=scope, command=command, expected=expected,
                discovery='recipe-source' if selectors else 'pending-effective-model',
                filtered=bool(selectors), normative_gate=not selectors,
                not_selected_test_sources=unselected_sources,
                not_selected_methods_in_selected_classes=unselected_methods,
                added_permanent_exclusions=[], quarantine=config['quarantine'],
                active_quarantine={k: v for k, v in config['quarantine'].items() if v.get('active', True)},
                historical_quarantine={k: v for k, v in config['quarantine'].items() if not v.get('active', True)},
                config_sha256=digest(CONFIG))
    if scope in config.get('campaigns', {}):
        result['forecast'] = config['campaigns'][scope].get('forecast', dict(seconds=None, basis='UNKNOWN', uncertainty='UNKNOWN'))
    return result


def _check_provenance_properties(suite, provenance, artifact, producer):
    required = {'pocoma.verification.id': provenance['invocation_id'],
                'pocoma.verification.module': artifact,
                'pocoma.verification.producer': producer}
    for key, value in required.items():
        properties = suite.findall(f"properties/property[@name='{key}']")
        if len(properties) != 1 or properties[0].get('value') != value:
            raise ValueError(f'Provenance mismatch: {key}; historical or unattributed report')


def _validate_suite_provenance(name, suite, provenance):
    parts = Path(name).parts
    valid_dir = len(parts) == 4 and parts[0] == 'reports' and parts[2] in ['surefire-reports', 'failsafe-reports']
    if not valid_dir:
        raise ValueError('Report outside authorized producer directory')
    artifact, producer = parts[1], parts[2].removesuffix('-reports')
    _check_provenance_properties(suite, provenance, artifact, producer)
    if provenance['expected_modules'].get(suite.get('name')) != artifact:
        raise ValueError('Provenance mismatch: report attributed to wrong source module')


def _parse_testcase_time(case, name, issues):
    try:
        seconds = float(case.get('time', '0'))
        if 0 <= seconds < float('inf'):
            return seconds
    except ValueError:
        pass
    issues.append(f'Invalid testcase time: {name}')
    return 0


def _assess_single_case(case, suite_name, name, state):
    _, executions, issues, seen, expected = state
    fqcn = case.get('classname', suite_name)
    method = case.get('name')
    if not all((fqcn, method, suite_name == fqcn)):
        issues.append(f'Invalid testcase identity: {name}')
    seconds = _parse_testcase_time(case, name, issues)
    seen[(fqcn, method)] += 1
    outcome_map = {'failure': 'FAIL', 'error': 'ERROR', 'skipped': 'SKIP'}
    problem = next((case.find(tag) for tag in outcome_map if case.find(tag) is not None), None)
    executions.append(dict(classname=fqcn, method=method, seconds=seconds,
        outcome='PASS' if problem is None else outcome_map[problem.tag],
        **({} if problem is None else {'diagnostic': problem.get('message', '')})))
    if fqcn not in expected or method not in expected[fqcn]:
        issues.append(f'Unexpected execution: {fqcn}#{method}')


def _assess_suite_cases(suite, declared, name, state):
    counts, _, issues, _, _ = state
    cases = suite.findall('testcase')
    actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                  errors=sum(c.find('error') is not None for c in cases),
                  skipped=sum(c.find('skipped') is not None for c in cases))
    if actual != declared:
        issues.append(f'Inconsistent counters: {name}')
    for key in counts:
        counts[key] += actual[key]
    for case in cases:
        _assess_single_case(case, suite.get('name'), name, state)


def _check_coverage_obligations(expected, seen, counts, issues):
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


def assess(reports, expected, exit_code, filtered, provenance=None):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    executions, issues, seen = [], [], collections.Counter()
    state = (counts, executions, issues, seen, expected)
    for name, raw in reports:
        try:
            suite = ET.fromstring(raw)
            if suite.tag != 'testsuite':
                raise ValueError('Expected Surefire testsuite')
            declared = {k: int(suite.get(k, '0')) for k in counts}
            if provenance is not None:
                _validate_suite_provenance(name, suite, provenance)
        except (ET.ParseError, ValueError) as error:
            issues.append(f'Invalid report {name}: {error}')
            continue
        _assess_suite_cases(suite, declared, name, state)
    _check_coverage_obligations(expected, seen, counts, issues)
    if exit_code:
        issues.append(f'Maven execution failed or interrupted (exit {exit_code}); no PASS')
    has_failure = bool(exit_code or counts['failures'] or counts['errors'])
    verdict = 'FAIL' if has_failure else ('INCONCLUSIVE' if issues else 'PASS')
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
            _kill_process_group(proc)
            raise


def _archive_reports(bundle, output, allowed, artifacts):
    reports = []
    for path in sorted((output / 'reports').rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Provenance: symbolic link in producer output: {path}')
        if path.is_dir():
            continue
        if path.parent not in allowed or path.stat().st_nlink != 1:
            raise ValueError(f'Provenance: unauthorized report location/link: {path}')
        relative = str(path.relative_to(output))
        raw = path.read_bytes()
        info = tarfile.TarInfo(relative)
        info.size = len(raw)
        bundle.addfile(info, io.BytesIO(raw))
        artifacts[relative] = hashlib.sha256(raw).hexdigest()
        if path.name.startswith('TEST-') and path.suffix == '.xml':
            reports.append((relative, raw))
    return reports


def _archive_target_evidence(bundle, started, artifacts):
    for path in sorted((ROOT / 'app').glob('*/target/**/*')):
        if not path.is_file() or path.stat().st_mtime_ns < started:
            continue
        relative = str(path.relative_to(ROOT))
        is_evidence = 'evidence' in relative.lower()
        is_report = '/surefire-reports/' in relative or '/failsafe-reports/' in relative
        if is_evidence and not is_report:
            bundle.add(path, arcname=relative)
            artifacts[relative] = digest(path)


def _collect_evidence_bundle(output, model, started):
    artifacts = {}
    allowed = {output / 'reports' / p['artifact'] / (producer + '-reports')
               for p in model for producer in ['surefire', 'failsafe']}
    for directory in [output / 'reports', *(p.parent for p in allowed), *allowed]:
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError(f'Provenance: producer directory replaced or absent: {directory}')
    with tarfile.open(output / 'raw.tar.gz', 'w:gz') as bundle:
        filenames = ['maven.log', 'effective-pom.xml', 'discovery.log'] + [p.name for p in sorted(output.glob('*.process.json'))]
        for filename in filenames:
            bundle.add(output / filename, arcname=filename)
            artifacts[filename] = digest(output / filename)
        reports = _archive_reports(bundle, output, allowed, artifacts)
        _archive_target_evidence(bundle, started, artifacts)
    return reports, artifacts


def _inputs_unchanged(inputs, controls, external_paths, external):
    ext_now = {name: digest(ROOT / name) if (ROOT / name).is_file() else 'DELETED' for name in external_paths}
    return app_inputs() == inputs and control_inputs() == controls and ext_now == external


def _handle_run_error(error, run_ctx, definition, measurements):
    created_output, output = run_ctx
    diagnostic = str(error) or 'Interrupted by keyboard; Maven execution not completed'
    if created_output:
        try:
            result = partial_reports(output, definition)
        except OSError as partial_error:
            result = dict(counts=dict(tests=0, failures=0, errors=0, skipped=0),
                          issues=[f'Partial collection failed: {partial_error}'], partial=True)
        has_err = any(result['counts'][k] for k in ['failures', 'errors'])
        interrupted = isinstance(error, (VerificationInterrupted, KeyboardInterrupt))
        result['verdict'] = 'FAIL' if interrupted or has_err else 'INCONCLUSIVE'
        result['issues'].append(diagnostic)
        result.update(command=definition['command'], invocation_id=definition.get('invocation_id'), exit_code=None,
                      timing=measurements.save() if measurements else None)
        result['operational_status'] = 'FAIL' if has_err else 'INCOMPLETE'
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(diagnostic, file=sys.stderr)
    return 2


def _prepare_and_discover(definition, output, measurements):
    output.mkdir(parents=True, exist_ok=False)
    measurements = Measurements(output, definition.get('forecast'))
    measurements.save()
    definition['invocation_id'] = uuid.uuid4().hex
    definition['reports_root'] = str(output / 'reports')
    (output / 'reports').mkdir()
    definition['command'] = definition['command'][:-1] + [
        '-Dpocoma.verification.id=' + definition['invocation_id'],
        '-Dpocoma.verification.reports=' + definition['reports_root'], definition['command'][-1]]
    with measurements.phase('input_inventory'):
        inputs, controls = app_inputs(), control_inputs()
    discovery_started = time.monotonic()
    with measurements.phase('discovery'):
        model, discovery_issues = effective_inventory(definition, output)
    discovery_seconds = time.monotonic() - discovery_started
    for project in model:
        for producer in ['surefire', 'failsafe']:
            (output / 'reports' / project['artifact'] / (producer + '-reports')).mkdir(parents=True, exist_ok=False)
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
    return measurements, model, discovery_issues, discovery_seconds, (inputs, controls, external_paths, external)


def _finalize_run_result(definition, output, model, run_meta):
    rc, started, seconds, discovery_seconds, discovery_issues, prior, measurements, snap = run_meta
    inputs, controls, external_paths, external = snap
    collection_started = time.monotonic()
    reports, artifacts = _collect_evidence_bundle(output, model, started)
    result = assess(reports, definition['expected'], rc, definition['filtered'], definition)
    for project in model:
        if any((ROOT / 'app' / project['module'] / 'target/generated-test-sources').rglob('*.java')):
            discovery_issues.append(f"{project['module']}: generated test discovery requires review")
    result['issues'] += discovery_issues
    if discovery_issues and result['verdict'] == 'PASS':
        result['verdict'] = 'INCONCLUSIVE'
    if not _inputs_unchanged(inputs, controls, external_paths, external):
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
    result['operational_status'] = 'INCOMPLETE' if result['verdict'] == 'INCONCLUSIVE' else result['verdict']
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ['verdict', 'counts', 'seconds', 'issues']}, ensure_ascii=False), flush=True)
    return 0 if result['verdict'] == 'PASS' else 1


def run(definition, output):
    reject_implicit_options()
    lock = ROOT / 'app/.verification.lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise ValueError('Another verification owns app targets (or stale app/.verification.lock; inspect before removal)')
    handlers = {sig: signal.signal(sig, interrupt) for sig in [signal.SIGTERM, signal.SIGHUP]}
    created_output, measurements = False, None
    try:
        created_output = True
        measurements, model, discovery_issues, discovery_seconds, snap = _prepare_and_discover(definition, output, measurements)
        prior = sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'app').glob('*/target/*-reports/TEST-*.xml'))
        started = time.time_ns()
        print(' '.join(definition['command']), '\nEvidence:', output, flush=True)
        with measurements.phase('maven'), (output / 'maven.log').open('wb') as log:
            rc = execute_maven(definition['command'], log)
        seconds = (time.time_ns() - started)/1e9
        return _finalize_run_result(definition, output, model, (rc, started, seconds, discovery_seconds, discovery_issues, prior, measurements, snap))
    except (ValueError, OSError, ET.ParseError, VerificationInterrupted, KeyboardInterrupt) as error:
        return _handle_run_error(error, (created_output and output.exists(), output), definition, measurements)
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


def _run_sequence_mode(args):
    if args.level or args.scope or args.dry_run:
        raise ValueError('Sequence is explicit and cannot be combined with a scope/level/dry-run')
    output = args.output or Path(tempfile.gettempdir()) / ('pocoma-read-' + str(time.time_ns()))
    return run_sequence(args.sequence, output.resolve())


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
            return _run_sequence_mode(args)
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
