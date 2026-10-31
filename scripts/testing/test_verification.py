"""Selection and evidence failure tests; no Maven/Docker needed."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('verify.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def xml(methods=('a',), **attributes):
    attrs = dict(tests=len(methods), failures=0, errors=0, skipped=0, **attributes)
    return ('<testsuite name="C" ' + ' '.join(f'{k}="{val}"' for k,val in attrs.items()) + '>' +
            ''.join(f'<testcase classname="C" name="{m}" time="0.1"/>' for m in methods) + '</testsuite>').encode()


class SelectionTests(unittest.TestCase):
    def test_levels_are_cumulative_and_source_complete(self):
        plans = [v.plan(level, 'identity') for level in ['FAST', 'CHANGE', 'INTEGRATION']]
        self.assertLess(set(plans[0]['expected']), set(plans[1]['expected']))
        self.assertLess(set(plans[1]['expected']), set(plans[2]['expected']))
        self.assertIn('runtime-monolith', plans[2]['command'][4])
        self.assertNotIn('architecture-tests', plans[2]['command'][4])
        self.assertEqual(plans[0]['command'][4], 'engine-consume-registration,domain-pot')
        self.assertTrue(all(p['expected'] and all(p['expected'].values()) for p in plans))
        for plan in plans:
            self.assertFalse(any('V3WorkerResourcesPostgresTest' in c for c in plan['expected']))
            self.assertFalse(any('V3WorkerPostgresTest' in c for c in plan['expected']))
        self.assertTrue(any('IdentityPrivilegesPostgresTest' in c for c in plans[1]['expected']))
        self.assertTrue(any('IdentityPublicationPostgresTest' in c for c in plans[1]['expected']))

    def test_certification_is_unfiltered_native_reactor(self):
        plan = v.plan('CERTIFICATION', None)
        self.assertEqual(plan['command'], ['./mvnw', '--batch-mode', '--no-transfer-progress', 'test'])
        self.assertFalse(plan['filtered'])
        self.assertEqual(set(plan['quarantine']), {'i5_r05', 'i5_r12'})
        self.assertEqual(next(iter(plan['expected'].values())), ['i5_r05', 'i5_r12'])
        with self.assertRaises(ValueError):
            v.plan('CERTIFICATION', 'identity')

    def test_canonical_slices_remain_unfiltered(self):
        for level in ['CHANGE', 'INTEGRATION']:
            plan = v.plan(level, 'COMMAND,EVENT')
            self.assertEqual(plan['command'][3:], ['-pl', 'runtime-command-consumption-worker,runtime-event-consumption-worker', '-am', 'test'])
            self.assertFalse(plan['filtered'])

    def test_quarantine_is_method_precise_and_no_sibling_exclusion(self):
        for name in ['i5_r05', 'i5_r12']:
            plan = v.plan('QUARANTINE', name)
            self.assertEqual(sum(map(len, plan['expected'].values())), 1)
            self.assertEqual(next(iter(plan['expected'].values())), [name])
            self.assertTrue(any(arg.endswith('#' + name) for arg in plan['command']))
            omitted = next(iter(plan['not_selected_methods_in_selected_classes'].values()))
            self.assertIn('i5_r11', omitted)
            self.assertNotIn(name, omitted)
            self.assertEqual(plan['added_permanent_exclusions'], [])
        with self.assertRaises(ValueError):
            v.plan('QUARANTINE', 'other')

    def test_missing_source_and_ambiguous_source_rejected(self):
        for index in [{}, {'C': [('C', Path('/x')), ('C', Path('/y'))]}]:
            with self.assertRaises(ValueError):
                v.resolve(['C'], index)

    def test_unknown_or_implicit_scopes_rejected(self):
        for level, scope in [('CHANGE', None), ('FAST', 'COMMAND'), ('CHANGE', 'bad')]:
            with self.assertRaises(ValueError):
                v.plan(level, scope)


class EvidenceTests(unittest.TestCase):
    def test_complete(self):
        self.assertEqual(v.assess([('x', xml())], {'C': ['a']}, 0, True)['verdict'], 'PASS')

    def test_absent_method_or_reports_is_inconclusive(self):
        self.assertEqual(v.assess([('x', xml())], {'C':['a', 'b']}, 0, True)['verdict'], 'INCONCLUSIVE')
        self.assertEqual(v.assess([], {}, 0, False)['verdict'], 'INCONCLUSIVE')

    def test_unexpected_method_or_duplicate_is_inconclusive(self):
        self.assertEqual(v.assess([('x', xml(('a','b')))], {'C':['a']}, 0, True)['verdict'], 'INCONCLUSIVE')
        self.assertEqual(v.assess([('x', xml(('a','a')))], {'C':['a']}, 0, True)['verdict'], 'INCONCLUSIVE')

    def test_inconsistent_counters_is_inconclusive(self):
        raw = xml().replace(b'tests="1"', b'tests="2"')
        self.assertEqual(v.assess([('x', raw)], {'C':['a']}, 0, True)['verdict'], 'INCONCLUSIVE')

    def test_malformed_report_preserves_receipt_without_pass(self):
        for raw in [b'<broken', b'<testsuites/>', xml().replace(b'tests="1"', b'tests="bad"')]:
            result = v.assess([('x', raw)], {'C':['a']}, 0, True)
            self.assertEqual(result['verdict'], 'INCONCLUSIVE')
            self.assertTrue(any('Invalid report' in issue for issue in result['issues']))

    def test_skips_never_pass_and_failure_preserved(self):
        for tag, outcome in [('skipped', 'INCONCLUSIVE'), ('failure', 'FAIL'), ('error', 'FAIL')]:
            raw = f'<testsuite tests="1" failures="{int(tag == "failure")}" errors="{int(tag == "error")}" skipped="{int(tag == "skipped")}"><testcase classname="C" name="a"><{tag} message="real oracle"/></testcase></testsuite>'.encode()
            result = v.assess([('x',raw)], {'C':['a']}, 0, True)
            self.assertEqual(result['verdict'], outcome)
            self.assertEqual(result['executions'][0]['diagnostic'], 'real oracle')
        self.assertEqual(v.assess([('x',xml())], {'C':['a']}, 1, True)['verdict'], 'FAIL')

    def test_parameter_invocations_checked_separately(self):
        names = ['p(boolean)[1]', 'p(boolean)[2]']
        self.assertEqual(v.assess([('x',xml(names))], {'C':names}, 0, True)['verdict'], 'PASS')
        self.assertEqual(v.assess([('x',xml(names[:1]))], {'C':names}, 0, True)['verdict'], 'INCONCLUSIVE')


if __name__ == '__main__':
    unittest.main()
