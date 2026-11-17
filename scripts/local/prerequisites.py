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
    if (not isinstance(data, dict) or any(type(data.get(key)) is not int or data[key] < 1
            for key in ('javaMajor', 'nodeMajor', 'npmMinimumMajor'))
            or not isinstance(data.get('brunoVersion'), str) or not re.fullmatch(r'\d+\.\d+\.\d+', data['brunoVersion'])):
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


def tools(root, command, dry_run=False, *, build_required=True):
    expected = contract(root)
    probe('git', ['rev-parse', '--verify', 'HEAD'], 'Git and repository metadata',
          'Install Git and use a Git clone, not a source archive; retry.', cwd=root)
    probe('docker', ['--version'], 'Docker CLI', 'Install Docker Desktop/the Docker CLI and retry.')
    probe('docker', ['info', '--format', '{{json .ServerVersion}}'], 'Accessible Docker daemon',
          'Start Docker; verify its context/socket permissions, then retry. No instance was changed.')
    probe('docker', ['compose', 'version', '--short'], 'Docker Compose plugin',
          'Install/enable the docker compose plugin (not only docker-compose), then retry.')
    starting = command in ('start', 'reload', 'upgrade-grafana', 'doctor') or (command == 'reset' and not dry_run)
    if starting:
        probe('docker', ['compose', '-f', '-', 'config', '--quiet'], 'Compose support for the distribution configuration',
              'Update the docker compose plugin to support JSON, limits, healthchecks and tmpfs, then retry.',
              input=json.dumps(dict(services=dict(check=dict(image='pocoma-probe:unused', mem_limit='64m', cpus=.5,
                  tmpfs=['/tmp:rw,size=1m'], healthcheck=dict(test=['CMD', 'true']), labels={'io.pocoma.probe':'true'})))))
    if starting or command == 'bruno':
        major('node', ['--version'], expected.get('nodeMinimumMajor', expected['nodeMajor']), minimum=True)
        cli = root / 'scripts/bruno-v3/node_modules/.bin/bru'
        if cli.exists():
            value = probe(cli, ['--version'], 'Bruno CLI ' + expected['brunoVersion'],
                          'Restore the locked local Bruno dependencies with npm ci --prefix scripts/bruno-v3; retry.')
            if value != expected['brunoVersion']:
                reject('PRE-BRUNO-VERSION', 'Bruno CLI ' + expected['brunoVersion'],
                       'different or unrecognized version', 'Restore the locked local Bruno dependencies and retry.')
        else:
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
    if starting:
        for name in ('maven.config', 'jvm.config', 'extensions.xml'):
            if (root / 'app/.mvn' / name).exists():
                reject('PRE-UNREVIEWED-MAVEN-CONFIG', 'Reviewed Maven configuration', 'app/.mvn/' + name + ' exists')
        major('java', ['-version'], expected['javaMajor'])
        if build_required:
            java_home = os.environ.get('JAVA_HOME')
            java = str(Path(java_home) / 'bin/java') if java_home else 'java'
            javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
            if java_home:
                major(java, ['-version'], expected['javaMajor'])
            major(javac, ['-version'], expected['javaMajor'])
        if build_required and not os.access(root / 'app/mvnw', os.X_OK):
            reject('PRE-WRAPPER', 'Executable app/mvnw', 'missing or not executable')


def sources(root, runtimes):
    files = ['app/pom.xml', 'app/mvnw', 'app/.mvn/wrapper/maven-wrapper.properties',
             'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json',
             'scripts/bruno-playground/inventory.json', 'scripts/functional-auth-proof.py',
             'scripts/operations/setup-v3-database.py', 'docker/keycloak/pocoma-realm.json',
             'docker/grafana/provisioning/datasources/prometheus.yml',
             'docker/grafana/provisioning/dashboards/dashboards.yml',
             'docker/grafana/dashboards/pocoma-runtime.json', 'docker/grafana/dashboards/pocoma-v3-overview.json']
    files += ['docker/v3-five-runtimes/' + name + '.properties' for name in runtimes]
    helper = root / 'scripts/operations/setup-v3-database.py'
    if not helper.is_file():
        reject('PRE-CONFIG-FILE', str(helper), 'missing')
    # Current helper owns literal SQL inputs. Read its AST, never execute it here.
    try:
        tree = ast.parse(helper.read_text())
    except (OSError, SyntaxError):
        reject('PRE-CONFIG-FILE', str(helper), 'unreadable or invalid Python')
    files += [node.value if '/' in node.value else 'scripts/v3/' + node.value
              for node in ast.walk(tree) if isinstance(node, ast.Constant)
              and isinstance(node.value, str) and node.value.endswith('.sql')]
    for name in files:
        path = root / name
        if not path.is_file() or not os.access(path, os.R_OK):
            reject('PRE-CONFIG-FILE', name, 'missing or unreadable')
        if path.suffix == '.json':
            document(path)
    realm = document(root / 'docker/keycloak/pocoma-realm.json')
    if (not isinstance(realm, dict) or any(not isinstance(realm.get(group), list) or not realm[group]
            or not all(isinstance(value, dict) for value in realm[group]) for group in ('users', 'clients'))):
        reject('PRE-CONFIG-REALM', 'Realm users and clients templates', 'incomplete configuration')


