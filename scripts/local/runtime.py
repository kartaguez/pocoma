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
import tempfile
import xml.etree.ElementTree as ET
from uuid import uuid4
from urllib.request import urlopen

from auth import interactive, login
from keycloak import LOCAL_PASSWORD, LOCAL_USERS, KeycloakFailure, access, discovery, identities
from bootstrap import AVAILABILITY_MODULE, SETUP_SCRIPT, fingerprint, initialize, ROLES
from public_api import PublicAPI, canary, seed
import grafana
import recovery
import prerequisites
import runtime_bruno
import runtime_grafana
import runtime_lifecycle

validate_bruno_results = runtime_bruno.validate_bruno_results

ROOT = Path(__file__).resolve().parents[2]
HOME_LOCAL = ROOT / '.pocoma-local'
RUNTIMES = {
    'http-write': ('web-write', 'v3-http-admission', 8080, 18080),
    'http-read': ('web-read', 'v3-http-read', 8082, 18082),
    'command-worker': ('worker-process', 'v3-consumption-worker-dev', 0, 18083),
    'results-worker': ('worker-materialize', 'v3-read-worker', 0, 18084),
    'business-read-worker': ('worker-materialize', 'v3-read-worker', 0, 18085),
}
WORKERS = ('command-worker', 'results-worker', 'business-read-worker')
IMAGES = {'postgres': 'postgres:17.6-alpine', 'keycloak': 'quay.io/keycloak/keycloak:26.7.0',
          'jre': 'eclipse-temurin:21.0.8_9-jre', 'prometheus': 'prom/prometheus:v3.5.0',
          'grafana': 'grafana/grafana:12.3.2'}
LABEL = 'io.pocoma.local.owner'
MAX_AGE = 300


class Refusal(RuntimeError):
    def __init__(self, control, code=2):
        super().__init__(control)
        self.code = code


def run(args, *, input=None, timeout=120, log=None, env=None):
    result = subprocess.run([str(a) for a in args], input=input, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, cwd=ROOT, env=env)
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
                 SETUP_SCRIPT, 'scripts/v3', 'scripts/functional-auth-proof.py',
                 'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json']).splitlines()
    paths = set(paths) | {str(p.relative_to(ROOT)) for p in (ROOT / 'scripts/local').glob('*.py') if not p.name.startswith('test_')}
    paths |= {str(p.relative_to(ROOT)) for p in (ROOT / 'scripts/bruno-playground').rglob('*') if p.is_file() and not (p.parent.name == 'environments' and p.name.startswith('Instance-'))}
    paths.update(['pocoma-local', AVAILABILITY_MODULE, 'scripts/local/toolchain.json', 'docker/keycloak/pocoma-realm.json',
                  'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json'])
    paths |= {str(p.relative_to(ROOT)) for p in (ROOT / 'docker/grafana').rglob('*') if p.is_file()}
    return hashlib.sha256(''.join(p + ':' + hashlib.sha256((ROOT / p).read_bytes()).hexdigest() + '\n'
                                 for p in sorted(paths) if (ROOT / p).is_file() and not Path(p).name.startswith('test_')).encode()).hexdigest()


def _check_ancestor_permissions(path):
    for candidate in [HOME_LOCAL, *path.parents, path]:
        if candidate == ROOT or ROOT not in candidate.parents:
            continue
        if candidate.is_symlink():
            raise Refusal('PRE-SYMLINK')
        if candidate.exists() and candidate.stat().st_mode & 0o077:
            raise Refusal('PRE-PRIVATE-PERMISSIONS')


