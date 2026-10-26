import subprocess,json,sys
from pathlib import Path
from datetime import datetime,timezone
root=Path('/private/tmp/pocoma-v3-l2-i1-execution')
gates=[('COMMAND','runtime-command-consumption-worker'),('READ','infra-persistence-read-jdbc'),('MONOLITH','runtime-monolith'),('ARCHITECTURE','architecture-tests')]
status=[]
for gate,anchor in gates:
    subprocess.run(['git','diff','--exit-code','12eaa5ae','--','app','scripts/v3','docs/testing/evidence/L2-I1/routing-vectors.csv'],cwd=root,check=True)
    entry={'gate':gate,'started_utc':datetime.now(timezone.utc).isoformat(),'status':'RUNNING'};status.append(entry)
    Path('/private/tmp/i1-gates-status.json').write_text(json.dumps(status,indent=2))
    logfile='/private/tmp/i1-final-'+gate.lower()+'.log'
    command=['./mvnw','-pl',anchor,'-am','test']
    with open(logfile,'w') as output:
        result=subprocess.run(command,cwd=root/'app',stdout=output,stderr=subprocess.STDOUT)
    entry.update({'finished_utc':datetime.now(timezone.utc).isoformat(),'status':'PASS' if result.returncode==0 else 'FAIL','exit_code':result.returncode,'log':logfile})
    Path('/private/tmp/i1-gates-status.json').write_text(json.dumps(status,indent=2))
    print(gate,result.returncode,flush=True)
    if result.returncode:sys.exit(result.returncode)
    subprocess.run(['python3','/private/tmp/collect_i1.py',gate,logfile,' '.join(command)],check=True)
