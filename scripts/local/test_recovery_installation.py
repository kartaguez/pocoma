"""Opt-in real Docker recovery proof. Preserves demo; stops and retains the new fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
from unittest.mock import patch
from uuid import uuid4, UUID, uuid5

import runtime as rt
import recovery


def qualify(instance, fixture, output):
    if output.exists() or (rt.HOME_LOCAL/'instances'/fixture).exists() or fixture == instance:
        raise RuntimeError('Require NEW output and distinct NEW qualification instance')
    output.mkdir(parents=True)
    path=rt.HOME_LOCAL/'instances'/instance
    m=rt.load_manifest(path)
    if m['state'] != 'RUNNING':
        raise RuntimeError('Require running demo; initial state will be restored')
    inputs=rt.source_hash()
    commands=[]
    def retain(data, phase):
        destination=output/phase;destination.mkdir()
        rt.save(destination/'command-result.json',data)
        if not data.get('evidence'):
            return
        for name in ('receipt.json','start-attempt.json','tooling-refresh.json','worker-recovery.json',
                     'generation-before.json','public-requests.json','command-observations.json','failure.json',
                     'late-result.json','keycloak-auth.json','grafana.json','bruno-receipt.json','bruno.xml','scenarios.jsonl'):
            source=Path(data['evidence'])/name
            if source.exists():shutil.copyfile(source,destination/name)
    def command(action, phase, target=instance, extra=(), executable=None, expected=0):
        print('Running '+phase,flush=True)
        argv=[str(executable or rt.ROOT/'pocoma-local'),action,'--instance',target,*extra]
        began=time.monotonic()
        completed=subprocess.run(argv,text=True,capture_output=True,cwd=rt.ROOT,timeout=900)
        data=json.loads(completed.stdout[completed.stdout.index('{'):])
        commands.append(dict(command=argv,phase=phase,exit=completed.returncode,expected=expected,
                             seconds=time.monotonic()-began,result=data))
        rt.save(output/'commands.json',commands);retain(data,phase)
        if completed.returncode != expected:raise RuntimeError('Failed '+phase+': '+data.get('control',data['state']))
        return data
    def file_hashes():
        return {name:hashlib.sha256((path/name).read_bytes()).hexdigest()
                for name in ('secrets.json','demo.json',*[module+'.jar' for module in m['build']['jars']])}
    canary=str(uuid5(UUID(m['owner']),'canary'))
    def stable_data():
        result={}
        for db,table,condition in [('pocoma_write','v3_write.v3_pot_identity',f"WHERE pot_id <> '{canary}'"),
                                  ('pocoma','keycloak.user_entity',''),('pocoma','keycloak.credential',''),
                                  ('pocoma','keycloak.realm',''),('pocoma','keycloak.client','')]:
            raw=rt.sql(path,m,db,f'BEGIN READ ONLY; SELECT md5(row_to_json(t)::text) FROM {table} t {condition} ORDER BY 1; COMMIT;')
            result[table]=dict(count=len(raw.splitlines()),sha256=hashlib.sha256(raw.encode()).hexdigest(),rows=raw.splitlines())
        return result
    def resources():
        containers={}
        for resource in rt.run(['docker','ps','-aq']).splitlines():
            value,=rt.docker_json('inspect',resource)
            if value['Config'].get('Labels',{}).get(rt.LABEL) != m['owner']:
                containers[resource]=dict(name=value['Name'],state=value['State']['Status'],image=value['Image'])
        return dict(containers=containers,volumes=rt.run(['docker','volume','ls','-q']).splitlines())
    def lease_snapshot(fpath,fm):
        result={}
        for authority in ('write','read'):
            result[authority]=json.loads(rt.sql(fpath,fm,'pocoma_'+authority,f"""
                BEGIN READ ONLY; SELECT json_agg(json_build_object('consumptionId',consumption_id,
                'partitionId',partition_id,'ownedUntil',owned_until,
                'remainingSeconds',greatest(0,extract(epoch from owned_until-clock_timestamp()))))
                FROM v3_{authority}.partition_ownership; COMMIT;
            """))
        return result
    files_before=file_hashes();data_before=stable_data();external_before=resources()
    original_ids={name:rt.compose(path,m,'ps','-aq',name) for name in (*rt.RUNTIMES,'postgres','keycloak','prometheus','grafana')}
    # Only hashes of the pre-existing unpublished work, never stage or commit its bytes.
    dirty_paths=rt.run(['git','diff','--name-only']).splitlines()
    bruno_paths=[p for p in dirty_paths if p.startswith('scripts/bruno-playground/')
                 or p in ('docs/v3/L7_Local_Playground_Bruno.md','scripts/local/test_bruno_gui.js')]
    bruno_before={p:hashlib.sha256((rt.ROOT/p).read_bytes()).hexdigest() for p in bruno_paths}
    rt.save(output/'initial.json',dict(inputs=inputs,sha=rt.run(['git','rev-parse','HEAD']),dirty=True,
        files=files_before,data=data_before,containers=original_ids,external=external_before,unpublishedBruno=bruno_before))
    stopped_demo=False
    fixture_started=False
    try:
        nominal=command('start','demo-nominal')
        assert original_ids=={name:rt.compose(path,m,'ps','-aq',name) for name in original_ids}, 'Unjustified demo recreation'
        record=rt.read(output/'demo-nominal/worker-recovery.json')
        assert set(record['transitions'].values())=={'PRESERVED'}
        assert len(record['observations'])==1, 'Nominal start waited unnecessarily'
        command('status','demo-status')
        late=command('status','historical-late-result',extra=('--run','1794676508-e55784d4'))
        assert late['lateResult']['result']==dict(outcome='COMPLETED',resultingPotVersion=4)
        alias=Path(str(rt.ROOT).replace('/Dev/','/dev/'))/'pocoma-local'
        assert alias.samefile(rt.ROOT/'pocoma-local')
        command('start','demo-case-alias',executable=alias)
        assert original_ids=={name:rt.compose(path,m,'ps','-aq',name) for name in original_ids}
        command('bruno','demo-bruno')
        stopped_demo=True
        command('stop','demo-stop')
        fixture_started=True
        first=command('start','fixture-first',target=fixture)
        fpath=rt.HOME_LOCAL/'instances'/fixture;fm=rt.load_manifest(fpath)
        first_record=rt.read(output/'fixture-first/worker-recovery.json')
        assert set(first_record['transitions'].values())=={'CREATED'}
        assert first_record['seconds']<30, 'Fresh instance waited for nonexistent leases'
        command('status','fixture-first-status',target=fixture)
        before=rt.generations(fpath,fm)
        leases=lease_snapshot(fpath,fm)
        assert all(row['remainingSeconds']>0 for rows in leases.values() for row in rows)
        rt.save(output/'leases-before-recreation.json',dict(observedAt=time.time(),generation=before,leases=leases))
        original_compose=rt.compose
        injected=[]
        def force_workers(path_arg, manifest, *args, **kwargs):
            if args[:2]==('up','-d') and 'command-worker' in args:
                assert manifest['instance']==fixture
                # Force only the dedicated workers, DURING start, after its before snapshot.
                other=tuple(x for x in args[2:] if x not in recovery.PARTICIPANTS)
                original_compose(path_arg,manifest,'up','-d',*other)
                actual=('up','-d','--force-recreate',*recovery.PARTICIPANTS)
                injected.append(dict(observedAt=time.time(),arguments=list(actual)))
                return original_compose(path_arg,manifest,*actual,**kwargs)
            return original_compose(path_arg,manifest,*args,**kwargs)
        print('Running fixture recreation inside start; unchanged 240s leases',flush=True)
        with rt.locked(fixture),rt.lifecycle_lock('start'):
            run_id=str(int(time.time()))+'-'+uuid4().hex[:8]
            evidence=fpath/'runs'/run_id;evidence.mkdir()
            began=time.monotonic()
            try:
                with patch.object(rt,'compose',side_effect=force_workers):result=rt.start(fpath,fm,evidence)
                result.update(instance=fixture,evidence=str(evidence),sha=rt.run(['git','rev-parse','HEAD']),dirty=True)
                rt.save(evidence/'receipt.json',result)
            except Exception:
                rt.diagnostics(fpath,fm,evidence)
                raise
        commands.append(dict(command='runtime.start with explicit qualification-only Compose force-recreate hook',
            phase='fixture-recreated',exit=result['exit'],seconds=time.monotonic()-began,result=result,injected=injected))
        rt.save(output/'commands.json',commands);retain(result,'fixture-recreated')
        recovered=rt.read(evidence/'worker-recovery.json')
        observed=rt.read(evidence/'command-observations.json')
        assert result['state']=='READY' and result['generationQualified']
        assert all(recovered['transitions'][name]=='RECREATED' for name in recovery.PARTICIPANTS)
        assert all(before[name]['containerId']!=recovered['after'][name]['containerId'] for name in recovery.PARTICIPANTS)
        assert any(not row['qualified'] for row in recovered['observations'])
        assert recovered['observations'][-1]['qualified'] and recovered['seconds']>30
        assert observed['admissions'] and min(row['observedAt'] for row in observed['admissions'])>=recovered['completedAt']
        assert result['canaryProof']['completedAt']-max(row['observedAt'] for row in observed['admissions'])<30
        assert result['canaryProof']['version']==first['canaryProof']['version']+1
        pot=result['canaryProof']['potId']
        count=rt.sql(fpath,fm,'pocoma_write',f"BEGIN READ ONLY; SELECT count(*) FROM v3_write.v3_pot_event WHERE pot_id='{pot}' AND subject_version={result['canaryProof']['version']}; COMMIT;")
        assert count=='1', 'Duplicate mutation'
        command('status','fixture-recreated-status',target=fixture)
        command('bruno','fixture-bruno',target=fixture)
        rt.save(output/'recreation-summary.json',dict(state='PASS',generationDetected=True,leasesInitiallyLive=True,
            recoverySeconds=recovered['seconds'],recoveredAt=recovered['completedAt'],
            firstAdmissionAt=min(row['observedAt'] for row in observed['admissions']),
            canarySeconds=result['canaryProof']['completedAt']-max(row['observedAt'] for row in observed['admissions']),
            unchangedCanaryBudgetSeconds=30,mutationsAtResultingVersion=int(count)))
    finally:
        try:
            if fixture_started:
                command('stop','fixture-stop',target=fixture)
        finally:
            if stopped_demo and rt.load_manifest(path)['state']=='STOPPED':
                command('start','demo-restored')
    command('status','demo-final-status')
    after_data=stable_data();after_files=file_hashes();external_after=resources()
    assert files_before==after_files
    assert all((set(before['rows']).issubset(after_data[table]['rows']) if table=='v3_write.v3_pot_identity'
                else before==after_data[table]) for table,before in data_before.items())
    since=nominal['lastStart']['startedAt']
    changed=rt.sql(path,m,'pocoma_write',f"""BEGIN READ ONLY;
        SELECT count(*) FROM v3_write.v3_pot_event e
        JOIN v3_write.v3_pot_version v ON v.pot_id=e.pot_id AND v.version=1
        WHERE e.pot_id <> '{canary}' AND v.recorded_at < to_timestamp({since})
        AND e.recorded_at >= to_timestamp({since}); COMMIT;""")
    assert changed=='0', 'An existing non-canary Pot was mutated'
    rt.save(output/'existing-domain-preservation.json',dict(state='PASS',since=since,
        mutationsOnPreexistingNonCanaryPots=int(changed),
        method='Read-only event audit against version-1 creation time; canary excluded'))
    assert bruno_before=={p:hashlib.sha256((rt.ROOT/p).read_bytes()).hexdigest() for p in bruno_before}
    assert all(external_after['containers'].get(k)==v for k,v in external_before['containers'].items())
    assert set(external_before['volumes']).issubset(external_after['volumes'])
    assert inputs==rt.source_hash(), 'Inputs changed during qualification'
    rt.save(output/'preservation.json',dict(files=after_files,stableData=after_data,unpublishedBruno=bruno_before,
        externalContainersUnchanged=True,preexistingVolumesRetained=True,demoRestored='RUNNING',
        fixtureRetained='STOPPED; data and diagnostics preserved'))
    rt.save(output/'qualification.json',dict(state='PASS',inputs=inputs,sha=rt.run(['git','rev-parse','HEAD']),dirty=True,
        fixture=fixture,scenario='Actual worker recreation during start with unexpired ownership leases',
        checks=['warm start without recreation','case alias without recreation','fresh start','full partition coverage',
                '240s lease recovery before admission','unchanged 30s canary','one mutation','late-result GET only',
                'generation-qualified status','Keycloak','WRITE/READ','Prometheus','Grafana dashboards','Bruno','demo preservation']))
    print('Recovery Docker qualification PASS: '+str(output),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance',default='demo')
    parser.add_argument('--fixture',required=True)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    qualify(args.instance,args.fixture,args.output)
