from pathlib import Path
import subprocess,sys
root=Path('/private/tmp/pocoma-v3-i3-execution')
for gate in ['architecture','command','read','monolith']:
    command=['python3','-B','scripts/testing/verify-v3-l2-i3.py',gate,'/private/tmp/pocoma-i3-gates/'+gate+'-1d7cf51c']
    with Path('/private/tmp/pocoma-v3-i3-final-'+gate+'.log').open('w') as output:
        result=subprocess.run(command,cwd=root,stdout=output,stderr=subprocess.STDOUT)
    print(gate,result.returncode,flush=True)
    if result.returncode:sys.exit(result.returncode)
