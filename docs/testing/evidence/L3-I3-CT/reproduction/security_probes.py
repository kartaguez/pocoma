from pathlib import Path
import subprocess, sys
P=Path(__file__).parent
ROOT=Path('/private/tmp/pocoma-l3-i3-audit-2b3567b')
C='pocoma-l3-i3-independent-audit'
CP=str(P)+':'+(P/'classpath.txt').read_text()
def java(mode,*args):
 r=subprocess.run(['java','-cp',CP,'IndependentAudit',mode,*args],capture_output=True,text=True)
 (P/(mode+'-'+('-'.join(args) if args else 'v2')+'.log')).write_text(r.stdout+r.stderr)
 print(mode,'exit',r.returncode)
 print('\n'.join(x for x in (r.stdout+r.stderr).splitlines() if x.startswith('AUDIT') or 'Exception in thread' in x or 'Caused by:' in x)[:5000])
 return r.returncode
def sql(s):
 r=subprocess.run(['docker','exec','-i',C,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-d','audit_write'],input='set search_path=audit;\n'+s,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r
def script(name):
 vars=dict(expected_database='audit_write',expected_schema='audit',expected_admin='postgres',authority='WRITE',runtime_role='audit_w',reactivation_role='audit_a',injector_role='audit_i',environment='DEV',fixture_identity='false',identity_authority='real',identity_read_role='audit_r',technical_probes='false')
 r=sql(''.join('\\set '+k+' '+v+'\n' for k,v in vars.items())+(ROOT/'scripts/v3'/name).read_text())
 print('AUDIT script',name,'exit',r.returncode)
if len(sys.argv)==1:
 java('permissions')
 java('boot-view')
sql('revoke truncate on v3_identity_event from audit_a,audit_i;')
for role in ['audit_a','audit_i']:
 sql('grant truncate on v3_identity_event to '+role+';')
 script('grant-l2-discovery.sql')
 script('grant-l2-worker-dev.sql')
 java('preflight')
 java('truncate',role,'v3_identity_event')
 sql('revoke truncate on v3_identity_event from '+role+';')
java('preflight')
