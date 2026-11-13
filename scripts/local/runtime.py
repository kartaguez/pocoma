#!/usr/bin/env python3
"""Owned, persistent local five-JVM playground. All mutable inputs are private."""
import argparse
import base64
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from uuid import uuid4
from urllib.request import urlopen

from auth import interactive, login, prove_pkce
from bootstrap import fingerprint, initialize, ROLES
from public_api import PublicAPI, canary, seed

ROOT = Path(__file__).resolve().parents[2]
HOME_LOCAL = ROOT / '.pocoma-local'
RUNTIMES = {
    'http-write': ('web-write', 'v3-http-admission', 8080, 18080),
    'http-read': ('web-read', 'v3-http-read', 8082, 18082),
    'command-worker': ('worker-process', 'v3-consumption-worker-dev', 0, 18083),
    'results-worker': ('worker-materialize', 'v3-read-worker', 0, 18084),
    'business-read-worker': ('worker-materialize', 'v3-read-worker', 0, 18085),
}
IMAGES = {'postgres': 'postgres:17.6-alpine', 'keycloak': 'quay.io/keycloak/keycloak:26.7.0',
          'jre': 'eclipse-temurin:21.0.8_9-jre', 'prometheus': 'prom/prometheus:v3.5.0'}
LABEL = 'io.pocoma.local.owner'
MAX_AGE = 300


class Refusal(RuntimeError):
    def __init__(self, control, code=2):
        super().__init__(control)
        self.code = code


def run(args, *, input=None, timeout=120, log=None):
    result = subprocess.run([str(a) for a in args], input=input, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, cwd=ROOT)
    if log:
        log.write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('COMMAND-FAILED:' + str(args[0]) + ' (see private diagnostics)')
    return result.stdout.strip()


def save(path, data):
    temp = path.with_suffix('.new')
    temp.write_text(json.dumps(data, indent=2) + '\n')
    temp.chmod(0o600)
    temp.replace(path)


def read(path):
    return json.loads(path.read_text())


def source_hash():
    # Product inputs, configs, SQL catalogue, oracles and distribution toolchain.
    paths = run(['git', 'ls-files', 'app', 'docker/v3-five-runtimes', 'scripts/local',
                 'scripts/operations/setup-v3-database.py', 'scripts/v3', 'scripts/functional-auth-proof.py',
                 'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json']).splitlines()
    paths = set(paths) | {str(p.relative_to(ROOT)) for p in (ROOT / 'scripts/local').glob('*.py') if not p.name.startswith('test_')}
    paths |= {str(p.relative_to(ROOT)) for p in (ROOT / 'scripts/bruno-playground').rglob('*') if p.is_file() and not (p.parent.name == 'environments' and p.name.startswith('Instance-'))}
    paths.update(['docker/keycloak/pocoma-realm.json', 'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json'])
    return hashlib.sha256(''.join(p + ':' + hashlib.sha256((ROOT / p).read_bytes()).hexdigest() + '\n'
                                 for p in sorted(paths) if (ROOT / p).is_file() and not Path(p).name.startswith('test_')).encode()).hexdigest()


def safe_tree(path):
    # Refuse symlinks and permissive state, including any ancestor below repo root.
    for candidate in [HOME_LOCAL, *path.parents, path]:
        if candidate == ROOT or ROOT not in candidate.parents:
            continue
        if candidate.is_symlink():
            raise Refusal('PRE-SYMLINK')
        if candidate.exists() and candidate.stat().st_mode & 0o077:
            raise Refusal('PRE-PRIVATE-PERMISSIONS')
    if path.exists():
        for child in path.rglob('*'):
            if child.is_symlink():
                raise Refusal('PRE-SYMLINK')


