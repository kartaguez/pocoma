"""Read-only, command-specific distribution checks. No installs or state writes."""
import ast
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


TOOLCHAIN_ENV_VARS = ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')


def clean_toolchain_env(env=None):
    """Return a subprocess environment with JVM/Maven override variables stripped."""
    cleaned = dict(os.environ if env is None else env)
    for variable in TOOLCHAIN_ENV_VARS:
        cleaned.pop(variable, None)
    return cleaned


class Failure(RuntimeError):
    code = 2

    def __init__(self, control, required, detected, action):
        super().__init__(control)
        self.diagnostic = dict(control=control, required=required, detected=detected, recommendedAction=action)


def reject(control, required, detected, action='Restore the distribution files; preserve existing instance data and retry.'):
    raise Failure(control, required, detected, action)


def document(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        reject('PRE-CONFIG-FILE', str(path), 'missing, unreadable or invalid JSON')


def contract(root):
    data = document(root / 'scripts/local/toolchain.json')
    if not isinstance(data, dict):
        reject('PRE-TOOLCHAIN-CONTRACT', 'Valid distribution toolchain contract', 'incomplete or invalid contract')
    for key in ('javaMajor', 'nodeMajor', 'npmMinimumMajor'):
        if type(data.get(key)) is not int or data[key] < 1:
            reject('PRE-TOOLCHAIN-CONTRACT', 'Valid distribution toolchain contract', 'incomplete or invalid contract')
    bruno_version = data.get('brunoVersion')
    if not isinstance(bruno_version, str) or not re.fullmatch(r'\d+\.\d+\.\d+', bruno_version):
        reject('PRE-TOOLCHAIN-CONTRACT', 'Valid distribution toolchain contract', 'incomplete or invalid contract')
    return data


def probe(tool, args, required, action, *, cwd=None, input=None):
    if not shutil.which(str(tool)):
        reject('PRE-TOOL-MISSING', required, str(tool) + ': not found or not executable', action)
    try:
        result = subprocess.run([str(tool), *args], input=input, capture_output=True, text=True, timeout=20, cwd=cwd, env=clean_toolchain_env())
    except (OSError, subprocess.TimeoutExpired):
        reject('PRE-TOOL-UNAVAILABLE', required, str(tool) + ': could not execute or timed out', action)
    if result.returncode:
        # Never publish raw stderr: contexts, registry credentials and proxies may occur there.
        reject('PRE-TOOL-FAILED', required, str(tool) + ': probe failed (exit ' + str(result.returncode) + ')', action)
    return (result.stdout + result.stderr).strip()


def major(tool, args, expected, *, minimum=False):
    label = tool + (' major >= ' if minimum else ' major ') + str(expected)
    action = 'Install/select ' + label + '; check PATH and JAVA_HOME, then retry.'
    value = probe(tool, args, label, action)
    match = re.search(r'(?:version\s+"?|(?:^|\n)(?:javac\s+)?v?)(\d+)(?:[.\s"+-]|$)', value)
    parsed = int(match[1]) if match else None
    valid = parsed is not None and (parsed >= expected if minimum else parsed == expected)
    if not valid:
        # Report only a parsed version, never arbitrary process output.
        reject('PRE-TOOL-VERSION', label,
               tool + ' major ' + (match[1] if match else 'unrecognized'), action)
    return value


def _check_docker_prerequisites(starting):
    probe('docker', ['--version'], 'Docker CLI', 'Install Docker Desktop/the Docker CLI and retry.')
    probe('docker', ['info', '--format', '{{json .ServerVersion}}'], 'Accessible Docker daemon',
          'Start Docker; verify its context/socket permissions, then retry. No instance was changed.')
    probe('docker', ['compose', 'version', '--short'], 'Docker Compose plugin',
          'Install/enable the docker compose plugin (not only docker-compose), then retry.')
    if not starting:
        return
    probe_compose = dict(services=dict(check=dict(
        image='pocoma-probe:unused', mem_limit='64m', cpus=.5,
        tmpfs=['/tmp:rw,size=1m'], healthcheck=dict(test=['CMD', 'true']), labels={'io.pocoma.probe': 'true'},
    )))
    probe('docker', ['compose', '-f', '-', 'config', '--quiet'],
          'Compose support for the distribution configuration',
          'Update the docker compose plugin to support JSON, limits, healthchecks and tmpfs, then retry.',
          input=json.dumps(probe_compose))


def _check_npm_prerequisites(expected):
    value = probe('npm', ['--version'], 'npm capable of npm ci with package-lock v3',
                  'Install the npm bundled with the supported Node version; include devDependencies and retry.')
    version = re.fullmatch(r'(\d+)\.\d+\.\d+(?:[-+].*)?', value)
    if not version or int(version[1]) < expected['npmMinimumMajor']:
        reject('PRE-NPM-VERSION', 'npm >= ' + str(expected['npmMinimumMajor']) + ' (package-lock v3)',
               'npm major ' + (version[1] if version else 'unrecognized'),
               'Use the npm bundled with the supported Node version, then retry.')
    if os.environ.get('NODE_ENV') == 'production' or 'dev' in os.environ.get('npm_config_omit', '').split():
        reject('PRE-NPM-DEV-DEPS', 'Bruno devDependencies enabled', 'development dependencies omitted',
               'Unset NODE_ENV=production/npm_config_omit=dev for preparation, then retry.')
    omitted = probe('npm', ['config', 'get', 'omit'], 'Bruno devDependencies enabled',
                    'Check npm configuration, include devDependencies, then retry.')
    if 'dev' in omitted.split():
        reject('PRE-NPM-DEV-DEPS', 'Bruno devDependencies enabled', 'npm configuration omits devDependencies',
               'Remove omit=dev from the relevant npm configuration for preparation, then retry.')


def _check_bruno_prerequisites(root, expected):
    major('node', ['--version'], expected.get('nodeMinimumMajor', expected['nodeMajor']), minimum=True)
    cli = root / 'scripts/bruno-v3/node_modules/.bin/bru'
    if not cli.exists():
        _check_npm_prerequisites(expected)
        return
    value = probe(cli, ['--version'], 'Bruno CLI ' + expected['brunoVersion'],
                  'Restore the locked local Bruno dependencies with npm ci --prefix scripts/bruno-v3; retry.')
    if value != expected['brunoVersion']:
        reject('PRE-BRUNO-VERSION', 'Bruno CLI ' + expected['brunoVersion'],
               'different or unrecognized version', 'Restore the locked local Bruno dependencies and retry.')


def _check_java_prerequisites(root, expected, build_required):
    for name in ('maven.config', 'jvm.config', 'extensions.xml'):
        if (root / 'app/.mvn' / name).exists():
            reject('PRE-UNREVIEWED-MAVEN-CONFIG', 'Reviewed Maven configuration', 'app/.mvn/' + name + ' exists')
    major('java', ['-version'], expected['javaMajor'])
    if not build_required:
        return
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        major(str(Path(java_home) / 'bin/java'), ['-version'], expected['javaMajor'])
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    major(javac, ['-version'], expected['javaMajor'])
    if not os.access(root / 'app/mvnw', os.X_OK):
        reject('PRE-WRAPPER', 'Executable app/mvnw', 'missing or not executable')


def tools(root, command, dry_run=False, *, build_required=True):
    expected = contract(root)
    probe('git', ['rev-parse', '--verify', 'HEAD'], 'Git and repository metadata',
          'Install Git and use a Git clone, not a source archive; retry.', cwd=root)
    starting = command in ('start', 'reload', 'upgrade-grafana', 'doctor') or (command == 'reset' and not dry_run)
    _check_docker_prerequisites(starting)
    if starting or command == 'bruno':
        _check_bruno_prerequisites(root, expected)
    if starting:
        _check_java_prerequisites(root, expected, build_required)


def _collect_sql_sources(root):
    helper = root / 'scripts/operations/setup-v3-database.py'
    if not helper.is_file():
        reject('PRE-CONFIG-FILE', str(helper), 'missing')
    # Current helper owns literal SQL inputs. Read its AST, never execute it here.
    try:
        tree = ast.parse(helper.read_text())
    except (OSError, SyntaxError):
        reject('PRE-CONFIG-FILE', str(helper), 'unreadable or invalid Python')
    return [
        node.value if '/' in node.value else 'scripts/v3/' + node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and node.value.endswith('.sql')
    ]


def _validate_realm_template(root):
    realm = document(root / 'docker/keycloak/pocoma-realm.json')
    if not isinstance(realm, dict):
        reject('PRE-CONFIG-REALM', 'Realm users and clients templates', 'incomplete configuration')
    for group in ('users', 'clients'):
        entries = realm.get(group)
        if not isinstance(entries, list) or not entries:
            reject('PRE-CONFIG-REALM', 'Realm users and clients templates', 'incomplete configuration')
        if not all(isinstance(v, dict) for v in entries):
            reject('PRE-CONFIG-REALM', 'Realm users and clients templates', 'incomplete configuration')


def sources(root, runtimes):
    files = ['app/pom.xml', 'app/mvnw', 'app/.mvn/wrapper/maven-wrapper.properties',
             'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json',
             'scripts/bruno-playground/inventory.json', 'scripts/functional-auth-proof.py',
             'scripts/operations/setup-v3-database.py', 'docker/keycloak/pocoma-realm.json',
             'docker/grafana/provisioning/datasources/prometheus.yml',
             'docker/grafana/provisioning/dashboards/dashboards.yml',
             'docker/grafana/dashboards/pocoma-runtime.json', 'docker/grafana/dashboards/pocoma-v3-overview.json']
    files += ['docker/v3-five-runtimes/' + name + '.properties' for name in runtimes]
    files += _collect_sql_sources(root)
    for name in files:
        path = root / name
        if not path.is_file() or not os.access(path, os.R_OK):
            reject('PRE-CONFIG-FILE', name, 'missing or unreadable')
        if path.suffix == '.json':
            document(path)
    _validate_realm_template(root)


def _validate_credential_group(data, group, names):
    values = data.get(group)
    if not isinstance(values, dict):
        reject('PRE-SECRETS-INVALID', 'Complete ' + group + ' entries in secrets.json', 'missing or invalid entries')
    for name in names:
        value = values.get(name)
        if not isinstance(value, str) or not value:
            reject('PRE-SECRETS-INVALID', 'Complete ' + group + ' entries in secrets.json', 'missing or invalid entries')
        if '\n' in value or '\r' in value:
            reject('PRE-SECRETS-INVALID', 'Complete ' + group + ' entries in secrets.json', 'missing or invalid entries')


def _validate_secret_keys(data):
    for key in ('lookup', 'comparison', 'invitation'):
        try:
            valid = isinstance(data.get(key), str) and len(base64.b64decode(data[key], validate=True)) == 32
        except ValueError:
            valid = False
        if not valid:
            reject('PRE-SECRETS-INVALID', '32-byte base64 key: ' + key, 'missing or invalid key')


def credentials(path, roles, users):
    data = document(path)
    if not isinstance(data, dict):
        reject('PRE-SECRETS-INVALID', 'Complete secrets.json object', 'invalid object')
    for group, names in (('passwords', (*roles, 'pocoma', 'keycloak')), ('users', users)):
        _validate_credential_group(data, group, names)
    if any(not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,}', value)
           for value in data['passwords'].values()):
        reject('PRE-SECRETS-INVALID', 'Generated URL-safe technical passwords (at least 32 characters)', 'invalid password format')
    _validate_secret_keys(data)
    return data


