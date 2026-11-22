"""Lifecycle operations (health, start, status, destroy) for local playground instances."""
import hashlib
import json
import math
from pathlib import Path
import sys
import time
from urllib.request import urlopen


def recover_fail_closed_runtimes(rt, path, m, evidence):
    """Detect already-Up runtimes stuck in fail-closed draining or depleted capacity and restart once."""
    reasons = {}
    expected_capacity = rt.configured_admission_capacity(path)
    for name in rt.RUNTIMES:
        reason = _check_runtime_degraded(rt, m['ports'][name], name, expected_capacity)
        if reason:
            reasons[name] = reason
    if not reasons:
        return []
    restarted = list(reasons)
    rt.save(evidence / 'runtime-recovery.json', dict(observedAt=time.time(), reasons=reasons, restarted=restarted))
    rt.compose(path, m, 'restart', *restarted, log=evidence / 'runtimes-recovery-restart.log')
    return restarted


def _check_runtime_degraded(rt, port, name, expected_capacity):
    try:
        readiness = rt.get_json(f'http://127.0.0.1:{port}/actuator/health/readiness')
    except Exception:
        return None
    if readiness.get('status') != 'UP':
        return None
    if name in rt.WORKERS:
        return _check_worker_degraded(rt, port)
    if name == 'http-write':
        text = rt.scrape_prometheus_text(port)
        available = rt.recovery.metric(text, 'pocoma_v3_admission_capacity_available') if text is not None else None
        if available is not None and available < expected_capacity:
            return f'ADMISSION_CAPACITY_DEPLETED:{available}/{expected_capacity}'
    return None


def _check_worker_degraded(rt, port):
    try:
        health = rt.get_json(f'http://127.0.0.1:{port}/actuator/health')
        if health.get('status') != 'UP':
            return 'WORKER_HEALTH_DOWN'
    except Exception:
        return 'WORKER_HEALTH_DOWN'
    text = rt.scrape_prometheus_text(port)
    if text is not None and rt.recovery.metric(text, 'pocoma_v3_worker_state', state='draining') == 1.0:
        return 'WORKER_DRAINING'
    return None


def _check_runtime_healthy(rt, m, name):
    port = m['ports'][name]
    data = rt.get_json(f'http://127.0.0.1:{port}/actuator/health/readiness')
    if data.get('status') != 'UP':
        raise RuntimeError(name)
    if name in rt.WORKERS and rt.get_json(f'http://127.0.0.1:{port}/actuator/health').get('status') != 'UP':
        raise RuntimeError(name + ':DRAINING')
    if name == 'http-write':
        text = rt.scrape_prometheus_text(port)
        if text is not None and rt.recovery.metric(text, 'pocoma_v3_admission_capacity_available') == 0.0:
            raise RuntimeError(name + ':CAPACITY_EXHAUSTED')


def _check_database_current(rt, path, m):
    for db in ('pocoma_write', 'pocoma_read'):
        if rt.sql(path, m, db, 'SELECT fingerprint FROM public.pocoma_local_bootstrap;') != rt.fingerprint():
            raise RuntimeError('DB-FINGERPRINT')
    rows = rt.sql(
        path, m, 'pocoma',
        "SELECT DISTINCT usename FROM pg_stat_activity WHERE datname IN ('pocoma_write','pocoma_read') "
        "AND usename <> 'pocoma' ORDER BY usename;",
    )
    if rt.catalogue(path, m) != m.get('catalogue'):
        raise RuntimeError('DB-CATALOGUE-DRIFT')
    priv_sql = (
        "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'pocoma_%' "
        "AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls);"
    )
    if rt.sql(path, m, 'pocoma', priv_sql) != '0':
        raise RuntimeError('DB-PRIVILEGED-RUNTIME')
    expected = {'pocoma_write', 'pocoma_admission', 'pocoma_read', 'pocoma_write_source_read', 'pocoma_read_http', 'pocoma_read_status'}
    if set(rows.splitlines()) != expected:
        raise RuntimeError('DB-PRINCIPALS')
    if rt.sql(path, m, 'pocoma_read', "SELECT state FROM public.read_maintenance_state;") not in ('NORMAL', 'READY'):
        raise RuntimeError('READ-MAINTENANCE')


