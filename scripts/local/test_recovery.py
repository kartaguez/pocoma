"""Recovery, generation, late-result and failure-evidence regression controls."""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recovery as rec
import runtime as rt
from public_api import PublicAPI


def generation(suffix='old', running=True):
    return {name: dict(containerId=name+suffix, createdAt='created-'+suffix,
                       startedAt='started-'+suffix, running=running) for name in rt.RUNTIMES}


def observations():
    texts, rows = {}, dict(read=[], write=[])
    for name, (authority, participants) in rec.PARTICIPANTS.items():
        texts[name] = ('pocoma_v3_worker_state{state="running"} 1\n'
            + f'pocoma_v3_lease_local{{authority="{authority}"}} {4*len(participants)}\n')
        rows[authority].extend(dict(consumptionId=p, partitionId=i, live=True, partitionCount=4)
                               for p in participants for i in range(4))
    return texts, rows


class Response(io.BytesIO):
    def __init__(self, status, data):
        super().__init__(json.dumps(data).encode()); self.code=status
        self.headers={'Cache-Control':'no-store'}


class RecoveryTests(unittest.TestCase):
    def test_all_36_pairs_and_local_usable_leases_are_required(self):
        texts, rows = observations()
        result = rec.coverage(texts, rows)
        self.assertTrue(all(v['qualified'] for v in result.values()))
        self.assertEqual(36, sum(v['expectedLeases'] for v in result.values()))
        for authority in rows:
            partial = copy.deepcopy(rows);partial[authority][0]['live']=False
            self.assertFalse(all(v['qualified'] for v in rec.coverage(texts, partial).values()))
        texts['command-worker']=texts['command-worker'].replace(' 8\n',' 7\n')
        self.assertFalse(rec.coverage(texts,rows)['command-worker']['qualified'])

    def test_duplicate_extra_wrong_partition_count_and_missing_metrics_fail_closed(self):
        texts, rows=observations()
        for alter in ('duplicate','extra','wrong-count'):
            bad=copy.deepcopy(rows)
            if alter=='duplicate':bad['write'].append(dict(bad['write'][0]))
            if alter=='extra':bad['write'].append(dict(bad['write'][0],partitionId=4))
            if alter=='wrong-count':bad['write'][0]['partitionCount']=8
            self.assertFalse(rec.coverage(texts,bad)['command-worker']['qualified'])
        texts['critical-read-worker']=''
        self.assertFalse(rec.coverage(texts,rows)['critical-read-worker']['qualified'])

    def test_first_start_and_preserved_restart_have_no_sleep(self):
        texts, rows=observations();covered=rec.coverage(texts,rows)
        for before in (dict.fromkeys(rt.RUNTIMES),generation()):
            with patch.object(rec.time,'sleep') as sleep:
                record=rec.await_coverage(lambda:covered,lambda:generation(),lambda r:None,before)
            sleep.assert_not_called()
            self.assertEqual('RECOVERED',record['state'])
            expected='CREATED' if not before['command-worker'] else 'PRESERVED'
            self.assertEqual(expected,record['transitions']['command-worker'])

    def test_stopped_restarted_and_active_recreated_are_distinct(self):
        before=generation(running=False);after=generation()
        after['command-worker']['startedAt']='new-start'
        self.assertEqual('RESTARTED',rec.transitions(before,after)['command-worker'])
        self.assertEqual('RECREATED',rec.transitions(generation(),generation('new'))['command-worker'])
        self.assertNotEqual(rec.identity(before),rec.identity(after))

    def test_unexpired_leases_and_partial_coverage_defer_canary_opportunity(self):
        texts, rows=observations();partial=copy.deepcopy(rows)
        partial['read'][0]['live']=False
        values=iter([rec.coverage(texts,partial),rec.coverage(texts,rows)])
        events=[]
        with patch.object(rec.time,'sleep',side_effect=lambda n:events.append('wait')):
            record=rec.await_coverage(lambda:next(values),lambda:generation('new'),
                lambda r:events.append(r['state']),generation())
        events.append('canary')
        self.assertEqual('RECREATED',record['transitions']['command-worker'])
        self.assertLess(events.index('wait'),events.index('RECOVERED'))
        self.assertLess(events.index('RECOVERED'),events.index('canary'))

    def test_recovery_impossible_is_bounded_and_diagnostic(self):
        saved=[]
        with self.assertRaisesRegex(RuntimeError,'WORKER-RECOVERY-TIMEOUT'):
            rec.await_coverage(lambda:{},lambda:generation(),lambda r:saved.append(copy.deepcopy(r)),generation(),budget=0)
        self.assertEqual('FAILED',saved[-1]['state'])
        self.assertFalse(saved[-1]['observations'][-1]['qualified'])

    def test_observation_exception_retains_error_class_without_secret_message(self):
        saved=[]
        def unavailable():raise OSError('secret')
        with self.assertRaises(RuntimeError):
            rec.await_coverage(unavailable,lambda:generation(),lambda r:saved.append(copy.deepcopy(r)),generation(),budget=0)
        self.assertIn('OSError',json.dumps(saved))
        self.assertNotIn('secret',json.dumps(saved))

    def test_stopped_worker_recovery_waits_for_actual_full_coverage(self):
        texts,rows=observations()
        blocked=dict(texts)
        blocked['critical-read-worker']=blocked['critical-read-worker'].replace(' 12\n',' 0\n')
        sequence=iter([rec.coverage(blocked,rows),rec.coverage(texts,rows)])
        before=generation(running=False);after=generation()
        for row in after.values():row['startedAt']='restarted'
        with patch.object(rec.time,'sleep') as wait:
            result=rec.await_coverage(lambda:next(sequence),lambda:after,lambda r:None,before)
        wait.assert_called_once()
        self.assertEqual({'RESTARTED'},set(result['transitions'].values()))
        self.assertEqual('RECOVERED',result['state'])

    def test_tooling_refresh_refuses_productive_drift_before_render_or_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            with patch.object(rt,'ownership'),patch.object(rt,'run',side_effect=['app/changed.java','']), \
                 patch.object(rt,'render') as render,patch.object(rt,'package') as package:
                with self.assertRaisesRegex(rt.Refusal,'PRE-INPUT-DRIFT'):
                    rt.refresh_tooling(path,dict(build=dict(sha='a'*40)),path)
            render.assert_not_called();package.assert_not_called()
            self.assertEqual(['tooling-refresh-refused.json'],[p.name for p in path.iterdir()])

    def test_generation_change_during_recovery_cannot_qualify(self):
        text,rows=observations();snapshots=iter([generation(),generation('new')])
        with self.assertRaisesRegex(RuntimeError,'GENERATION-CHANGED'):
            rec.await_coverage(lambda:rec.coverage(text,rows),lambda:next(snapshots),lambda r:None,generation())

    def test_old_fresh_unbound_stopped_and_expired_proofs_cannot_qualify(self):
        proof=dict(completedAt=100,generationId=rec.identity(generation()))
        self.assertTrue(rec.proof_state(proof,generation(),101,300)['demonstrated'])
        for current,now in [(generation('new'),101),(generation(running=False),101),(generation(),401)]:
            self.assertFalse(rec.proof_state(proof,current,now,300)['demonstrated'])
        self.assertFalse(rec.proof_state(dict(completedAt=100),generation(),101,300)['demonstrated'])

    def test_poll_404_503_then_success_and_30_second_budget(self):
        api=PublicAPI('http://write','http://read','secret')
        with patch.object(api,'call',side_effect=[(404,{}),(503,{}),(200,{'outcome':'COMPLETED'})]),patch('public_api.time.sleep'):
            self.assertEqual('COMPLETED',api.poll('/commands/id/result')['outcome'])
        self.assertEqual(30,api.last_poll['budgetSeconds'])

    def test_network_error_is_explicit_and_persisted_without_credentials(self):
        records=[]
        api=PublicAPI('http://write','http://read','secret-bearer',lambda api:records.append(copy.deepcopy(api.trace)))
        with patch('public_api.urllib.request.urlopen',side_effect=urllib.error.URLError('secret-network-url')):
            with self.assertRaisesRegex(RuntimeError,'API-NETWORK-ERROR:GET:/pots/a'):
                api.call('GET','/pots/a',binding='secret-binding')
        self.assertEqual('API-NETWORK-ERROR',records[-1][-1]['control'])
        self.assertNotIn('secret',json.dumps(records))

    def test_response_body_network_failure_is_explicit_and_retains_http_status(self):
        response=Response(200,{})
        records=[]
        api=PublicAPI('http://write','http://read','secret',lambda api:records.append(copy.deepcopy(api.trace)))
        with patch('public_api.urllib.request.urlopen',return_value=response), \
             patch.object(response,'read',side_effect=TimeoutError('secret')):
            with self.assertRaisesRegex(RuntimeError,'API-NETWORK-ERROR'):api.call('GET','/pots/a')
        self.assertEqual(200,records[-1][-1]['status'])
        self.assertEqual('API-NETWORK-ERROR',records[-1][-1]['control'])
        self.assertNotIn('secret',json.dumps(records))

    def test_failed_canary_admission_trace_last_observation_and_id_are_persisted(self):
        records=[]
        api=PublicAPI('http://write','http://read','secret',lambda api:records.append(copy.deepcopy(
            dict(trace=api.trace,admissions=api.admissions,lastPoll=api.last_poll))))
        def response(request,timeout):
            return Response(202,dict(requestId='request-id')) if request.method=='POST' else Response(404,{})
        with patch('public_api.urllib.request.urlopen',side_effect=response), \
             patch('public_api.time.monotonic',side_effect=[0,0,31]),patch('public_api.time.sleep'):
            with self.assertRaisesRegex(RuntimeError,'API-POLL-TIMEOUT'):
                api.submit('/commands',dict(operation='UpdatePotDetails'),'run-id')
        last=records[-1]
        self.assertEqual('request-id',last['admissions'][0]['requestId'])
        self.assertEqual([202,404],[r['status'] for r in last['trace']])
        self.assertEqual(404,last['lastPoll']['status'])
        self.assertEqual('API-POLL-TIMEOUT',last['lastPoll']['control'])
        self.assertIn('observedAt',last['admissions'][0])
        self.assertNotIn('secret',json.dumps(records))

    def test_late_result_uses_only_get_and_preserves_original_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);run=path/'runs/1794676508-e55784d4';run.mkdir(parents=True)
            receipt=dict(state='NOT_READY',control='API-POLL-TIMEOUT:/commands/8a22eb39-b241-4263-8910-da026287ebad/result')
            rt.save(run/'receipt.json',receipt);original=(run/'receipt.json').read_bytes()
            api=PublicAPI('http://write','http://read','secret')
            with patch.object(api,'call',return_value=(200,dict(outcome='COMPLETED',resultingPotVersion=4,bindingToken='secret'))) as call:
                result=rt.late_result(path,api,run.name,path)
            call.assert_called_once_with('GET','/commands/8a22eb39-b241-4263-8910-da026287ebad/result')
            self.assertEqual(original,(run/'receipt.json').read_bytes())
            self.assertEqual('COMPLETED',result['result']['outcome'])
            self.assertNotIn('secret',(path/'late-result.json').read_text())

    def test_start_failure_history_preserves_last_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);evidence=path/'runs/new';evidence.mkdir(parents=True)
            rt.save(path/'start-history.json',dict(lastSuccessfulStart=dict(runId='old')))
            with patch.object(rt,'run',return_value='sha'),patch.object(rt,'generations',return_value=generation()), \
                 patch.object(rt,'start_distribution',side_effect=RuntimeError('WORKER-RECOVERY-TIMEOUT')):
                with self.assertRaisesRegex(RuntimeError,'WORKER-RECOVERY-TIMEOUT'):rt.start(path,{},evidence)
            history=rt.read(path/'start-history.json')
            self.assertEqual('FAILED',history['lastStart']['state'])
            self.assertEqual('old',history['lastSuccessfulStart']['runId'])
            self.assertEqual('WORKER-RECOVERY-TIMEOUT',rt.read(evidence/'failure.json')['control'])

    def test_status_old_generation_and_failed_last_start_remain_unproven(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            rt.save(path/'canary.json',dict(potId='pot',version=3,runId='old',completedAt=rt.time.time(),
                                         generationId=rec.identity(generation())))
            rt.save(path/'start-history.json',dict(lastStart=dict(state='FAILED',control='WORKER-RECOVERY-TIMEOUT')))
            with patch.object(rt,'ownership'),patch.object(rt,'current',return_value={'DB':'PASS'}), \
                 patch.object(rt,'generations',return_value=generation('new')), \
                 patch.object(rt,'identities',return_value=({'AUTH':'PASS'},dict(bruno='secret'))), \
                 patch.object(PublicAPI,'call',side_effect=[(200,dict(active=True,bindingToken='secret')),
                     (200,dict(potVersion=3,label='canary-old'))]), \
                 patch.object(rt.grafana,'checks',return_value=({},{})),patch.object(rt,'observability',return_value='AVAILABLE'):
                result=rt.status(path,dict(ports=dict(write=1,read=2),issuer='issuer'))
            self.assertEqual('NOT_READY',result['state'])
            self.assertEqual('FAILED',result['lastStart']['state'])
            self.assertEqual('FRESH',result['proofFreshness'])
            self.assertFalse(result['canaryProof']['generationQualified'])


if __name__=='__main__':unittest.main()