def credentials(path, roles, users):
    data = document(path)
    if not isinstance(data, dict):
        reject('PRE-SECRETS-INVALID', 'Complete secrets.json object', 'invalid object')
    for group, names in (('passwords', (*roles, 'pocoma', 'keycloak')), ('users', users)):
        values = data.get(group)
        if not isinstance(values, dict) or any(not isinstance(values.get(name), str) or not values[name]
                                               or '\n' in values[name] or '\r' in values[name] for name in names):
            reject('PRE-SECRETS-INVALID', 'Complete ' + group + ' entries in secrets.json', 'missing or invalid entries')
    if any(not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,}', value)
           for value in data['passwords'].values()):
        reject('PRE-SECRETS-INVALID', 'Generated URL-safe technical passwords (at least 32 characters)', 'invalid password format')
    for key in ('lookup', 'comparison', 'invitation'):
        try:
            valid = isinstance(data.get(key), str) and len(base64.b64decode(data[key], validate=True)) == 32
        except ValueError:
            valid = False
        if not valid:
            reject('PRE-SECRETS-INVALID', '32-byte base64 key: ' + key, 'missing or invalid key')
    return data


def manifest(path):
    m = document(path / 'manifest.json')
    if (not isinstance(m, dict) or m.get('schema') != 1 or m.get('instance') != path.name
            or not isinstance(m.get('owner'), str) or not re.fullmatch(r'[0-9a-f-]{36}', m['owner'])
            or m.get('project') != 'pocoma-lb-' + path.name + '-' + m['owner'][:8]
            or not isinstance(m.get('inputs'), str) or not re.fullmatch(r'[0-9a-f]{64}', m['inputs'])
            or m.get('state') not in ('NEW', 'RUNNING', 'STOPPED')):
        reject('PRE-MANIFEST-IDENTITY', 'Valid owned instance manifest', 'incomplete or inconsistent manifest')
    ports = m.get('ports')
    if (not isinstance(ports, dict) or any(type(v) is not int or not 0 < v < 65536 for v in ports.values())
            or len(set(ports.values())) != len(ports)):
        reject('PRE-CONFIG-PORTS', 'Distinct TCP ports in 1..65535', 'invalid manifest ports')
    return m


def deployment(path, m, services, runtimes, roles, users):
    """Validate the persisted inputs actually consumed on restart, without rendering."""
    data = credentials(path / 'secrets.json', roles, users)
    modules = {value[0] for value in runtimes.values()}
    build = m.get('build')
    images = m.get('images')
    if (not isinstance(build, dict) or not isinstance(build.get('jars'), dict)
            or set(build['jars']) != modules or not isinstance(images, dict)
            or not isinstance(build.get('sha'), str) or not re.fullmatch(r'[0-9a-f]{40}', build['sha'])):
        reject('PRE-CONFIG-BUILD', 'Complete recorded runtime artifacts and images', 'inconsistent manifest')
    expected_issuer = 'http://localhost:' + str(m['ports']['issuer']) + '/realms/pocoma'
    if m.get('issuer') != expected_issuer:
        reject('PRE-CONFIG-ISSUER', 'Issuer matching the retained port', 'inconsistent issuer')
    for module, expected in build['jars'].items():
        if not isinstance(expected, str) or hashlib.sha256((path / (module + '.jar')).read_bytes()).hexdigest() != expected:
            reject('PRE-CONFIG-JAR', 'Original runtime JAR: ' + module, 'artifact hash mismatch')
    if (path / 'pg-password').read_text() != data['passwords']['pocoma']:
        reject('PRE-CONFIG-SECRETS', 'Original matching PostgreSQL password file', 'credential mismatch')
    expected_sql = "CREATE ROLE keycloak LOGIN PASSWORD '" + data['passwords']['keycloak'] + "';\nCREATE SCHEMA keycloak AUTHORIZATION keycloak;\n"
    if (path / 'pg-init.sql').read_text() != expected_sql:
        reject('PRE-CONFIG-SECRETS', 'Original matching PostgreSQL initialization file', 'configuration mismatch')
    realm = document(path / 'realm.json')
    actual_users = realm.get('users') if isinstance(realm, dict) else None
    if (not isinstance(actual_users, list) or len(actual_users) != len(users)
            or {u.get('username') for u in actual_users if isinstance(u, dict)} != set(users)):
        reject('PRE-CONFIG-REALM', 'Original local user configuration', 'inconsistent user templates')
    for user in actual_users:
        if (not isinstance(user, dict) or user.get('credentials') !=
                [dict(type='password', value=data['users'].get(user.get('username')), temporary=False)]):
            reject('PRE-CONFIG-SECRETS', 'Original matching realm credentials', 'credential mismatch')
    published = {name: [m['ports'][name]] for name in runtimes}
    published['http-write'] += [m['ports']['write']]
    published['http-read'] += [m['ports']['read']]
    published.update(keycloak=[m['ports']['issuer']], prometheus=[m['ports']['metrics']])
    if 'grafana' in m['ports']:
        published['grafana'] = [m['ports']['grafana']]
    for name, service in services.items():
        role = 'jre' if name in runtimes else name
        image = images.get(role)
        if (not isinstance(service, dict) or not isinstance(image, dict)
                or not image.get('digest') or service.get('image') != image['digest']):
            reject('PRE-CONFIG-COMPOSE', 'Recorded native image for ' + name, 'image mismatch')
        ports = service.get('ports', [])
        if (not isinstance(ports, list) or not all(isinstance(v, str) for v in ports)
                or sorted(v.split(':')[1] for v in ports if v.startswith('127.0.0.1:')) !=
                sorted(str(v) for v in published.get(name, [])) or len(ports) != len(published.get(name, []))):
            reject('PRE-CONFIG-COMPOSE', 'Original loopback ports for ' + name, 'port mismatch')
    env = services['keycloak'].get('environment')
    if not isinstance(env, dict) or env.get('KC_DB_PASSWORD') != data['passwords']['keycloak']:
        reject('PRE-CONFIG-SECRETS', 'Original Keycloak database configuration', 'credential mismatch')
    for name in runtimes:
        values = {}
        for line in (path / (name + '.properties')).read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
        passwords = {key: value for key, value in values.items() if key.endswith('.password')}
        if not passwords:
            reject('PRE-CONFIG-PROPERTIES', 'Runtime database configuration: ' + name, 'missing credential entries')
        for key, value in passwords.items():
            role = values.get(key[:-len('password')] + 'username')
            if role not in data['passwords'] or value != data['passwords'][role]:
                reject('PRE-CONFIG-SECRETS', 'Original database credentials for ' + name, 'credential mismatch')
        keys = (('pocoma.v3.http.admission.lookup-key', 'lookup'),
                ('pocoma.v3.http.admission.comparison-keys.k1', 'comparison')) if name == 'http-write' else ()
        if name in ('http-read', 'command-worker'):
            keys = (('pocoma.v3.invitation.keys.local', 'invitation'),)
        if any(values.get(key) != data[secret] for key, secret in keys):
            reject('PRE-CONFIG-SECRETS', 'Original application keys for ' + name, 'key mismatch')