def current(rt, path, m, *, wait=False):
    checks = {}

    def run_check(name, action):
        try:
            rt.await_check(action, 150, name) if wait else action()
            checks[name] = 'PASS'
        except Exception:
            checks[name] = 'FAIL'

    for name in rt.RUNTIMES:
        run_check('JVM-' + name, lambda n=name: _check_runtime_healthy(rt, m, n))
    run_check('DB-CURRENT', lambda: _check_database_current(rt, path, m))
    run_check('IDP-DISCOVERY', lambda: _check_idp_discovery(rt, m))
    return checks


def _check_idp_discovery(rt, m):
    data = rt.get_json(m['issuer'] + '/.well-known/openid-configuration')
    if data.get('issuer') != m['issuer'] or 'S256' not in data.get('code_challenge_methods_supported', []):
        raise RuntimeError('IDP-DISCOVERY')


def observability(rt, m, wait=False):
    def targets():
        data = rt.get_json(f'http://127.0.0.1:{m["ports"]["metrics"]}/api/v1/targets')['data']['activeTargets']
        if {t['labels']['job'] for t in data if t['health'] == 'up'} != set(rt.RUNTIMES):
            raise RuntimeError('OBS-TARGETS')
        for name in rt.RUNTIMES:
            with urlopen(f'http://127.0.0.1:{m["ports"][name]}/actuator/prometheus', timeout=5) as response:
                text = response.read().decode()
            if 'jvm_memory_used_bytes' not in text or 'pocoma_' not in text:
                raise RuntimeError('OBS-METRICS')
    try:
        rt.await_check(targets, 30, 'OBS-TOOLS') if wait else targets()
        return 'AVAILABLE'
    except Exception:
        return 'DEGRADED'


def _prepare_distribution_runtime(rt, path, m, evidence):
    if m['inputs'] != rt.source_hash():
        rt.refresh_tooling(path, m, evidence)
    if 'grafana' not in m['ports']:
        raise rt.Refusal('GRAFANA_UPGRADE_REQUIRED: run upgrade-grafana --instance ' + m['instance'])
    before = rt.generations(path, m)
    rt.save(evidence / 'generation-before.json', before)
    m['architecture'] = rt.preflight(path, m)
    for resource in rt.run(['docker', 'ps', '-q', '--filter', 'label=' + rt.LABEL]).splitlines():
        value, = rt.docker_json('inspect', resource)
        if value['Config']['Labels'].get(rt.LABEL) != m['owner']:
            raise rt.Refusal('PRE-OTHER-PLAYGROUND-ACTIVE: stop the other instance first')
    rt.ownership(m)
    cli = rt.ROOT / 'scripts/bruno-v3/node_modules/.bin/bru'
    if not cli.exists():
        rt.run(['npm', 'ci', '--prefix', rt.ROOT / 'scripts/bruno-v3'], timeout=300, log=evidence / 'npm.log')
    if rt.run([cli, '--version']) != rt.prerequisites.contract(rt.ROOT)['brunoVersion']:
        raise rt.Refusal('PRE-BRUNO-VERSION')
    images = rt.prepare_images(path, m, evidence)
    if m.get('images') and m['images'] != images:
        raise rt.Refusal('PRE-IMAGE-DIGEST-DRIFT')
    m['images'] = images
    m['toolchain'] = dict(
        python=sys.version,
        docker=rt.run(['docker', 'version', '--format', '{{.Client.Version}}/{{.Server.Version}}']),
        compose=rt.run(['docker', 'compose', 'version', '--short']),
        node=rt.run(['node', '--version']),
        java=rt.run(['java', '--version']),
    )
    return before, rt.private(path)


