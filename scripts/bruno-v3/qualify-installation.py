#!/usr/bin/env python3
"""Qualify the separate local V3 runtimes using only resources created by this invocation."""
import argparse
import base64
import copy
import hashlib
from http.client import RemoteDisconnected
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from uuid import uuid4

COLLECTION = Path(__file__).resolve().parent
ROOT = COLLECTION.parents[1]
CONFIG = ROOT / 'docker/v3-five-runtimes'
RUNTIMES = {
    'http-read': ('web-read', 'v3-http-read'),
    'business-read-worker': ('worker-materialize', 'v3-read-worker'),
    'http-write': ('web-write', 'v3-http-admission'),
    'results-worker': ('worker-materialize', 'v3-read-worker'),
    'command-worker': ('worker-process', 'v3-consumption-worker-dev'),
}
CORPORA = {
    '02 Admission and Commands': [f'B{i}' for i in range(201, 225)],
    '04 Invitations': [f'B{i}' for i in range(401, 405)],
    '06 Installation Checks': ['B501', 'B502'],
}


def call(*args, **options):
    return subprocess.run(args, text=True, check=True, timeout=120, **options)


def output(*args):
    return call(*args, stdout=subprocess.PIPE).stdout.strip()


def read_json(path):
    return json.loads(path.read_text())


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def free_ports(count):
    sockets = []
    try:
        for _ in range(count):
            listener = socket.socket()
            sockets.append(listener)
            listener.bind(('127.0.0.1', 0))
        return [listener.getsockname()[1] for listener in sockets]
    finally:
        for listener in sockets:
            listener.close()


def await_http(url, process=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError('Runtime exited before readiness: ' + url)
        try:
            with urlopen(url, timeout=2) as result:
                if result.status == 200:
                    return
        except (HTTPError, URLError, TimeoutError, RemoteDisconnected):
            pass
        time.sleep(0.25)
    raise RuntimeError('Readiness deadline: ' + url)


def package_path(module):
    return ROOT / f'app/runtime-{module}/target/pocoma-runtime-{module}-0.0.1-SNAPSHOT-exec.jar'


def prepare_realm(temp):
    realm = read_json(ROOT / 'docker/keycloak/pocoma-realm.json')
    template, = realm['clients']
    for name in ('recipe-expiring', 'recipe-other-audience'):
        client = copy.deepcopy(template)
        client['clientId'] = name
        if name == 'recipe-expiring':
            client['attributes']['access.token.lifespan'] = '10'
        else:
            client['defaultClientScopes'].remove('pocoma-api-access')
        realm['clients'].append(client)
    path = temp / 'pocoma-realm.json'
    save(path, realm)
    return path, realm


def load_auth(issuer, realm):
    spec = importlib.util.spec_from_file_location('auth', ROOT / 'scripts/functional-auth-proof.py')
    auth = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auth)
    auth.ISSUER = issuer
    tokens = []
    for user in realm['users']:
        password, = [item['value'] for item in user['credentials'] if item['type'] == 'password']
        values, verifier = auth.authorize(['openid'], username=user['username'], password=password)
        token, claims = auth.exchange(values, verifier)
        auth.check_token(claims, set())
        tokens.append((token, claims['sub']))
    if len(tokens) != 3 or len({subject for _, subject in tokens}) != 3:
        raise RuntimeError('Three distinct Keycloak subjects required')
    auth.CLIENT = 'recipe-expiring'
    values, verifier = auth.authorize(['openid'], username=user['username'], password=password)
    expired, claims = auth.exchange(values, verifier)
    auth.check_token(claims, set())
    if not 0 < claims['exp'] - claims['iat'] <= 15:
        raise RuntimeError('Keycloak expiring fixture has unexpected lifespan')
    expiry = claims['exp']
    auth.CLIENT = 'recipe-other-audience'
    values, verifier = auth.authorize(['openid'], username=user['username'], password=password)
    wrong_audience, claims = auth.exchange(values, verifier)
    if claims.get('iss') != issuer or 'pocoma-api' in (claims.get('aud') or []):
        raise RuntimeError('Keycloak wrong-audience fixture has unexpected claims')
    return tokens, (expired, expiry, wrong_audience)


