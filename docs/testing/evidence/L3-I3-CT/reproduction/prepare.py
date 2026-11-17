from pathlib import Path
import subprocess, re, json
ROOT=Path('/private/tmp/pocoma-l3-i3-audit-2b3567b')
OUT=Path(__file__).parent
C='pocoma-l3-i3-independent-audit'
def psql(db,sql):
    r=subprocess.run(['docker','exec','-i',C,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],input=sql,text=True,capture_output=True)
    if r.returncode: raise RuntimeError(r.stderr)
    return r.stdout
psql('postgres',"create role audit_w login; create role audit_r login; create role audit_a login; create role audit_i login; create database audit_write; create database audit_read;")
base=ROOT/'app/infra-persistence-primary-jpa/src/main/resources/db'
readbase=ROOT/'app/infra-persistence-read-jdbc/src/main/resources/db'
for db,role,authority in [('audit_write','audit_w','WRITE'),('audit_read','audit_r','READ')]:
    psql(db,'create schema audit; create extension pgcrypto with schema public;')
    if authority=='WRITE':
        files=[p for folder in ['v3-pot','v3-pot-execution','v3-consumption-write','v3-identity'] for p in (base/folder/'migration').glob('V*.sql')]
    else: files=list((readbase/'v3-consumption-read/migration').glob('V*.sql'))
    files.sort(key=lambda p:int(re.match(r'V(\d+)',p.name)[1]))
    assert len(files)==(6 if authority=='WRITE' else 1),files
    for p in files:
        sql=p.read_text()
        for k,v in dict(expectedDatabase=db,expectedSchema='audit',expectedAdmin='postgres',expectedRuntime=role).items(): sql=sql.replace('${'+k+'}',v)
        psql(db,'set search_path=audit;\n'+sql)
    for name in ['bootstrap-l2-routing.sql','grant-l2-lifecycle.sql','grant-l2-discovery.sql','bootstrap-l2-worker-dev.sql','grant-l2-worker-dev.sql']:
        vars=dict(expected_database=db,expected_schema='audit',expected_admin='postgres',authority=authority,runtime_role=role,reactivation_role='audit_a',injector_role='audit_i',environment='DEV',fixture_identity='false',identity_authority='real',identity_read_role='audit_r',technical_probes='false')
        sql='set search_path=audit;\n'+''.join('\\set '+k+' '+v+'\n' for k,v in vars.items())+(ROOT/'scripts/v3'/name).read_text()
        psql(db,sql)
    print(db,'CURRENT migrated',len(files),'and delivered scripts applied')
psql('audit_write',"set search_path=audit; insert into v3_eui_authority(eui_id,issuer,subject) values ('00000000-0000-0000-0000-000000000001','audit','anchor');")
(OUT/'setup.json').write_text(json.dumps({'schema':'audit','write':'audit_write','read':'audit_read','roles':['audit_w','audit_r','audit_a','audit_i'],'bootstrap':'CURRENT V3 V1-V6, READ technical V1, delivered grant scripts, no Flyway history'},indent=2))