def _boot_distribution_stack(rt, path, m, boot_spec):
    evidence, credentials, force_recreate_runtimes = boot_spec
    rt.package(path, m, evidence)
    m['issuer'] = rt.render(path, m, credentials)
    rt.save(path / 'manifest.json', m)
    rt.compose(path, m, 'up', '-d', 'postgres', log=evidence / 'postgres-up.log')
    rt.await_check(
        lambda: rt.sql(path, m, 'pocoma', "SELECT 1 FROM pg_namespace WHERE nspname='keycloak';")
        or (_ for _ in ()).throw(RuntimeError()),
        60, 'DB-INIT-TIMEOUT',
    )
    rt.initialize(lambda db, text: rt.sql(path, m, db, text), credentials['passwords'])
    rt.properties(path, m, credentials)
    rt.compose(path, m, 'up', '-d', 'keycloak', log=evidence / 'keycloak-up.log')
    rt.await_check(lambda: rt.discovery(m['issuer']), 150, 'KEYCLOAK_OIDC_DISCOVERY_UNAVAILABLE')
    auth_checks, tokens = rt.identities(m['issuer'], lambda data: rt.save(evidence / 'keycloak-auth.json', data))
    extra_infra = ['nats'] if m.get('wakeupMode', 'nats') == 'nats' else []
    if extra_infra:
        rt.compose(path, m, 'up', '-d', *extra_infra, log=evidence / 'nats-up.log')
    if force_recreate_runtimes:
        recreate = tuple(rt.RUNTIMES) if force_recreate_runtimes is True else tuple(force_recreate_runtimes)
        remaining = [r for r in rt.RUNTIMES if r not in recreate]
        rt.compose(path, m, 'up', '-d', '--force-recreate', *recreate, log=evidence / 'runtimes-up.log')
        rt.compose(path, m, 'up', '-d', *remaining, 'prometheus', 'grafana', log=evidence / 'observability-up.log')
    else:
        rt.compose(path, m, 'up', '-d', *rt.RUNTIMES, 'prometheus', 'grafana', log=evidence / 'runtimes-up.log')
    observed_catalogue = rt.catalogue(path, m)
    if m.get('catalogue') and m['catalogue'] != observed_catalogue:
        raise rt.Refusal('DB-CATALOGUE-DRIFT')
    m['catalogue'] = observed_catalogue
    rt.verify_acl(path, m, credentials)
    rt.save(path / 'manifest.json', m)
    return auth_checks, tokens


def _verify_canary_and_observability(rt, path, m, verify_spec):
    evidence, tokens, checks, recovered = verify_spec
    write, read_url = rt.urls(m)
    api = guest_api = None

    def persist(client):
        rt.save(evidence / 'public-requests.json', sorted(api.trace + guest_api.trace, key=lambda row: row['startedAt']))
        rt.save(evidence / 'command-observations.json', dict(
            runId=evidence.name, generation=recovered['after'],
            generationId=recovered['generationId'], recoveryState=recovered['state'],
            admissions=api.admissions + guest_api.admissions, lastPoll=client.last_poll,
        ))

    api = rt.PublicAPI(write, read_url, tokens['bruno'], persist)
    guest_api = rt.PublicAPI(write, read_url, tokens['guest'], persist)
    demo_file = path / 'demo.json'
    rt.seed(m['owner'], api, guest_api, rt.read(demo_file) if demo_file.exists() else {}, lambda s: rt.save(demo_file, s))
    canary_result = rt.canary(m['owner'], api, evidence.name, {}, lambda s: rt.save(evidence / 'canary-intent.json', s))
    after = rt.generations(path, m)
    if rt.recovery.identity(after) != recovered['generationId']:
        raise RuntimeError('CANARY-GENERATION-CHANGED')
    canary_result.update(generationId=recovered['generationId'], generation=after)
    rt.save(path / 'canary.json', canary_result)
    rt.save(evidence / 'public-requests.json', api.trace + guest_api.trace)
    tools = rt.observability(m, wait=True)
    grafana_checks, grafana_diagnostics = rt.grafana.checks(m, rt.RUNTIMES, rt.await_check, rt.save, evidence, wait=True)
    checks.update(grafana_checks)
    final_generation = rt.generations(path, m)
    if rt.recovery.identity(final_generation) != recovered['generationId'] or not all(row and row['running'] for row in final_generation.values()):
        raise RuntimeError('CANARY-GENERATION-CHANGED')
    return tools, canary_result, grafana_diagnostics