def instance(path, command, runtimes, roles, users):
    if not (path / 'manifest.json').exists():
        retained = {'lock', 'runs'}
        if path.exists() and any(p.name not in retained for p in path.iterdir()):
            reject('PRE-PARTIAL-INSTANCE', 'Manifest for existing instance files', 'partial instance without manifest',
                   'Restore the original manifest from backup; preserve files and inspect ownership. No automatic recovery.')
        if command != 'start':
            reject('PRE-MANIFEST-MISSING', 'Existing instance manifest', 'manifest absent')
        return None
    m = manifest(path)
    if command in ('start', 'reload', 'upgrade-grafana', 'bruno'):
        if not (path / 'secrets.json').is_file():
            reject('PRE-SECRETS-MISSING', 'Original secrets.json for this instance', 'secrets absent',
                   'Restore the original secrets from backup; preserve data. Secrets will not be regenerated.')
        credentials(path / 'secrets.json', roles, users)
    if command in ('start', 'reload', 'upgrade-grafana'):
        expected = {'write', 'read', 'issuer', *runtimes, 'metrics'}
        if not expected.issubset(m['ports']):
            reject('PRE-CONFIG-PORTS', 'Complete endpoint ports', 'missing manifest ports')
        deployed = (path / 'compose.json').exists()
        if m['state'] != 'NEW' and not deployed:
            reject('PRE-PARTIAL-INSTANCE', 'Existing compose.json', 'deployment configuration absent')
        if deployed:
            config = document(path / 'compose.json')
            services = config.get('services') if isinstance(config, dict) else None
            expected_services = {*runtimes, 'postgres', 'keycloak', 'prometheus'}
            if not isinstance(services, dict) or not expected_services.issubset(services):
                reject('PRE-CONFIG-COMPOSE', 'Complete owned service configuration', 'missing services')
            required = ['pg-password', 'pg-init.sql', 'realm.json', 'prometheus.yml']
            required += [name + '.properties' for name in runtimes]
            required += [module + '.jar' for module in sorted({value[0] for value in runtimes.values()})]
            if 'grafana' in m['ports']:
                required += ['grafana/provisioning/datasources/prometheus.yml',
                             'grafana/provisioning/dashboards/dashboards.yml',
                             'grafana/dashboards/pocoma-runtime.json', 'grafana/dashboards/pocoma-v3-overview.json']
            for name in required:
                if not (path / name).is_file():
                    reject('PRE-PARTIAL-INSTANCE', 'Existing instance file: ' + name, 'missing file')
            deployment(path, m, services, runtimes, roles, users)
    return m
