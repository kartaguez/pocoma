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


if __name__ == '__main__':
    unittest.main()
