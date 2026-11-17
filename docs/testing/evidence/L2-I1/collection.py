#!/usr/bin/env python3
"""Collect only suites named by a fresh Maven log; never infer PASS from stale reports."""
import sys,re,json,hashlib,tarfile,subprocess,xml.etree.ElementTree as E
from pathlib import Path
from datetime import datetime,timezone
root=Path('/private/tmp/pocoma-v3-l2-i1-execution')
base=root/'docs/testing/evidence/L2-I1'
gate,log,command=sys.argv[1:]
base.mkdir(parents=True,exist_ok=True)
log=Path(log);content=log.read_text()
assert '[INFO] BUILD SUCCESS' in content, 'gate did not succeed'
modules={}
for p in (root/'app').glob('*/pom.xml'):
 tree=E.parse(p).getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
 a=tree.find('m:artifactId',ns)
 if a is None:a=tree.find('artifactId')
 if a is not None:modules[a.text]=p.parent
suites=[];module=None;seen=set()
for line in content.splitlines():
 m=re.search(r'--- surefire:.* @ (\S+) ---',line)
 if m:module=modules.get(m[1])
 m=re.match(r'\[INFO\] Running (\S+)',line)
 if not m:continue
 assert module, line
 file=module/'target/surefire-reports'/('TEST-'+m[1]+'.xml')
 assert file.exists(),file
 if file in seen:continue
 seen.add(file);t=E.parse(file).getroot()
 methods=[]
 for case in t.findall('testcase'):
  status='PASS'
  if case.find('skipped') is not None:status='SKIP'
  if case.find('failure') is not None:status='FAIL'
  if case.find('error') is not None:status='ERROR'
  reason=case.find('skipped')
  methods.append({'class':case.attrib.get('classname'),'method':case.attrib['name'],'status':status,'seconds':case.attrib.get('time'),
    **({'skip_reason':reason.attrib.get('message',reason.text)} if reason is not None else {})})
 suites.append({'module':module.name,'class':m[1],'xml':str(file.relative_to(root)),
  'tests':int(t.attrib.get('tests',0)),'failures':int(t.attrib.get('failures',0)),'errors':int(t.attrib.get('errors',0)),
  'skipped':int(t.attrib.get('skipped',0)),'methods':methods})
assert suites
source_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
summary={'scope':'I1 local bounded foundation; not G1/G2/G3 certification','gate':gate,'collected_utc':datetime.now(timezone.utc).isoformat(),
'code_sha':source_sha,'command':command,'exit_code':0,'totals':{k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']},'suites':suites}
assert summary['totals']['failures']==summary['totals']['errors']==0
archive=base/(gate+'.tar.gz')
with tarfile.open(archive,'w:gz') as tar:
 tar.add(log,arcname='maven.log')
 for s in suites:tar.add(root/s['xml'],arcname=s['xml'])
 for module in set(s['module'] for s in suites):
  for p in (root/'app'/module/'target').glob('l2-i1-proof/*'):tar.add(p,arcname=str(p.relative_to(root)))
  # Fresh L1 measurement files live in target; retain their data as well.
  for p in (root/'app'/module/'target').glob('**/*'):
   if p.is_file() and ('performance' in p.name.lower() or 'f2-' in p.name.lower()) and p.suffix in ['.json','.csv','.txt']:
    tar.add(p,arcname=str(p.relative_to(root)))
summary['archive']={'file':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}
(base/(gate+'-summary.json')).write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n')
print(gate,summary['totals'],summary['archive'])
