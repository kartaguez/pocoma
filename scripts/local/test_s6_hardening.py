"""Targeted unit tests for S6 operational hardening (S6.1, S6.3, S6.4)."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recovery as rec
import runtime as rt
from public_api import PublicAPI


def make_generation(suffix='a'):
    return {
        name: {
            'containerId': f'cid-{name}-{suffix}',
            'createdAt': '2026-11-15T20:00:00Z',
            'startedAt': f'2026-11-15T20:00:0{idx}Z',
            'running': True,
        }
        for idx, name in enumerate(rt.RUNTIMES, 1)
    }


def make_manifest():
    return {
        'ports': {
            'write': 55574,
            'read': 55575,
            'http-write': 55580,
            'http-read': 55581,
            'command-worker': 55582,
            'results-worker': 55583,
            'business-read-worker': 55584,
            'metrics': 55576,
            'grafana': 55577,
        },
        'issuer': 'http://127.0.0.1:55578/realms/pocoma',
    }


class S6HardeningTests(unittest.TestCase):
    def test_s61_draining_worker_fails_current_even_if_readiness_up(self):
        m = make_manifest()

        def fake_get_json(url):
            if '/actuator/health/readiness' in url:
                return {'status': 'UP'}
            if f':{m["ports"]["command-worker"]}/actuator/health' in url:
                return {'status': 'DOWN', 'details': {'workerState': 'DRAINING'}}
            if '/actuator/health' in url:
                return {'status': 'UP'}
            return {'issuer': m['issuer'], 'code_challenge_methods_supported': ['S256']}

        with tempfile.TemporaryDirectory() as folder, \
                patch.object(rt, 'get_json', side_effect=fake_get_json), \
                patch.object(rt, 'scrape_prometheus_text', return_value='pocoma_v3_admission_capacity_available{endpoint="admission"} 4.0\n'), \
                patch.object(rt, 'sql', side_effect=[rt.fingerprint(), rt.fingerprint(),
                                                      'pocoma_admission\npocoma_read\npocoma_read_http\npocoma_read_status\npocoma_write\npocoma_write_source_read',
                                                      '0', 'NORMAL']), \
                patch.object(rt, 'catalogue', return_value=None):
            checks = rt.current(Path(folder), m)
        self.assertEqual('FAIL', checks['JVM-command-worker'])
        self.assertEqual('PASS', checks['JVM-results-worker'])
        self.assertEqual('PASS', checks['JVM-http-write'])

    def test_s61_exhausted_http_write_fails_current_and_triggers_single_controlled_restart(self):
        m = make_manifest()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            evidence = path / 'runs/r1'
            evidence.mkdir(parents=True)
            (path / 'http-write.properties').write_text('pocoma.v3.http.admission.capacity=4\n')

            def fake_get_json(url):
                if '/actuator/health/readiness' in url:
                    return {'status': 'UP'}
                if f':{m["ports"]["results-worker"]}/actuator/health' in url:
                    return {'status': 'DOWN'}
                return {'status': 'UP'}

            def fake_prom(port):
                if port == m['ports']['http-write']:
                    return 'pocoma_v3_admission_capacity_available{endpoint="admission"} 0.0\n'
                return 'pocoma_v3_worker_state{state="running"} 1.0\n'

            with patch.object(rt, 'get_json', side_effect=fake_get_json), \
                    patch.object(rt, 'scrape_prometheus_text', side_effect=fake_prom), \
                    patch.object(rt, 'compose') as compose_mock:
                restarted = rt.recover_fail_closed_runtimes(path, m, evidence)

            self.assertEqual(['http-write', 'results-worker'], restarted)
            compose_mock.assert_called_once_with(
                path, m, 'restart', 'http-write', 'results-worker',
                log=evidence / 'runtimes-recovery-restart.log')
            recovery_record = rt.read(evidence / 'runtime-recovery.json')
            self.assertEqual(['http-write', 'results-worker'], recovery_record['restarted'])

    def test_s61_healthy_runtimes_never_restarted(self):
        m = make_manifest()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            evidence = path / 'runs/r1'
            evidence.mkdir(parents=True)
            (path / 'http-write.properties').write_text('pocoma.v3.http.admission.capacity=4\n')

            with patch.object(rt, 'get_json', return_value={'status': 'UP'}), \
                    patch.object(rt, 'scrape_prometheus_text',
                                 return_value='pocoma_v3_admission_capacity_available{endpoint="admission"} 4.0\npocoma_v3_worker_state{state="draining"} 0.0\n'), \
                    patch.object(rt, 'compose') as compose_mock:
                restarted = rt.recover_fail_closed_runtimes(path, m, evidence)

            self.assertEqual([], restarted)
            compose_mock.assert_not_called()

    def _run_status(self, *, checks, gen_after, proof, tools='AVAILABLE'):
        m = make_manifest()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            if proof is not None:
                rt.save(path / 'canary.json', proof)
            with patch.object(rt, 'ownership'), \
                    patch.object(rt, 'current', return_value=checks), \
                    patch.object(rt, 'generations', return_value=gen_after), \
                    patch.object(rt, 'identities', return_value=({'IDP-AUTH': 'PASS'}, dict(bruno='secret'))), \
                    patch.object(PublicAPI, 'call', side_effect=[
                        (200, dict(active=True, bindingToken='secret')),
                        (200, dict(potVersion=3, label='canary-run1')),
                    ]), \
                    patch.object(rt.grafana, 'checks', return_value=({'GRAFANA-UP': 'PASS'}, {})), \
                    patch.object(rt, 'observability', return_value=tools):
                return rt.status(path, m)

    def test_s63_operational_with_fresh_proof_is_ready_and_qualified(self):
        gen = make_generation('a')
        checks = {**{'JVM-' + k: 'PASS' for k in rt.RUNTIMES}, 'DB-CURRENT': 'PASS', 'IDP-DISCOVERY': 'PASS'}
        proof = dict(potId='pot', version=3, runId='run1', completedAt=rt.time.time() - 5, generationId=rec.identity(gen))
        result = self._run_status(checks=checks, gen_after=gen, proof=proof)
        self.assertEqual('READY', result['state'])
        self.assertEqual(0, result['exit'])
        self.assertEqual('OPERATIONAL', result['operationalState'])
        self.assertEqual('QUALIFIED', result['qualificationState'])
        self.assertTrue(result['operational']['workersOperational'])
        self.assertEqual('FRESH', result['qualification']['proofFreshness'])

    def test_s63_operational_with_stale_proof_distinguishes_stale_from_failure(self):
        gen = make_generation('a')
        checks = {**{'JVM-' + k: 'PASS' for k in rt.RUNTIMES}, 'DB-CURRENT': 'PASS', 'IDP-DISCOVERY': 'PASS'}
        proof = dict(potId='pot', version=3, runId='run1', completedAt=rt.time.time() - (rt.MAX_AGE + 45), generationId=rec.identity(gen))
        result = self._run_status(checks=checks, gen_after=gen, proof=proof)
        self.assertEqual('NOT_READY', result['state'])
        self.assertEqual(1, result['exit'])
        self.assertEqual('AVAILABLE', result['technical'])
        self.assertEqual('OPERATIONAL', result['operationalState'])
        self.assertTrue(result['operational']['servicesAvailable'])
        self.assertEqual('STALE', result['qualificationState'])
        self.assertTrue(result['qualification']['generationQualified'])
        self.assertTrue(result['qualification']['proofValid'])
        self.assertEqual('STALE_OR_MISSING', result['qualification']['proofFreshness'])

    def test_s63_non_operational_draining_worker_marks_operational_unavailable(self):
        gen = make_generation('a')
        checks = {**{'JVM-' + k: 'PASS' for k in rt.RUNTIMES}, 'JVM-command-worker': 'FAIL', 'DB-CURRENT': 'PASS', 'IDP-DISCOVERY': 'PASS'}
        proof = dict(potId='pot', version=3, runId='run1', completedAt=rt.time.time() - 5, generationId=rec.identity(gen))
        result = self._run_status(checks=checks, gen_after=gen, proof=proof)
        self.assertEqual('NOT_READY', result['state'])
        self.assertEqual(1, result['exit'])
        self.assertEqual('UNAVAILABLE', result['operationalState'])
        self.assertFalse(result['operational']['workersOperational'])

    def test_s63_generation_changed_and_partial_restart_report_generation_mismatch(self):
        gen_before = make_generation('a')
        gen_partial = make_generation('a')
        gen_partial['command-worker']['startedAt'] = '2026-11-15T20:10:00Z'
        checks = {**{'JVM-' + k: 'PASS' for k in rt.RUNTIMES}, 'DB-CURRENT': 'PASS', 'IDP-DISCOVERY': 'PASS'}
        proof = dict(potId='pot', version=3, runId='run1', completedAt=rt.time.time() - 5, generationId=rec.identity(gen_before))
        result = self._run_status(checks=checks, gen_after=gen_partial, proof=proof)
        self.assertEqual('NOT_READY', result['state'])
        self.assertEqual('OPERATIONAL', result['operationalState'])
        self.assertEqual('GENERATION_MISMATCH', result['qualificationState'])
        self.assertFalse(result['qualification']['generationQualified'])

    def test_s63_start_restores_qualified_state(self):
        gen = make_generation('b')
        canary_proof = dict(potId='pot', version=4, runId='run2', completedAt=rt.time.time(), generationId=rec.identity(gen), generation=gen)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            evidence = path / 'runs/r2'
            evidence.mkdir(parents=True)
            with patch.object(rt, 'run', return_value='f9c0ef64'), \
                    patch.object(rt, 'start_distribution', return_value=dict(state='READY', exit=0, qualificationTools='AVAILABLE', canaryProof=canary_proof)):
                result = rt.start(path, make_manifest(), evidence)
        self.assertEqual('READY', result['state'])
        self.assertEqual('OPERATIONAL', result['operationalState'])
        self.assertEqual('QUALIFIED', result['qualificationState'])

    def test_s64_dry_run_rejected_on_unsupported_commands(self):
        for cmd in ('start', 'upgrade-grafana', 'status', 'stop', 'bruno', 'login', 'gui'):
            with patch.object(sys, 'argv', ['runtime.py', cmd, '--dry-run']), \
                    patch.object(rt, 'prepare') as prepare_mock:
                with self.assertRaises(SystemExit) as ctx:
                    rt.main()
                self.assertEqual(2, ctx.exception.code)
                prepare_mock.assert_not_called()


if __name__ == '__main__':
    unittest.main()
