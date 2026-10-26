from pathlib import Path
import subprocess,re,json
log=Path('/private/tmp/i1-final-command.log').read_text()
ids=list(dict.fromkeys(re.findall(r'Container postgres:17-alpine is starting: ([0-9a-f]{64})',log)))
proof=[]
for identifier in reversed(ids):
 proc=subprocess.run(['docker','inspect',identifier],capture_output=True,text=True)
 if proc.returncode:continue
 item=json.loads(proc.stdout)[0]
 if not item['State']['Running']:continue
 env=dict(e.split('=',1) for e in item['Config']['Env'] if '=' in e)
 metadata={k:item[k] for k in ['Id','Image']};metadata['host_ports']=item['NetworkSettings']['Ports'];metadata['image']=item['Config']['Image']
 query="select json_build_object('system_identifier',(select system_identifier::text from pg_control_system()),'version',version(),'database',current_database(),'user',current_user,'server_address',inet_server_addr(),'server_port',inet_server_port(),'max_connections',current_setting('max_connections'),'shared_buffers',current_setting('shared_buffers'),'transaction_isolation',current_setting('transaction_isolation'),'encoding',current_setting('server_encoding'),'timezone',current_setting('TimeZone'))"
 r=subprocess.run(['docker','exec',identifier,'psql','-U',env['POSTGRES_USER'],'-d',env['POSTGRES_DB'],'-At','-c',query],capture_output=True,text=True)
 if r.returncode==0:metadata['postgres']=json.loads(r.stdout);proof.append(metadata)
 if len(proof)>=4:break
p=Path('/private/tmp/pocoma-v3-l2-i1-execution/docs/testing/evidence/L2-I1/postgres-live-environment.json')
old=json.loads(p.read_text()) if p.exists() else {'instances':[]}
known={d['Id']:d for d in old['instances']};known.update({d['Id']:d for d in proof})
p.write_text(json.dumps({'source_log':'COMMAND/maven.log','scope':'Only live Testcontainers IDs observed in the final COMMAND log; no unrelated containers inspected','instances':list(known.values())},indent=2)+'\n')
print('PostgreSQL clusters recorded:',len(known))