@contextmanager
def locked(instance, create=False):
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,30}', instance):
        raise Refusal('PRE-INSTANCE-ID')
    path = HOME_LOCAL / 'instances' / instance
    safe_tree(HOME_LOCAL)
    safe_tree(path)
    if not path.exists() and not create:
        raise Refusal('PRE-UNKNOWN-INSTANCE')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (path / 'lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('PRE-INSTANCE-LOCKED', 3) from None
        yield path


@contextmanager
def lifecycle_lock(command):
    if command not in ('start', 'reset'):
        yield
        return
    with (HOME_LOCAL / 'lifecycle.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('PRE-LIFECYCLE-LOCKED', 3) from None
        yield


def allocate_ports():
    sockets = []
    try:
        for _ in range(9):
            s = socket.socket()
            s.bind(('127.0.0.1', 0))
            sockets.append(s)
        return dict(zip(['write', 'read', 'issuer', *RUNTIMES, 'metrics'], [s.getsockname()[1] for s in sockets]))
    finally:
        for s in sockets:
            s.close()


def new_manifest(path, instance):
    owner = str(uuid4())
    return dict(schema=1, instance=instance, owner=owner,
                project='pocoma-lb-' + instance + '-' + owner[:8], ports=allocate_ports(),
                sha=run(['git', 'rev-parse', 'HEAD']), inputs=source_hash(), state='NEW')


def load_manifest(path):
    m = read(path / 'manifest.json')
    if (m['schema'] != 1 or m['instance'] != path.name or
        not re.fullmatch(r'[0-9a-f-]{36}', m['owner']) or
        m['project'] != 'pocoma-lb-' + path.name + '-' + m['owner'][:8]):
        raise Refusal('PRE-MANIFEST-IDENTITY')
    return m


def docker_json(*args):
    return json.loads(run(['docker', *args]))


def ownership(m):
    """Check every project object and refuse extra/foreign state before mutation."""
    project = m['project']
    for kind, query, inspect in (
        ('container', ['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project], ['inspect']),
        ('network', ['network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project], ['network', 'inspect']),
        ('volume', ['volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project], ['volume', 'inspect'])):
        ids = run(['docker', *query]).splitlines()
        for resource in ids:
            value, = docker_json(*inspect, resource)
            labels = value.get('Config', {}).get('Labels', {}) if kind == 'container' else value.get('Labels', {})
            if labels.get(LABEL) != m['owner']:
                raise Refusal('PRE-FOREIGN-' + kind.upper())
            if kind == 'volume' and value['Name'] != project + '-data':
                raise Refusal('PRE-EXTRA-VOLUME')
            if kind == 'network' and value['Name'] != project + '_default':
                raise Refusal('PRE-EXTRA-NETWORK')
            if kind == 'container':
                service = labels.get('com.docker.compose.service')
                if service not in {*RUNTIMES, 'postgres', 'keycloak', 'prometheus'}:
                    raise Refusal('PRE-EXTRA-CONTAINER')
    # A name collision without a project label must not be adopted by Compose.
    for kind, name in [('volume', project + '-data'), ('network', project + '_default')]:
        result = subprocess.run(['docker', kind, 'inspect', name], capture_output=True, text=True)
        if result.returncode == 0 and json.loads(result.stdout)[0].get('Labels', {}).get(LABEL) != m['owner']:
            raise Refusal('PRE-FOREIGN-' + kind.upper())


def compose(path, m, *args, timeout=180, log=None):
    return run(['docker', 'compose', '-p', m['project'], '-f', path / 'compose.json', *args], timeout=timeout, log=log)


def pg_id(path, m):
    value = compose(path, m, 'ps', '-q', 'postgres')
    if not value:
        raise RuntimeError('DB-NOT-RUNNING')
    return value


def sql(path, m, database, text):
    # stdin, no password args/environment; local socket inside owned PG only.
    return run(['docker', 'exec', '-i', pg_id(path, m), 'psql', '-X', '-q', '-tA',
                '-v', 'ON_ERROR_STOP=1', '-U', 'pocoma', '-d', database], input=text, timeout=90)


def private(path):
    file = path / 'secrets.json'
    if file.exists():
        return read(file)
    key = lambda: base64.b64encode(secrets.token_bytes(32)).decode()
    data = dict(passwords={role: secrets.token_urlsafe(32) for role in (*ROLES, 'pocoma', 'keycloak')},
                users={name: secrets.token_urlsafe(24) for name in ('bruno', 'guest', 'third')},
                lookup=key(), comparison=key(), invitation=key())
    save(file, data)
    return data


def prepare_images(path, m, evidence):
    images = {}
    for role, reference in IMAGES.items():
        try:
            value, = docker_json('image', 'inspect', reference)
        except RuntimeError:
            run(['docker', 'pull', reference], timeout=600, log=evidence / ('pull-' + role + '.log'))
            value, = docker_json('image', 'inspect', reference)
        if value['Architecture'] not in ('arm64', 'amd64'):
            raise Refusal('PRE-IMAGE-ARCH')
        if value['Architecture'] != m['architecture']:
            raise Refusal('PRE-IMAGE-NON-NATIVE')
        digest = next((d for d in value['RepoDigests'] if d.startswith(reference.split(':')[0] + '@')), None)
        if not digest:
            raise Refusal('PRE-IMAGE-DIGEST')
        images[role] = {'reference': reference, 'digest': digest, 'id': value['Id'], 'architecture': value['Architecture']}
    return images


def render(path, m, credentials):
    labels = {LABEL: m['owner']}
    password = path / 'pg-password'
    password.write_text(credentials['passwords']['pocoma'])
    # PostgreSQL's unprivileged entrypoint reads this private mount after gosu.
    password.chmod(0o644)
    realm = read(ROOT / 'docker/keycloak/pocoma-realm.json')
    template = realm['users'][0]
    realm['users'] = [dict(template, username=user, email=user + '@pocoma.local',
                           credentials=[dict(type='password', value=value, temporary=False)])
                      for user, value in credentials['users'].items()]
    save(path / 'realm.json', realm)
    (path / 'realm.json').chmod(0o644)
    (path / 'pg-init.sql').write_text("CREATE ROLE keycloak LOGIN PASSWORD '" + credentials['passwords']['keycloak'] +
                                     "';\nCREATE SCHEMA keycloak AUTHORIZATION keycloak;\n")
    (path / 'pg-init.sql').chmod(0o644)
    services = {}
    services['postgres'] = dict(image=m['images']['postgres']['digest'], labels=labels,
        command=['postgres', '-c', 'log_min_error_statement=panic'],
        environment=dict(POSTGRES_USER='pocoma', POSTGRES_DB='pocoma', POSTGRES_PASSWORD_FILE='/run/pg-password'),
        volumes=['data:/var/lib/postgresql/data', f'{path}/pg-password:/run/pg-password:ro',
                 f'{path}/pg-init.sql:/docker-entrypoint-initdb.d/01-keycloak.sql:ro'],
        mem_limit='1024m', cpus=1.5,
        healthcheck=dict(test=['CMD-SHELL', 'pg_isready -U pocoma -d pocoma'], interval='2s', timeout='3s', retries=40))
    ports = m['ports']
    issuer = f'http://localhost:{ports["issuer"]}/realms/pocoma'
    services['keycloak'] = dict(image=m['images']['keycloak']['digest'], labels=labels,
        command=['start-dev', '--import-realm', '--hostname=http://localhost:' + str(ports['issuer']), '--health-enabled=true'],
        environment=dict(KC_DB='postgres', KC_DB_URL='jdbc:postgresql://postgres:5432/pocoma', KC_DB_SCHEMA='keycloak',
                         KC_DB_USERNAME='keycloak', KC_DB_PASSWORD=credentials['passwords']['keycloak'],
                         JAVA_OPTS_KC_HEAP='-Xms128m -Xmx512m'),
        volumes=[f'{path}/realm.json:/opt/keycloak/data/import/pocoma-realm.json:ro'],
        ports=[f'127.0.0.1:{ports["issuer"]}:8080'], mem_limit='896m', cpus=1.5)
    for name, (module, profile, app_port, management) in RUNTIMES.items():
        services[name] = dict(image=m['images']['jre']['digest'], labels=labels,
            entrypoint=['java', '-Xms64m', '-Xmx384m', '-XX:+UseSerialGC', '-jar', '/app/runtime.jar',
                        '--spring.profiles.active=' + profile, '--spring.config.additional-location=file:/app/settings.properties'],
            volumes=[f'{path}/{module}.jar:/app/runtime.jar:ro', f'{path}/{name}.properties:/app/settings.properties:ro'],
            ports=[f'127.0.0.1:{ports[name]}:{management}'] +
                  ([f'127.0.0.1:{ports["write" if name == "http-write" else "read"]}:{app_port}'] if app_port else []),
            mem_limit='640m', cpus=1)
    (path / 'prometheus.yml').write_text('global:\n  scrape_interval: 2s\nscrape_configs:\n' + ''.join(
        f'  - job_name: {name}\n    metrics_path: /actuator/prometheus\n    static_configs:\n      - targets: ["{name}:{management}"]\n'
        for name, (_, _, _, management) in RUNTIMES.items()))
    services['prometheus'] = dict(image=m['images']['prometheus']['digest'], labels=labels,
        volumes=[f'{path}/prometheus.yml:/etc/prometheus/prometheus.yml:ro'],
        ports=[f'127.0.0.1:{ports["metrics"]}:9090'], mem_limit='256m', cpus=.5)
    save(path / 'compose.json', dict(services=services, networks={'default': {'labels': labels}},
        volumes={'data': {'name': m['project'] + '-data', 'labels': labels}}))
    return issuer


def properties(path, m, credentials):
    pg, = docker_json('inspect', pg_id(path, m))
    address = pg['NetworkSettings']['Networks'][m['project'] + '_default']['IPAddress']
    for name in RUNTIMES:
        text = (ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
        text = text.replace('172.28.0.2/32', address + '/32').replace('http://localhost:8081/realms/pocoma', m['issuer'])
        replacements = {'pocoma-local-only': credentials['passwords']['pocoma_write'],
                        'pocoma-source-local-only': credentials['passwords']['pocoma_write_source_read'],
                        'pocoma-read-http-local-only': credentials['passwords']['pocoma_read_http'],
                        'pocoma-read-status-local-only': credentials['passwords']['pocoma_read_status']}
        # Admission and READ use distinct random credentials even where old fixture shared one.
        for old, new in replacements.items():
            text = text.replace(old, new)
        for line in text.splitlines():
            if '.password=' in line:
                prefix = line.split('.password=')[0]
                username = re.search(r'(?m)^' + re.escape(prefix) + r'\.username=(.*)$', text)
                if username:
                    text = text.replace(line, prefix + '.password=' + credentials['passwords'][username[1]])
        if name == 'http-write':
            for prop, key in [('lookup-key', 'lookup'), ('comparison-keys.k1', 'comparison')]:
                text = re.sub(r'(?m)^pocoma.v3.http.admission.' + re.escape(prop) + '=.*$',
                              'pocoma.v3.http.admission.' + prop + '=' + credentials[key], text)
        if name in ('http-read', 'command-worker'):
            text += '\npocoma.v3.invitation.current-key=local\npocoma.v3.invitation.key-ids=local\npocoma.v3.invitation.keys.local=' + credentials['invitation'] + '\n'
        (path / (name + '.properties')).write_text(text)


def package(path, m, evidence):
    for variable in ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        if os.environ.get(variable):
            raise Refusal('PRE-TOOLCHAIN-ENV:' + variable)
    for name in ('maven.config', 'jvm.config', 'extensions.xml'):
        if (ROOT / 'app/.mvn' / name).exists():
            raise Refusal('PRE-UNREVIEWED-MAVEN-CONFIG:' + name)
    build = HOME_LOCAL / 'builds' / m['inputs']
    build.mkdir(parents=True, exist_ok=True)
    # One owner for Maven outputs, including between independently owned instances.
    with (HOME_LOCAL / 'build.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('PRE-BUILD-LOCKED', 3) from None
        modules = sorted({v[0] for v in RUNTIMES.values()})
        if not (build / 'receipt.json').exists():
            command = [str(ROOT / 'app/mvnw'), '-f', str(ROOT / 'app/pom.xml'), '-pl',
                       ','.join('runtime-' + module for module in modules), '-am', 'package', '-DskipTests']
            run(command, timeout=1200, log=evidence / 'package.log')
            hashes = {}
            for module in modules:
                source = ROOT / f'app/runtime-{module}/target/pocoma-runtime-{module}-0.0.1-SNAPSHOT-exec.jar'
                target = build / (module + '.jar')
                shutil.copyfile(source, target)
                hashes[module] = hashlib.sha256(target.read_bytes()).hexdigest()
            save(build / 'receipt.json', dict(command=command, sha=run(['git', 'rev-parse', 'HEAD']),
                                            inputs=m['inputs'], jars=hashes, tests='SKIPPED: packaging only'))
        receipt = read(build / 'receipt.json')
        for module, expected in receipt['jars'].items():
            source = build / (module + '.jar')
            if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                raise Refusal('PRE-JAR-HASH')
            shutil.copyfile(source, path / (module + '.jar'))
        m['build'] = receipt


def recovery_window(path, m, evidence):
    """Wait the captured physical ownership window before restarting stopped workers.

    This does not widen the public polling budget or mutate a lease. Active
    workers may renew other partitions; only the captured time is awaited.
    """
    stopped = []
    for name in ('command-worker', 'results-worker', 'business-read-worker'):
        resource = compose(path, m, 'ps', '-aq', name)
        if resource:
            value, = docker_json('inspect', resource)
            if not value['State']['Running']:
                stopped.append(name)
    if not stopped:
        return
    databases = set()
    if 'command-worker' in stopped:
        databases.add(('pocoma_write', 'v3_write'))
    if any(name != 'command-worker' for name in stopped):
        databases.add(('pocoma_read', 'v3_read'))
    observations = {}
    for db, schema in sorted(databases):
        seconds = float(sql(path, m, db, f"""
            SELECT greatest(0,coalesce(extract(epoch from (max(until)-clock_timestamp())),0))
            FROM (SELECT owned_until AS until FROM {schema}.partition_ownership
                  UNION ALL SELECT claimed_until FROM {schema}.consumption_slot) AS observed;
        """))
        if seconds > 241:
            raise Refusal('RECOVERY-WINDOW-OUTSIDE-DECLARED-LEASE')
        observations[db] = seconds
    wait_seconds = max(observations.values(), default=0)
    record = dict(stoppedWorkers=stopped, remainingSeconds=observations,
                  action='WAIT_CAPTURED_WINDOW_NO_LEASE_MUTATION', startedAt=time.time())
    save(evidence / 'recovery-window.json', record)
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        time.sleep(min(.5, max(0, deadline-time.monotonic())))
    record['completedAt'] = time.time()
    save(evidence / 'recovery-window.json', record)


def await_check(action, timeout, name):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return action()
        except Exception:
            time.sleep(.5)
    raise RuntimeError(name)


def get_json(url):
    with urlopen(url, timeout=5) as response:
        return json.load(response)


def catalogue(path, m):
    result = {}
    for db, schema in [('pocoma_write', 'v3_write'), ('pocoma_read', 'v3_read')]:
        # Schema-only pg_dump excludes user data. PG17 adds random restrict tokens.
        raw = run(['docker', 'exec', pg_id(path, m), 'pg_dump', '-U', 'pocoma', '-d', db,
                   '--schema-only', '--schema=' + schema])
        raw = '\n'.join(line for line in raw.splitlines() if not line.startswith(('\\restrict ', '\\unrestrict ')))
        result[db] = hashlib.sha256(raw.encode()).hexdigest()
    return result


def verify_acl(path, m, credentials):
    tests = [
        ('pocoma_write_source_read','pocoma_write','DELETE FROM v3_write.v3_command WHERE false;'),
        ('pocoma_admission','pocoma_write','SELECT binding_token FROM v3_write.v3_link_proposal LIMIT 0;'),
        ('pocoma_read_http','pocoma_read','DELETE FROM v3_read.read_current_pot WHERE false;'),
        ('pocoma_read_status','pocoma_read','SELECT * FROM v3_read.read_current_pot LIMIT 0;')]
    for role, db, query in tests:
        args = ['docker','exec','-i',pg_id(path,m),'sh','-c',
                'read -r PGPASSWORD; export PGPASSWORD; exec psql -X -h 127.0.0.1 -U "$1" -d "$2" -v ON_ERROR_STOP=1',
                'psql-private',role,db]
        # First prove authentication works; otherwise a bad password could fake ACL refusal.
        run(args, input=credentials['passwords'][role]+'\nSELECT current_user;\n')
        result = subprocess.run(args, input=credentials['passwords'][role]+'\n'+query+'\n', text=True, capture_output=True)
        if result.returncode == 0 or 'permission denied' not in result.stderr:
            raise RuntimeError('DB-ACL:' + role)


def current(path, m, *, wait=False):
    checks = {}
    def check(name, action):
        try:
            if wait:
                await_check(action, 150, name)
            else:
                action()
            checks[name] = 'PASS'
        except Exception:
            checks[name] = 'FAIL'
    def healthy(name):
        data = get_json(f'http://127.0.0.1:{m["ports"][name]}/actuator/health/readiness')
        if data.get('status') != 'UP':
            raise RuntimeError(name)
    for name in RUNTIMES:
        check('JVM-' + name, lambda name=name: healthy(name))
    def database():
        for db in ('pocoma_write', 'pocoma_read'):
            if sql(path, m, db, 'SELECT fingerprint FROM public.pocoma_local_bootstrap;') != fingerprint():
                raise RuntimeError('DB-FINGERPRINT')
        rows = sql(path, m, 'pocoma', "SELECT DISTINCT usename FROM pg_stat_activity WHERE datname IN ('pocoma_write','pocoma_read') AND usename <> 'pocoma' ORDER BY usename;")
        if catalogue(path, m) != m.get('catalogue'):
            raise RuntimeError('DB-CATALOGUE-DRIFT')
        if sql(path, m, 'pocoma', "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'pocoma_%' AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls);") != '0':
            raise RuntimeError('DB-PRIVILEGED-RUNTIME')
        expected = {'pocoma_write', 'pocoma_admission', 'pocoma_read', 'pocoma_write_source_read', 'pocoma_read_http', 'pocoma_read_status'}
        if set(rows.splitlines()) != expected:
            raise RuntimeError('DB-PRINCIPALS')
        if sql(path, m, 'pocoma_read', "SELECT state FROM public.read_maintenance_state;") not in ('NORMAL', 'READY'):
            raise RuntimeError('READ-MAINTENANCE')
    check('DB-CURRENT', database)
    def discovery():
        data = get_json(m['issuer'] + '/.well-known/openid-configuration')
        if data.get('issuer') != m['issuer'] or 'S256' not in data.get('code_challenge_methods_supported', []):
            raise RuntimeError('IDP-DISCOVERY')
    check('IDP-DISCOVERY', discovery)
    return checks


def observability(m, wait=False):
    def targets():
        data = get_json(f'http://127.0.0.1:{m["ports"]["metrics"]}/api/v1/targets')['data']['activeTargets']
        if {t['labels']['job'] for t in data if t['health'] == 'up'} != set(RUNTIMES):
            raise RuntimeError('OBS-TARGETS')
        for name in RUNTIMES:
            with urlopen(f'http://127.0.0.1:{m["ports"][name]}/actuator/prometheus', timeout=5) as response:
                text = response.read().decode()
            if 'jvm_memory_used_bytes' not in text or 'pocoma_' not in text:
                raise RuntimeError('OBS-METRICS')
    try:
        await_check(targets, 30, 'OBS-TOOLS') if wait else targets()
        return 'AVAILABLE'
    except Exception:
        return 'DEGRADED'


def diagnostics(path, m, evidence):
    if not (path / 'compose.json').exists():
        return
    try:
        output = compose(path, m, 'logs', '--no-color', '--tail', '250')
        values = private(path)
        for value in [*values['passwords'].values(), *values['users'].values(), values['lookup'], values['comparison'], values['invitation']]:
            output = output.replace(value, '[REDACTED]')
        output = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED-JWT]', output)
        (evidence / 'containers.log').write_text(output)
    except Exception:
        (evidence / 'diagnostics-error.txt').write_text('Container diagnostics unavailable; environment retained.\n')


def preflight(path, m):
    data = docker_json('info', '--format', '{{json .}}')
    if data['MemTotal'] < 6 * 1024**3:
        raise Refusal('PRE-RESOURCES: Docker >=6GiB')
    # Cached restart needs diagnostic headroom, not a second cold-install budget.
    cached = (HOME_LOCAL / 'builds' / m['inputs'] / 'receipt.json').exists()
    minimum = 256 * 1024**2 if cached else 2 * 1024**3
    for reference in IMAGES.values():
        result = subprocess.run(['docker','image','inspect',reference],capture_output=True,text=True)
        if result.returncode:
            minimum = max(minimum, 4 * 1024**3)
    free = shutil.disk_usage(ROOT).free
    save(path / 'resources.json', dict(dockerMemoryBytes=data['MemTotal'], cpus=data['NCPU'],
         freeDiskBytes=free, requiredDiskBytes=minimum, buildCached=cached))
    if free < minimum:
        raise Refusal('PRE-DISK: requiredBytes=' + str(minimum) + ', availableBytes=' + str(free))
    architecture = {'aarch64': 'arm64', 'x86_64': 'amd64'}.get(data['Architecture'], data['Architecture'])
    if architecture not in ('arm64', 'amd64'):
        raise Refusal('PRE-ARCHITECTURE')
    return architecture


def start(path, m, evidence):
    if m['inputs'] != source_hash():
        raise Refusal('PRE-INPUT-DRIFT: use a new instance or explicit reset')
    m['architecture'] = preflight(path, m)
    active = run(['docker', 'ps', '-q', '--filter', 'label=' + LABEL]).splitlines()
    for resource in active:
        value, = docker_json('inspect', resource)
        if value['Config']['Labels'].get(LABEL) != m['owner']:
            raise Refusal('PRE-OTHER-PLAYGROUND-ACTIVE: stop the other instance first')
    ownership(m)
    credentials = private(path)
    cli = ROOT / 'scripts/bruno-v3/node_modules/.bin/bru'
    if not cli.exists():
        run(['npm', 'ci', '--prefix', ROOT / 'scripts/bruno-v3'], timeout=300, log=evidence / 'npm.log')
    if run([cli, '--version']) != '4.3.0':
        raise Refusal('PRE-BRUNO-VERSION')
    images = prepare_images(path, m, evidence)
    if m.get('images') and m['images'] != images:
        raise Refusal('PRE-IMAGE-DIGEST-DRIFT')
    m['images'] = images
    m['toolchain'] = dict(python=sys.version, docker=run(['docker','version','--format','{{.Client.Version}}/{{.Server.Version}}']),
                          compose=run(['docker','compose','version','--short']), node=run(['node','--version']),
                          java=run(['java','--version']))
    package(path, m, evidence)
    m['issuer'] = render(path, m, credentials)
    save(path / 'manifest.json', m)
    compose(path, m, 'up', '-d', 'postgres', log=evidence / 'postgres-up.log')
    await_check(lambda: sql(path, m, 'pocoma', "SELECT 1 FROM pg_namespace WHERE nspname='keycloak';") or
                (_ for _ in ()).throw(RuntimeError()), 60, 'DB-INIT-TIMEOUT')
    initialize(lambda db, text: sql(path, m, db, text), credentials['passwords'])
    properties(path, m, credentials)
    recovery_window(path, m, evidence)
    compose(path, m, 'up', '-d', 'keycloak', log=evidence / 'keycloak-up.log')
    await_check(lambda: get_json(m['issuer'] + '/.well-known/openid-configuration'), 150, 'IDP-TIMEOUT')
    compose(path, m, 'up', '-d', *RUNTIMES, 'prometheus', log=evidence / 'runtimes-up.log')
    observed_catalogue = catalogue(path, m)
    if m.get('catalogue') and m['catalogue'] != observed_catalogue:
        raise Refusal('DB-CATALOGUE-DRIFT')
    m['catalogue'] = observed_catalogue
    verify_acl(path, m, credentials)
    save(path / 'manifest.json', m)
    checks = current(path, m, wait=True)
    if 'FAIL' in checks.values():
        save(evidence / 'checks.json', checks)
        raise RuntimeError('READINESS:' + ','.join(k for k, v in checks.items() if v == 'FAIL'))
    owner = prove_pkce(m['issuer'], 'bruno', credentials['users']['bruno'])
    guest = login(m['issuer'], 'guest', credentials['users']['guest'])
    write, read_url = urls(m)
    api, guest_api = PublicAPI(write, read_url, owner), PublicAPI(write, read_url, guest)
    demo_file = path / 'demo.json'
    demo = seed(m['owner'], api, guest_api, read(demo_file) if demo_file.exists() else {}, lambda s: save(demo_file, s))
    run_id = evidence.name
    state = {}
    canary_result = canary(m['owner'], api, run_id, state, lambda s: save(evidence / 'canary-intent.json', s))
    save(path / 'canary.json', canary_result)
    save(evidence / 'public-requests.json', api.trace + guest_api.trace)
    tools = observability(m, wait=True)
    m.update(state='RUNNING', startedAt=time.time())
    save(path / 'manifest.json', m)
    return verdict(checks, True, tools, canary_result)


def urls(m):
    return f'http://127.0.0.1:{m["ports"]["write"]}', f'http://127.0.0.1:{m["ports"]["read"]}'


def verdict(checks, fresh, tools, proof=None):
    business = all(v == 'PASS' for v in checks.values())
    state = 'READY' if business and fresh and tools == 'AVAILABLE' else ('DEGRADED' if business and fresh else 'NOT_READY')
    return dict(state=state, exit=0 if state == 'READY' else (4 if state == 'DEGRADED' else 1),
                business='AVAILABLE' if business else 'UNAVAILABLE', canary='AVAILABLE' if fresh else 'STALE_OR_UNPROVEN',
                qualificationTools=tools, fullyQualifiable=False,
                checks=checks, canaryProof=proof, observedAt=time.time())


def status(path, m):
    ownership(m)
    checks = current(path, m)
    try:
        credentials = private(path)
        token = login(m['issuer'], 'bruno', credentials['users']['bruno'])
        api = PublicAPI(*urls(m), token)
        proof_file = path / 'canary.json'
        if proof_file.exists():
            proof = read(proof_file)
            status_code, binding = api.call('GET', '/me/binding')
            if status_code != 200 or not binding.get('active'):
                raise RuntimeError('READ-BINDING')
            status_code, view = api.call('GET', '/pots/' + proof['potId'], binding=binding['bindingToken'])
            if status_code != 200 or view['potVersion'] != proof['version'] or view['label'] != 'canary-' + proof['runId']:
                raise RuntimeError('READ-CANARY')
        checks['IDP-PKCE-SESSION'] = 'PASS'
    except Exception:
        checks['IDP-PKCE-SESSION'] = 'FAIL'
    proof = read(path / 'canary.json') if (path / 'canary.json').exists() else None
    fresh = bool(proof and 0 <= time.time() - proof['completedAt'] <= MAX_AGE)
    return verdict(checks, fresh, observability(m), proof)


def stop(path, m, evidence):
    ownership(m)
    slots = {}
    exit_codes = {}
    if (path / 'compose.json').exists():
        try:
            slots = {db: sql(path, m, db, 'SELECT count(*) FROM ' + schema + '.consumption_slot;')
                     for db, schema in [('pocoma_write','v3_write'), ('pocoma_read','v3_read')]}
        except Exception:
            slots = {'observation': 'UNAVAILABLE'}
        # Graceful stop preserves containers, networks, volume, outstanding leases.
        compose(path, m, 'stop', '-t', '45', *RUNTIMES, timeout=260, log=evidence / 'stop-jvm.log')
        ids = compose(path, m, 'ps', '-aq').splitlines()
        for resource in ids:
            value, = docker_json('inspect', resource)
            name = value['Config']['Labels'].get('com.docker.compose.service')
            if name in RUNTIMES:
                exit_codes[name] = value['State']['ExitCode']
        compose(path, m, 'stop', '-t', '30', 'keycloak', 'prometheus', 'postgres', timeout=120, log=evidence / 'stop-deps.log')
    m['state'] = 'STOPPED'
    save(path / 'manifest.json', m)
    killed = [name for name, code in exit_codes.items() if code == 137]
    return dict(state='STOPPED' if not killed else 'STOPPED_WITH_FORCED_KILL', exit=0 if not killed else 1,
                volumes='PRESERVED', runtimeExitCodes=exit_codes, forcedKill=killed, slotsBeforeStop=slots)


def bruno(path, m, evidence, interactive_login=False):
    ownership(m)
    credentials = private(path)
    tokens = {name: login(m['issuer'], name, password) for name, password in credentials['users'].items()}
    if interactive_login:
        tokens['third'] = interactive(m['issuer'])
    collection = ROOT / 'scripts/bruno-playground'
    cli = ROOT / 'scripts/bruno-v3/node_modules/.bin/bru'
    if not cli.exists():
        run(['npm', 'ci', '--prefix', ROOT / 'scripts/bruno-v3'], timeout=240, log=evidence / 'npm.log')
    environment = {**os.environ, 'POCOMA_PG_WRITE': urls(m)[0], 'POCOMA_PG_READ': urls(m)[1],
        'POCOMA_PG_ISSUER': m['issuer'], 'POCOMA_PG_OWNER': tokens['third'], 'POCOMA_PG_GUEST': tokens['guest'],
        'POCOMA_PG_RECEIPT': str(evidence / 'scenarios.jsonl')}
    command = [str(cli), 'run', 'PG01 Public Journey.bru', '--env', 'Local', '--sandbox=developer', '--reporter-junit', str(evidence / 'bruno.xml')]
    with (evidence / 'bruno-private.log').open('w') as output:
        result = subprocess.run(command, cwd=collection, env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    # Bruno's default console may include response bodies on failure. Private log
    # is redacted before the command returns, no JSON reporter persists bodies.
    logfile = evidence / 'bruno-private.log'
    text = logfile.read_text()
    for token in tokens.values():
        text = text.replace(token, '[REDACTED]')
    logfile.write_text(text)
    file = evidence / 'scenarios.jsonl'
    actual = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
    expected = read(collection / 'inventory.json')['scenarios']
    expected_tests = read(collection / 'inventory.json')['namedTests']
    report = evidence / 'bruno.xml'
    cases = ET.parse(report).findall('.//testcase') if report.exists() else []
    junit_pass = len(cases) == expected_tests and all(c.get('status') == 'pass' and c.find('failure') is None and c.find('error') is None for c in cases)
    success = junit_pass and result.returncode == 0 and [r['scenario'] for r in actual if r['verdict'] == 'PASS'] == expected
    save(evidence / 'bruno-receipt.json', dict(command=command, expected=expected,
        actual=[r['scenario'] for r in actual], namedTests=len(cases), junitPass=junit_pass, exit=result.returncode, success=success))
    return dict(state='BRUNO_PASS' if success else 'BRUNO_FAIL', exit=0 if success else 1)


def gui(path, m):
    """Prepare only public endpoints; native Bruno owns the OAuth sessions."""
    ownership(m)
    target = ROOT / 'scripts/bruno-playground/environments' / ('Instance-' + m['instance'] + '.bru')
    if target.is_symlink():
        raise Refusal('PRE-SYMLINK')
    discovery = get_json(m['issuer'] + '/.well-known/openid-configuration')
    if discovery.get('issuer') != m['issuer'] or 'S256' not in discovery.get('code_challenge_methods_supported', []):
        raise Refusal('IDP-DISCOVERY')
    # No reset, migration, password read, browser callback listener or JWT export.
    text = 'vars {\n  writeUrl: ' + urls(m)[0] + '\n  readUrl: ' + urls(m)[1] + '\n  issuerUrl: ' + m['issuer'] + '\n}\n'
    target.write_text(text)
    target.chmod(0o600)
    return dict(state='GUI_CONFIGURED', exit=0, environment=str(target),
                next='In Bruno select Instance-' + m['instance'] + '; 00 Login Owner and 01 Login Guest > Auth > Get Access Token, then PG01.')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'status', 'stop', 'reset', 'bruno', 'login', 'gui'])
    parser.add_argument('--instance', default=None)
    parser.add_argument('--confirm')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--interactive', action='store_true')
    args = parser.parse_args()
    instance = args.instance or 'demo'
    path = evidence = m = None
    try:
        if args.command == 'reset' and (args.instance is None or args.confirm != 'RESET:' + instance):
            raise Refusal('RESET-EXPLICIT-INSTANCE-AND-CONFIRMATION-REQUIRED')
        with locked(instance, create=args.command == 'start') as path, lifecycle_lock(args.command):
            if not (path / 'manifest.json').exists():
                if args.command != 'start':
                    raise Refusal('PRE-MANIFEST-MISSING')
                m = new_manifest(path, instance)
                save(path / 'manifest.json', m)
            m = load_manifest(path)
            evidence = path / 'runs' / (str(int(time.time())) + '-' + uuid4().hex[:8])
            evidence.mkdir(parents=True)
            if args.command == 'start':
                result = start(path, m, evidence)
            elif args.command == 'status':
                result = status(path, m)
            elif args.command == 'stop':
                result = stop(path, m, evidence)
            elif args.command == 'bruno':
                result = bruno(path, m, evidence, args.interactive)
            elif args.command in ('login', 'gui'):
                result = gui(path, m)
            else:
                ownership(m)
                if args.dry_run:
                    result = dict(state='RESET_PLAN', exit=0, project=m['project'], volume=m['project'] + '-data')
                else:
                    stop(path, m, evidence)
                    diagnostics(path, m, evidence)
                    if (path / 'compose.json').exists():
                        compose(path, m, 'down', '--volumes', '--timeout', '45', timeout=180, log=evidence / 'reset.log')
                    # Retain all runs, remove only owned generated inputs, rebootstrap.
                    for file in path.iterdir():
                        if file.is_file() and file.name not in ('lock', 'manifest.json'):
                            file.unlink()
                    m = new_manifest(path, instance)
                    save(path / 'manifest.json', m)
                    result = start(path, m, evidence)
            result.update(instance=instance, project=m['project'], sha=run(['git', 'rev-parse', 'HEAD']),
                          dirty=bool(run(['git', 'status', '--porcelain'])), urls=dict(zip(('write', 'read'), urls(m))),
                          issuer=m.get('issuer'), evidence=str(evidence))
            save(evidence / 'receipt.json', result)
            if result['exit']:
                diagnostics(path, m, evidence)
            print(json.dumps(result, indent=2))
            return result['exit']
    except (Exception, KeyboardInterrupt) as error:
        code = error.code if isinstance(error, Refusal) else (130 if isinstance(error, KeyboardInterrupt) else 1)
        # Do not stringify HTTP errors: their URLs can contain authorization codes.
        control = str(error) if isinstance(error, (Refusal, RuntimeError)) else type(error).__name__
        result = dict(state='NOT_READY', exit=code, control=control, instance=instance,
                      evidence=str(evidence) if evidence else None, environment='PRESERVED')
        if evidence:
            save(evidence / 'receipt.json', result)
            diagnostics(path, m, evidence)
        print(json.dumps(result, indent=2))
        return code


if __name__ == '__main__':
    sys.exit(main())
