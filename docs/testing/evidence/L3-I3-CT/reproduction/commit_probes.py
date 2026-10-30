from pathlib import Path
import subprocess
P=Path(__file__).parent; ROOT=Path('/private/tmp/pocoma-l3-i3-audit-2b3567b')
C='pocoma-l3-i3-independent-audit'
def psql(sql):
 r=subprocess.run(['docker','exec','-i',C,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-d','audit_write'],input='set search_path=audit;\n'+sql,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r
def script(name):
 vars=dict(expected_database='audit_write',expected_schema='audit',expected_admin='postgres',authority='WRITE',runtime_role='audit_w',reactivation_role='audit_a',injector_role='audit_i',environment='DEV',fixture_identity='false',identity_authority='real',identity_read_role='audit_r',technical_probes='false')
 psql(''.join('\\set '+k+' '+v+'\n' for k,v in vars.items())+(ROOT/'scripts/v3'/name).read_text())
 print('AUDIT',name,'accepted',flush=True)
for role,table in [('audit_a','v3_identity_event'),('audit_i','v3_identity_completion_event')]:
 psql('grant truncate on '+table+' to '+role+';')
 script('grant-l2-discovery.sql');script('grant-l2-worker-dev.sql')
 subprocess.run(['python3','-B',str(P/'run_java.py'),'preflight'],check=True)
 subprocess.run(['python3','-B',str(P/'run_java.py'),'truncate-commit',role,table],check=True)
 psql('revoke truncate on '+table+' from '+role+';')
print('AUDIT final disposable dataset intentionally corrupted by these two committed negative probes; no production data touched',flush=True)