WAKEUP_STATE_LABELS = {0: 'NOOP', 1: 'CONNECTED', 2: 'DEGRADED'}


def _scrape_nats_varz(rt, cid):
    """Best-effort internal scrape of Core NATS /varz (never exposed on host/Internet)."""
    try:
        raw = rt.run(['docker', 'exec', cid, 'wget', '-qO-', 'http://127.0.0.1:8222/varz'], timeout=5)
        data = json.loads(raw)
        return dict(
            connections=data.get('connections'),
            totalConnections=data.get('total_connections'),
            subscriptions=data.get('subscriptions'),
            inMsgs=data.get('in_msgs'),
            outMsgs=data.get('out_msgs'),
            slowConsumers=data.get('slow_consumers'),
            memBytes=data.get('mem'),
            cpuPct=data.get('cpu'),
            uptime=data.get('uptime'),
        )
    except Exception:
        return None


def wakeup_diagnostics(rt, path, m):
    """Inspect NATS broker state and runtime wakeup telemetry without conflating with Pocoma business availability."""
    configured = m.get('wakeupMode', 'nats')
    broker = dict(configured=(configured == 'nats'), state='NOT_CONFIGURED', healthy=False, available=False)
    if configured == 'nats' and (path / 'compose.json').exists():
        try:
            cid = rt.compose(path, m, 'ps', '-aq', 'nats', timeout=10)
            if cid:
                info, = rt.docker_json('inspect', cid)
                st = info.get('State', {})
                running = bool(st.get('Running'))
                health = st.get('Health', {}).get('Status')
                broker.update(
                    state=st.get('Status', 'unknown'),
                    running=running,
                    health=health or ('running' if running else 'stopped'),
                    healthy=(running and health in (None, 'healthy')),
                    available=(running and health in (None, 'healthy')),
                )
                if broker['available']:
                    varz = _scrape_nats_varz(rt, cid)
                    if varz is not None:
                        broker['varz'] = varz
            else:
                broker.update(state='ABSENT', running=False, health='absent', healthy=False, available=False)
        except Exception:
            broker.update(state='UNOBSERVABLE', running=False, health='unknown', healthy=False, available=False)
    runtimes = {}
    for name in getattr(rt, 'WAKEUP_RUNTIMES', ()):
        port = m.get('ports', {}).get(name)
        text = rt.scrape_prometheus_text(port) if port else None
        if text is None:
            runtimes[name] = dict(state='UNOBSERVABLE')
            continue
        code_raw = rt.recovery.metric(text, 'pocoma_v3_wakeup_state')
        code = int(code_raw) if (code_raw is not None and math.isfinite(code_raw)) else None
        runtimes[name] = dict(
            state=WAKEUP_STATE_LABELS.get(code, 'UNKNOWN'),
            published=rt.recovery.metric(text, 'pocoma_v3_wakeup_published_total'),
            received=rt.recovery.metric(text, 'pocoma_v3_wakeup_received_total'),
            coalesced=rt.recovery.metric(text, 'pocoma_v3_wakeup_coalesced_total'),
            useful=rt.recovery.metric(text, 'pocoma_v3_wakeup_useful_total'),
            spurious=rt.recovery.metric(text, 'pocoma_v3_wakeup_spurious_total'),
            ignoredNoLease=rt.recovery.metric(text, 'pocoma_v3_wakeup_ignored_no_lease_total'),
            fallbackTransitions=rt.recovery.metric(text, 'pocoma_v3_wakeup_fallback_transitions_total'),
            reconnects=rt.recovery.metric(text, 'pocoma_v3_wakeup_reconnects_total'),
            publishErrors=rt.recovery.metric(text, 'pocoma_v3_wakeup_publish_errors_total'),
        )
    effective_states = {v.get('state') for v in runtimes.values()}
    effective_mode = (
        'NOOP' if configured == 'noop'
        else 'CONNECTED' if effective_states == {'CONNECTED'} and broker['available']
        else 'DEGRADED' if 'DEGRADED' in effective_states or not broker['available']
        else 'MIXED'
    )
    return dict(
        topology=m.get('topology', 'T1A'),
        configuredMode=configured,
        effectiveMode=effective_mode,
        broker=broker,
        brokerAvailable=broker['available'],
        runtimes=runtimes,
    )


