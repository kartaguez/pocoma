"""Safety and false-positive regression controls for destructive local orchestration."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bootstrap
import runtime
from public_api import exact_balances, canary


class LocalSafety(unittest.TestCase):
    def test_unknown_instance_is_not_adopted(self):
        with self.assertRaises(runtime.Refusal):
            with runtime.locked('../foreign'):
                self.fail('escaped state')

    def test_nested_symlink_is_refused_before_operation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            root.chmod(0o700)
            (root/'foreign').symlink_to('/tmp')
            with self.assertRaisesRegex(runtime.Refusal, 'SYMLINK'):
                runtime.safe_tree(root)

    def test_instance_lock_refuses_concurrent_owner(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'HOME_LOCAL', Path(folder)):
            with runtime.locked('same', create=True):
                with self.assertRaisesRegex(runtime.Refusal, 'LOCKED'):
                    with runtime.locked('same'):
                        self.fail('second owner')

    def test_foreign_project_label_refused(self):
        manifest = {'project':'owned','owner':'uuid'}
        with patch.object(runtime, 'run', return_value='foreign'), patch.object(runtime, 'docker_json',
                return_value=[{'Config':{'Labels':{runtime.LABEL:'other'}}}]):
            with self.assertRaisesRegex(runtime.Refusal, 'FOREIGN-CONTAINER'):
                runtime.ownership(manifest)

    def test_unnamed_reset_refused_without_docker(self):
        with patch.object(sys, 'argv', ['runtime.py','reset','--confirm','RESET:demo']), patch.object(runtime, 'run') as command:
            self.assertEqual(2, runtime.main())
            command.assert_not_called()

    def test_missing_manifest_does_not_create_ownership(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime, 'HOME_LOCAL', Path(folder)):
            target=Path(folder)/'instances'/'orphan';target.mkdir(parents=True,mode=0o700)
            with patch.object(sys,'argv',['runtime.py','stop','--instance','orphan']):
                self.assertEqual(2,runtime.main())
            self.assertFalse((target/'manifest.json').exists())

    def test_recovery_waits_observed_lease_without_mutation_or_polling_extension(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runtime,'compose',return_value='stopped-worker'), \
             patch.object(runtime,'docker_json',return_value=[{'State':{'Running':False}}]), \
             patch.object(runtime,'sql',return_value='0') as sql:
            runtime.recovery_window(Path(folder),{},Path(folder))
            data=json.loads((Path(folder)/'recovery-window.json').read_text())
            self.assertEqual('WAIT_CAPTURED_WINDOW_NO_LEASE_MUTATION',data['action'])
            self.assertEqual(2,sql.call_count)
            for call in sql.call_args_list:
                self.assertNotIn('UPDATE',call.args[3])
                self.assertNotIn('DELETE',call.args[3])
            self.assertIn('completedAt',data)

    def test_health_only_cannot_be_ready(self):
        result=runtime.verdict({'HTTP':'PASS'},False,'AVAILABLE')
        self.assertEqual('NOT_READY',result['state'])
        self.assertNotEqual(0,result['exit'])

    def test_observability_degradation_has_distinct_exit(self):
        result=runtime.verdict({'BUSINESS':'PASS'},True,'DEGRADED')
        self.assertEqual('AVAILABLE',result['business'])
        self.assertEqual('DEGRADED',result['state'])
        self.assertEqual(4,result['exit'])
        self.assertFalse(result['fullyQualifiable'])

    def test_critical_failure_dominates_recent_canary(self):
        result=runtime.verdict({'PG':'FAIL'},True,'AVAILABLE')
        self.assertEqual('NOT_READY',result['state'])
        self.assertEqual(1,result['exit'])

    def test_exact_balance_rejects_invalid_sum_and_float(self):
        for balances in ({'a':{'n':'1','d':'3'}},{'a':{'n':0,'d':'1'}},{'a':{'n':'0','d':'0'}}):
            with self.assertRaises(RuntimeError):
                exact_balances({'balances':balances})

    def test_previous_canary_version_is_not_new_proof(self):
        class FakeAPI:
            def binding(self): return 'private-binding'
            def command(self,*args): return 'request',{'resultingPotVersion':4}
            def pot(self,*args): return {'potVersion':4}
        with self.assertRaisesRegex(RuntimeError,'CANARY-NEW-VERSION'):
            canary('00000000-0000-0000-0000-000000000001',FakeAPI(),'new-run',{},lambda s:None)

    def test_bootstrap_composes_single_transaction_and_random_roles(self):
        calls=[]
        def sql(db,text):
            calls.append((db,text))
            if "FROM pg_database" in text: return '1'
            if "pocoma_local_bootstrap')" in text: return 'f'
            return ''
        passwords={role:'random-'+role for role in bootstrap.ROLES}
        bootstrap.initialize(sql,passwords)
        ddl=[text for _,text in calls if 'CREATE TABLE public.pocoma_local_bootstrap' in text]
        self.assertEqual(2,len(ddl))
        for text in ddl:
            self.assertEqual(1,len(__import__('re').findall(r'(?im)^BEGIN;',text)))
            self.assertEqual(1,len(__import__('re').findall(r'(?im)^COMMIT;',text)))
            self.assertIn('GRANT',text)
            self.assertLess(text.index('GRANT'),text.index('CREATE TABLE public.pocoma_local_bootstrap'))
        roles='\n'.join(text for _,text in calls if 'CREATE ROLE' in text)
        self.assertNotIn('local-only',roles)
        self.assertIn('random-pocoma_admission',roles)

    def test_bootstrap_resume_does_not_replay_committed_databases(self):
        def sql(db,text):
            if "FROM pg_database" in text: return '1'
            if "pocoma_local_bootstrap')" in text: return 't'
            if text.startswith('SELECT fingerprint'):return bootstrap.fingerprint()
            self.assertNotIn('CREATE TABLE public.pocoma_local_bootstrap',text)
            return ''
        bootstrap.initialize(sql,{r:'random-'+r for r in bootstrap.ROLES})

    def test_bruno_zero_receipts_cannot_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);(path/'runs').mkdir()
            with patch.object(runtime,'ownership'),patch.object(runtime,'private',return_value={'users':{'bruno':'a','guest':'b','third':'c'}}),\
                 patch.object(runtime,'login',side_effect=['token-owner','token-guest','token-third']),\
                 patch.object(runtime.subprocess,'run',return_value=subprocess.CompletedProcess([],0)):
                result=runtime.bruno(path,{'ports':{'write':1,'read':2},'issuer':'http://localhost'},path/'runs')
            self.assertEqual('BRUNO_FAIL',result['state'])
            self.assertEqual([],json.loads((path/'runs/bruno-receipt.json').read_text())['actual'])


if __name__=='__main__':
    unittest.main()
