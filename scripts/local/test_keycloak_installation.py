"""Fresh L7-LB qualification; retains all data and never resets/destroys anything."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time

import runtime as rt
import test_gui_auth_protocol


def qualify(instance, output):
    path = rt.HOME_LOCAL / 'instances' / instance
    if path.exists() or output.exists():
        raise RuntimeError('Require NEW instance and evidence directory')
    output.mkdir(parents=True, mode=0o700)
    inputs = rt.source_hash()
    commands = []
    def command(action, phase):
        argv = [str(rt.ROOT / 'pocoma-local'), action, '--instance', instance]
        print('Running ' + phase, flush=True)
        began = time.monotonic()
        completed = subprocess.run(argv, capture_output=True, text=True, timeout=1800, cwd=rt.ROOT)
        result = json.loads(completed.stdout[completed.stdout.index('{'):])
        commands.append(dict(command=argv, phase=phase, exit=completed.returncode,
                             seconds=time.monotonic() - began, result=result))
        rt.save(output / 'commands.json', commands)
        dest = output / phase
        dest.mkdir()
        source = Path(result['evidence'])
        for name in ('receipt.json', 'keycloak-auth.json', 'public-requests.json', 'bruno-receipt.json',
                     'bruno.xml', 'scenarios.jsonl', 'recovery-window.json', 'checks.json', 'package.log'):
            if (source / name).exists():
                shutil.copyfile(source / name, dest / name)
        if completed.returncode:
            raise RuntimeError('Qualification failed: ' + phase + '; see commands.json and private diagnostics')
        return result
    try:
        first = command('start', 'fresh-start')
        assert first['state'] == 'READY'
        command('status', 'fresh-status')
        gui = command('gui', 'gui')
        environment = Path(gui['environment']).read_text()
        assert all(word not in environment.lower() for word in ('bearer', 'password', 'access_token'))
        command('bruno', 'cli')
        print('Running installed native Bruno protocol', flush=True)
        test_gui_auth_protocol.execute(instance, output / 'native-installed.json')
        m = rt.load_manifest(path)
        rt.save(output / 'manifest-public.json', {k: m[k] for k in (
            'instance', 'owner', 'project', 'ports', 'inputs', 'sha', 'images', 'architecture', 'toolchain', 'build')})
        keycloak_id = rt.compose(path, m, 'ps', '-q', 'keycloak')
        version = rt.run(['docker', 'exec', keycloak_id, '/opt/keycloak/bin/kc.sh', '--version'])
        assert 'Keycloak 26.7.0' in version
        bindings = {}
        for resource in rt.compose(path, m, 'ps', '-aq').splitlines():
            value, = rt.docker_json('inspect', resource)
            service = value['Config']['Labels']['com.docker.compose.service']
            bindings[service] = value['HostConfig'].get('PortBindings') or {}
            assert all(binding['HostIp'] == '127.0.0.1' for entries in bindings[service].values() for binding in entries or [])
        assert not bindings['postgres']
        rt.save(output / 'platform.json', dict(keycloakVersion=version, bindings=bindings))
        demo_before = (path / 'demo.json').read_bytes()
        command('stop', 'stop')
        resumed = command('start', 'restart')
        assert resumed['state'] == 'READY'
        assert resumed['canaryProof']['version'] == first['canaryProof']['version'] + 1
        assert (path / 'demo.json').read_bytes() == demo_before
        command('status', 'restart-status')
        if rt.source_hash() != inputs:
            raise RuntimeError('Distribution inputs changed during qualification')
        rt.save(output / 'qualification.json', dict(verdict='PASS', instance=instance, inputs=inputs,
            testedSha=rt.run(['git', 'rev-parse', 'HEAD']), dirty=bool(rt.run(['git', 'status', '--porcelain'])),
            checks=['fresh READY', 'five JVMs/PG/Keycloak/Prometheus', 'master admin Code+S256',
                    'four accounts enabled/no required actions', 'three users Code+S256, missing/plain rejected',
                    '118 Bruno requests/236 named tests', 'installed Bruno parser/cache/VM/transport (version in receipt)',
                    'GUI public environment', 'loopback-only bindings and no PG port',
                    'graceful stop and restart READY', 'new canary version/demo checkpoints preserved'],
            limitations=['Rendered GUI login/Runner not executed; no complete GUI acceptance claim.',
                         'No historical L7 certification change, full reactor or destructive reset.',
                         'Dependency/image caches reused; new databases and fresh distribution package.'],
            resources='RUNNING_AND_RETAINED'))
        print('Fresh Keycloak qualification PASS: ' + str(output), flush=True)
    except Exception as error:
        rt.save(output / 'qualification.json', dict(verdict='FAIL', instance=instance, inputs=inputs,
            errorClass=type(error).__name__, resources='PRESERVED'))
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    qualify(args.instance, args.output.resolve())