def _reconfigure_wakeup_mode(rt, path, m, evidence, wakeup_mode, force_recreate_runtimes):
    """Non-destructively switch an existing instance between nats and noop wakeup modes.

    Preserves PostgreSQL data, Keycloak state, secrets, ports, and deployment identity.
    Only stops/removes NATS (when switching to noop) or starts NATS (when switching to nats)
    and force-recreates the affected WAKEUP_RUNTIMES.
    """
    previous_mode = m.get('wakeupMode', 'nats')
    target_mode = wakeup_mode if wakeup_mode is not None else previous_mode
    if target_mode == previous_mode:
        return False, previous_mode, force_recreate_runtimes
    if previous_mode == 'nats' and target_mode == 'noop':
        if (path / 'compose.json').exists():
            rt.compose(path, m, 'rm', '-f', '-s', 'nats', log=evidence / 'nats-remove.log')
        nats_conf = path / 'nats.conf'
        if nats_conf.exists():
            nats_conf.unlink()
    m['wakeupMode'] = target_mode
    rt.save(path / 'manifest.json', m)
    rt.save(
        evidence / 'wakeup-mode-change.json',
        dict(
            previousMode=previous_mode,
            targetMode=target_mode,
            recreatedRuntimes=list(rt.WAKEUP_RUNTIMES),
            observedAt=time.time(),
        ),
    )
    if force_recreate_runtimes is True:
        merged_recreate = True
    elif force_recreate_runtimes:
        merged_recreate = tuple(dict.fromkeys((*force_recreate_runtimes, *rt.WAKEUP_RUNTIMES)))
    else:
        merged_recreate = tuple(rt.WAKEUP_RUNTIMES)
    return True, previous_mode, merged_recreate


def start_distribution(rt, path, m, evidence, *, force_recreate_runtimes=None, wakeup_mode=None):
    before, credentials = _prepare_distribution_runtime(rt, path, m, evidence)
    mode_changed, previous_mode, effective_recreate = _reconfigure_wakeup_mode(
        rt, path, m, evidence, wakeup_mode, force_recreate_runtimes
    )
    auth_checks, tokens = _boot_distribution_stack(rt, path, m, (evidence, credentials, effective_recreate))
    rt.recover_fail_closed_runtimes(path, m, evidence)
    checks = rt.current(path, m, wait=True)
    checks.update(auth_checks)
    if 'FAIL' in checks.values():
        rt.save(evidence / 'checks.json', checks)
        raise RuntimeError('READINESS:' + ','.join(k for k, v in checks.items() if v == 'FAIL'))
    recovered = rt.recover_workers(path, m, evidence, before)
    checks['WORKER-PARTITIONS'] = 'PASS'
    tools, canary_result, grafana_diagnostics = _verify_canary_and_observability(rt, path, m, (evidence, tokens, checks, recovered))
    m.update(state='RUNNING', startedAt=time.time())
    rt.save(path / 'manifest.json', m)
    result = rt.verdict(checks, True, tools, canary_result)
    wakeup = wakeup_diagnostics(rt, path, m)
    wakeup['modeChanged'] = mode_changed
    if mode_changed:
        wakeup['previousMode'] = previous_mode
    result['wakeup'] = wakeup
    if grafana_diagnostics:
        result['grafanaDiagnostics'] = grafana_diagnostics
    return result


def _observe_late_and_canary(rt, path, m, status_spec):
    evidence, diagnostic_run, history, checks = status_spec
    late, content_valid, auth_proof = None, False, {}

    def record(data):
        auth_proof.update(data)
        if evidence:
            rt.save(evidence / 'keycloak-auth.json', data)

    try:
        auth_checks, tokens = rt.identities(m['issuer'], record)
        checks.update(auth_checks)
        api = rt.PublicAPI(*rt.urls(m), tokens['bruno'])
        late = _fetch_late_result(rt, path, api, (diagnostic_run, history, evidence))
        content_valid = _verify_canary_endpoint(path, api)
        checks['IDP-PKCE-SESSION'] = 'PASS'
    except Exception:
        checks['IDP-PKCE-SESSION'] = 'FAIL'
    checks.update(auth_proof.get('checks', {}))
    return late, content_valid, auth_proof


