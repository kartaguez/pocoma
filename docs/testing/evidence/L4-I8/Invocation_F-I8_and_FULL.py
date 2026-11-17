"""Invocation receipt: reuse the existing verifier, without altering its selector or registry."""
import importlib.util,json,sys
from pathlib import Path
root=Path(sys.argv[1]); output=Path(sys.argv[2]); phase=sys.argv[3]
spec=importlib.util.spec_from_file_location('verification',root/'scripts/testing/verify.py'); v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
if phase=='F-I8':
 config=json.loads(v.CONFIG.read_text()); scope='L4-I8-F-I8'
 tests={
 'com.kartaguez.pocoma.runtime.task.v3.ReadActivationSettingsTest':['independentSelectionAndTemporalPreflight'],
 'com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeAssemblyPostgresTest':['rootsAndPrivilegesAreClosed','sixParticipantsProgressThroughWorkers'],
 'com.kartaguez.pocoma.runtime.task.v3.ReadFacadesPostgresTest':['currentHistoryAndConcurrentAdvanceStayExact','readinessDoesNotInventFreshness','expenseBatchesRespectExactManifestAndBounds','bindingAndResultsKeepTheirOwnAuthorities','navigationIsKeysetAndNeverAuthorizes'],
 'com.kartaguez.pocoma.ReadWorkerResourcesPostgresTest':['drainAndRestartPreserveDurableProgress','allParticipantsFitPoolsAndRenewalEnvelope'],
 'com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest':['projectionAndMonolithSharePartitionsSafely']}
 config['campaigns'][scope]={'level':'INTEGRATION','modules':['runtime-task-consumption-worker','runtime-monolith'],'expected_invocations':11,'tests':tests,'forecast':{'seconds':900,'basis':'I8 plan central forecast; no deadline','uncertainty':'HIGH'}}
 definition=v.plan('INTEGRATION',scope,config)
 definition['command']=['./mvnw','-pl','runtime-task-consumption-worker,runtime-monolith','-am','-Dtest=ReadActivationSettingsTest,ReadRuntimeAssemblyPostgresTest,ReadFacadesPostgresTest,ReadWorkerResourcesPostgresTest,ReadRootsCoexistencePostgresTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
elif phase=='FULL':
 definition=v.plan('CERTIFICATION',None)
 definition['command']=['./mvnw','test']
 definition['scope']='L4-I8-C-I8-F-L4-HUMAN-APPROVED-MUTUALIZATION'
else: raise ValueError('unknown campaign')
original=v.control_inputs
def controls():
 result=original(); result[str(Path(__file__).resolve())]=v.digest(Path(__file__)); return result
v.control_inputs=controls
sys.exit(v.run(definition,output.resolve()))
