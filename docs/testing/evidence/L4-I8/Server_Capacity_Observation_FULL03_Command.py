"""Read-only PostgreSQL witness during the existing T11 invocation; no extra test or Maven run."""
from pathlib import Path
import subprocess,json,time,datetime
log=Path('/private/tmp/pocoma-i8-FULL-03/maven.log')
out=Path('/Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L4-I8/Server_Capacity_Observation.json')
query="""select json_build_object(
'observed_at',clock_timestamp(),'server_started_at',pg_postmaster_start_time(),'version',version(),
'max_connections',current_setting('max_connections')::int,
'superuser_reserved_connections',current_setting('superuser_reserved_connections')::int,
'reserved_connections',current_setting('reserved_connections')::int,
'client_connections',(select count(*) from pg_stat_activity where backend_type='client backend'),
'root_connections',(select count(*) from pg_stat_activity where application_name like 'i8-monolith-%' or application_name like 'i8-projection-%'),
'connections_by_pool',(select json_agg(x) from(select datname,application_name,count(*) connections from pg_stat_activity where application_name like 'i8-monolith-%' or application_name like 'i8-projection-%' group by datname,application_name order by datname,application_name)x));"""
position=0; armed=False
while True:
 if log.exists():
  with log.open() as stream:
   stream.seek(position); new=stream.read(); position=stream.tell()
  if 'Running com.kartaguez.pocoma.ReadRootsCoexistencePostgresTest' in new: armed=True
  if 'BUILD FAILURE' in new or 'BUILD SUCCESS' in new:
   out.write_text(json.dumps({'status':'NOT_OBSERVED','reason':'campaign ended before observation','query':query},indent=2)+'\n');break
 if armed:
  inventory=subprocess.run(['docker','ps','--format','{{.ID}} {{.Image}}'],text=True,capture_output=True,check=True).stdout
  for line in inventory.splitlines():
   cid,image=line.split(' ',1)
   if 'ryuk' in image: continue
   result=subprocess.run(['docker','exec','-e','PGAPPNAME=i8-capacity-observer',cid,'psql','-X','-A','-t','-U','test','-d','test','-c',query],text=True,capture_output=True)
   if result.returncode: continue
   try: row=json.loads(result.stdout)
   except ValueError: continue
   if row.get('root_connections')==64:
    ordinary=row['max_connections']-row['superuser_reserved_connections']-row['reserved_connections']
    evidence={'status':'OBSERVED','tested_application_sha':'090b5327cd2a4e974bb8907c62c4e65b82a17e62','invocation_id':json.loads(Path('/private/tmp/pocoma-i8-FULL-03/plan.json').read_text())['invocation_id'],'container':cid,'image':image,'sql':query,'observation':row,'declared_fixture_admin_observer_budget':12,'ordinary_connection_capacity':ordinary,'required_root_plus_fixture_budget':76,'budget_fits':ordinary>=76,'observer':'one independent psql connection, included in the 12-connection fixture/admin/observer budget','limits':'Local fixture server capacity, not production sizing or global performance certification.'}
    out.write_text(json.dumps(evidence,indent=2)+'\n');print(json.dumps(evidence),flush=True);raise SystemExit(0)
 time.sleep(1)
