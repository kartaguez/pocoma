"""Qualify the delivered collection on a NEW disposable local instance, then reset.

Stops no other instance. Refuses an existing target/output. No rendered GUI claim.
Only allowlisted receipts, JUnit and build logs are copied; no HTTP body/session.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parent))
import runtime
import test_gui_auth_protocol
from auth import login


def execute(instance, output):
    path=runtime.HOME_LOCAL/'instances'/instance
    if path.exists() or output.exists():
        raise RuntimeError('Require NEW disposable instance and evidence directory')
    output.mkdir(parents=True,mode=0o700)
    commands=[]
    inputs=runtime.source_hash()
    tested_sha=runtime.run(['git','rev-parse','HEAD'])
    dirty=bool(runtime.run(['git','status','--porcelain']))
    def command(action, phase, *extra):
        argv=[str(runtime.ROOT/'pocoma-local'),action,'--instance',instance,*extra]
        started=time.monotonic()
        result=subprocess.run(argv,cwd=runtime.ROOT,text=True,capture_output=True,timeout=1500)
        # The facade's CURRENT bootstrap prints human progress before its receipt.
        try:data=json.loads(result.stdout[result.stdout.index('{'):])
        except (json.JSONDecodeError,ValueError):raise RuntimeError('Facade did not produce a JSON receipt')
        commands.append(dict(argv=argv,phase=phase,seconds=time.monotonic()-started,exit=result.returncode,result=data))
        runtime.save(output/'commands.json',commands)
        if result.returncode:raise RuntimeError('Local facade failed: '+phase)
        if action=='bruno':
            target=output/phase;target.mkdir()
            source=Path(data['evidence'])
            for name in ('receipt.json','bruno-receipt.json','bruno.xml','scenarios.jsonl'):
                shutil.copyfile(source/name,target/name)
        return data
    try:
        first=command('start','fresh-start')
        command('gui','fresh-gui-config')
        command('bruno','fresh-cli')
        test_gui_auth_protocol.execute(instance,output/'native-421.json')
        # Technical routes use the same shipped .bru files, independently counted.
        m=runtime.load_manifest(path);credentials=runtime.private(path)
        owner=login(m['issuer'],'third',credentials['users']['third'])
        cli=runtime.ROOT/'scripts/bruno-v3/node_modules/.bin/bru'
        target=output/'technical';target.mkdir()
        argv=[str(cli),'run','80 - Technical','-r','--env','Local','--sandbox=developer','--bail','--reporter-junit',str(target/'bruno.xml')]
        environment={**os.environ,'POCOMA_PG_OWNER':owner,'POCOMA_PG_ISSUER':m['issuer'],
            'POCOMA_PG_WRITE':runtime.urls(m)[0],'POCOMA_PG_READ':runtime.urls(m)[1],
            'POCOMA_PG_MANAGEMENT':'http://127.0.0.1:'+str(m['ports']['http-read']),
            'POCOMA_PG_RECEIPT':str(target/'requests.jsonl')}
        result=subprocess.run(argv,cwd=runtime.ROOT/'scripts/bruno-playground',env=environment,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)
        records=[json.loads(line) for line in (target/'requests.jsonl').read_text().splitlines()]
        suites=ET.parse(target/'bruno.xml').findall('.//testsuite')
        catalogue=runtime.read(runtime.ROOT/'scripts/bruno-playground/catalogue.json')
        inventory={'requests':[dict(id=x['id'],file=x['file'],tests=[x['id']+' transport and privacy',x['id']+' contract and variables'],
            method='GET',pathTemplate=('/api/v3/diagnostics/stage-handoffs' if x['id']=='T01' else '/actuator/health' if x['id']=='T02' else '/actuator/prometheus' if x['id']=='T03' else '/actuator/stage-handoffs'),status=200)
            for x in sorted(catalogue,key=lambda x:x['id']) if x['folder']=='80 - Technical']}
        technical=runtime.validate_bruno_results(inventory,records,suites,result.returncode)
        runtime.save(target/'receipt.json',dict(command=argv,**technical))
        if not technical['success']:raise RuntimeError('Technical Bruno proof failed')
        # Only this newly created, explicitly disposable fixture is reset.
        command('reset','reset-plan','--dry-run','--confirm','RESET:'+instance)
        reset=command('reset','reset','--confirm','RESET:'+instance)
        command('gui','reset-gui-config')
        command('bruno','after-reset-cli')
        m=runtime.load_manifest(path)
        runtime.save(output/'manifest-public.json',{key:m[key] for key in ('instance','owner','project','ports','sha','inputs','architecture','images','build','toolchain')})
        if inputs!=runtime.source_hash():raise RuntimeError('Operational input changed during qualification')
        package_log=Path(first['evidence'])/'package.log'
        if package_log.exists():shutil.copyfile(package_log,output/'package.log')
        runtime.save(output/'qualification.json',dict(verdict='PASS',testedSha=tested_sha,dirty=dirty,inputs=inputs,
            inputsAfter=runtime.source_hash(),instance=instance,initialProject=first['project'],resetProject=reset['project'],
            qualification='Fresh databases/realm, existing host dependency caches, canonical distribution package only; no full reactor or Java test claim',
            gui='Native4.2.1 parser/request VM/test VM/HTTP transport; rendered login and runner require separate evidence',
            sourceHashes={str(p.relative_to(runtime.ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                [Path(__file__).resolve(),runtime.ROOT/'scripts/local/test_bruno_gui.js',runtime.ROOT/'scripts/local/test_gui_auth_protocol.py',runtime.ROOT/'scripts/local/test_bruno_qualification.py']}))
        print('I3 new-instance, delivered CLI, native4.2.1, technical requests and reset proof PASS: '+str(output))
    except Exception as error:
        runtime.save(output/'qualification.json',dict(verdict='FAIL',testedSha=tested_sha,dirty=dirty,inputs=inputs,
            errorClass=type(error).__name__,limitation='Inspect allowlisted command receipts; private instance diagnostics remain local.'))
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();execute(args.instance,args.output.resolve())
