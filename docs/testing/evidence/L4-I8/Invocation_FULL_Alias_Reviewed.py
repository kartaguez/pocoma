"""Invocation receipt: reuse the existing verifier, without altering its selector or registry."""
import importlib.util,json,sys
from pathlib import Path
root=Path(sys.argv[1]); output=Path(sys.argv[2]); phase=sys.argv[3]
spec=importlib.util.spec_from_file_location('verification',root/'scripts/testing/verify.py'); v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
# Sole reviewed top-level filename alias. No selector or permanent registry change.
original_sources=v.sources
alias_path=root/'app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/JpaTerminalSequencingPostgresTest.java'
if v.digest(alias_path)!='ffd8b66275bb459df958da6a387ef84474c1cc59da1b8192035d70939c5a3194': raise ValueError('Reviewed alias source changed')
def reviewed_sources(*args,**kwargs):
 index=original_sources(*args,**kwargs)
 index['CurrentTransactionManagerPostgresTest']=[('com.kartaguez.pocoma.runtime.command.consumption.sequencing.CurrentTransactionManagerPostgresTest',alias_path)]
 return index
v.sources=reviewed_sources
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
