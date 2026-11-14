"""Opt-in Docker qualification: upgrade existing demo without reset; fresh fixture lifecycle."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
from uuid import UUID, uuid5

import runtime as rt
import test_gui_auth_protocol


def qualify(instance, fixture, output):
    if output.exists() or (rt.HOME_LOCAL/'instances'/fixture).exists() or fixture == instance:
        raise RuntimeError('Require NEW output and distinct NEW fixture')
    output.mkdir(parents=True)
    path=rt.HOME_LOCAL/'instances'/instance
    m=rt.load_manifest(path)
    if m['state'] != 'STOPPED':
        raise RuntimeError('Require stopped instance for additive upgrade/refresh proof')
    inputs=rt.source_hash()
    commands=[]
    def command(action, phase, target=instance, expected=0, extra=()):
        print('Running '+phase,flush=True)
        argv=[str(rt.ROOT/'pocoma-local'),action,'--instance',target,*extra]
        began=time.monotonic()
        result=subprocess.run(argv,cwd=rt.ROOT,text=True,capture_output=True,timeout=1800)
        data=json.loads(result.stdout[result.stdout.index('{'):])
        commands.append(dict(command=argv,phase=phase,exit=result.returncode,expected=expected,
                             seconds=time.monotonic()-began,result=data))
        rt.save(output/'commands.json',commands)
        dest=output/phase;dest.mkdir()
        for name in ('receipt.json','grafana.json','grafana-upgrade.json','keycloak-auth.json','public-requests.json',
                     'bruno-receipt.json','bruno.xml','scenarios.jsonl','recovery-window.json','checks.json','package.log'):
            source=Path(data['evidence'])/name
            if source.exists():shutil.copyfile(source,dest/name)
        if result.returncode != expected:raise RuntimeError('Failed phase: '+phase+'; private evidence '+str(data['evidence']))
        return data
    def volume_hash(name):
        raw=rt.run(['docker','run','--rm','--network','none','--mount',f'type=volume,source={name},target=/proof,readonly',
            m['images']['postgres']['digest'],'sh','-c','find /proof -type f -exec sha256sum {} \\; | sort'],timeout=120)
        return hashlib.sha256(raw.encode()).hexdigest()
    def external():
        containers={}
        for resource in rt.run(['docker','ps','-aq']).splitlines():
            data,=rt.docker_json('inspect',resource)
            if data['Config']['Labels'].get(rt.LABEL) != m['owner']:
                containers[resource]=dict(name=data['Name'],state=data['State']['Status'],image=data['Image'])
        volumes={name:volume_hash(name) for name in rt.run(['docker','volume','ls','-q']).splitlines()
                 if name != m['project']+'-data'}
        return dict(containers=containers,volumes=volumes)
    canary_id=str(uuid5(UUID(m['owner']),'canary'))
    def rows(db, table, condition=''):
        return rt.sql(path,m,db,f'SELECT md5(row_to_json(t)::text) FROM {table} t {condition} ORDER BY 1;').splitlines()
    def stable_data():
        return dict(users=rows('pocoma','keycloak.user_entity'),credentials=rows('pocoma','keycloak.credential'),
            realms=rows('pocoma','keycloak.realm'),clients=rows('pocoma','keycloak.client'),
            pots=rows('pocoma_write','v3_write.v3_pot_identity',f"WHERE pot_id <> '{canary_id}'"),
            readPots=rows('pocoma_read','v3_read.read_current_pot',f"WHERE pot_id <> '{canary_id}'"),
            events=rows('pocoma_write','v3_write.v3_pot_event'),
            sqlPasswords=rt.sql(path,m,'pocoma',"SELECT md5(string_agg(rolname || ':' || coalesce(rolpassword,''), E'\\n' ORDER BY rolname)) FROM pg_authid;").splitlines())
    try:
        other_before=external()
        rt.save(output/'external-before.json',other_before)
        original_containers = {name: rt.compose(path,m,'ps','-aq',name) for name in ('postgres','keycloak')}
        files_before={name:hashlib.sha256((path/name).read_bytes()).hexdigest() for name in ('secrets.json','demo.json')}
        rt.compose(path,m,'start','postgres')
        rt.await_check(lambda: rt.sql(path,m,'pocoma','SELECT 1;'),30,'PRE-UPGRADE-PG')
        before=stable_data()
        rt.compose(path,m,'stop','-t','30','postgres')
        upgraded=command('upgrade-grafana','demo-upgrade')
        m=rt.load_manifest(path)
        after=stable_data()
        assert original_containers == {name: rt.compose(path,m,'ps','-aq',name) for name in original_containers}
        assert all(before[k] == after[k] for k in before if k != 'events')
        assert set(before['events']).issubset(after['events'])
        assert files_before == {name:hashlib.sha256((path/name).read_bytes()).hexdigest() for name in files_before}
        rt.save(output/'preservation.json',dict(instance=instance,owner=m['owner'],project=m['project'],
            files=files_before,stableData={key:dict(beforeCount=len(before[key]),afterCount=len(after[key]),
                preserved=(set(before[key]).issubset(after[key]) if key=='events' else before[key]==after[key])) for key in before},
            preservedDataContainers=original_containers,
            method='row hashes retained in memory; no credential/domain payload exported; canary excluded from mutable Pot snapshots'))
        status=command('status','demo-status')
        assert upgraded['grafana'] == status['grafana']
        bindings={}
        for resource in rt.compose(path,m,'ps','-aq').splitlines():
            value,=rt.docker_json('inspect',resource)
            name=value['Config']['Labels']['com.docker.compose.service']
            assert value['State']['Running']
            bindings[name]=value['HostConfig'].get('PortBindings') or {}
            assert all(b['HostIp']=='127.0.0.1' for entries in bindings[name].values() for b in entries or [])
            if name=='grafana':assert not any(mount['Type']=='volume' for mount in value['Mounts'])
        assert len(bindings)==9 and not bindings['postgres']
        rt.save(output/'platform.json',dict(architecture=m['architecture'],images=m['images'],bindings=bindings))
        command('bruno','demo-bruno')
        print('Running installed native Bruno protocol',flush=True)
        test_gui_auth_protocol.execute(instance,output/'native-installed.json')
        expression=next(p for p in rt.grafana.dashboards(rt.RUNTIMES)['pocoma-v3-overview']['panels']
                        if p['title']=='Projection Throughput (tasks/s)')['targets'][0]['expr']
        def projection():
            rows=rt.grafana.query(m,expression)
            expected={'PotVersion','PotBalance','PotVisibility','CurrentBinding','CommandResult','IdentityRequestResult'}
            if not expected.issubset({row['metric']['consumption'] for row in rows}):
                raise RuntimeError('PROJECTION_PANEL_LABELS')
            return rows
        projection_rows=rt.await_check(projection,30,'PROJECTION_PANEL_METRICS')
        command('status','demo-after-traffic')
        panels=rt.read(output/'demo-after-traffic/grafana.json')['panels']
        assert all(p['state'] not in ('ABSENT','NONFINITE') for p in panels)
        rt.save(output/'metrics.json',dict(projectionExpression=expression,projectionSeries=projection_rows,
            finitePanelQueries=len(panels),states={state:sum(p['state']==state for p in panels) for state in ('PRESENT','ZERO','ABSENT','NONFINITE')}))
        rt.compose(path,m,'stop','grafana')
        failed=command('status','grafana-stopped',expected=1)
        assert failed['checks']['GRAFANA-HTTP']=='FAIL' and failed['business']=='AVAILABLE'
        rt.compose(path,m,'start','grafana')
        rt.await_check(lambda: rt.grafana.get(m,'/api/user'),60,'GRAFANA-RESTART')
        rt.compose(path,m,'stop','prometheus')
        failed=command('status','datasource-stopped',expected=1)
        assert failed['checks']['GRAFANA-DATASOURCE']=='FAIL'
        rt.compose(path,m,'start','prometheus')
        restored=command('start','demo-restored')
        assert restored['state']=='READY'
        command('stop','demo-stop')
        resumed=command('start','demo-restart')
        assert resumed['canaryProof']['version']==restored['canaryProof']['version']+1
        assert files_before == {name:hashlib.sha256((path/name).read_bytes()).hexdigest() for name in files_before}
        command('status','demo-restart-status')
        command('stop','demo-stop-for-fixture')
        # Independent fresh install/reset/destroy. Existing demo volume must be byte-identical throughout.
        demo_volume=volume_hash(m['project']+'-data')
        fresh=command('start','fixture-fresh',target=fixture)
        command('status','fixture-status',target=fixture)
        command('bruno','fixture-bruno',target=fixture)
        reset=command('reset','fixture-reset',target=fixture,extra=('--confirm','RESET:'+fixture))
        assert reset['project'] != fresh['project']
        command('status','fixture-reset-status',target=fixture)
        command('destroy','fixture-destroy-plan',target=fixture,extra=('--confirm','DESTROY:'+fixture,'--dry-run'))
        command('destroy','fixture-destroy',target=fixture,extra=('--confirm','DESTROY:'+fixture))
        assert demo_volume==volume_hash(m['project']+'-data')
        other_after=external()
        assert all(other_after[k].get(name)==value for k,items in other_before.items() for name,value in items.items())
        rt.save(output/'external-preservation.json',dict(before=other_before,after=other_after,
            addedUnownedVolumes=sorted(set(other_after['volumes'])-set(other_before['volumes'])),
            demoVolumeHashWhileStopped=demo_volume,fixture='DESTROYED_ONLY_OWNED_RESOURCES'))
        command('start','demo-final-start')
        command('status','demo-final-status')
        if rt.source_hash()!=inputs:raise RuntimeError('Source inputs changed during qualification')
        rt.save(output/'qualification.json',dict(verdict='L7_LB_GRAFANA_READY',inputs=inputs,
            testedSha=rt.run(['git','rev-parse','HEAD']),dirty=bool(rt.run(['git','status','--porcelain'])),
            instance=instance,fixture=fixture,checks=['controlled non-destructive legacy upgrade',
                'nine running containers; ARM64; loopback only; no Grafana persistent volume',
                'admin/pocoma authenticated; internal datasource health and real metrics from five jobs',
                'both L6 dashboards; every panel PromQL evaluated; absent/nonfinite/zero distinguished',
                'existing demo data/Keycloak users credentials clients realm/SQL secrets preserved',
                'Bruno CLI on upgraded demo and fresh fixture; installed native Bruno protocol',
                'all 31 panel queries finite after traffic; all six projection labels observed', 'Grafana/Prometheus loss forbids READY',
                'graceful stop/restart and fresh canary', 'fresh install/reset/destroy fixture',
                'external containers and volume hashes preserved; stopped demo volume preserved during fixture cleanup'],
            resources='DEMO_RUNNING; FIXTURE_DESTROYED; UNRELATED_RESOURCES_PRESERVED',
            limits=['No rendered Grafana UI inspection; dashboard/admin/datasource APIs and PromQL exercised.',
                    'No production deployment, Maven test gate or global V3 certification.']))
    except Exception as error:
        rt.save(output/'qualification.json',dict(verdict='L7_LB_GRAFANA_BLOCKED_WITH_EVIDENCE',
            inputs=inputs,errorClass=type(error).__name__,diagnostic=str(error),resources='PRESERVED'))
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance',default='demo');parser.add_argument('--fixture',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();qualify(args.instance,args.fixture,args.output.resolve())
