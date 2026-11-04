"""READ selector and measurement controls, using only fake Maven processes/models."""
import importlib.util
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('verify.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class ExactCampaignTests(unittest.TestCase):
    def test_sequence_stops_after_failure_and_never_retries(self):
        config = dict(sequences={'read': ['a', 'b']}, campaigns={'a': {'level': 'FAST'}, 'b': {'level': 'CHANGE'}})
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'sequence'
            def fail(definition, directory):
                directory.mkdir()
                (directory / 'result.json').write_text(json.dumps(dict(
                    verdict='FAIL', operational_status='FAIL', counts=dict(tests=1, failures=1, errors=0, skipped=0))))
                return 1
            with patch.object(v, 'plan', return_value={}) as plan, patch.object(v, 'run', side_effect=fail) as run:
                self.assertEqual(v.run_sequence('read', out, config), 1)
                self.assertEqual(run.call_count, 1)
                self.assertEqual(plan.call_count, 1)
            receipt = json.loads((out / 'sequence.json').read_text())
            self.assertEqual(receipt['campaigns']['b']['status'], 'NOT RUN')
            self.assertEqual(receipt['automatic_retries'], 0)

    def probe(self, mutate=lambda c: None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'app/p/src/test/java/CTest.java'
            source.parent.mkdir(parents=True)
            source.write_text('''package p;
class CTest {
    @Test void exact() {}
    @Test void exactSibling() {}
    @ParameterizedTest @ValueSource(booleans = {true, false}) void variant(boolean b) {}
}''')
            config = dict(anchors={}, recipes={}, quarantine={}, campaigns={
                'READ-FAST-1': dict(level='FAST', modules=['p'], expected_invocations=3,
                    tests={'p.CTest': ['exact', 'variant']},
                    forecast=dict(seconds=60, basis='initial indicative envelope', uncertainty='HIGH'))})
            mutate(config['campaigns']['READ-FAST-1'])
            return v.plan('FAST', 'READ-FAST-1', config, root)

    def test_positive_methods_and_individual_parameter_invocations(self):
        result = self.probe()
        self.assertEqual(result['expected'], {'p.CTest': ['exact', 'variant(boolean)[1]', 'variant(boolean)[2]']})
        self.assertIn('-Dtest=p.CTest#exact+variant', result['command'])
        self.assertEqual(result['not_selected_methods_in_selected_classes'], {'p.CTest': ['exactSibling']})
        self.assertFalse(result['normative_gate'])
        self.assertEqual(result['forecast']['seconds'], 60)
        self.assertNotIn('architecture-tests', str(result['command']))

    def test_wrong_count_missing_method_wildcard_and_duplicate_refused(self):
        for mutate in [lambda c: c.update(expected_invocations=2),
                       lambda c: c.update(tests={'p.CTest': ['missing']}),
                       lambda c: c.update(tests={'p.CTest': ['exact*']}),
                       lambda c: c.update(tests={'p.CTest': ['exact', 'exact']}),
                       lambda c: c.update(level='CHANGE'),
                       lambda c: c.update(tests={})]:
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                self.probe(mutate)


class MeasurementTests(unittest.TestCase):
    def test_forecast_exceeded_is_informational_and_phase_completes(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(v.time, 'monotonic', side_effect=[0, 1, 2, 121, 122, 123]):
            measurements = v.Measurements(Path(tmp), dict(seconds=60, basis='indicative', uncertainty='HIGH'))
            with measurements.phase('maven'):
                pass
            receipt = measurements.save()
            self.assertEqual(receipt['phases']['maven'], dict(status='FINISHED', seconds=120))
            self.assertEqual(receipt['forecast_delta_seconds'], 63)
            self.assertTrue(receipt['forecast_exceeded'])
            self.assertFalse(receipt['forecast_is_blocking'])

    def test_unknown_forecast_and_interrupted_phase_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            measurements = v.Measurements(Path(tmp))
            with self.assertRaises(KeyboardInterrupt), measurements.phase('maven'):
                raise KeyboardInterrupt()
            receipt = json.loads((Path(tmp) / 'timings.json').read_text())
            self.assertEqual(receipt['phases']['maven']['status'], 'INTERRUPTED_OR_FAILED')
            self.assertIsNone(receipt['forecast_delta_seconds'])
            self.assertEqual(receipt['forecast']['basis'], 'UNKNOWN')

    def test_healthy_process_has_no_forecast_timeout(self):
        process = MagicMock(pid=123)
        process.__enter__.return_value = process
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory() as tmp, (Path(tmp) / 'maven.log').open('wb') as log, patch.object(v.subprocess, 'Popen', return_value=process), patch.object(v.os, 'killpg') as kill:
            self.assertEqual(v.execute_maven(['fake-maven'], log), 0)
            process.wait.assert_called_once_with()
            kill.assert_not_called()

    def test_interrupt_captures_processes_and_kills_forks(self):
        process = MagicMock(pid=123)
        process.__enter__.return_value = process
        process.wait.side_effect = [KeyboardInterrupt(), 143]
        with tempfile.TemporaryDirectory() as tmp, (Path(tmp) / 'maven.log').open('wb') as log, patch.object(v.subprocess, 'Popen', return_value=process), patch.object(v.subprocess, 'run', return_value=MagicMock(stdout='123 1 123 S 00:01 java')), patch.object(v.os, 'killpg') as kill:
            with self.assertRaises(KeyboardInterrupt):
                v.execute_maven(['fake-maven'], log)
            self.assertEqual(kill.call_args_list, [unittest.mock.call(123, signal.SIGTERM), unittest.mock.call(123, signal.SIGKILL)])
            receipt = json.loads((Path(tmp) / 'maven.process.json').read_text())
            self.assertEqual(receipt['process_group'], 123)
            self.assertIn('java', receipt['interrupted_processes'])


class PartialEvidenceTests(unittest.TestCase):
    def test_interruption_retains_attributed_counts_and_rejects_stale_xml(self):
        for invocation, count in [('current', 1), ('historical', 0)]:
            with self.subTest(invocation=invocation), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)
                directory = output / 'reports/p/surefire-reports'
                directory.mkdir(parents=True)
                raw = f'''<testsuite name="p.CTest" tests="1" failures="0" errors="0" skipped="0">
<properties><property name="pocoma.verification.id" value="{invocation}"/>
<property name="pocoma.verification.module" value="p"/>
<property name="pocoma.verification.producer" value="surefire"/></properties>
<testcase classname="p.CTest" name="exact" time="0.1"/></testsuite>'''
                (directory / 'TEST-C.xml').write_text(raw)
                (directory / 'TEST-in-progress.xml').write_text('<unfinished')
                definition = dict(expected={'p.CTest': ['exact', 'missing']}, filtered=True,
                                  expected_modules={'p.CTest': 'p'}, invocation_id='current')
                result = v.partial_reports(output, definition)
                self.assertEqual(result['counts']['tests'], count)
                self.assertEqual(result['verdict'], 'FAIL')
                self.assertTrue(result['partial'])
                self.assertEqual(len(result['artifacts']), 2)
                self.assertTrue(any('Invalid report' in i for i in result['issues']))
                self.assertEqual((directory / 'TEST-C.xml').read_text(), raw)


if __name__ == '__main__':
    unittest.main()

class L5I1SelectionTests(unittest.TestCase):
    def test_exact_whole_class_inventory_and_heap_safety_bound(self):
        plan = v.plan('INTEGRATION', 'l5-i1')
        self.assertEqual([len(methods) for methods in plan['expected'].values()], [24, 10, 6])
        self.assertIn('-Dtest=V3ReadHttpContractTest,V3ReadQuotaPostgresTest,V3HttpCompositionPostgresTest', plan['command'])
        self.assertIn('-DargLine=-Xmx512m', plan['command'])
        self.assertNotIn('architecture-tests', str(plan['command']))
        self.assertTrue(plan['filtered'])

    def test_whole_class_cannot_omit_method_or_change_heap_silently(self):
        config = json.loads(v.CONFIG.read_text())
        campaign = config['campaigns']['l5-i1']
        campaign['tests']['com.kartaguez.pocoma.V3ReadHttpContractTest'].pop()
        campaign['expected_invocations'] = 39
        with self.assertRaisesRegex(ValueError, 'retain every source method'):
            v.plan('INTEGRATION', 'l5-i1', config)
        config = json.loads(v.CONFIG.read_text())
        config['campaigns']['l5-i1']['jvm_max_heap_mib'] = 513
        with self.assertRaisesRegex(ValueError, 'JVM safety bound'):
            v.plan('INTEGRATION', 'l5-i1', config)