def _is_hex(value, length):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{' + str(length) + r'}', value) is not None


def _validate_manifest_identity(path, m):
    if not isinstance(m, dict) or m.get('schema') != 1:
        reject('PRE-MANIFEST-IDENTITY', 'Valid owned instance manifest', 'incomplete or inconsistent manifest')
    if m.get('instance') != path.name or m.get('state') not in ('NEW', 'RUNNING', 'STOPPED'):
        reject('PRE-MANIFEST-IDENTITY', 'Valid owned instance manifest', 'incomplete or inconsistent manifest')
    owner = m.get('owner')
    if not isinstance(owner, str) or not re.fullmatch(r'[0-9a-f-]{36}', owner):
        reject('PRE-MANIFEST-IDENTITY', 'Valid owned instance manifest', 'incomplete or inconsistent manifest')
    if m.get('project') != 'pocoma-lb-' + path.name + '-' + owner[:8] or not _is_hex(m.get('inputs'), 64):
        reject('PRE-MANIFEST-IDENTITY', 'Valid owned instance manifest', 'incomplete or inconsistent manifest')


def manifest(path):
    m = document(path / 'manifest.json')
    _validate_manifest_identity(path, m)
    ports = m.get('ports')
    if not isinstance(ports, dict) or len(set(ports.values())) != len(ports):
        reject('PRE-CONFIG-PORTS', 'Distinct TCP ports in 1..65535', 'invalid manifest ports')
    if any(type(v) is not int or not 0 < v < 65536 for v in ports.values()):
        reject('PRE-CONFIG-PORTS', 'Distinct TCP ports in 1..65535', 'invalid manifest ports')
    return m


