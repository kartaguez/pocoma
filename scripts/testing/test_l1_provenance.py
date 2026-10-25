"""Adversarial tests for the L1 tested/published-input correspondence (real temporary Git trees)."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('verify_l1',Path(__file__).with_name('verify-v3-l1.py'))
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)

class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.original=verify.ROOT;verify.ROOT=Path(self.tmp.name)
        self.git('init','-q');self.git('config','user.email','proof@example.invalid');self.git('config','user.name','Proof fixture')
        self.write('app/src/Code.java','class Code {}\n');self.write('app/src/test.sql','select 1;\n');self.write('docs/v3/L1_E_Execution_Report.md','pending\n')
        self.commit();self.tested=self.git('rev-parse','HEAD').strip();self.inputs=verify.inputs(self.tested)
    def tearDown(self):
        verify.ROOT=self.original;self.tmp.cleanup()
    def git(self,*args):return subprocess.check_output(['git',*args],cwd=verify.ROOT,text=True)
    def write(self,name,content):
        path=verify.ROOT/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    def commit(self):self.git('add','.');self.git('commit','-qm','fixture')
    def test_clean_commit_and_only_documentary_followup_are_accepted(self):
        self.assertEqual('PASS',verify.compare(self.tested,self.inputs)['verdict'])
        self.write('docs/v3/L1_E_Execution_Report.md','measured result\n');self.commit()
        verify.publication(self.tested,self.git('rev-parse','HEAD').strip(),False)
    def test_dirty_source_untracked_input_and_deleted_sql_are_rejected(self):
        for action in ('dirty','untracked','deleted','mode'):
            with self.subTest(action=action):
                self.git('reset','--hard',self.tested);self.git('clean','-fdq')
                if action=='dirty':self.write('app/src/Code.java','class Different {}\n')
                if action=='untracked':self.write('app/src/Extra.java','class Extra {}\n')
                if action=='mode':(verify.ROOT/'app/src/Code.java').chmod(0o755)
                if action=='deleted':(verify.ROOT/'app/src/test.sql').unlink()
                with self.assertRaisesRegex(RuntimeError,'Input mismatch|Tracked input changes'):verify.compare(self.tested,self.inputs)
    def test_executable_followup_cannot_be_published_under_documentary_label(self):
        self.write('app/src/test.sql','select 2;\n');self.commit()
        with self.assertRaisesRegex(RuntimeError,'Untested publication'):verify.publication(self.tested,self.git('rev-parse','HEAD').strip(),False)
    def test_executable_file_cannot_hide_in_evidence_exclusion(self):
        self.write('docs/testing/evidence/L1-DE/hidden.py','print("executable")\n');self.commit()
        with self.assertRaisesRegex(RuntimeError,'Untested publication'):verify.publication(self.tested,self.git('rev-parse','HEAD').strip(),False)
    def test_external_binary_change_and_new_dependency_are_rejected(self):
        for after in ({'jdk':'changed','jar':'same'},{'jdk':'same','jar':'same','new':'new'}):
            with self.assertRaisesRegex(RuntimeError,'External build inputs changed'):verify.verify_external({'jdk':'same','jar':'same'},after,{})
        with self.assertRaisesRegex(RuntimeError,'Classpath dependency'):verify.verify_external({'jar':'before'},{'jar':'before'},{'jar':'different'})
    def test_remote_head_mismatch_is_rejected(self):
        original=verify.git
        try:
            verify.git=lambda *args:b'badhead\trefs/heads/feat/v3-architecture\n' if args[0]=='ls-remote' else original(*args)
            with self.assertRaisesRegex(RuntimeError,'Remote differs'):verify.publication(self.tested,self.tested,True)
        finally:verify.git=original

if __name__=='__main__':unittest.main()
