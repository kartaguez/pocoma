#!/usr/bin/env python3
"""Unit tests for T1A + Core NATS integration in pocoma-local."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime as rt
import runtime_lifecycle as rl


class T1ANatsLocalTests(unittest.TestCase):
    def _fixture(self, path, wakeup_mode='nats'):
        m = rt.new_manifest(path, 'test-t1a', wakeup_mode=wakeup_mode)
        m['images'] = {
            role: dict(reference=ref, digest=f'{ref.split(":")[0]}@sha256:{"0" * 64}', id='id', architecture='arm64')
            for role, ref in rt.IMAGES.items()
        }
        creds = rt.private(path, create=True)
        m['issuer'] = rt.render(path, m, creds)
        return m, creds

    def test_default_t1a_nats_renders_ten_services_with_internal_only_nats_and_token(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            m, creds = self._fixture(path, wakeup_mode='nats')
            compose = rt.read(path / 'compose.json')
            self.assertEqual(10, len(compose['services']))
            self.assertIn('nats', compose['services'])
            self.assertIn('critical-read-worker', compose['services'])
            self.assertIn('pot-read-worker', compose['services'])
            self.assertNotIn('results-worker', compose['services'])
            self.assertNotIn('business-read-worker', compose['services'])
            nats_svc = compose['services']['nats']
            self.assertNotIn('ports', nats_svc)
            self.assertEqual('unless-stopped', nats_svc['restart'])
            nats_conf = (path / 'nats.conf').read_text()
            self.assertIn('jetstream: false', nats_conf)
            self.assertIn(creds['passwords']['nats'], nats_conf)
            self.assertNotIn('pocoma-nats-local-only', nats_conf)
            for name in rt.WAKEUP_RUNTIMES:
                raw = (rt.ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
                rendered = rt._render_runtime_properties(name, raw, m, creds, '172.28.0.2')
                self.assertIn('pocoma.v3.wakeup.mode=nats', rendered)
                self.assertIn(creds['passwords']['nats'], rendered)

    def test_noop_wakeup_mode_renders_nine_services_without_nats(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            m, creds = self._fixture(path, wakeup_mode='noop')
            compose = rt.read(path / 'compose.json')
            self.assertEqual(9, len(compose['services']))
            self.assertNotIn('nats', compose['services'])
            self.assertFalse((path / 'nats.conf').exists())
            for name in rt.WAKEUP_RUNTIMES:
                raw = (rt.ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
                rendered = rt._render_runtime_properties(name, raw, m, creds, '172.28.0.2')
                self.assertIn('pocoma.v3.wakeup.mode=noop', rendered)

    def test_wakeup_diagnostics_distinguishes_stopped_broker_from_pocoma_availability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            m, _ = self._fixture(path, wakeup_mode='nats')
            prom_degraded = (
                'pocoma_v3_wakeup_state{role="process"} 2.0\n'
                'pocoma_v3_wakeup_fallback_transitions_total{role="process"} 1.0\n'
                'pocoma_v3_wakeup_reconnects_total{role="process"} 0.0\n'
            )
            with patch.object(rt, 'compose', return_value='cid-nats'), \
                    patch.object(rt, 'docker_json', return_value=[{'State': {'Running': False, 'Status': 'exited'}}]), \
                    patch.object(rt, 'scrape_prometheus_text', return_value=prom_degraded):
                diag = rl.wakeup_diagnostics(rt, path, m)
            self.assertEqual('T1A', diag['topology'])
            self.assertEqual('nats', diag['configuredMode'])
            self.assertEqual('DEGRADED', diag['effectiveMode'])
            self.assertFalse(diag['brokerAvailable'])
            self.assertEqual('exited', diag['broker']['state'])
            self.assertEqual('DEGRADED', diag['runtimes']['command-worker']['state'])
            self.assertEqual(1.0, diag['runtimes']['command-worker']['fallbackTransitions'])

    def test_non_destructive_switch_nats_to_noop_and_back_preserves_identity_and_recreates_only_wakeup_runtimes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            evidence = path / 'evidence'
            evidence.mkdir()
            m, creds = self._fixture(path, wakeup_mode='nats')
            rt.save(path / 'manifest.json', m)
            original_owner = m['owner']
            original_project = m['project']
            original_ports = dict(m['ports'])
            original_nats_token = creds['passwords']['nats']

            # 1. Stage NATS -> NOOP: manifest.wakeupMode stays 'nats' until commit!
            changed, resumed, prev, target, recreate = rl._prepare_wakeup_transition(
                rt, path, m, evidence, 'noop', None
            )
            self.assertTrue(changed)
            self.assertFalse(resumed)
            self.assertEqual('nats', prev)
            self.assertEqual('noop', target)
            self.assertEqual(tuple(rt.WAKEUP_RUNTIMES), recreate)
            self.assertEqual('nats', rt.read(path / 'manifest.json')['wakeupMode'])
            self.assertEqual('noop', rt.read(path / 'manifest.json')['wakeupTransition']['to'])
            self.assertTrue((path / 'nats.conf').exists(), 'nats.conf preserved until commit')

            # Commit NATS -> NOOP
            rt.render(path, dict(m, wakeupMode='noop'), creds)
            rl._commit_wakeup_transition(rt, path, m, 'noop')
            self.assertEqual('noop', rt.read(path / 'manifest.json')['wakeupMode'])
            self.assertNotIn('wakeupTransition', rt.read(path / 'manifest.json'))
            self.assertFalse((path / 'nats.conf').exists())
            self.assertEqual(original_owner, m['owner'])
            self.assertEqual(original_project, m['project'])
            self.assertEqual(original_ports, m['ports'])
            compose_noop = rt.read(path / 'compose.json')
            self.assertEqual(9, len(compose_noop['services']))
            self.assertNotIn('nats', compose_noop['services'])

            # 2. Restart without --wakeup-mode (wakeup_mode=None) preserves durable NOOP mode
            changed_same, resumed_same, prev_same, target_same, recreate_same = rl._prepare_wakeup_transition(
                rt, path, m, evidence, None, None
            )
            self.assertFalse(changed_same)
            self.assertFalse(resumed_same)
            self.assertEqual('noop', prev_same)
            self.assertEqual('noop', target_same)
            self.assertIsNone(recreate_same)

            # 3. Switch NOOP -> NATS
            changed_back, resumed_back, prev_back, target_back, recreate_back = rl._prepare_wakeup_transition(
                rt, path, m, evidence, 'nats', None
            )
            self.assertTrue(changed_back)
            self.assertEqual('noop', prev_back)
            self.assertEqual('nats', target_back)
            self.assertEqual(tuple(rt.WAKEUP_RUNTIMES), recreate_back)
            rt.render(path, dict(m, wakeupMode='nats'), creds)
            rl._commit_wakeup_transition(rt, path, m, 'nats')
            self.assertEqual('nats', rt.read(path / 'manifest.json')['wakeupMode'])
            compose_nats = rt.read(path / 'compose.json')
            self.assertEqual(10, len(compose_nats['services']))
            self.assertIn('nats', compose_nats['services'])
            self.assertIn(original_nats_token, (path / 'nats.conf').read_text())

    def test_fault_injection_recovery_across_all_six_interruption_points_both_directions(self):
        import prerequisites

        fault_points = [
            'before_prepare_compose',
            'after_prepare_before_apply',
            'after_nats_resource_change',
            'during_runtime_recreation',
            'before_manifest_commit',
            'during_revert_to_previous_mode',
        ]
        for start_mode, target_mode in (('nats', 'noop'), ('noop', 'nats')):
            for fault in fault_points:
                with self.subTest(direction=f'{start_mode}->{target_mode}', fault=fault):
                    with tempfile.TemporaryDirectory() as directory:
                        import hashlib
                        path = Path(directory) / 'test-t1a'
                        path.mkdir()
                        m, creds = self._fixture(path, wakeup_mode=start_mode)
                        m['state'] = 'RUNNING'
                        jar_hash = hashlib.sha256(b'jar').hexdigest()
                        m['build'] = {
                            'sha': 'a' * 40,
                            'jars': {mod: jar_hash for mod in sorted({v[0] for v in rt.RUNTIMES.values()})},
                        }
                        # Create dummy JARs, properties, and grafana files so prerequisites.instance validates fully
                        for mod in sorted({v[0] for v in rt.RUNTIMES.values()}):
                            (path / (mod + '.jar')).write_bytes(b'jar')
                        for name in rt.RUNTIMES:
                            raw = (rt.ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
                            (path / (name + '.properties')).write_text(
                                rt._render_runtime_properties(name, raw, m, creds, '172.28.0.2')
                            )
                        rt.save(path / 'manifest.json', m)
                        orig_secrets = (path / 'secrets.json').read_text()
                        orig_ports = dict(m['ports'])
                        orig_owner = m['owner']

                        injected = {'active': True, 'stage': fault}

                        def run_start(req_mode):
                            ev = path / 'runs' / f'run-{req_mode}-{injected["active"]}'
                            ev.mkdir(parents=True, exist_ok=True)

                            def fake_package(p, mf, e):
                                if injected['active'] and injected['stage'] == 'before_prepare_compose':
                                    raise RuntimeError('INJECTED_PACKAGE_FAIL')

                            def fake_compose(p, mf, *args, **kw):
                                if injected['active'] and injected['stage'] == 'after_prepare_before_apply' and args[:3] == ('up', '-d', 'postgres'):
                                    raise RuntimeError('INJECTED_BEFORE_APPLY_FAIL')
                                if injected['active'] and injected['stage'] == 'after_nats_resource_change' and '--force-recreate' in args:
                                    raise RuntimeError('INJECTED_AFTER_NATS_FAIL')
                                if injected['active'] and injected['stage'] == 'during_runtime_recreation' and '--force-recreate' in args:
                                    raise RuntimeError('INJECTED_RUNTIME_RECREATE_FAIL')
                                if injected['active'] and injected['stage'] == 'during_revert_to_previous_mode' and '--force-recreate' in args:
                                    raise RuntimeError('INJECTED_REVERT_FAIL')
                                return ''

                            def fake_properties(p, mf, c):
                                for name in rt.RUNTIMES:
                                    raw = (rt.ROOT / 'docker/v3-five-runtimes' / (name + '.properties')).read_text()
                                    (p / (name + '.properties')).write_text(
                                        rt._render_runtime_properties(name, raw, mf, c, '172.28.0.2')
                                    )

                            def fake_verify(rt_mod, p, mf, spec):
                                if injected['active'] and injected['stage'] == 'before_manifest_commit':
                                    raise RuntimeError('INJECTED_BEFORE_COMMIT_FAIL')
                                return 'AVAILABLE', {'potId': 'p1', 'version': 1, 'generationId': 'g1'}, None

                            with patch.object(rl, '_prepare_distribution_runtime', return_value=({}, creds)), \
                                    patch.object(rt, 'package', side_effect=fake_package), \
                                    patch.object(rt, 'compose', side_effect=fake_compose), \
                                    patch.object(rt, 'run', return_value=''), \
                                    patch.object(rt, 'sql', return_value='1'), \
                                    patch.object(rt, 'initialize'), \
                                    patch.object(rt, 'properties', side_effect=fake_properties), \
                                    patch.object(rt, 'discovery', return_value=True), \
                                    patch.object(rt, 'identities', return_value=({}, {'bruno': 't', 'guest': 't'})), \
                                    patch.object(rt, 'catalogue', return_value=m.get('catalogue')), \
                                    patch.object(rt, 'verify_acl'), \
                                    patch.object(rt, 'recover_fail_closed_runtimes'), \
                                    patch.object(rt, 'current', return_value={'DB-CURRENT': 'PASS'}), \
                                    patch.object(rt, 'recover_workers', return_value={'after': {}, 'generationId': 'g1', 'state': 'OK'}), \
                                    patch.object(rl, '_verify_canary_and_observability', side_effect=fake_verify), \
                                    patch.object(rt, 'verdict', return_value={'state': 'READY', 'exit': 0}), \
                                    patch.object(rl, 'wakeup_diagnostics', side_effect=lambda r, p, mf: {'configuredMode': mf['wakeupMode'], 'effectiveMode': mf['wakeupMode'].upper()}):
                                return rl.start_distribution(rt, path, m, ev, wakeup_mode=req_mode)

                        if fault == 'during_revert_to_previous_mode':
                            # First interrupt mid-transition to target_mode, then interrupt revert to start_mode!
                            injected['stage'] = 'during_runtime_recreation'
                            with self.assertRaises(RuntimeError):
                                run_start(target_mode)
                            injected['stage'] = 'during_revert_to_previous_mode'
                            with self.assertRaises(RuntimeError):
                                run_start(start_mode)
                            # prerequisites.instance MUST still accept the instance for recovery!
                            prerequisites.instance(path, 'start', rt.RUNTIMES, rt.ROLES, rt.LOCAL_USERS)
                            # Now clear fault and resume without --wakeup-mode -> finishes revert to start_mode!
                            injected['active'] = False
                            res = run_start(None)
                            expected_final = start_mode
                        else:
                            with self.assertRaises(RuntimeError):
                                run_start(target_mode)
                            # Manifest must NOT falsely commit target_mode yet!
                            persisted = rt.read(path / 'manifest.json')
                            self.assertEqual(start_mode, persisted['wakeupMode'])
                            self.assertIn('wakeupTransition', persisted)
                            self.assertEqual(target_mode, persisted['wakeupTransition']['to'])
                            # prerequisites.instance MUST allow non-destructive recovery!
                            prerequisites.instance(path, 'start', rt.RUNTIMES, rt.ROLES, rt.LOCAL_USERS)
                            # Clear fault and run plain start (resume) -> converges cleanly to target_mode!
                            injected['active'] = False
                            res = run_start(None)
                            expected_final = target_mode

                        self.assertEqual('READY', res['state'])
                        self.assertTrue(res['wakeup'].get('transitionRecovered'))
                        final_m = rt.read(path / 'manifest.json')
                        self.assertEqual(expected_final, final_m['wakeupMode'])
                        self.assertNotIn('wakeupTransition', final_m)
                        # Post-recovery prerequisites.instance must pass cleanly with NO pending transition
                        prerequisites.instance(path, 'start', rt.RUNTIMES, rt.ROLES, rt.LOCAL_USERS)
                        self.assertEqual(orig_secrets, (path / 'secrets.json').read_text())
                        self.assertEqual(orig_ports, final_m['ports'])
                        self.assertEqual(orig_owner, final_m['owner'])
                        compose = rt.read(path / 'compose.json')
                        self.assertEqual(expected_final == 'nats', 'nats' in compose['services'])
                        self.assertEqual(expected_final == 'nats', (path / 'nats.conf').exists())

    def test_wakeup_diagnostics_includes_internal_nats_varz_when_broker_available(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            m, _ = self._fixture(path, wakeup_mode='nats')
            prom_connected = 'pocoma_v3_wakeup_state{role="process"} 1.0\n'
            varz_json = (
                '{"connections": 4, "total_connections": 5, "subscriptions": 36,'
                ' "in_msgs": 120, "out_msgs": 120, "slow_consumers": 0,'
                ' "mem": 14680064, "cpu": 0.2, "uptime": "1m10s"}'
            )
            with patch.object(rt, 'compose', return_value='cid-nats'), \
                    patch.object(rt, 'docker_json', return_value=[{'State': {'Running': True, 'Status': 'running', 'Health': {'Status': 'healthy'}}}]), \
                    patch.object(rt, 'run', return_value=varz_json), \
                    patch.object(rt, 'scrape_prometheus_text', return_value=prom_connected):
                diag = rl.wakeup_diagnostics(rt, path, m)
            self.assertEqual('CONNECTED', diag['effectiveMode'])
            self.assertTrue(diag['brokerAvailable'])
            self.assertEqual(4, diag['broker']['varz']['connections'])
            self.assertEqual(5, diag['broker']['varz']['totalConnections'])
            self.assertEqual(36, diag['broker']['varz']['subscriptions'])
            self.assertEqual(0, diag['broker']['varz']['slowConsumers'])
            self.assertEqual(14680064, diag['broker']['varz']['memBytes'])

    def test_cli_wakeup_mode_validation_and_default_none(self):
        with patch.object(sys, 'argv', ['pocoma-local', 'start', '--instance', 'demo']):
            args = rt._parse_cli_args()
            self.assertIsNone(args.wakeup_mode)
        with patch.object(sys, 'argv', ['pocoma-local', 'start', '--instance', 'demo', '--wakeup-mode', 'noop']):
            args = rt._parse_cli_args()
            self.assertEqual('noop', args.wakeup_mode)
        with patch.object(sys, 'argv', ['pocoma-local', 'status', '--instance', 'demo', '--wakeup-mode', 'noop']):
            with self.assertRaises(SystemExit):
                rt._parse_cli_args()


if __name__ == '__main__':
    unittest.main()
