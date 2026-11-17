import subprocess,json,sys
from pathlib import Path
from datetime import datetime,timezone
root=Path('/private/tmp/pocoma-v3-l2-i1-execution')
subprocess.run(['git','diff','--exit-code','12eaa5ae','--','app',':(exclude)app/architecture-tests'],cwd=root,check=True)
status=json.loads(Path('/private/tmp/i1-gates-status.json').read_text())
entry=status[-1]
entry['prior_attempt']={k:entry[k] for k in ['started_utc','finished_utc','status','exit_code']}
entry.update({'started_utc':datetime.now(timezone.utc).isoformat(),'status':'RUNNING','code_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()})
Path('/private/tmp/i1-gates-status.json').write_text(json.dumps(status,indent=2))
command=['./mvnw','-pl','architecture-tests','-am','test']
with open('/private/tmp/i1-final-architecture.log','w') as output:
 r=subprocess.run(command,cwd=root/'app',stdout=output,stderr=subprocess.STDOUT)
entry.update({'finished_utc':datetime.now(timezone.utc).isoformat(),'status':'PASS' if r.returncode==0 else 'FAIL','exit_code':r.returncode})
Path('/private/tmp/i1-gates-status.json').write_text(json.dumps(status,indent=2))
print('ARCHITECTURE',r.returncode,flush=True)
if r.returncode:sys.exit(r.returncode)
subprocess.run(['python3','/private/tmp/collect_i1.py','ARCHITECTURE','/private/tmp/i1-final-architecture.log',' '.join(command)],check=True)
