"""Provenance contract against the real CLI, real Maven and current JUnit tests.

Requires Java/Maven and Docker for the nominal 28-test FOUNDATION fixture.
POCOMA_PROVENANCE_EVIDENCE optionally retains all receipts in a new directory.
Faults are injected by a wrapper in a disposable clone, never by mocking the CLI
or its subprocess API. Maven actually executes the four H2 tests in partial runs.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]

WRAPPER = r'''#!/usr/bin/env python3
import json, os, shutil, subprocess, sys, time, xml.etree.ElementTree as ET
from pathlib import Path
app = Path(__file__).resolve().parent
config = json.loads((app.parent/'fault.json').read_text())
args = sys.argv[1:]
mode = config['mode']
testing = args[-1:] == ['test']
if testing and mode.startswith('replay'):
    args[-1:-1] = ['-Dtest=SpringTransactionRunnerTest', '-Dsurefire.failIfNoSpecifiedTests=false']
if testing and mode == 'forks':
    args[-1:-1] = ['-DforkCount=2', '-DreuseForks=false']
if testing and mode == 'hold':
    Path(config['marker']).touch()
    while not Path(config['release']).exists(): time.sleep(.02)
child = subprocess.Popen([str(app/'native/mvnw'), *args])
if testing and mode == 'interrupt':
    Path(config['marker']).touch()
rc = child.wait()
if not testing or rc: sys.exit(rc)
reports = Path(next(a.split('=', 1)[1] for a in args if a.startswith('-Dpocoma.verification.reports=')))
directory = reports/'pocoma-infra-tx-spring/surefire-reports'
if mode.startswith('replay'):
    dest = app/'infra-tx-spring/target/surefire-reports' if mode == 'replay-target' else directory
    dest.mkdir(parents=True, exist_ok=True)
    transfers = []
    for src in Path(config['historical']).glob('*.xml'):
        if 'SpringTransactionRunnerTest.xml' in src.name and mode != 'replay-previous': continue
        target = dest/src.name
        shutil.copyfile(src, target)  # intentionally renews mtime, preserving old bytes
        transfers.append(dict(source=str(src), destination=str(target), cases=len(ET.parse(target).getroot().findall('testcase')), source_mtime_ns=src.stat().st_mtime_ns, destination_mtime_ns=target.stat().st_mtime_ns))
    (reports.parent/'transfers.json').write_text(json.dumps(transfers, indent=2))
elif mode in ['missing', 'truncate', 'absent', 'duplicate', 'unexpected', 'wrong-module', 'wrong-id', 'missing-id', 'symlink']:
    p = next(directory.glob('TEST-*.xml'))
    tree = ET.parse(p); suite = tree.getroot()
    if mode == 'missing':
        suite.remove(suite.find('testcase')); suite.set('tests', str(int(suite.get('tests'))-1))
    elif mode == 'truncate':
        p.write_bytes(b'<testsuite'); sys.exit(0)
    elif mode == 'absent':
        p.unlink(); sys.exit(0)
    elif mode == 'symlink':
        p.unlink(); p.symlink_to(next(Path(config['historical']).glob('*.xml'))); sys.exit(0)
    elif mode == 'duplicate':
        cases = suite.findall('testcase'); cases[1].set('name', cases[0].get('name'))
    elif mode == 'unexpected':
        suite.find('testcase').set('name', 'unexecutedMethod')
    else:
        key = 'pocoma.verification.module' if mode == 'wrong-module' else 'pocoma.verification.id'
        prop = suite.find("properties/property[@name='%s']" % key)
        if mode == 'missing-id': suite.find('properties').remove(prop)
        else: prop.set('value', 'previous-invocation-or-module')
    tree.write(p)
sys.exit(1 if mode == 'maven-failure' else 0)
'''


class ProvenanceCLITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = None
        if os.environ.get('POCOMA_PROVENANCE_EVIDENCE'):
            cls.base = Path(os.environ['POCOMA_PROVENANCE_EVIDENCE']).resolve()
            cls.base.mkdir(parents=True, exist_ok=False)
        else:
            cls.temporary = tempfile.TemporaryDirectory(prefix='pocoma-provenance-cli-')
            cls.base = Path(cls.temporary.name)
        cls.checkout = cls.base/'checkout'
        subprocess.run(['git', 'clone', '--quiet', '--shared', str(ROOT), str(cls.checkout)], check=True)
        for name in ['app/pom.xml', 'scripts/testing/verify.py', 'scripts/testing/verification-campaigns.json']:
            shutil.copyfile(ROOT/name, cls.checkout/name)
        cls.historical = cls.base/'historical'; cls.historical.mkdir()
        archive = ROOT/'docs/testing/evidence/verification-counter-audit-6d40ed17/counter-audit-evidence.tar.gz'
        manifest = json.loads(archive.with_name('manifest.json').read_text())
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['published_files'][str(archive.relative_to(ROOT))]['sha256']
        with tarfile.open(archive) as bundle:
            for entry in bundle:
                if entry.name.startswith('counter-audit/baseline-xml/') and entry.isfile():
                    raw = bundle.extractfile(entry).read()
                    assert hashlib.sha256(raw).hexdigest() == manifest['archive_members'][entry.name]['sha256']
                    target = cls.historical/Path(entry.name).name
                    target.write_bytes(raw); os.utime(target, (1, 1))
        app = cls.checkout/'app'
        (app/'native').mkdir()
        (app/'mvnw').rename(app/'native/mvnw')
        shutil.copytree(app/'.mvn', app/'native/.mvn')
        (app/'mvnw').write_text(WRAPPER); (app/'mvnw').chmod(0o755)
        cls.records = []
        cls.nominal = cls.invoke('nominal-28', level='CHANGE', scope='FOUNDATION')
        if cls.nominal['result'].get('verdict') != 'PASS':
            raise AssertionError(f'Nominal FOUNDATION failed: {cls.nominal}')

    @classmethod
    def tearDownClass(cls):
        if cls.temporary:
            cls.temporary.cleanup()

    @classmethod
    def start(cls, name, mode='nominal', level='FAST', scope='foundation', environment=None, historical=None):
        marker = cls.base/(name+'.started'); release = cls.base/(name+'.release')
        (cls.checkout/'fault.json').write_text(json.dumps(dict(mode=mode, historical=str(historical or cls.historical), marker=str(marker), release=str(release))))
        output = cls.base/name
        command = [sys.executable, '-B', 'scripts/testing/verify.py', level, scope, '--output', str(output)]
        log = (cls.base/(name+'.console.log')).open('wb')
        process = subprocess.Popen(command, cwd=cls.checkout, env=dict(os.environ, **(environment or {})), stdout=log, stderr=subprocess.STDOUT)
        log.close()
        return dict(process=process, output=output, command=command, name=name, mode=mode, marker=marker)

    @classmethod
    def finish(cls, run):
        rc = run['process'].wait(timeout=120)
        path = run['output']/'result.json'
        result = json.loads(path.read_text()) if path.exists() else None
        record = dict(name=run['name'], mode=run['mode'], command=run['command'], exit_code=rc, result=result)
        cls.records.append(record)
        (cls.base/'cli-records.json').write_text(json.dumps(cls.records, indent=2)+'\n')
        return record

    @classmethod
    def invoke(cls, name, **kwargs):
        return cls.finish(cls.start(name, **kwargs))

    def nonpass(self, record, diagnostic=None):
        self.assertNotEqual(record['exit_code'], 0)
        self.assertNotEqual((record['result'] or {}).get('verdict'), 'PASS')
        if diagnostic:
            self.assertIn(diagnostic, json.dumps(record['result']))

    def test_T1_exact_counterexample_and_replay_into_isolated_producer(self):
        external = self.base/'external-maven-base/.mvn'; external.mkdir(parents=True)
        (external/'jvm.config').write_text('-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false\n')
        record = self.invoke('T1-exact-external-base', level='CHANGE', scope='FOUNDATION', environment={'MAVEN_BASEDIR':str(external.parent)})
        self.nonpass(record)
        self.assertIn('MAVEN_BASEDIR is set', (self.base/'T1-exact-external-base.console.log').read_text())
        for mode in ['replay-target', 'replay-isolated']:
            record = self.invoke('T1-'+mode, mode=mode, level='CHANGE', scope='FOUNDATION')
            self.nonpass(record, 'Expected one fresh execution')
            self.assertEqual(record['result']['counts']['tests'], 4)
            log = (self.base/('T1-'+mode)/'maven.log').read_text()
            self.assertIn('Tests run: 4, Failures: 0, Errors: 0, Skipped: 0', log)
            transfers = json.loads((self.base/('T1-'+mode)/'transfers.json').read_text())
            self.assertEqual(sum(t['cases'] for t in transfers), 24)
            self.assertTrue(all(t['destination_mtime_ns'] > t['source_mtime_ns'] for t in transfers))
            if mode == 'replay-isolated':
                self.assertIn('Provenance mismatch', json.dumps(record['result']['issues']))

    def test_T2_nominal_foundation_28(self):
        self.assertEqual(self.nominal['result']['counts'], dict(tests=28, failures=0, errors=0, skipped=0))
        self.assertEqual(self.nominal['result']['expected_invocations'], 28)

    def test_T3_historical_reports_do_not_contribute(self):
        dest = self.checkout/'app/infra-tx-spring/target/surefire-reports'; dest.mkdir(parents=True, exist_ok=True)
        for p in self.historical.glob('*.xml'): shutil.copyfile(p, dest/p.name)
        record = self.invoke('T3-historical')
        self.assertEqual(record['result']['verdict'], 'PASS')
        self.assertEqual(record['result']['counts']['tests'], 4)
        self.assertEqual(len(record['result']['previous_reports_not_counted']), 6)
        self.assertTrue(all(k.startswith('reports/') for k in record['result']['artifacts'] if k.endswith('.xml') and 'TEST-' in k))

    def test_T4_incomplete(self):
        record = self.invoke('T4-missing', mode='missing')
        self.nonpass(record, 'Expected one fresh execution')
        self.assertEqual(record['result']['counts']['tests'], 3)

    def test_T5_interrupted_and_failed_maven(self):
        run = self.start('T5-interrupted', mode='interrupt')
        self.await_marker(run)
        run['process'].send_signal(signal.SIGTERM)
        self.nonpass(self.finish(run), 'Interrupted by signal')
        self.assertFalse((self.checkout/'app/.verification.lock').exists())
        record = self.invoke('T5-maven-failure', mode='maven-failure')
        self.nonpass(record, 'Maven execution failed or interrupted')
        self.assertEqual(record['result']['counts']['tests'], 4)

    def test_T6_successive_different_scopes(self):
        first = self.invoke('T6-first-fast')
        previous = Path(first['result']['reports_root'])/'pocoma-infra-tx-spring/surefire-reports'
        second = self.invoke('T6-second-canonical', mode='replay-previous', historical=previous, level='CHANGE', scope='FOUNDATION')
        self.assertEqual(first['result']['verdict'], 'PASS')
        self.nonpass(second, 'Provenance mismatch')
        self.assertEqual(second['result']['counts']['tests'], 0)
        self.assertNotEqual(first['result']['invocation_id'], second['result']['invocation_id'])
        self.assertNotEqual(first['result']['reports_root'], second['result']['reports_root'])

    def await_marker(self, run):
        deadline = time.monotonic()+30
        while not run['marker'].exists() and run['process'].poll() is None and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue(run['marker'].exists(), (self.base/(run['name']+'.console.log')).read_text())

    def test_T7_concurrent_invocation_refused(self):
        first = self.start('T7-owner', mode='hold')
        try:
            self.await_marker(first)
            second = self.invoke('T7-contender')
            self.nonpass(second)
            self.assertIn('Another verification owns app targets', (self.base/'T7-contender.console.log').read_text())
            self.assertFalse((self.base/'T7-contender').exists())
        finally:
            first['process'].send_signal(signal.SIGTERM)
            self.nonpass(self.finish(first), 'Interrupted by signal')

    def test_T8_implicit_jvm_filter_refused(self):
        record = self.invoke('T8-java-options', environment={'_JAVA_OPTIONS':'-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false'})
        self.nonpass(record)
        self.assertIn('_JAVA_OPTIONS is set', (self.base/'T8-java-options.console.log').read_text())
        self.assertFalse((self.base/'T8-java-options').exists())

    def test_T9_legitimate_filters_and_multiple_forks(self):
        record = self.invoke('T9-filtered-forks', mode='forks', level='CHANGE')
        self.assertEqual(record['result']['verdict'], 'PASS')
        self.assertEqual(record['result']['counts']['tests'], 28)
        commands = {ET.parse(p).getroot().find("properties/property[@name='sun.java.command']").get('value')
                    for p in Path(record['result']['reports_root']).rglob('TEST-*.xml')}
        self.assertEqual(len(commands), 6)  # reuseForks=false actually used six fork JVMs
        pom = self.checkout/'app/pom.xml'; original = pom.read_bytes()
        try:
            pom.write_bytes(original.replace(b'${pocoma.verification.reports}/${project.artifactId}/surefire-reports',
                                             b'${project.build.directory}/surefire-reports'))
            self.nonpass(self.invoke('T9-producer-override'), 'producer provenance configuration missing or overridden')
            self.assertFalse((self.base/'T9-producer-override/maven.log').exists())
        finally:
            pom.write_bytes(original)

    def test_T10_absent_malformed_incoherent_and_unattributed_xml(self):
        for mode in ['absent', 'truncate', 'duplicate', 'unexpected', 'wrong-module', 'wrong-id', 'missing-id', 'symlink']:
            with self.subTest(mode=mode):
                self.nonpass(self.invoke('T10-'+mode, mode=mode))


if __name__ == '__main__':
    unittest.main()