def _validate_build_and_issuer(path, m, runtimes):
    modules = {value[0] for value in runtimes.values()}
    build = m.get('build')
    if not isinstance(build, dict) or not isinstance(m.get('images'), dict):
        reject('PRE-CONFIG-BUILD', 'Complete recorded runtime artifacts and images', 'inconsistent manifest')
    jars = build.get('jars')
    if not isinstance(jars, dict) or set(jars) != modules or not _is_hex(build.get('sha'), 40):
        reject('PRE-CONFIG-BUILD', 'Complete recorded runtime artifacts and images', 'inconsistent manifest')
    expected_issuer = 'http://localhost:' + str(m['ports']['issuer']) + '/realms/pocoma'
    if m.get('issuer') != expected_issuer:
        reject('PRE-CONFIG-ISSUER', 'Issuer matching the retained port', 'inconsistent issuer')
    _validate_jar_hashes(path, jars)


def _validate_jar_hashes(path, jars):
    for module, expected in jars.items():
        if not isinstance(expected, str) or hashlib.sha256((path / (module + '.jar')).read_bytes()).hexdigest() != expected:
            reject('PRE-CONFIG-JAR', 'Original runtime JAR: ' + module, 'artifact hash mismatch')


def _validate_pg_and_realm_files(path, data, users):
    if (path / 'pg-password').read_text() != data['passwords']['pocoma']:
        reject('PRE-CONFIG-SECRETS', 'Original matching PostgreSQL password file', 'credential mismatch')
    expected_sql = "CREATE ROLE keycloak LOGIN PASSWORD '" + data['passwords']['keycloak'] + "';\nCREATE SCHEMA keycloak AUTHORIZATION keycloak;\n"
    if (path / 'pg-init.sql').read_text() != expected_sql:
        reject('PRE-CONFIG-SECRETS', 'Original matching PostgreSQL initialization file', 'configuration mismatch')
    _validate_realm_users(path, data, users)