def safe_tree(path):
    # Refuse symlinks and permissive state, including any ancestor below repo root.
    _check_ancestor_permissions(path)
    if path.exists() and any(child.is_symlink() for child in path.rglob('*')):
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
    if command not in ('start', 'reload', 'upgrade-grafana', 'reset', 'destroy'):
        yield
        return
    with (HOME_LOCAL / 'lifecycle.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('PRE-LIFECYCLE-LOCKED', 3) from None
        yield


def allocate_ports(names=None):
    names = names or ['write', 'read', 'issuer', *RUNTIMES, 'metrics', 'grafana']
    sockets = []
    try:
        for _ in names:
            s = socket.socket()
            sockets.append(s)
            s.bind(('127.0.0.1', 0))
        return dict(zip(names, [s.getsockname()[1] for s in sockets]))
    finally:
        for s in sockets:
            s.close()


def new_manifest(path, instance):
    owner = str(uuid4())
    return dict(schema=1, instance=instance, owner=owner,
                project='pocoma-lb-' + instance + '-' + owner[:8], ports=allocate_ports(),
                sha=run(['git', 'rev-parse', 'HEAD']), inputs=source_hash(), state='NEW')


def load_manifest(path):
    return prerequisites.manifest(path)


def docker_json(*args):
    return json.loads(run(['docker', *args]))


def _verify_resource_ownership(kind, resource, project, owner):
    inspect = ['inspect'] if kind == 'container' else [kind, 'inspect']
    value, = docker_json(*inspect, resource)
    labels = value.get('Config', {}).get('Labels', {}) if kind == 'container' else value.get('Labels', {})
    if labels.get(LABEL) != owner:
        raise Refusal('PRE-FOREIGN-' + kind.upper())
    if kind == 'volume' and value['Name'] != project + '-data':
        raise Refusal('PRE-EXTRA-VOLUME')
    if kind == 'network' and value['Name'] != project + '_default':
        raise Refusal('PRE-EXTRA-NETWORK')
    if kind == 'container' and labels.get('com.docker.compose.service') not in {*RUNTIMES, 'postgres', 'keycloak', 'prometheus', 'grafana'}:
        raise Refusal('PRE-EXTRA-CONTAINER')


def ownership(m):
    """Check every project object and refuse extra/foreign state before mutation."""
    project = m['project']
    queries = (
        ('container', ['ps', '-aq']),
        ('network', ['network', 'ls', '-q']),
        ('volume', ['volume', 'ls', '-q']),
    )
    for kind, query in queries:
        for resource in run(['docker', *query, '--filter', 'label=com.docker.compose.project=' + project]).splitlines():
            _verify_resource_ownership(kind, resource, project, m['owner'])
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


def sql(path, m, database, text, timeout=90):
    # stdin, no password args/environment; local socket inside owned PG only.
    return run(['docker', 'exec', '-i', pg_id(path, m), 'psql', '-X', '-q', '-tA',
                '-v', 'ON_ERROR_STOP=1', '-U', 'pocoma', '-d', database], input=text, timeout=timeout)


def private(path, *, create=False):
    file = path / 'secrets.json'
    if file.exists():
        return prerequisites.credentials(file, ROLES, LOCAL_USERS)
    if not create or (path / 'compose.json').exists():
        prerequisites.reject('PRE-SECRETS-MISSING', 'Original secrets.json for this instance', 'secrets absent',
            'Restore the original secrets from backup; preserve data. Secrets will not be regenerated.')
    if (path / 'manifest.json').exists():
        m = load_manifest(path)
        if m['state'] != 'NEW' or any(key in m for key in ('build', 'images', 'issuer')):
            prerequisites.reject('PRE-SECRETS-MISSING', 'Original secrets.json for a previously initialized instance',
                                 'existing deployment identity; regeneration refused')
    key = lambda: base64.b64encode(secrets.token_bytes(32)).decode()
    data = dict(passwords={role: secrets.token_urlsafe(32) for role in (*ROLES, 'pocoma', 'keycloak')},
                users={name: LOCAL_PASSWORD for name in LOCAL_USERS},
                lookup=key(), comparison=key(), invitation=key())
    # Exclusive creation: a concurrent/partial initialization must never replace secrets.
    with file.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(data, indent=2) + '\n')
    file.chmod(0o600)
    return data


def prepare_images(path, m, evidence, roles=None):
    images = {}
    for role, reference in IMAGES.items():
        if roles is not None and role not in roles:
            continue
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


