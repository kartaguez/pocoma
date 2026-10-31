"""Selection and evidence failure tests; no Maven/Docker needed."""
import importlib.util
import io
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




class AuditRegressionTests(unittest.TestCase):
    def test_no_inventory_cannot_pass_even_with_successful_tests(self):
        self.assertEqual(v.assess([('x', xml())], {}, 0, False)['verdict'], 'INCONCLUSIVE')

    def test_equal_total_different_identities_never_passes(self):
        for filtered in [False, True]:
            result = v.assess([('x', xml(('a', 'replacement')))], {'C': ['a', 'b']}, 0, filtered)
            self.assertEqual(result['counts']['tests'], 2)
            self.assertEqual(result['verdict'], 'INCONCLUSIVE')
            self.assertTrue(any('Unexpected execution' in issue for issue in result['issues']))

    def test_audit_four_test_counterexample_is_not_complete(self):
        import tarfile
        from unittest.mock import patch
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            # Actual Maven-selected FOUNDATION effective model, assembled without Maven.
            model = '<projects xmlns:m="http://maven.apache.org/POM/4.0.0">'
            for module in ['port-transaction', 'infra-tx-spring']:
                base = v.ROOT / 'app' / module
                model += f'<m:project><m:artifactId>pocoma-{module}</m:artifactId><m:build><m:sourceDirectory>{base}/src/main/java</m:sourceDirectory><m:testSourceDirectory>{base}/src/test/java</m:testSourceDirectory></m:build></m:project>'
            (output / 'effective-pom.xml').write_text(model + '</projects>')
            definition = v.plan('CHANGE', 'FOUNDATION')
            with patch.object(v.subprocess, 'run') as run:
                run.return_value.returncode = 0
                _, issues = v.effective_inventory(definition, output)
            self.assertEqual(issues, [])
            self.assertEqual(sum(map(len, definition['expected'].values())), 28)
        archive = v.ROOT / 'docs/testing/evidence/verification-independent-audit-d0927d94/audit-evidence.tar.gz'
        with tarfile.open(archive) as bundle:
            raw = bundle.extractfile('audit/foundation-hidden-filter-h2/raw.tar.gz').read()
        with tarfile.open(fileobj=io.BytesIO(raw)) as bundle:
            reports = [(m.name, bundle.extractfile(m).read()) for m in bundle
                       if '/surefire-reports/TEST-' in m.name and m.name.endswith('.xml')]
        result = v.assess(reports, definition['expected'], 0, False)
        self.assertEqual(result['counts']['tests'], 4)
        self.assertEqual(result['verdict'], 'INCONCLUSIVE')
        self.assertEqual(sum('Expected one fresh execution' in s for s in result['issues']), 24)

    def test_foundation_second_recipe_uses_shared_expansion(self):
        fast, change, integration = [v.plan(level, 'foundation') for level in ['FAST', 'CHANGE', 'INTEGRATION']]
        self.assertEqual([sum(map(len, p['expected'].values())) for p in [fast, change, integration]], [4, 28, 28])
        self.assertLess(set(fast['expected']), set(change['expected']))
        self.assertEqual(change['expected'], integration['expected'])
        self.assertTrue(all(p['filtered'] and not p['normative_gate'] for p in [fast, change, integration]))
        self.assertEqual(change['command'][4], 'infra-tx-spring')

    def test_jvm_injection_refused_before_any_subprocess(self):
        import os
        from unittest.mock import patch
        for key in ['_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS']:
            with patch.dict(os.environ, {key: '-Dtest=SpringTransactionRunnerTest'}, clear=True), patch.object(v.subprocess, 'run') as proc:
                with self.assertRaisesRegex(ValueError, key):
                    v.run(v.plan('CHANGE', 'FOUNDATION'), Path('/must-not-be-created'))
                proc.assert_not_called()

    def test_implicit_maven_files_refused(self):
        import os
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp, patch.object(v, 'ROOT', Path(tmp)), patch.dict(os.environ, {}, clear=True):
            directory = Path(tmp) / 'app/.mvn'; directory.mkdir(parents=True)
            for name in ['maven.config', 'jvm.config', 'extensions.xml']:
                p = directory / name; p.write_text('-Dtest=C')
                with self.assertRaisesRegex(ValueError, 'implicit Maven configuration'):
                    v.reject_implicit_options()
                p.unlink()

    def test_unknown_provider_and_removed_method_fail_closed(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'C.java'
            for text in ['@TestFactory void dynamic() {}', '@ParameterizedTest @MethodSource("cases") void p(int x) {}', '@CustomTest void a() {}', 'void disappeared() {}', '@Test @Disabled void a() {}']:
                p.write_text(text)
                with self.assertRaises(ValueError):
                    v.required_methods('C', p)

    def test_malformed_case_identity_and_time_cannot_pass(self):
        for raw in [xml().replace(b'time="0.1"', b'time="nan"'), xml().replace(b'time="0.1"', b'time="bad"'), xml().replace(b'classname="C"', b'classname="Other"')]:
            self.assertEqual(v.assess([('x', raw)], {'C':['a']}, 0, False)['verdict'], 'INCONCLUSIVE')

    def test_preserved_historical_logs_recount_singular_sources(self):
        import tarfile
        archive = v.ROOT / 'docs/testing/evidence/verification-optimization/followup-builds.tar.gz'
        with tarfile.open(archive) as bundle:
            for member, count, events in [('followup/full-clean-compile.log', 757, 59), ('followup/identity-compile-after-change.log', 452, 22)]:
                metric = v.compilation_metrics(bundle.extractfile(member).read().decode())
                self.assertEqual(metric['announced_recompiled_source_inputs'], count)
                self.assertEqual(len(metric['compiler_events']), events)

    def test_compiler_singular_plural_main_test_and_generated_inventory(self):
        import tempfile
        from unittest.mock import patch
        log = '\n'.join(['[INFO] --- compiler:3.14.1:compile (default-compile) @ p ---',
            '[INFO] Compiling 1 source file with javac to target/classes',
            '[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ p ---',
            '[INFO] Compiling 2 source files with javac to target/test-classes'])
        with tempfile.TemporaryDirectory() as tmp, patch.object(v, 'ROOT', Path(tmp)):
            base = Path(tmp) / 'app/p'
            for name in ['src/main/java/A.java', 'src/test/java/B.java', 'target/generated-sources/annotations/G.java']:
                p = base / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text('class A {}')
            result = v.compilation_metrics(log, [dict(module='p', main_root=str(base/'src/main/java'), test_root=str(base/'src/test/java'))])
        self.assertEqual(result['announced_recompiled_source_inputs'], 3)
        self.assertEqual([e['goal'] for e in result['compiler_events']], ['compile', 'testCompile'])
        self.assertEqual(result['source_inventory'][0]['present_generated_main_java'], 1)
        self.assertEqual(result['source_inventory'][0]['included_main_root_java'], 1)
        self.assertIsNone(result['reused_source_count'])

    def test_sql_relevant_change_invalidates_but_unrelated_file_does_not(self):
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp, patch.object(v, 'ROOT', Path(tmp)):
            base = Path(tmp)
            source = base / 'app/p/src/test/java/C.java'; source.parent.mkdir(parents=True)
            source.write_text('package p; class C { void script() { Path.of("../../scripts/v3/" + name); script("used.sql"); } }')
            sql = base / 'scripts/v3/used.sql'; sql.parent.mkdir(parents=True); sql.write_text('select 1;')
            unrelated = sql.with_name('unrelated.sql'); unrelated.write_text('select 2;')
            def fingerprint():
                return {name:v.digest(base/name) for name in v.external_sql_inputs({'p.C':['a']})}
            initial = fingerprint()
            unrelated.write_text('select 3;'); self.assertEqual(initial, fingerprint())
            sql.write_text('select 4;'); self.assertNotEqual(initial, fingerprint())
            self.assertEqual(set(initial), {'scripts/v3/used.sql'})
            sql.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing external SQL'):
                fingerprint()

    def test_freshness_pipeline_old_absent_partial_and_current_reports(self):
        import os
        import tempfile
        import types
        from unittest.mock import patch
        for scenario in ['old-only', 'absent', 'partial', 'stale-copied', 'complete', 'sql-changed', 'discovery-uncertain']:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as tmp, patch.object(v, 'ROOT', Path(tmp)), patch.dict(os.environ, {}, clear=True):
                base = Path(tmp); (base/'app').mkdir()
                report = base/'app/p/target/surefire-reports/TEST-C.xml'; report.parent.mkdir(parents=True)
                report.write_bytes(xml(('a','b')))
                sql = base/'used.sql'; sql.write_text('select 1')
                definition = dict(command=['./mvnw','test'], expected={'C':['a','b']}, filtered=False, level='CHANGE', scope='FOUNDATION', quarantine={})
                output = base/'evidence'
                def discover(d, o):
                    (o/'effective-pom.xml').write_text('<projects/>'); (o/'discovery.log').write_text('model')
                    return [], ['uncertain discovery'] if scenario == 'discovery-uncertain' else []
                def execute(*args, **kwargs):
                    if scenario not in ['old-only', 'absent']:
                        report.write_bytes(xml(('a',) if scenario == 'partial' else ('a','b')))
                        if scenario == 'stale-copied':
                            os.utime(report, (1,1))
                    if scenario == 'sql-changed':
                        sql.write_text('select 2')
                    return types.SimpleNamespace(returncode=0)
                with patch.object(v, 'effective_inventory', side_effect=discover), patch.object(v, 'external_sql_inputs', return_value=['used.sql']), patch.object(v, 'capture', return_value={}), patch.object(v, 'app_inputs', return_value={}), patch.object(v.subprocess, 'run', side_effect=execute):
                    rc = v.run(definition, output)
                result = json.loads((output/'result.json').read_text())
                self.assertEqual(result['verdict'], 'PASS' if scenario == 'complete' else 'INCONCLUSIVE')
                self.assertEqual(rc, 0 if scenario == 'complete' else 1)
                self.assertTrue((output/'previous-reports/app/p/target/surefire-reports/TEST-C.xml').is_file())
                self.assertFalse((base/'app/.verification.lock').exists())


class DiscoveryTests(unittest.TestCase):
    def probe(self, mutation=None):
        import tempfile
        import os
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp, patch.object(v, 'ROOT', Path(tmp)):
            base = Path(tmp) / 'app/p'
            path = base / 'src/test/java/CTest.java'; path.parent.mkdir(parents=True)
            path.write_text('package p; class CTest { @Test void a() {} }')
            (base/'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>p</artifactId></project>')
            output = Path(tmp) / 'evidence'; output.mkdir()
            extra = mutation(base, path) if mutation else ''
            model = f'<projects xmlns:m="http://maven.apache.org/POM/4.0.0"><m:project><m:artifactId>p</m:artifactId><m:build><m:sourceDirectory>{base}/src/main/java</m:sourceDirectory><m:testSourceDirectory>{base}/src/test/java</m:testSourceDirectory>{extra}</m:build></m:project></projects>'
            (output/'effective-pom.xml').write_text(model)
            d = dict(command=['./mvnw', 'test'], expected={}, filtered=False)
            with patch.object(v.subprocess, 'run') as proc:
                proc.return_value.returncode = 0
                _, issues = v.effective_inventory(d, output)
            return d, issues

    def test_nominal_source_identity_from_effective_model(self):
        definition, issues = self.probe()
        self.assertEqual(definition['expected'], {'p.CTest':['a']})
        self.assertEqual(issues, [])

    def test_effective_surefire_filter_never_certifies_even_equal_population(self):
        def mutation(base, path):
            return '<m:plugins><m:plugin><m:artifactId>maven-surefire-plugin</m:artifactId><m:configuration><m:test>CTest</m:test></m:configuration></m:plugin></m:plugins>'
        _, issues = self.probe(mutation)
        self.assertTrue(any('unreviewed effective Surefire' in i for i in issues))

    def test_disappeared_test_annotation_is_not_silently_a_helper(self):
        def mutation(base, path):
            path.write_text('package p; class CTest { void a() {} }')
            return ''
        definition, issues = self.probe(mutation)
        self.assertEqual(definition['expected'], {})
        self.assertTrue(any('cannot establish JUnit discovery' in i for i in issues))

    def test_unknown_composed_annotation_alongside_regular_test_is_not_ignored(self):
        def mutation(base, path):
            path.write_text('package p; class CTest { @Test void a() {} @MyTest void hidden() {} }')
            return ''
        definition, issues = self.probe(mutation)
        self.assertEqual(definition['expected'], {})
        self.assertTrue(any('unreviewed annotations' in i for i in issues))

    def test_helpers_in_same_file_and_inherited_tests_are_distinguished(self):
        for annotation, expected_issue in [('', False), ('@Test', True)]:
            def mutation(base, path):
                path.write_text(f'package p; class CTest extends Helper {{ @Test void a() {{}} }} abstract class Helper {{ {annotation} void inherited() {{}} }}')
                return ''
            _, issues = self.probe(mutation)
            self.assertEqual(bool(issues), expected_issue)


if __name__ == '__main__':
    unittest.main()
