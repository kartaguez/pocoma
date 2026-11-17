"""Declarative Grafana for the private L7-LB distribution only."""
import base64
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
USERNAME, PASSWORD = 'admin', 'pocoma'
DASHBOARDS = {'pocoma-runtime': 'Pocoma runtime',
              'pocoma-v3-overview': 'Pocoma V3 - Architecture & Observability Overview'}
LEGACY_JOBS = 'pocoma-api|pocoma-command-consumption-worker|pocoma-latest-known-version-consumption-worker|pocoma-event-consumption-workers|pocoma-task-consumption-workers'


def _adapt_expr(expr, runtimes):
    return expr.replace(LEGACY_JOBS, '|'.join(runtimes)).replace(
        'MaterializePotVersion|MaterializePotBalance|MaterializePotVisibility|MaterializeCurrentBinding|MaterializeCommandResult|MaterializeIdentityRequestResult',
        'PotVersion|PotBalance|PotVisibility|CurrentBinding|CommandResult|IdentityRequestResult',
    )


def _adapt_value(value, runtimes):
    if isinstance(value, list):
        for child in value:
            _adapt_value(child, runtimes)
        return
    if not isinstance(value, dict):
        return
    if value.get('type') == 'prometheus' and 'uid' in value:
        value['uid'] = 'Prometheus'
    for key, child in value.items():
        if key == 'expr':
            value[key] = _adapt_expr(child, runtimes)
        else:
            _adapt_value(child, runtimes)


def dashboards(runtimes):
    """Reuse L6 panels; adapt only topology selectors and datasource identity."""
    result = {}
    for uid in DASHBOARDS:
        data = json.loads((ROOT / 'docker/grafana/dashboards' / (uid + '.json')).read_text())
        _adapt_value(data, runtimes)
        result[uid] = data
    return result


def provision(path, runtimes):
    root = path / 'grafana'
    for directory in (root, root / 'provisioning', root / 'provisioning/datasources',
                      root / 'provisioning/dashboards', root / 'dashboards'):
        directory.mkdir(exist_ok=True)
        directory.chmod(0o755)  # UID472 traverses only below the private instance root.
    datasource = (ROOT / 'docker/grafana/provisioning/datasources/prometheus.yml').read_text()
    (root / 'provisioning/datasources/prometheus.yml').write_text(datasource.replace('editable: true', 'editable: false'))
    provider = (ROOT / 'docker/grafana/provisioning/dashboards/dashboards.yml').read_text()
    (root / 'provisioning/dashboards/dashboards.yml').write_text(
        provider.replace('editable: true', 'editable: false').replace('/var/lib/grafana/dashboards', '/etc/grafana/dashboards'))
    for uid, data in dashboards(runtimes).items():
        (root / 'dashboards' / (uid + '.json')).write_text(json.dumps(data, indent=2) + '\n')
    for file in root.rglob('*'):
        if file.is_file():
            file.chmod(0o644)


def service(path, m, label):
    return dict(image=m['images']['grafana']['digest'], labels={label: m['owner']},
        environment=dict(GF_SECURITY_ADMIN_USER=USERNAME, GF_SECURITY_ADMIN_PASSWORD=PASSWORD,
                         GF_USERS_ALLOW_SIGN_UP='false', GF_AUTH_ANONYMOUS_ENABLED='false',
                         GF_ANALYTICS_REPORTING_ENABLED='false', GF_ANALYTICS_CHECK_FOR_UPDATES='false',
                         GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES='false', GF_PLUGINS_PREINSTALL_DISABLED='true'),
        ports=[f'127.0.0.1:{m["ports"]["grafana"]}:3000'],
        volumes=[f'{path}/grafana/provisioning:/etc/grafana/provisioning:ro',
                 f'{path}/grafana/dashboards:/etc/grafana/dashboards:ro'],
        tmpfs=['/var/lib/grafana:rw,uid=472,gid=0,mode=0750,size=128m'],
        mem_limit='384m', cpus=.5,
        depends_on=['prometheus'])


def access(m):
    if 'grafana' not in m['ports']:
        return None
    return dict(url=f'http://127.0.0.1:{m["ports"]["grafana"]}/', username=USERNAME,
                dashboard=DASHBOARDS['pocoma-runtime'], dashboardUrl=f'http://127.0.0.1:{m["ports"]["grafana"]}/d/pocoma-runtime')


def get(m, route):
    request = Request(access(m)['url'].rstrip('/') + route,
        headers={'Authorization': 'Basic ' + base64.b64encode((USERNAME + ':' + PASSWORD).encode()).decode()})
    with urlopen(request, timeout=5) as response:
        return json.load(response)