def psql(container, db, schema, query, role='pocoma', password=None):
    args = ['docker', 'exec']
    options = {}
    if password is not None:
        args.extend(['--env', 'PGPASSWORD'])
        options['env'] = {**os.environ, 'PGPASSWORD': password}
    args.extend(['--env', f'PGOPTIONS=-c search_path={schema},public', container, 'psql',
                 '-X', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', '-U', role, '-d', db, '-tAc', query])
    if password is not None:
        args.extend(['-h', '127.0.0.1'])
    return subprocess.run(args, check=False, capture_output=True, text=True, timeout=10, **options)


def result_vector(container, db, schema, query):
    result = psql(container, db, schema, query)
    result.check_returncode()
    return json.loads(result.stdout)


def read_write_convergence(container):
    # Verify terminal result contents by request and version, including rejections.
    write = result_vector(container, 'pocoma_write', 'v3_write', """
      select json_build_array(
        (select json_agg(json_build_array(r.request_id,c.requester_issuer,c.requester_subject,r.outcome,r.resulting_pot_version,r.rejection) order by r.request_id)
          from v3_command_result r join v3_command c using(request_id)),
        (select json_agg(json_build_array(r.request_id,c.requester_issuer,c.requester_subject,c.operation,r.outcome,r.target_version,r.rejection) order by r.request_id)
          from v3_identity_request_result r join v3_identity_request c using(request_id)),
        (select count(*) from v3_command where status='PENDING'),
        (select count(*) from v3_identity_request where status='PENDING'),
        (select count(*) from consumption_slot),
        (select count(*) from v3_link_proposal))
    """)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        read = result_vector(container, 'pocoma_read', 'v3_read', """
          select json_build_array(
            (select json_agg(json_build_array(request_id,requester_issuer,requester_subject,write_outcome,resulting_pot_version,rejection) order by request_id)
              from read_command_result),
            (select json_agg(json_build_array(request_id,requester_issuer,requester_subject,operation,write_outcome,target_version,rejection) order by request_id)
              from read_identity_request_result),
            (select count(*) from consumption_slot))
        """)
        if write[:2] == read[:2] and write[2:] == [0, 0, 0, 0] and read[2] == 0:
            return {'commandResults': len(write[0]), 'identityResults': len(write[1]),
                    'pendingOrSlots': 0, 'linkProposals': 0}
        time.sleep(0.1)
    raise RuntimeError('WRITE/READ result convergence deadline or residual slot')


def observe_runtime_principals(container):
    sessions = result_vector(container, 'pocoma', 'public', """
        select coalesce(json_agg(row_to_json(active) order by database,principal),'[]')
        from (
          select datname database, usename principal, count(*) connections,
                 bool_or(rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls) privileged
          from pg_stat_activity join pg_roles on rolname=usename
          where datname in ('pocoma_read','pocoma_write') and backend_type='client backend'
          group by datname,usename
        ) active
    """)
    expected = {
        ('pocoma_write', 'pocoma_admission'), ('pocoma_write', 'pocoma_write'),
        ('pocoma_write', 'pocoma_write_source_read'), ('pocoma_read', 'pocoma_read'),
        ('pocoma_read', 'pocoma_read_http'), ('pocoma_read', 'pocoma_read_status'),
    }
    observed = {(row['database'], row['principal']) for row in sessions}
    if observed != expected or any(row['privileged'] for row in sessions):
        raise RuntimeError('Unexpected application database sessions')
    return sessions


def prove_acl(container, password):
    operations = [
        ('pocoma_admission', password, 'pocoma_write', 'v3_write',
         'select binding_token from v3_write.v3_link_proposal'),
        ('pocoma_write_source_read', 'pocoma-source-local-only', 'pocoma_write', 'v3_write',
         'delete from v3_write.v3_identity_request where false'),
        ('pocoma_read_http', 'pocoma-read-http-local-only', 'pocoma_read', 'v3_read',
         'delete from v3_read.read_current_pot where false'),
        ('pocoma_read_status', 'pocoma-read-status-local-only', 'pocoma_read', 'v3_read',
         'select * from v3_read.read_current_pot'),
        ('pocoma_read', password, 'pocoma_read', 'v3_read',
         "insert into v3_read.consumption_routing_contract "
         "select gen_random_uuid(), 'POT_EVENT', 'sha256-v1', 4 where false"),
    ]
    results = []
    for role, credential, db, schema, query in operations:
        principal = psql(container, db, schema, 'select current_user', role, credential)
        principal.check_returncode()
        if principal.stdout.strip() != role:
            raise RuntimeError('Unexpected SQL principal')
        denied = psql(container, db, schema, query, role, credential)
        if denied.returncode != 1 or '42501' not in denied.stderr:
            raise RuntimeError('Expected an effective permission denial: ' + role)
        results.append({'principal': role, 'database': db, 'operation': query, 'sqlstate': '42501', 'result': 'DENIED'})
    return results


def check_redaction(files, values):
    leaked = []
    for file in files:
        content = file.read_text(errors='replace')
        sanitized = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',
                           '[JWT REDACTED]', content)
        sanitized = re.sub(r'pocoma://invitation/[A-Za-z0-9_-]+', '[INVITATION REDACTED]', sanitized)
        for secret in values:
            sanitized = sanitized.replace(secret, '[REDACTED]')
        if sanitized != content:
            file.write_text(sanitized)
            leaked.append(file.name)
    if leaked:
        raise RuntimeError('Secret detected and removed from ' + ', '.join(leaked))


def run_corpora(evidence, env, secrets):
    all_receipts = {}
    for corpus, expected in CORPORA.items():
        if corpus == '06 Installation Checks' and time.time() <= int(env['POCOMA_L7_EXPIRED_EPOCH']):
            raise RuntimeError('Expired-token stimulus did not reach its expiry before B501')
        folder = evidence / expected[0]
        folder.mkdir()
        receipts = folder / 'requests.jsonl'
        command = [str(COLLECTION / 'node_modules/.bin/bru'), 'run', '-r', corpus, '--env', 'Local',
                   '--sandbox', 'developer', '--noproxy', '--reporter-json', str(folder / 'bruno.json'),
                   '--reporter-skip-all-headers', '--reporter-skip-body']
        names = ('POCOMA_I2_REQUEST_RECEIPTS', 'POCOMA_I4_RECEIPTS', 'POCOMA_L7_RECEIPTS')
        with (folder / 'bruno.log').open('w') as log:
            began = time.monotonic()
            process = subprocess.run(command, cwd=COLLECTION, env={**env, **dict.fromkeys(names, str(receipts))},
                                     text=True, stdout=log, stderr=subprocess.STDOUT, timeout=240)
        check_redaction(folder.iterdir(), secrets)
        actual = [json.loads(line) for line in receipts.read_text().splitlines()] if receipts.exists() else []
        save(folder / 'receipt.json', {'command': command, 'exit': process.returncode,
                                      'seconds': time.monotonic() - began, 'expected': expected,
                                      'actual': [r['scenario'] for r in actual]})
        if process.returncode or sorted(r['scenario'] for r in actual if r['verdict'] == 'PASS') != expected:
            raise RuntimeError('Incomplete or failed Bruno corpus: ' + corpus)
        all_receipts[corpus] = len(actual)
    return all_receipts


def local_key():
    return base64.b64encode(secrets.token_bytes(32)).decode('ascii')


def execute(evidence, temp):
    pg_image = output('docker', 'image', 'inspect', '--format={{.Id}}', 'postgres:17-alpine')
    kc_image = output('docker', 'image', 'inspect', '--format={{.Id}}', 'quay.io/keycloak/keycloak:26.7.0')
    prefix = 'pocoma-l7-recipe-' + uuid4().hex[:12]
    names = []
    processes = {}
    network = False
    pg_port, kc_port, write_port, read_port, *management = free_ports(9)
    issuer = f'http://localhost:{kc_port}/realms/pocoma'
    password = 'pocoma-local-only'
    invitation_key = local_key()
    summary = {'status': 'FAIL', 'sha': output('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
               'dirty': output('git', '-C', str(ROOT), 'status', '--porcelain'),
               'images': {'postgres': pg_image, 'keycloak': kc_image},
               'ports': {'write': write_port, 'read': read_port, 'issuer': kc_port, 'postgres': pg_port},
               'runtimes': {}, 'cleanup': {}}
    began = time.monotonic()
    try:
        call('docker', 'network', 'create', prefix, stdout=subprocess.DEVNULL)
        network = True
        pg = prefix + '-pg'
        call('docker', 'run', '-d', '--name', pg, '--network', prefix, '--network-alias', 'postgres',
             '-p', f'127.0.0.1:{pg_port}:5432', '-e', 'POSTGRES_USER=pocoma', '-e', 'POSTGRES_DB=pocoma',
             '-e', f'POSTGRES_PASSWORD={password}', '-v',
             f'{ROOT}/docker/postgres/functional-init.sql:/docker-entrypoint-initdb.d/functional-init.sql:ro',
             pg_image, stdout=subprocess.DEVNULL)
        names.append(pg)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if psql(pg, 'pocoma', 'public', 'select 1 from pg_namespace where nspname=\'keycloak\'',
                    'pocoma', password).stdout.strip() == '1':
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('PostgreSQL fixture readiness deadline')
        with (evidence / 'bootstrap.log').open('w') as log:
            call('python3', str(ROOT / 'scripts/operations/setup-v3-database.py'),
                 '--postgres-container', pg, stdout=log, stderr=subprocess.STDOUT)
        summary['postgresVersion'] = psql(pg, 'pocoma', 'public', 'show server_version').stdout.strip()
        pg_address = output('docker', 'inspect', '--format',
                            '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', pg)
        kc = prefix + '-kc'
        realm_file, realm = prepare_realm(temp)
        call('docker', 'run', '-d', '--name', kc, '--network', prefix,
             '-p', f'127.0.0.1:{kc_port}:8080', '-e', 'KC_DB=postgres',
             '-e', 'KC_DB_URL=jdbc:postgresql://postgres:5432/pocoma', '-e', 'KC_DB_SCHEMA=keycloak',
             '-e', 'KC_DB_USERNAME=pocoma', '-e', f'KC_DB_PASSWORD={password}', '-v',
             f'{realm_file}:/opt/keycloak/data/import/pocoma-realm.json:ro',
             kc_image, 'start-dev', '--import-realm', f'--hostname=http://localhost:{kc_port}',
             stdout=subprocess.DEVNULL)
        names.append(kc)
        await_http(issuer + '/.well-known/openid-configuration')
        for (name, (module, profile)), management_port in zip(RUNTIMES.items(), management):
            jar = package_path(module)
            properties = (CONFIG / f'{name}.properties').read_text()
            properties = (properties.replace('postgres:5432', f'127.0.0.1:{pg_port}')
                          .replace('postgres,5432,', f'127.0.0.1,{pg_port},')
                          .replace('172.28.0.2/32', pg_address + '/32')
                          .replace('http://localhost:8081/realms/pocoma', issuer)
                          .replace('http://keycloak:8080/realms/pocoma', issuer)
                          .replace('=0.0.0.0', '=127.0.0.1'))
            app_port = {'http-write': write_port, 'http-read': read_port}.get(name, 0)
            properties = re.sub(r'(?m)^server.port=.*$', f'server.port={app_port}', properties)
            properties = re.sub(r'(?m)^management.server.port=.*$',
                                f'management.server.port={management_port}', properties)
            if name in {'http-read', 'command-worker'}:
                properties += ('\npocoma.v3.invitation.current-key=recipe'
                               '\npocoma.v3.invitation.key-ids=recipe'
                               f'\npocoma.v3.invitation.keys.recipe={invitation_key}\n')
            if name == 'http-write':
                for key in ('lookup-key', 'comparison-keys.k1'):
                    properties = re.sub(
                        rf'(?m)^pocoma\.v3\.http\.admission\.{re.escape(key)}=.*$',
                        f'pocoma.v3.http.admission.{key}={local_key()}', properties)
            settings = temp / f'{name}.properties'
            with open(settings, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as config:
                config.write(properties)
            args = ['java', '-Xms128m', '-Xmx512m', '-XX:+UseSerialGC',
                    f'-Dpocoma.v3.handoff.log={temp / (name + "-handoffs.jsonl")}',
                    '-jar', str(jar), f'--spring.profiles.active={profile}',
                    f'--spring.config.additional-location={settings.as_uri()}']
            with (evidence / f'{name}.log').open('w') as log:
                process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            processes[name] = process
            summary['runtimes'][name] = {'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                                         'profile': profile, 'ready': False,
                                         'managementPort': management_port}
            await_http(f'http://127.0.0.1:{management_port}/actuator/health/readiness', process)
            summary['runtimes'][name]['ready'] = True
        sessions, (expired, expiry, wrong_audience) = load_auth(issuer, realm)
        (owner, subject), (guest, guest_subject), (third, _) = sessions
        write_url, read_url = f'http://127.0.0.1:{write_port}', f'http://127.0.0.1:{read_port}'
        env = {**os.environ, 'POCOMA_L7_FRESH': '1', 'POCOMA_L7_WRITE_URL': write_url,
               'POCOMA_L7_READ_URL': read_url, 'POCOMA_I2_BASE_URL': read_url,
               'POCOMA_I2_ISSUER': issuer, 'POCOMA_I4_BASE_URL': read_url,
               'POCOMA_I2_HELPER': str(COLLECTION / 'lib/i2-admission.js'), 'POCOMA_I2_bearerOwner': owner, 'POCOMA_I2_bearerOther': guest,
               'POCOMA_L7_OWNER_SUBJECT': subject, 'POCOMA_L7_OTHER_SUBJECT': guest_subject,
               'POCOMA_L7_EXPIRED_BEARER': expired, 'POCOMA_L7_WRONG_AUDIENCE_BEARER': wrong_audience,
               'POCOMA_L7_EXPIRED_EPOCH': str(expiry),
               'POCOMA_I4_BEARER_OWNER': owner, 'POCOMA_I4_BEARER_GUEST': guest,
               'POCOMA_I4_BEARER_SECOND': third}
        summary['bruno'] = run_corpora(evidence, env, (owner, guest, third, invitation_key, expired, wrong_audience))
        summary['sqlConvergence'] = read_write_convergence(pg)
        summary['acl'] = prove_acl(pg, password)
        summary['activeSqlPrincipals'] = observe_runtime_principals(pg)
        bindings = result_vector(pg, 'pocoma_write', 'v3_write',
                                 "select json_agg(binding_token) from v3_eui_version where binding_token is not null")
        check_redaction(evidence.rglob('*.log'), (owner, guest, third, expired, wrong_audience,
                                                invitation_key, *bindings))
        summary['status'] = 'PASS'
    except Exception as error:
        summary['error'] = str(error)
        raise
    finally:
        for name, process in reversed(list(processes.items())):
            if process.poll() is None:
                process.terminate()
            try:
                summary['cleanup'][name] = process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.kill()
                summary['cleanup'][name] = 'SIGKILL'
                process.wait()
                summary['status'] = 'FAIL'
        for name in reversed(names):
            result = subprocess.run(['docker', 'rm', '-fv', name], capture_output=True, timeout=30)
            summary['cleanup'][name] = result.returncode
            if result.returncode:
                summary['status'] = 'FAIL'
        if network:
            result = subprocess.run(['docker', 'network', 'rm', prefix], capture_output=True, timeout=30)
            summary['cleanup']['network'] = result.returncode
            if result.returncode:
                summary['status'] = 'FAIL'
        summary['seconds'] = time.monotonic() - began
        save(evidence / 'receipt.json', summary)
    if summary['status'] != 'PASS':
        raise RuntimeError('Installation qualification or cleanup failed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    evidence = args.output.resolve()
    evidence.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='pocoma-l7-recipe-') as temp:
        execute(evidence, Path(temp))