def _fetch_late_result(rt, path, api, spec):
    diagnostic_run, history, evidence = spec
    last = history.get('lastStart', {})
    requested = diagnostic_run
    if not requested and last.get('state') == 'FAILED' and last.get('control', '').startswith('API-POLL-TIMEOUT:/commands/'):
        requested = last['runId']
    if not requested:
        return None
    try:
        return rt.late_result(path, api, requested, evidence)
    except Exception as error:
        late = dict(sourceRunId=requested, control='LATE-RESULT-OBSERVATION-FAILED',
                    errorClass=type(error).__name__, originalVerdictPreserved=True)
        if evidence:
            rt.save(evidence / 'late-result.json', late)
        return late


def _verify_canary_endpoint(path, api):
    proof_file = path / 'canary.json'
    if not proof_file.exists():
        return False
    proof = rt.read(proof_file) if False else None
    import json
    proof = json.loads(proof_file.read_text())
    status_code, binding = api.call('GET', '/me/binding')
    if status_code != 200 or not binding.get('active'):
        raise RuntimeError('READ-BINDING')
    status_code, view = api.call('GET', '/pots/' + proof['potId'], binding=binding['bindingToken'])
    if status_code != 200 or view['potVersion'] != proof['version'] or view['label'] != 'canary-' + proof['runId']:
        raise RuntimeError('READ-CANARY')
    return True


def _qualification_state(freshness, proof, content_valid):
    if freshness['demonstrated']:
        return 'QUALIFIED'
    if proof and freshness['generationQualified'] and content_valid and not freshness['fresh']:
        return 'STALE'
    if proof and not freshness['generationQualified']:
        return 'GENERATION_MISMATCH'
    return 'UNQUALIFIED'


def status(rt, path, m, evidence=None, diagnostic_run=None):
    rt.ownership(m)
    checks = rt.current(path, m)
    before = rt.generations(path, m)
    history = rt.read(path / 'start-history.json') if (path / 'start-history.json').exists() else {}
    late, content_valid, auth_proof = _observe_late_and_canary(rt, path, m, (evidence, diagnostic_run, history, checks))
    grafana_checks, grafana_diagnostics = rt.grafana.checks(m, rt.RUNTIMES, rt.await_check, rt.save, evidence)
    checks.update(grafana_checks)
    proof = rt.read(path / 'canary.json') if (path / 'canary.json').exists() else None
    after = rt.generations(path, m)
    freshness = rt.recovery.proof_state(proof, after, time.time(), rt.MAX_AGE)
    freshness['demonstrated'] = freshness['demonstrated'] and content_valid
    if rt.recovery.identity(before) != rt.recovery.identity(after):
        freshness.update(generationQualified=False, demonstrated=False)
    proof = dict(proof, generationQualified=freshness['generationQualified']) if proof else None
    tools = rt.observability(m)
    result = rt.verdict(checks, freshness['demonstrated'], tools, proof)
    result['wakeup'] = wakeup_diagnostics(rt, path, m)
    _enrich_status_result(rt, result, (checks, freshness, history, after, proof, content_valid, tools, late, auth_proof, grafana_diagnostics))
    return result