def _validate_realm_users(path, data, users):
    realm = document(path / 'realm.json')
    actual_users = realm.get('users') if isinstance(realm, dict) else None
    if not isinstance(actual_users, list) or len(actual_users) != len(users):
        reject('PRE-CONFIG-REALM', 'Original local user configuration', 'inconsistent user templates')
    if {u.get('username') for u in actual_users if isinstance(u, dict)} != set(users):
        reject('PRE-CONFIG-REALM', 'Original local user configuration', 'inconsistent user templates')
    for user in actual_users:
        expected_creds = [dict(type='password', value=data['users'].get(user.get('username')), temporary=False)]
        if not isinstance(user, dict) or user.get('credentials') != expected_creds:
            reject('PRE-CONFIG-SECRETS', 'Original matching realm credentials', 'credential mismatch')


def _published_ports(m, runtimes):
    published = {name: [m['ports'][name]] for name in runtimes}
    published['http-write'] += [m['ports']['write']]
    published['http-read'] += [m['ports']['read']]
    published.update(keycloak=[m['ports']['issuer']], prometheus=[m['ports']['metrics']])
    if 'grafana' in m['ports']:
        published['grafana'] = [m['ports']['grafana']]
    return published


def _validate_single_service(name, service, image, expected_ports):
    if not isinstance(service, dict) or not isinstance(image, dict):
        reject('PRE-CONFIG-COMPOSE', 'Recorded native image for ' + name, 'image mismatch')
    if not image.get('digest') or service.get('image') != image['digest']:
        reject('PRE-CONFIG-COMPOSE', 'Recorded native image for ' + name, 'image mismatch')
    ports = service.get('ports', [])
    if not isinstance(ports, list) or not all(isinstance(v, str) for v in ports):
        reject('PRE-CONFIG-COMPOSE', 'Original loopback ports for ' + name, 'port mismatch')
    loopback = [v.split(':')[1] for v in ports if v.startswith('127.0.0.1:')]
    if sorted(loopback) != sorted(str(v) for v in expected_ports) or len(ports) != len(expected_ports):
        reject('PRE-CONFIG-COMPOSE', 'Original loopback ports for ' + name, 'port mismatch')


def _validate_compose_services(m, services, runtimes, data):
    images = m['images']
    published = _published_ports(m, runtimes)
    for name, service in services.items():
        role = 'jre' if name in runtimes else name
        _validate_single_service(name, service, images.get(role), published.get(name, []))
    env = services['keycloak'].get('environment')
    if not isinstance(env, dict) or env.get('KC_DB_PASSWORD') != data['passwords']['keycloak']:
        reject('PRE-CONFIG-SECRETS', 'Original Keycloak database configuration', 'credential mismatch')


def _read_properties_file(path):
    values = {}
    for line in path.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    return values