def render(path, m, credentials, *, deployment_path=None):
    labels = {LABEL: m['owner']}
    password = path / 'pg-password'
    password.write_text(credentials['passwords']['pocoma'])
    # PostgreSQL's unprivileged entrypoint reads this private mount after gosu.
    password.chmod(0o644)
    realm = read(ROOT / 'docker/keycloak/pocoma-realm.json')
    template = realm['users'][0]
    realm['users'] = [dict(template, username=user, email=user + '@pocoma.local', enabled=True, requiredActions=[],
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
                         KC_BOOTSTRAP_ADMIN_USERNAME='admin', KC_BOOTSTRAP_ADMIN_PASSWORD=LOCAL_PASSWORD,
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
    grafana.provision(path, RUNTIMES)
    services['grafana'] = grafana.service(path, m, LABEL)
    # macOS may expose the same checkout as /dev and /Dev. Preserve the original
    # mount spelling so Compose does not recreate unchanged database containers.
    mount_root = m.get('mountRoot', str(deployment_path or path))
    if not Path(mount_root).samefile(deployment_path or path):
        raise Refusal('PRE-MOUNT-ROOT-DRIFT')
    services = json.loads(json.dumps(services).replace(str(path) + '/', mount_root + '/'))
    save(path / 'compose.json', dict(services=services, networks={'default': {'labels': labels}},
        volumes={'data': {'name': m['project'] + '-data', 'labels': labels}}))
    return issuer


def _render_runtime_properties(name, text, m, credentials, address):
    text = text.replace('172.28.0.2/32', address + '/32').replace('http://localhost:8081/realms/pocoma', m['issuer'])
    replacements = {'pocoma-local-only': credentials['passwords']['pocoma_write'],
                    'pocoma-source-local-only': credentials['passwords']['pocoma_write_source_read'],
                    'pocoma-read-http-local-only': credentials['passwords']['pocoma_read_http'],
                    'pocoma-read-status-local-only': credentials['passwords']['pocoma_read_status']}
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
    return text


def properties(path, m, credentials):
    pg, = docker_json('inspect', pg_id(path, m))
    address = pg['NetworkSettings']['Networks'][m['project'] + '_default']['IPAddress']
    for name in RUNTIMES:
        raw = (ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
        (path / (name + '.properties')).write_text(_render_runtime_properties(name, raw, m, credentials, address))


def _package_all_modules(path, m, evidence, all_modules):
    build = HOME_LOCAL / 'builds' / m['inputs']
    build.mkdir(parents=True, exist_ok=True)
    if not (build / 'receipt.json').exists():
        command = [str(ROOT / 'app/mvnw'), '-f', str(ROOT / 'app/pom.xml'), '-pl',
                   ','.join('runtime-' + module for module in all_modules), '-am', 'package', '-DskipTests']
        run(command, timeout=1200, log=evidence / 'package.log', env=prerequisites.clean_toolchain_env())
        hashes = {}
        for module in all_modules:
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


def _package_selected_modules(path, m, evidence, selected):
    command = [str(ROOT / 'app/mvnw'), '-f', str(ROOT / 'app/pom.xml'), '-pl',
               ','.join('runtime-' + module for module in selected), '-am', 'package', '-DskipTests']
    run(command, timeout=1200, log=evidence / 'package.log', env=prerequisites.clean_toolchain_env())
    hashes = dict(m.get('build', {}).get('jars', {}))
    for module in selected:
        source = ROOT / f'app/runtime-{module}/target/pocoma-runtime-{module}-0.0.1-SNAPSHOT-exec.jar'
        target = path / (module + '.jar')
        shutil.copyfile(source, target)
        hashes[module] = hashlib.sha256(target.read_bytes()).hexdigest()
    m['build'] = dict(command=command, sha=run(['git', 'rev-parse', 'HEAD']),
                      inputs=m['inputs'], jars=hashes, partialModules=selected,
                      tests='SKIPPED: selective packaging only')


def package(path, m, evidence, *, only_modules=None):
    for name in ('maven.config', 'jvm.config', 'extensions.xml'):
        if (ROOT / 'app/.mvn' / name).exists():
            raise Refusal('PRE-UNREVIEWED-MAVEN-CONFIG:' + name)
    (HOME_LOCAL / 'builds' / m['inputs']).mkdir(parents=True, exist_ok=True)
    with (HOME_LOCAL / 'build.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('PRE-BUILD-LOCKED', 3) from None
        all_modules = sorted({v[0] for v in RUNTIMES.values()})
        selected = sorted(only_modules) if only_modules is not None else all_modules
        if set(selected) == set(all_modules):
            _package_all_modules(path, m, evidence, all_modules)
        else:
            _package_selected_modules(path, m, evidence, selected)


def generations(path, m):
    """Actual container/process incarnations; never infer recreation from Compose stdout."""
    result = {}
    if not (path / 'compose.json').exists():
        return dict.fromkeys(RUNTIMES)
    for name in RUNTIMES:
        resource = compose(path, m, 'ps', '-aq', name, timeout=10)
        if not resource:
            result[name] = None
            continue
        value, = json.loads(run(['docker', 'inspect', resource], timeout=10))
        result[name] = dict(containerId=value['Id'], createdAt=value['Created'],
                            startedAt=value['State']['StartedAt'], running=value['State']['Running'])
    return result


def worker_coverage(path, m):
    texts, rows = {}, {}
    for name in recovery.PARTICIPANTS:
        with urlopen(f'http://127.0.0.1:{m["ports"][name]}/actuator/prometheus', timeout=2) as response:
            texts[name] = response.read().decode()
    for authority in ('write', 'read'):
        schema = 'v3_' + authority
        raw = sql(path, m, 'pocoma_' + authority, f"""
            BEGIN READ ONLY;
            SET LOCAL statement_timeout='2000ms';
            SELECT coalesce(json_agg(json_build_object(
                'consumptionId',c.consumption_id,'partitionId',p.partition_id,
                'partitionCount',c.partition_count,
                'live',coalesce(p.owner_token IS NOT NULL AND p.owned_until>clock_timestamp(),false)
            )), '[]') FROM {schema}.consumption_routing_contract c
            LEFT JOIN {schema}.partition_ownership p USING(consumption_id);
            COMMIT;
        """, timeout=4)
        rows[authority] = json.loads(raw)
    return recovery.coverage(texts, rows)


def recover_workers(path, m, evidence, before):
    return recovery.await_coverage(lambda: worker_coverage(path, m), lambda: generations(path, m),
        lambda record: save(evidence / 'worker-recovery.json', record), before)


def _check_refresh_baseline(m, evidence):
    baseline = m.get('build', {}).get('sha', '')
    if not re.fullmatch(r'[0-9a-f]{40}', baseline):
        raise Refusal('PRE-TOOLING-BASELINE-UNPROVEN')
    protected = ('app', 'docker/v3-five-runtimes', 'docker/keycloak', 'scripts/v3',
        SETUP_SCRIPT, AVAILABILITY_MODULE, 'scripts/local/bootstrap.py',
        'scripts/local/keycloak.py', 'scripts/local/auth.py',
        'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json')
    try:
        changed = run(['git', 'diff', '--name-only', baseline, '--', *protected]).splitlines()
        changed += run(['git', 'ls-files', '--others', '--exclude-standard', '--', *protected]).splitlines()
    except RuntimeError:
        raise Refusal('PRE-INPUT-DRIFT: tooling baseline unavailable') from None
    if changed:
        save(evidence / 'tooling-refresh-refused.json', dict(baseline=baseline, changedProtectedInputs=changed))
        raise Refusal('PRE-INPUT-DRIFT: production inputs changed; preserve instance')
    return baseline


def _verify_refresh_staging(path, m):
    for module, expected in m['build']['jars'].items():
        if hashlib.sha256((path / (module + '.jar')).read_bytes()).hexdigest() != expected:
            raise Refusal('PRE-JAR-HASH')
    old = read(path / 'compose.json')
    mount_root = Path(old['services']['keycloak']['volumes'][0].split(':')[0]).parent
    if not mount_root.samefile(path):
        raise Refusal('PRE-MOUNT-ROOT-DRIFT')
    candidate = json.loads(json.dumps(m))
    candidate['mountRoot'] = str(mount_root)
    with tempfile.TemporaryDirectory(prefix='tooling-refresh-', dir=path) as directory:
        stage = Path(directory)
        render(stage, candidate, read(path / 'secrets.json'), deployment_path=path)
        if read(stage / 'compose.json') != old:
            raise Refusal('PRE-TOOLING-COMPOSE-DRIFT')
        for name in ('pg-password', 'pg-init.sql', 'realm.json', 'prometheus.yml'):
            if (stage / name).read_bytes() != (path / name).read_bytes():
                raise Refusal('PRE-TOOLING-CONFIG-DRIFT:' + name)
    return candidate


def refresh_tooling(path, m, evidence):
    """Prove an existing deployment unchanged before adopting new local-only tooling."""
    ownership(m)
    baseline = _check_refresh_baseline(m, evidence)
    candidate = _verify_refresh_staging(path, m)
    previous = m['inputs']
    candidate['inputs'] = source_hash()
    build = HOME_LOCAL / 'builds' / candidate['inputs']
    build.mkdir(parents=True, exist_ok=True)
    receipt = dict(m['build'], inputs=candidate['inputs'], reusedFromInputs=previous,
                   reuse='PRODUCTION_AND_DEPLOYMENT_EQUIVALENCE; original build SHA retained')
    for module in receipt['jars']:
        shutil.copyfile(path / (module + '.jar'), build / (module + '.jar'))
    save(build / 'receipt.json', receipt)
    save(evidence / 'tooling-refresh.json', dict(baseline=baseline, previousInputs=previous,
        inputs=candidate['inputs'], jars=receipt['jars'], method=receipt['reuse']))
    m.update(candidate)
    save(path / 'manifest.json', m)


def await_check(action, timeout, name):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            return action()
        except Exception as error:
            last = error
            time.sleep(.5)
    if isinstance(last, KeycloakFailure):
        raise last
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


def configured_admission_capacity(path):
    props = path / 'http-write.properties'
    if not props.exists():
        props = ROOT / 'docker/v3-five-runtimes/http-write.properties'
    if props.exists():
        match = re.search(r'(?m)^pocoma\.v3\.http\.admission\.capacity=(\d+)\s*$', props.read_text())
        if match:
            return int(match.group(1))
    return 1


def scrape_prometheus_text(port):
    try:
        with urlopen(f'http://127.0.0.1:{port}/actuator/prometheus', timeout=2) as response:
            return response.read().decode()
    except Exception:
        return None


def recover_fail_closed_runtimes(path, m, evidence):
    """Detect already-Up runtimes stuck in fail-closed draining or depleted admission capacity and restart them once."""
    return runtime_lifecycle.recover_fail_closed_runtimes(sys.modules[__name__], path, m, evidence)


def current(path, m, *, wait=False):
    return runtime_lifecycle.current(sys.modules[__name__], path, m, wait=wait)


def observability(m, wait=False):
    return runtime_lifecycle.observability(sys.modules[__name__], m, wait=wait)


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


def preflight(path, m, *, record=True):
    data = docker_json('info', '--format', '{{json .}}')
    if data['MemTotal'] < 6 * 1024**3:
        raise Refusal('PRE-RESOURCES: Docker >=6GiB')
    # Cached restart needs diagnostic headroom, not a second cold-install budget.
    cached = (HOME_LOCAL / 'builds' / m['inputs'] / 'receipt.json').exists()
    has_missing = any(
        subprocess.run(['docker', 'image', 'inspect', reference], capture_output=True, text=True).returncode != 0
        for reference in IMAGES.values()
    )
    minimum = _required_disk_bytes(cached, has_missing)
    free = shutil.disk_usage(ROOT).free
    if record:
        save(path / 'resources.json', dict(dockerMemoryBytes=data['MemTotal'], cpus=data['NCPU'],
             freeDiskBytes=free, requiredDiskBytes=minimum, buildCached=cached))
    if free < minimum:
        raise Refusal('PRE-DISK: requiredBytes=' + str(minimum) + ', availableBytes=' + str(free))
    architecture = {'aarch64': 'arm64', 'x86_64': 'amd64'}.get(data['Architecture'], data['Architecture'])
    if architecture not in ('arm64', 'amd64'):
        raise Refusal('PRE-ARCHITECTURE')
    return architecture


def upgrade_grafana(path, m, evidence):
    """Explicit Grafana upgrade/refresh of a stopped deployment, never a reset."""
    return runtime_grafana.upgrade_grafana(sys.modules[__name__], path, m, evidence)


def _check_reload_compatibility(baseline, evidence):
    forbidden_roots = (
        'docker/v3-five-runtimes', 'docker/keycloak', 'scripts/v3',
        SETUP_SCRIPT, AVAILABILITY_MODULE, 'scripts/local/bootstrap.py',
        'scripts/local/keycloak.py', 'scripts/local/auth.py',
        'scripts/local/public_api.py',
        'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json',
    )
    try:
        changed_infra = run(['git', 'diff', '--name-only', baseline, '--', *forbidden_roots]).splitlines()
        changed_infra += run(['git', 'ls-files', '--others', '--exclude-standard', '--', *forbidden_roots]).splitlines()
        changed_app = run(['git', 'diff', '--name-only', baseline, '--', 'app']).splitlines()
        changed_app += run(['git', 'ls-files', '--others', '--exclude-standard', '--', 'app']).splitlines()
    except RuntimeError:
        raise Refusal('PRE-INPUT-DRIFT: tooling baseline unavailable') from None
    changed_sql = [p for p in changed_app if '/src/test/' not in p and p.lower().endswith('.sql')]
    incompatible = sorted(set(changed_infra) | set(changed_sql))
    if incompatible:
        save(evidence / 'reload-refused.json', dict(baseline=baseline, changedIncompatibleInputs=incompatible))
        raise Refusal('RELOAD_RESET_REQUIRED: SQL or infrastructure inputs changed; run reset')


def _ensure_no_foreign_active(owner):
    for resource in run(['docker', 'ps', '-q', '--filter', 'label=' + LABEL]).splitlines():
        value, = docker_json('inspect', resource)
        if value['Config']['Labels'].get(LABEL) != owner:
            raise Refusal('PRE-OTHER-PLAYGROUND-ACTIVE: stop the other instance first')


def reload_instance(path, m, evidence, *, runtimes=None):
    """Rebuild and recreate selected (or all 5) runtime containers when SQL/infra inputs are unchanged."""
    ownership(m)
    baseline = m.get('build', {}).get('sha', '')
    if not re.fullmatch(r'[0-9a-f]{40}', baseline):
        raise Refusal('PRE-TOOLING-BASELINE-UNPROVEN')
    if not m.get('catalogue'):
        raise Refusal('RELOAD_BASELINE_UNPROVEN: start this instance first')
    if 'grafana' not in m['ports']:
        raise Refusal('GRAFANA_UPGRADE_REQUIRED: run upgrade-grafana --instance ' + m['instance'])
    selected_runtimes = tuple(dict.fromkeys(runtimes)) if runtimes else tuple(RUNTIMES)
    new_inputs = source_hash()
    if m['inputs'] == new_inputs:
        return start(path, m, evidence)
    _check_reload_compatibility(baseline, evidence)
    candidate = json.loads(json.dumps(m))
    candidate['architecture'] = preflight(path, m)
    _ensure_no_foreign_active(m['owner'])
    candidate['inputs'] = new_inputs
    candidate['sha'] = run(['git', 'rev-parse', 'HEAD'])
    package(path, candidate, evidence, only_modules={RUNTIMES[r][0] for r in selected_runtimes} if runtimes else None)
    save(evidence / 'reload.json', dict(
        baseline=baseline, previousInputs=m['inputs'], inputs=candidate['inputs'],
        sha=candidate['sha'], runtimes=list(selected_runtimes), jars=candidate['build']['jars'],
    ))
    m.clear()
    m.update(candidate)
    save(path / 'manifest.json', m)
    return start(path, m, evidence, force_recreate_runtimes=selected_runtimes)


def start(path, m, evidence, *, force_recreate_runtimes=None):
    history_file = path / 'start-history.json'
    history = read(history_file) if history_file.exists() else {}
    attempt = dict(runId=evidence.name, startedAt=time.time(), state='STARTING',
                   sha=run(['git', 'rev-parse', 'HEAD']), dirty=bool(run(['git', 'status', '--porcelain'])))
    history['lastStart'] = attempt
    save(history_file, history)
    save(evidence / 'start-attempt.json', attempt)
    try:
        result = start_distribution(path, m, evidence, force_recreate_runtimes=force_recreate_runtimes)
        attempt.update(state='SUCCEEDED' if result['exit'] == 0 else 'FAILED',
                       completedAt=time.time(), generationId=result['canaryProof']['generationId'])
        if result['exit'] == 0:
            history['lastSuccessfulStart'] = dict(attempt)
        result.update(lastStart=dict(attempt), lastSuccessfulStart=history.get('lastSuccessfulStart'),
                      currentGeneration=result['canaryProof']['generation'], generationQualified=True,
                      technical='AVAILABLE', businessEvidence='PROVEN', proofFreshness='FRESH',
                      operationalState='OPERATIONAL',
                      operational=dict(state='OPERATIONAL', servicesAvailable=True,
                                       workersOperational=True, dependenciesAccessible=True,
                                       observability=result.get('qualificationTools', 'AVAILABLE')),
                      qualificationState='QUALIFIED',
                      qualification=dict(state='QUALIFIED',
                                         lastQualificationAt=result['canaryProof'].get('completedAt'),
                                         lastQualificationRunId=result['canaryProof'].get('runId'),
                                         generationQualified=True, proofFreshness='FRESH',
                                         proofValid=True, proofAgeSeconds=0.0, maxAgeSeconds=MAX_AGE))
        return result
    except (Exception, KeyboardInterrupt) as error:
        control = str(error) if isinstance(error, (RuntimeError, Refusal)) else type(error).__name__
        attempt.update(state='FAILED', completedAt=time.time(), control=control)
        try:
            attempt['generation'] = generations(path, m)
            attempt['generationId'] = recovery.identity(attempt['generation'])
        except Exception:
            attempt['generationObservation'] = 'UNAVAILABLE'
        save(evidence / 'failure.json', dict(runId=evidence.name, control=control, observedAt=time.time(),
            recoveryEvidence='worker-recovery.json', httpEvidence='public-requests.json',
            commandEvidence='command-observations.json', generation=attempt.get('generation')))
        raise
    finally:
        save(history_file, history)
        save(evidence / 'start-attempt.json', attempt)


def start_distribution(path, m, evidence, *, force_recreate_runtimes=None):
    return runtime_lifecycle.start_distribution(
        sys.modules[__name__], path, m, evidence, force_recreate_runtimes=force_recreate_runtimes
    )


def urls(m):
    return f'http://127.0.0.1:{m["ports"]["write"]}', f'http://127.0.0.1:{m["ports"]["read"]}'


def verdict(checks, fresh, tools, proof=None):
    business = all(v == 'PASS' for k, v in checks.items() if not k.startswith('GRAFANA-'))
    grafana_ready = all(v == 'PASS' for k, v in checks.items() if k.startswith('GRAFANA-'))
    state = ('NOT_READY' if not grafana_ready else
             'READY' if business and fresh and tools == 'AVAILABLE' else ('DEGRADED' if business and fresh else 'NOT_READY'))
    return dict(state=state, exit=0 if state == 'READY' else (4 if state == 'DEGRADED' else 1),
                business='AVAILABLE' if business else 'UNAVAILABLE', canary='AVAILABLE' if fresh else 'STALE_OR_UNPROVEN',
                qualificationTools=tools, fullyQualifiable=False,
                checks=checks, canaryProof=proof, observedAt=time.time())


def late_result(path, api, run_id, evidence):
    if not re.fullmatch(r'[0-9]{10}-[0-9a-f]{8}', run_id):
        raise Refusal('DIAGNOSTIC-RUN-ID')
    original = path / 'runs' / run_id
    receipt = read(original / 'receipt.json')
    match = re.search(r'API-POLL-TIMEOUT:(/commands/[0-9a-f-]{36}/result)', receipt.get('control', ''))
    if not match:
        raise Refusal('DIAGNOSTIC-NO-COMMAND-TIMEOUT')
    route = match[1]
    code, data = api.call('GET', route)
    result = dict(sourceRunId=run_id, originalState=receipt['state'], observedAt=time.time(),
        requestId=route.split('/')[2], status=code,
        result={k: data[k] for k in ('outcome', 'resultingPotVersion') if k in data},
        originalVerdictPreserved=True, readOnly=True)
    if evidence:
        save(evidence / 'late-result.json', result)
    return result


def status(path, m, evidence=None, diagnostic_run=None):
    return runtime_lifecycle.status(sys.modules[__name__], path, m, evidence=evidence, diagnostic_run=diagnostic_run)


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
        deps = ['keycloak', 'prometheus', 'postgres']
        if 'grafana' in read(path / 'compose.json')['services']:
            deps.insert(0, 'grafana')
        compose(path, m, 'stop', '-t', '30', *deps, timeout=120, log=evidence / 'stop-deps.log')
    m['state'] = 'STOPPED'
    save(path / 'manifest.json', m)
    killed = [name for name, code in exit_codes.items() if code == 137]
    return dict(state='STOPPED' if not killed else 'STOPPED_WITH_FORCED_KILL', exit=0 if not killed else 1,
                volumes='PRESERVED', runtimeExitCodes=exit_codes, forcedKill=killed, slotsBeforeStop=slots)


def unowned_attached_volumes(m):
    """A mount is not ownership: preserve volumes without this project's labels."""
    preserved = set()
    for resource in run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + m['project']]).splitlines():
        container, = docker_json('inspect', resource)
        for mount in container.get('Mounts', []):
            if mount['Type'] != 'volume':
                continue
            volume, = docker_json('volume', 'inspect', mount['Name'])
            labels = volume.get('Labels') or {}
            if (labels.get(LABEL) != m['owner'] or labels.get('com.docker.compose.project') != m['project']):
                preserved.add(mount['Name'])
    return sorted(preserved)


def destruction_plan(path, m):
    ownership(m)
    return dict(state='DESTROY_PLAN', exit=0, instance=m['instance'], owner=m['owner'], project=m['project'],
                volume=m['project'] + '-data', network=m['project'] + '_default',
                services=[*RUNTIMES, 'postgres', 'keycloak', 'prometheus', *(['grafana'] if 'grafana' in m.get('ports', {}) else [])],
                persistentDataLost=['PostgreSQL WRITE/READ and Keycloak database in owned volume',
                                    'Generated credentials, configuration, demo and canary checkpoints'],
                retained=str(path / 'runs'), preservedUnownedVolumes=unowned_attached_volumes(m),
                operations=['Verify manifest/UUID/resource ownership', 'Gracefully stop this instance',
                            'Compose down without --volumes; preserve unowned attached volumes',
                            'Remove only the named data volume after checking its owner/project labels',
                            'Remove generated instance files; retain lock and diagnostic runs'],
                command='./pocoma-local destroy --instance ' + m['instance'] + ' --confirm DESTROY:' + m['instance'])


def remove_project(path, m, evidence, log_name):
    return runtime_lifecycle.remove_project(sys.modules[__name__], path, m, evidence, log_name)


def destroy(path, m, evidence):
    return runtime_lifecycle.destroy(sys.modules[__name__], path, m, evidence)


def bruno(path, m, evidence, interactive_login=False):
    return runtime_bruno.bruno(sys.modules[__name__], (path, m, evidence), interactive_login)


def gui(path, m):
    """Prepare only public endpoints; native Bruno owns the OAuth sessions."""
    ownership(m)
    target = ROOT / 'scripts/bruno-playground/environments' / ('Instance-' + m['instance'] + '.bru')
    if target.is_symlink():
        raise Refusal('PRE-SYMLINK')
    discovery(m['issuer'])
    # No reset, migration, password read, browser callback listener or JWT export.
    text = 'vars {\n  writeUrl: ' + urls(m)[0] + '\n  readUrl: ' + urls(m)[1] + '\n  issuerUrl: ' + m['issuer'] + '\n}\n'
    if 'http-read' in m['ports']:
        text = text.replace('\n}', '\n  managementReadUrl: http://127.0.0.1:' + str(m['ports']['http-read']) + '\n}')
    target.write_text(text)
    target.chmod(0o600)
    return dict(state='GUI_CONFIGURED', exit=0, environment=str(target),
                **access(m['issuer']),
                next='In Bruno select Instance-' + m['instance'] + '; 00 - Getting Started / 00 Login Owner and 01 Login Guest > Auth > Get Access Token; Run Folder 90 - End-to-End Journeys / J01.')


def prepare(command, instance, dry_run=False):
    """All prerequisites before even creating the instance directory or lock file."""
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,30}', instance):
        raise Refusal('PRE-INSTANCE-ID')
    path = HOME_LOCAL / 'instances' / instance
    safe_tree(HOME_LOCAL)
    safe_tree(path)
    m = prerequisites.instance(path, command, RUNTIMES, ROLES, LOCAL_USERS)
    starting = command in ('start', 'reload', 'upgrade-grafana') or (command == 'reset' and not dry_run)
    build_required = False
    if starting:
        prerequisites.sources(ROOT, RUNTIMES)
        prerequisites.probe('git', ['rev-parse', '--verify', 'HEAD'], 'Git and repository metadata',
                            'Install Git and use a Git clone, then retry.', cwd=ROOT)
        inputs = source_hash()
        build_required = not (HOME_LOCAL / 'builds' / inputs / 'receipt.json').exists()
    prerequisites.tools(ROOT, command, dry_run, build_required=build_required)
    if starting:
        preflight(path, dict(inputs=inputs), record=False)
        _ensure_no_foreign_active(m['owner'] if m else None)
    if m:
        ownership(m)


def _required_disk_bytes(build_cached, has_missing_images):
    minimum = 256 * 1024**2 if build_cached else 2 * 1024**3
    return max(minimum, 4 * 1024**3) if has_missing_images else minimum


def doctor():
    """Read-only workstation and cache check without creating instance state."""
    safe_tree(HOME_LOCAL)
    prerequisites.sources(ROOT, RUNTIMES)
    inputs = source_hash()
    prerequisites.tools(ROOT, 'doctor', False, build_required=True)
    build_cached = (HOME_LOCAL / 'builds' / inputs / 'receipt.json').exists()
    bruno_cli = (ROOT / 'scripts/bruno-v3/node_modules/.bin/bru').exists()
    cached_images, missing_images = [], []
    for image in IMAGES.values():
        probe = subprocess.run(['docker', 'image', 'inspect', image], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (cached_images if probe.returncode == 0 else missing_images).append(image)
    mem_raw = run(['docker', 'info', '--format', '{{.MemTotal}}'])
    mem_mib = int(mem_raw) // (1024 * 1024) if mem_raw.isdigit() else None
    free_disk_bytes = shutil.disk_usage(ROOT).free
    required_disk_bytes = _required_disk_bytes(build_cached, bool(missing_images))
    disk_sufficient = free_disk_bytes >= required_disk_bytes
    disk_diagnostic = (
        None
        if disk_sufficient
        else f'PRE-DISK: requiredBytes={required_disk_bytes}, availableBytes={free_disk_bytes}'
    )
    return dict(
        state='READY' if disk_sufficient else 'NOT_READY',
        exit=0 if disk_sufficient else 2,
        readOnly=True,
        python='.'.join(str(v) for v in sys.version_info[:3]),
        inputs=inputs,
        buildCached=build_cached,
        brunoCliInstalled=bruno_cli,
        cachedImages=cached_images,
        missingImages=missing_images,
        dockerMemoryMiB=mem_mib,
        recommendedMemoryMiB=8192,
        memorySufficient=(mem_mib is None or mem_mib >= 7680),
        freeDiskBytes=free_disk_bytes,
        requiredDiskBytes=required_disk_bytes,
        diskSufficient=disk_sufficient,
        diskDiagnostic=disk_diagnostic,
    )


def _parse_cli_args():
    parser = argparse.ArgumentParser(prog='pocoma-local', description=__doc__)
    parser.add_argument('command', choices=['start', 'reload', 'doctor', 'upgrade-grafana', 'status', 'stop', 'reset', 'destroy', 'bruno', 'login', 'gui'])
    parser.add_argument('--instance', default=None)
    parser.add_argument('--confirm')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--interactive', action='store_true')
    parser.add_argument('--run', help='status only: read the late result of a timed-out run')
    parser.add_argument('--runtime', action='append', choices=sorted(RUNTIMES),
                        help='reload only: rebuild and recreate only the selected runtime(s)')
    args = parser.parse_args()
    if args.run and args.command != 'status':
        parser.error('--run is only valid with status')
    if args.runtime and args.command != 'reload':
        parser.error('--runtime is only valid with reload')
    if args.dry_run and args.command not in ('reset', 'destroy'):
        parser.error('--dry-run is only valid with reset or destroy')
    return args


def _execute_reset(path, m, evidence, instance):
    ownership(m)
    stop(path, m, evidence)
    diagnostics(path, m, evidence)
    volumes = remove_project(path, m, evidence, 'reset.log') if (path / 'compose.json').exists() else {}
    for file in path.iterdir():
        if file.is_file() and file.name not in ('lock', 'manifest.json'):
            file.unlink()
    m = new_manifest(path, instance)
    save(path / 'manifest.json', m)
    private(path, create=True)
    result = start(path, m, evidence)
    result.update(volumes)
    return m, result


def _dispatch_command(args, path, m, evidence, instance):
    handlers = {
        'start': lambda: start(path, m, evidence),
        'reload': lambda: reload_instance(path, m, evidence, runtimes=args.runtime),
        'upgrade-grafana': lambda: upgrade_grafana(path, m, evidence),
        'status': lambda: status(path, m, evidence, args.run),
        'stop': lambda: stop(path, m, evidence),
        'bruno': lambda: bruno(path, m, evidence, args.interactive),
        'login': lambda: gui(path, m),
        'gui': lambda: gui(path, m),
        'destroy': lambda: destruction_plan(path, m) if args.dry_run else destroy(path, m, evidence),
    }
    if args.command in handlers:
        return m, handlers[args.command]()
    if args.dry_run:
        ownership(m)
        return m, dict(state='RESET_PLAN', exit=0, project=m['project'], volume=m['project'] + '-data')
    return _execute_reset(path, m, evidence, instance)


def _handle_cli_error(error, instance, path, m, evidence):
    code = error.code if isinstance(error, (Refusal, prerequisites.Failure)) else (130 if isinstance(error, KeyboardInterrupt) else 1)
    control = str(error) if isinstance(error, (Refusal, RuntimeError)) else type(error).__name__
    result = dict(state='NOT_READY', exit=code, control=control, instance=instance,
                  evidence=str(evidence) if evidence else None, environment='PRESERVED')
    if isinstance(error, prerequisites.Failure):
        result['diagnostic'] = error.diagnostic
    if isinstance(error, KeycloakFailure):
        result['diagnostic'] = dict(error.diagnostic, evidence=str(evidence / 'keycloak-auth.json') if evidence else None)
        if evidence and not (evidence / 'keycloak-auth.json').exists():
            save(evidence / 'keycloak-auth.json', dict(diagnostic=error.diagnostic,
                 checks={str(error): 'FAIL'}, observedAt=time.time()))
    if evidence:
        save(evidence / 'receipt.json', result)
        diagnostics(path, m, evidence)
    print(json.dumps(result, indent=2))
    return code


def main():
    os.umask(0o077)
    args = _parse_cli_args()
    instance = args.instance or 'demo'
    path = evidence = m = None
    try:
        if args.command == 'doctor':
            result = doctor()
            print(json.dumps(result, indent=2))
            return result['exit']
        if args.command in ('reset', 'destroy') and (args.instance is None or args.confirm != args.command.upper() + ':' + instance):
            raise Refusal(f'{args.command.upper()}-EXPLICIT-INSTANCE-AND-CONFIRMATION-REQUIRED')
        prepare(args.command, instance, args.dry_run)
        with locked(instance, create=args.command == 'start') as path, lifecycle_lock(args.command):
            prerequisites.instance(path, args.command, RUNTIMES, ROLES, LOCAL_USERS)
            if not (path / 'manifest.json').exists():
                if args.command != 'start':
                    raise Refusal('PRE-MANIFEST-MISSING')
                save(path / 'manifest.json', new_manifest(path, instance))
                private(path, create=True)
            m = load_manifest(path)
            evidence = path / 'runs' / (str(int(time.time())) + '-' + uuid4().hex[:8])
            evidence.mkdir(parents=True)
            m, result = _dispatch_command(args, path, m, evidence, instance)
            result.update(instance=instance, project=m['project'], sha=run(['git', 'rev-parse', 'HEAD']),
                          dirty=bool(run(['git', 'status', '--porcelain'])), urls=dict(zip(('write', 'read'), urls(m))),
                          issuer=m.get('issuer'), evidence=str(evidence))
            if args.command in ('start', 'reload', 'upgrade-grafana', 'status', 'gui', 'login') and m.get('issuer'):
                result.update(access(m['issuer']))
                result['grafana'] = grafana.access(m)
                result['keycloakAuthEvidence'] = str(evidence / 'keycloak-auth.json') if args.command in ('start', 'reload', 'upgrade-grafana', 'status') else None
            save(evidence / 'receipt.json', result)
            if result['exit']:
                diagnostics(path, m, evidence)
            print(json.dumps(result, indent=2))
            return result['exit']
    except (Exception, KeyboardInterrupt) as error:
        return _handle_cli_error(error, instance, path, m, evidence)


if __name__ == '__main__':
    sys.exit(main())