def query(m, expression):
    data = get(m, '/api/datasources/proxy/uid/Prometheus/api/v1/query?' + urlencode({'query': expression}))
    if data.get('status') != 'success':
        raise RuntimeError('PromQL query failed')
    return data['data']['result']


def _check_http(m, proof):
    health = get(m, '/api/health')
    user = get(m, '/api/user')
    valid_admin = user.get('login') == USERNAME and bool(user.get('isGrafanaAdmin'))
    if health.get('database') != 'ok' or not valid_admin:
        raise RuntimeError('Grafana health/admin authentication')
    proof.update(version=health.get('version'), adminAuthenticated=True)


def _check_datasource(m, runtimes, proof):
    ds = get(m, '/api/datasources/uid/Prometheus')
    health = get(m, '/api/datasources/uid/Prometheus/health')
    valid_ds = ds.get('type') == 'prometheus' and ds.get('url') == 'http://prometheus:9090' and ds.get('isDefault')
    if not valid_ds or health.get('status') != 'OK':
        raise RuntimeError('Datasource configuration/health')
    # Exercise Grafana -> Prometheus, rather than only the host Prometheus API.
    observed = {}
    for metric in ('up', 'jvm_memory_used_bytes', 'process_cpu_usage'):
        rows = query(m, metric)
        threshold = 1 if metric == 'up' else 0
        jobs = {r['metric'].get('job') for r in rows if float(r['value'][1]) >= threshold}
        if jobs != set(runtimes):
            raise RuntimeError('Missing runtime metric: ' + metric)
        observed[metric] = sorted(jobs)
    pocoma_jobs = {r['metric'].get('job') for r in query(m, 'count({__name__=~"pocoma_.+"}) by (job)')}
    if pocoma_jobs != set(runtimes):
        raise RuntimeError('Missing Pocoma metrics')
    proof['datasource'] = dict(uid=ds['uid'], url=ds['url'], health=health['status'], metrics=observed)


def _check_dashboards(m, runtimes, proof):
    for uid, expected in dashboards(runtimes).items():
        data = get(m, '/api/dashboards/uid/' + uid)
        actual = data['dashboard']
        if not data['meta'].get('provisioned') or actual['title'] != expected['title']:
            raise RuntimeError('Dashboard provisioning')
        if actual['panels'] != expected['panels']:
            raise RuntimeError('Dashboard panel/datasource drift')
    proof['dashboards'] = DASHBOARDS


def _probe_panel_target(m, uid, panel_title, target):
    import math
    rows = query(m, target['expr'])
    values = [float(row['value'][1]) for row in rows]
    if not values:
        state = 'ABSENT'
    elif not any(map(math.isfinite, values)):
        state = 'NONFINITE'
    elif all(v == 0 for v in values):
        state = 'ZERO'
    else:
        state = 'PRESENT'
    return dict(dashboard=uid, panel=panel_title, refId=target['refId'],
                expression=target['expr'], state=state, series=len(rows))


def _collect_panel_proofs(m, runtimes, result, proof):
    for uid, data in dashboards(runtimes).items():
        for panel in data['panels']:
            for target in panel.get('targets', []):
                try:
                    proof['panels'].append(_probe_panel_target(m, uid, panel['title'], target))
                except Exception as error:
                    result['GRAFANA-DASHBOARD'] = 'FAIL'
                    proof['diagnostics']['GRAFANA-DASHBOARD'] = dict(
                        code='GRAFANA_PROMQL_UNAVAILABLE', errorClass=type(error).__name__)


def checks(m, runtimes, await_check, save, evidence=None, wait=False):
    result, proof = {}, {'diagnostics': {}, 'panels': []}

    def check(name, diagnostic, action):
        try:
            await_check(action, 60, diagnostic) if wait else action()
            result[name] = 'PASS'
        except Exception as error:
            result[name] = 'FAIL'
            proof['diagnostics'][name] = dict(code=diagnostic, errorClass=type(error).__name__)

    check('GRAFANA-HTTP', 'GRAFANA_START_TIMEOUT', lambda: _check_http(m, proof))
    check('GRAFANA-DATASOURCE', 'GRAFANA_DATASOURCE_UNAVAILABLE', lambda: _check_datasource(m, runtimes, proof))
    check('GRAFANA-DASHBOARD', 'GRAFANA_DASHBOARD_NOT_PROVISIONED', lambda: _check_dashboards(m, runtimes, proof))
    # Capture real panel results and distinguish empty, nonfinite, zero and nonzero.
    # Empty activity-dependent timers do not block readiness; structural metrics above do.
    if all(value == 'PASS' for value in result.values()):
        _collect_panel_proofs(m, runtimes, result, proof)
    proof['checks'] = result
    if evidence:
        save(evidence / 'grafana.json', proof)
    return result, proof['diagnostics']
