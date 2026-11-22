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

            # 1. Switch NATS -> NOOP
            compose_calls = []
            with patch.object(rt, 'compose', side_effect=lambda *a, **kw: compose_calls.append(a[2:])):
                changed, prev, recreate = rl._reconfigure_wakeup_mode(rt, path, m, evidence, 'noop', None)
            self.assertTrue(changed)
            self.assertEqual('nats', prev)
            self.assertEqual('noop', m['wakeupMode'])
            self.assertEqual(tuple(rt.WAKEUP_RUNTIMES), recreate)
            self.assertIn(('rm', '-f', '-s', 'nats'), compose_calls)
            self.assertFalse((path / 'nats.conf').exists())
            self.assertEqual(original_owner, m['owner'])
            self.assertEqual(original_project, m['project'])
            self.assertEqual(original_ports, m['ports'])
            self.assertEqual('noop', rt.read(path / 'manifest.json')['wakeupMode'])
            # Re-render verifies 9 services without nats, postgres/keycloak unchanged
            rt.render(path, m, creds)
            compose_noop = rt.read(path / 'compose.json')
            self.assertEqual(9, len(compose_noop['services']))
            self.assertNotIn('nats', compose_noop['services'])

            # 2. Restart without --wakeup-mode (wakeup_mode=None) preserves durable NOOP mode
            changed_same, prev_same, recreate_same = rl._reconfigure_wakeup_mode(rt, path, m, evidence, None, None)
            self.assertFalse(changed_same)
            self.assertEqual('noop', prev_same)
            self.assertEqual('noop', m['wakeupMode'])
            self.assertIsNone(recreate_same)

            # 3. Switch NOOP -> NATS
            compose_calls.clear()
            with patch.object(rt, 'compose', side_effect=lambda *a, **kw: compose_calls.append(a[2:])):
                changed_back, prev_back, recreate_back = rl._reconfigure_wakeup_mode(rt, path, m, evidence, 'nats', None)
            self.assertTrue(changed_back)
            self.assertEqual('noop', prev_back)
            self.assertEqual('nats', m['wakeupMode'])
            self.assertEqual(tuple(rt.WAKEUP_RUNTIMES), recreate_back)
            self.assertEqual([], compose_calls)
            self.assertEqual('nats', rt.read(path / 'manifest.json')['wakeupMode'])
            rt.render(path, m, creds)
            compose_nats = rt.read(path / 'compose.json')
            self.assertEqual(10, len(compose_nats['services']))
            self.assertIn('nats', compose_nats['services'])
            self.assertIn(original_nats_token, (path / 'nats.conf').read_text())

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