def _enrich_status_result(rt, result, data):
    checks, freshness, history, after, proof, content_valid, tools, late, auth_proof, grafana_diagnostics = data
    technical = all(v == 'PASS' for k, v in checks.items() if not k.startswith('GRAFANA-'))
    grafana_ready = all(v == 'PASS' for k, v in checks.items() if k.startswith('GRAFANA-'))
    services_available = bool(technical and grafana_ready)
    operational_state = 'UNAVAILABLE' if not services_available else ('OPERATIONAL' if tools == 'AVAILABLE' else 'DEGRADED_OBSERVABILITY')
    qual_state = _qualification_state(freshness, proof, content_valid)
    result.update(
        technical='AVAILABLE' if technical else 'UNAVAILABLE',
        businessEvidence='PROVEN' if freshness['demonstrated'] else 'UNPROVEN',
        lastStart=history.get('lastStart'), lastSuccessfulStart=history.get('lastSuccessfulStart'),
        currentGeneration=after, generationQualified=freshness['generationQualified'],
        proofFreshness='FRESH' if freshness['fresh'] else 'STALE_OR_MISSING',
        proofAgeSeconds=freshness['ageSeconds'], operationalState=operational_state,
        operational=dict(
            state=operational_state, servicesAvailable=services_available,
            workersOperational=all(checks.get('JVM-' + w) == 'PASS' for w in rt.WORKERS),
            dependenciesAccessible=(checks.get('DB-CURRENT') == 'PASS' and checks.get('IDP-DISCOVERY') == 'PASS'),
            observability=tools,
        ),
        qualificationState=qual_state,
        qualification=dict(
            state=qual_state,
            lastQualificationAt=proof.get('completedAt') if proof else None,
            lastQualificationRunId=proof.get('runId') if proof else None,
            generationQualified=freshness['generationQualified'],
            proofFreshness='FRESH' if freshness['fresh'] else 'STALE_OR_MISSING',
            proofValid=bool(content_valid),
            proofAgeSeconds=freshness['ageSeconds'],
            maxAgeSeconds=rt.MAX_AGE,
        ),
    )
    if technical and not freshness['demonstrated']:
        result['business'] = 'UNPROVEN'
    if late:
        result['lateResult'] = late
    if 'diagnostic' in auth_proof:
        result['diagnostic'] = auth_proof['diagnostic']
    if grafana_diagnostics:
        result['grafanaDiagnostics'] = grafana_diagnostics


def remove_project(rt, path, m, evidence, log_name):
    rt.ownership(m)
    preserved = rt.unowned_attached_volumes(m)
    rt.compose(path, m, 'down', '--timeout', '45', timeout=180, log=evidence / log_name)
    rt.ownership(m)
    removed = []
    for volume in rt.run(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + m['project']]).splitlines():
        value, = rt.docker_json('volume', 'inspect', volume)
        labels = value.get('Labels') or {}
        if volume != m['project'] + '-data' or labels.get(rt.LABEL) != m['owner'] or labels.get('com.docker.compose.project') != m['project']:
            raise rt.Refusal('PRE-FOREIGN-VOLUME')
        rt.run(['docker', 'volume', 'rm', volume])
        removed.append(volume)
    for volume in preserved:
        rt.docker_json('volume', 'inspect', volume)
    for query in (['ps', '-aq'], ['volume', 'ls', '-q'], ['network', 'ls', '-q']):
        if rt.run(['docker', *query, '--filter', 'label=com.docker.compose.project=' + m['project']]):
            raise rt.Refusal('DESTROY-RESOURCES-REMAIN: preserve manifest and inspect inventory')
    result = dict(removedOwnedVolumes=removed, preservedUnownedVolumes=preserved)
    rt.save(evidence / 'volume-removal.json', result)
    return result


def destroy(rt, path, m, evidence):
    plan = rt.destruction_plan(path, m)
    if not (path / 'compose.json').exists():
        raise rt.Refusal('DESTROY-COMPOSE-MISSING: retain state and inspect resource inventory')
    rt.save(evidence / 'destroy-plan.json', plan)
    if rt.stop(path, m, evidence)['exit']:
        raise rt.Refusal('DESTROY-STOP-INCOMPLETE: inspect diagnostics before retrying')
    rt.diagnostics(path, m, evidence)
    volumes = remove_project(rt, path, m, evidence, 'destroy.log')
    for file in path.iterdir():
        if file.is_file() and file.name != 'lock':
            file.unlink()
    if (path / 'grafana').exists():
        rt.shutil.rmtree(path / 'grafana')
    return dict(state='DESTROYED', exit=0, project=m['project'], diagnostics='PRESERVED', **volumes)
