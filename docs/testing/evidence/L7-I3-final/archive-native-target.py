import gzip,hashlib,json,re,subprocess,sys,tarfile
from pathlib import Path
from datetime import datetime
name=sys.argv[1];src=Path('certification-raw/l7-i3-final-'+name);out=Path('docs/testing/evidence/L7-I3-final')/name
assert not out.exists()
expected={'read-reacquire-01':{'l73_allReadParticipantsRetainUncertainCapacityAndExactEffects'},'restart-01':{'l73_recreatedDiscoveryAndEnginesPreserveOnlyUnfinishedSlots'},'invitation-concurrency-01':{'l73_concurrentInvitationAcceptanceHasOneWinnerForOnePlace','l73_concurrentInvitationAcceptanceAttachesEachActorToTheirOwnPlace'},'invitation-01':{'invitationPublicationRejectsMissingUnknownAndWrongRequests'},'identity-01':{'l73_concurrentIdentityBatchesPreserveSafePrefixAcrossResolution'},'worker-01':{'l73_distinctWorkerSettingsShareOnlyTheRoutingContract','l73_invalidConfigurationPreservesPendingRequestsAndFailureBudget'},'takeover-01':{'l73_sameIdentityRequestHasOneFinalizerAfterClaimTakeover'},'read-01':{'l73_twoFailedReadTransactionsDoNotBlockTheThirdConsumption','l73_quarantinedOldVersionsResumeWithoutRegressingRealProjections','l73_allReadParticipantsRetainUncertainCapacityAndExactEffects'}}
read=lambda s:(src/s).read_text().strip()
start=read('started-at');finish=read('finished-at');sha=read('sha');rc=int(read('exit-code'))
xml=list((src/'reports').rglob('TEST-*.xml'));cases=[];counts=dict(tests=0,failures=0,errors=0,skipped=0)
for p in xml:
 suite=__import__('xml.etree.ElementTree',fromlist=['']).parse(p).getroot()
 assert suite.find("./properties/property[@name='pocoma.verification.id']").get('value')=='l7-i3-final-'+name
 for k in counts:counts[k]+=int(suite.get(k,'0'))
 for case in suite.findall('testcase'):
  cases.append({'classname':case.get('classname'),'method':case.get('name'),'seconds':float(case.get('time','0')),'outcome':'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'})
passed=rc==0 and {x['method'] for x in cases}==expected[name] and all(x['outcome']=='PASS' for x in cases) and counts['tests']==len(expected[name])
result={'verdict':'PASS' if passed else 'FAIL','sha':sha,'sourceState':read('worktree') or 'clean','scope':'Filtered targeted feedback; no canonical slice/global gate claimed','counts':counts,'executions':cases,'startedAt':start,'finishedAt':finish,'wallSeconds':(datetime.fromisoformat(finish)-datetime.fromisoformat(start)).total_seconds(),'command':['./mvnw','--batch-mode','--no-transfer-progress','-pl',read('anchor'),'-am','-Dtest='+read('selection'),'-Dsurefire.failIfNoSpecifiedTests=false','-Dpocoma.verification.id=l7-i3-final-'+name,'-Dpocoma.verification.reports='+str((src/'reports').resolve()),'test'],'environment':{'JDK':read('jdk'),'TESTCONTAINERS_HOST_OVERRIDE':'127.0.0.1','PostgreSQL':'17.11 Testcontainers, disposable schema','runtimeConfiguration':'Existing fixtures; unchanged SQL budgets and N=3 retry policy, zero fixture retry delay'},'artifacts':{}}
log=(src/'maven.log').read_text();duration=re.search(r'Total time:\s+([^\n]+)',log);result['mavenTime']=duration.group(1).strip() if duration else None
out.mkdir()
secret=re.compile(rb'(?<![\w-])eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{20,}|-----BEGIN (?:EC |RSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----')
with tarfile.open(out/'physical.tar.gz','w:gz') as tar:
 for p in Path('app').glob('*/target/**/*'):
  if not p.is_file() or 'evidence' not in str(p).lower() or not datetime.fromisoformat(start).timestamp() <= p.stat().st_mtime < datetime.fromisoformat(finish).timestamp()+1:continue
  data=p.read_bytes();assert not secret.search(data),p
  tar.add(p,arcname=str(p));result['artifacts'][str(p)]=hashlib.sha256(data).hexdigest()
result['physicalArchiveSha256']=hashlib.file_digest((out/'physical.tar.gz').open('rb'),'sha256').hexdigest()
for p in [src/'maven.log',*xml]:
 data=p.read_bytes();assert not secret.search(data),p
 target=p.name+'.gz';(out/target).write_bytes(gzip.compress(data,mtime=0));result['artifacts'][target]=hashlib.sha256(data).hexdigest()
result['testInputSha256']={}
for path in subprocess.check_output(['git','ls-files','app','scripts/v3'],text=True).splitlines():
 if '/src/test/' in path or path.endswith('reactivate-l2-slot.sql'):
  old=subprocess.check_output(['git','show',sha+':'+path]);result['testInputSha256'][path]=hashlib.sha256(old).hexdigest()
(out/'receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(name,result['verdict'],counts,result['mavenTime'],'physical files',len(result['artifacts'])-len(xml)-1)
