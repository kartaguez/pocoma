"""Grafana upgrade/refresh orchestration for stopped local instances."""
import hashlib
import json
from pathlib import Path
import re
import tempfile

from bootstrap import AVAILABILITY_MODULE, SETUP_SCRIPT


_PROTECTED_INPUTS = (
    'app', 'docker/v3-five-runtimes', 'docker/keycloak', 'scripts/v3',
    SETUP_SCRIPT, AVAILABILITY_MODULE, 'scripts/local/bootstrap.py',
    'scripts/local/public_api.py', 'scripts/local/keycloak.py', 'scripts/local/auth.py',
    'scripts/bruno-v3/package.json', 'scripts/bruno-v3/package-lock.json',
)


def _ensure_stopped(rt, path, m):
    if m.get('state') != 'STOPPED':
        raise rt.Refusal('GRAFANA_UPGRADE_STOP_REQUIRED: stop this instance first; data is preserved')
    for resource in rt.compose(path, m, 'ps', '-aq').splitlines():
        value, = rt.docker_json('inspect', resource)
        if value['State']['Running']:
            raise rt.Refusal('GRAFANA_UPGRADE_STOP_REQUIRED')


def _verify_protected_baseline(rt, m, evidence):
    baseline = m.get('build', {}).get('sha', '')
    if not re.fullmatch(r'[0-9a-f]{40}', baseline):
        raise rt.Refusal('GRAFANA_UPGRADE_BASELINE_UNPROVEN')
    changed = rt.run(['git', 'diff', '--name-only', baseline, '--', *_PROTECTED_INPUTS]).splitlines()
    changed += rt.run(['git', 'ls-files', '--others', '--exclude-standard', '--', *_PROTECTED_INPUTS]).splitlines()
    if changed:
        rt.save(evidence / 'grafana-upgrade-refused.json', dict(changedProtectedInputs=changed, baseline=baseline))
        raise rt.Refusal('GRAFANA_UPGRADE_PRODUCTION_DRIFT: preserve instance; inspect grafana-upgrade-refused.json')
    return baseline


def _verify_jars(rt, path, m):
    for module, expected in m['build']['jars'].items():
        if hashlib.sha256((path / (module + '.jar')).read_bytes()).hexdigest() != expected:
            raise rt.Refusal('GRAFANA_UPGRADE_JAR_DRIFT')


def _verify_deployment_artifacts(rt, path, m, installed):
    _verify_jars(rt, path, m)
    if not (path / 'secrets.json').is_file():
        raise rt.Refusal('GRAFANA_UPGRADE_SECRETS_MISSING')
    old = rt.read(path / 'compose.json')
    expected_services = {*rt.RUNTIMES, 'postgres', 'keycloak', 'prometheus', *(['grafana'] if installed else [])}
    if set(old['services']) != expected_services:
        raise rt.Refusal('GRAFANA_UPGRADE_TOPOLOGY_DRIFT')
    mount_root = Path(old['services']['keycloak']['volumes'][0].split(':')[0]).parent
    if not mount_root.samefile(path):
        raise rt.Refusal('GRAFANA_UPGRADE_MOUNT_ROOT_DRIFT')
    return old, mount_root


def _find_staged_drift(stage, path, old, installed):
    new = json.loads((stage / 'compose.json').read_text().replace(str(stage), str(path)))
    legacy = new if installed else dict(new, services={k: v for k, v in new['services'].items() if k != 'grafana'})
    if legacy != old:
        return None, 'GRAFANA_UPGRADE_COMPOSE_DRIFT'
    for name in ('pg-password', 'pg-init.sql', 'realm.json', 'prometheus.yml'):
        if (stage / name).read_bytes() != (path / name).read_bytes():
            return None, 'GRAFANA_UPGRADE_CONFIG_DRIFT:' + name
    return new, None


def _stage_and_apply_compose(rt, path, candidate, stage_spec):
    old, evidence, installed, original_manifest = stage_spec
    with tempfile.TemporaryDirectory(prefix='grafana-upgrade-', dir=path) as directory:
        stage = Path(directory)
        rt.render(stage, candidate, rt.read(path / 'secrets.json'), deployment_path=path)
        new, drift = _find_staged_drift(stage, path, old, installed)
        if drift:
            raise rt.Refusal(drift)
        preserved = ['secrets.json', 'demo.json', 'canary.json', *[mod + '.jar' for mod in original_manifest['build']['jars']],
                     *[name + '.properties' for name in rt.RUNTIMES]]
        hashes = {name: hashlib.sha256((path / name).read_bytes()).hexdigest()
                  for name in preserved if (path / name).exists()}
        rt.save(evidence / 'manifest-before-upgrade.json', original_manifest)
        rt.save(evidence / 'compose-before-upgrade.json', old)
        rt.grafana.provision(path, rt.RUNTIMES)
        rt.save(path / 'compose.json', new)
        return hashes


def _build_upgrade_candidate(rt, path, spec):
    m, evidence, installed, mount_root = spec
    candidate = json.loads(json.dumps(m))
    candidate['mountRoot'] = str(mount_root)
    candidate['architecture'] = rt.preflight(path, m)
    images = rt.prepare_images(path, candidate, evidence)
    if {k: v for k, v in images.items() if k in m['images']} != m['images']:
        raise rt.Refusal('PRE-IMAGE-DIGEST-DRIFT')
    candidate['images'] = images
    if not installed:
        candidate['ports'].update(rt.allocate_ports(['grafana']))
        if candidate['ports']['grafana'] in m['ports'].values():
            raise rt.Refusal('GRAFANA_UPGRADE_PORT_COLLISION: retry before mutation')
    return candidate


def upgrade_grafana(rt, path, m, evidence):
    """Explicit Grafana upgrade/refresh of a stopped deployment, never a reset."""
    rt.ownership(m)
    installed = 'grafana' in m['ports']
    if installed and m['inputs'] == rt.source_hash():
        return rt.start(path, m, evidence)
    _ensure_stopped(rt, path, m)
    baseline = _verify_protected_baseline(rt, m, evidence)
    old, mount_root = _verify_deployment_artifacts(rt, path, m, installed)
    candidate = _build_upgrade_candidate(rt, path, (m, evidence, installed, mount_root))
    hashes = _stage_and_apply_compose(rt, path, candidate, (old, evidence, installed, m))
    candidate['inputs'] = rt.source_hash()
    candidate['grafanaUpgrade'] = dict(
        baseline=baseline, previousInputs=m['inputs'], inputs=candidate['inputs'],
        preservedFiles=hashes, evidence=str(evidence),
        method='PRODUCTION_AND_DEPLOYMENT_EQUIVALENCE',
        operation='REFRESH_PROVISIONING' if installed else 'ADD_GRAFANA',
    )
    rt.save(evidence / 'grafana-upgrade.json', candidate['grafanaUpgrade'])
    m.clear()
    m.update(candidate)
    rt.save(path / 'manifest.json', m)
    return rt.start(path, m, evidence)