def _validate_single_runtime_properties(path, name, data):
    values = _read_properties_file(path / (name + '.properties'))
    passwords = {key: value for key, value in values.items() if key.endswith('.password')}
    if not passwords:
        reject('PRE-CONFIG-PROPERTIES', 'Runtime database configuration: ' + name, 'missing credential entries')
    for key, value in passwords.items():
        role = values.get(key[:-len('password')] + 'username')
        if role not in data['passwords'] or value != data['passwords'][role]:
            reject('PRE-CONFIG-SECRETS', 'Original database credentials for ' + name, 'credential mismatch')
    if any(values.get(key) != data[secret] for key, secret in _expected_secret_keys(name)):
        reject('PRE-CONFIG-SECRETS', 'Original application keys for ' + name, 'key mismatch')


def _expected_secret_keys(name):
    if name == 'http-write':
        return (('pocoma.v3.http.admission.lookup-key', 'lookup'),
                ('pocoma.v3.http.admission.comparison-keys.k1', 'comparison'))
    if name in ('http-read', 'command-worker'):
        return (('pocoma.v3.invitation.keys.local', 'invitation'),)
    return ()


def _validate_runtime_properties(path, runtimes, data):
    for name in runtimes:
        _validate_single_runtime_properties(path, name, data)


def deployment(path, m, services, runtimes, roles, users):
    """Validate the persisted inputs actually consumed on restart, without rendering."""
    data = credentials(path / 'secrets.json', roles, users)
    _validate_build_and_issuer(path, m, runtimes)
    _validate_pg_and_realm_files(path, data, users)
    _validate_compose_services(m, services, runtimes, data)
    _validate_runtime_properties(path, runtimes, data)


def _handle_missing_manifest(path, command):
    retained = {'lock', 'runs'}
    if path.exists() and any(p.name not in retained for p in path.iterdir()):
        reject('PRE-PARTIAL-INSTANCE', 'Manifest for existing instance files', 'partial instance without manifest',
               'Restore the original manifest from backup; preserve files and inspect ownership. No automatic recovery.')
    if command != 'start':
        reject('PRE-MANIFEST-MISSING', 'Existing instance manifest', 'manifest absent')
    return None


def _expected_deployed_files(m, runtimes):
    required = ['pg-password', 'pg-init.sql', 'realm.json', 'prometheus.yml']
    required += [name + '.properties' for name in runtimes]
    required += [module + '.jar' for module in sorted({value[0] for value in runtimes.values()})]
    if 'grafana' in m['ports']:
        required += ['grafana/provisioning/datasources/prometheus.yml',
                     'grafana/provisioning/dashboards/dashboards.yml',
                     'grafana/dashboards/pocoma-runtime.json', 'grafana/dashboards/pocoma-v3-overview.json']
    return required


def _validate_deployed_service_files(path, m, runtimes):
    config = document(path / 'compose.json')
    services = config.get('services') if isinstance(config, dict) else None
    expected_services = {*runtimes, 'postgres', 'keycloak', 'prometheus'}
    if not isinstance(services, dict) or not expected_services.issubset(services):
        reject('PRE-CONFIG-COMPOSE', 'Complete owned service configuration', 'missing services')
    for name in _expected_deployed_files(m, runtimes):
        if not (path / name).is_file():
            reject('PRE-PARTIAL-INSTANCE', 'Existing instance file: ' + name, 'missing file')
    return services


def instance(path, command, runtimes, roles, users):
    if not (path / 'manifest.json').exists():
        return _handle_missing_manifest(path, command)
    m = manifest(path)
    if command in ('start', 'reload', 'upgrade-grafana', 'bruno'):
        if not (path / 'secrets.json').is_file():
            reject('PRE-SECRETS-MISSING', 'Original secrets.json for this instance', 'secrets absent',
                   'Restore the original secrets from backup; preserve data. Secrets will not be regenerated.')
        credentials(path / 'secrets.json', roles, users)
    if command not in ('start', 'reload', 'upgrade-grafana'):
        return m
    if not {'write', 'read', 'issuer', *runtimes, 'metrics'}.issubset(m['ports']):
        reject('PRE-CONFIG-PORTS', 'Complete endpoint ports', 'missing manifest ports')
    deployed = (path / 'compose.json').exists()
    if m['state'] != 'NEW' and not deployed:
        reject('PRE-PARTIAL-INSTANCE', 'Existing compose.json', 'deployment configuration absent')
    if deployed:
        services = _validate_deployed_service_files(path, m, runtimes)
        deployment(path, m, services, runtimes, roles, users)
    return m
