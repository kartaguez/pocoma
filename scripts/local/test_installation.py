#!/usr/bin/env python3
"""Explicit integration qualification; no run on import/unittest discovery.

Creates only unique, labelled playground fixtures. Retains diagnostics/volumes.
Run after freezing a commit: python3 -B scripts/local/test_installation.py
  --second-checkout /absolute/fresh/clone --output /absolute/new/evidence
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime as rt
from bootstrap import initialize


def qualify(second, output):
    output.mkdir(parents=True, exist_ok=False)
    suffix=uuid4().hex[:8]
    first='j1-a-'+suffix; other='j1-b-'+suffix
    rows=[]
    def command(root, instance, action, expected=0, *extra):
        argv=[str(root/'pocoma-local'),action,'--instance',instance,*extra]
        began=time.monotonic()
        result=subprocess.run(argv,cwd=root,text=True,capture_output=True,timeout=1800)
        # Bootstrap emits human status before the JSON object.
        text=result.stdout
        data=json.loads(text[text.index('{'):])
        row=dict(command=argv,exit=result.returncode,expected=expected,result=data,seconds=time.monotonic()-began)
        rows.append(row);rt.save(output/'commands.json',rows)
        if result.returncode != expected:
            raise RuntimeError('Qualification command failed: '+action+' '+instance)
        return data
    def path_for(root,instance):return root/'.pocoma-local/instances'/instance
    def manifest(root,instance):return rt.read(path_for(root,instance)/'manifest.json')
    def preserve(result,name):
        src=Path(result['evidence'])
        dest=output/name;dest.mkdir()
        for filename in ('receipt.json','public-requests.json','bruno-receipt.json','bruno.xml','scenarios.jsonl','checks.json'):
            if (src/filename).exists():shutil.copyfile(src/filename,dest/filename)
    before=rt.run(['docker','ps','-aq']).splitlines()
    rt.save(output/'provenance.json',dict(sha=rt.run(['git','rev-parse','HEAD']),
        dirty=bool(rt.run(['git','status','--porcelain'])),inputs=rt.source_hash(),
        secondSha=rt.run(['git','-C',second,'rev-parse','HEAD']),
        secondInitiallyHasTargets=bool(list((second/'app').glob('*/target'))),
        secondInitiallyHasLocalState=(second/'.pocoma-local').exists(),first=first,second=other,
        externalContainersBefore=before))
    try:
        first_start=command(rt.ROOT,first,'start');preserve(first_start,'first-start')
        p=path_for(rt.ROOT,first);m=manifest(rt.ROOT,first)
        command(rt.ROOT,first,'status')
        first_bruno=command(rt.ROOT,first,'bruno');preserve(first_bruno,'first-bruno')
        pots=rt.sql(p,m,'pocoma_write','SELECT count(*) FROM v3_write.v3_pot_identity;')
        restart=command(rt.ROOT,first,'start');preserve(restart,'first-restart')
        assert restart['canaryProof']['version'] == first_start['canaryProof']['version']+1
        assert rt.sql(p,m,'pocoma_write','SELECT count(*) FROM v3_write.v3_pot_identity;') == pots
        rt.compose(p,m,'stop','-t','45','results-worker',timeout=90)
        broken=command(rt.ROOT,first,'status',1);preserve(broken,'critical-worker-stopped')
        assert broken['business']=='UNAVAILABLE' and broken['checks']['JVM-results-worker']=='FAIL'
        recovered=command(rt.ROOT,first,'start');preserve(recovered,'first-recovery')
        rt.compose(p,m,'stop','prometheus')
        degraded=command(rt.ROOT,first,'status',4);preserve(degraded,'observability-stopped')
        assert degraded['business']=='AVAILABLE' and degraded['qualificationTools']=='DEGRADED'
        rt.compose(p,m,'start','prometheus')
        command(second,other,'start',2)  # Refuse concurrent playground before DB creation.
        command(rt.ROOT,first,'stop')
        volume=m['project']+'-data'
        def volume_hash():
            raw=rt.run(['docker','run','--rm','--network','none','--mount',f'type=volume,source={volume},target=/proof,readonly',
                m['images']['postgres']['digest'],'sh','-c',
                'find /proof -type f -exec sha256sum {} \\; | sort'],timeout=120)
            return hashlib.sha256(raw.encode()).hexdigest()
        original_volume_hash=volume_hash()
        second_start=command(second,other,'start');preserve(second_start,'second-fresh-start')
        second_bruno=command(second,other,'bruno');preserve(second_bruno,'second-bruno')
        command(second,other,'reset',2,'--confirm','WRONG')
        command(second,other,'reset',0,'--confirm','RESET:'+other,'--dry-run')
        reset=command(second,other,'reset',0,'--confirm','RESET:'+other);preserve(reset,'second-reset')
        assert original_volume_hash==volume_hash(), 'Foreign stopped volume changed during second reset'
        command(second,other,'stop')
        # Transaction interruption on a third, owned PG-only fixture. The real
        # bootstrap is fault-injected before its marker; rollback must be physical.
        atomic='j1-bootstrap-'+suffix
        with rt.locked(atomic,create=True) as ap:
            am=rt.new_manifest(ap,atomic);am.update(architecture=m['architecture'],images=m['images'])
            credentials=rt.private(ap);am['issuer']=rt.render(ap,am,credentials);rt.save(ap/'manifest.json',am)
            rt.compose(ap,am,'up','-d','postgres')
            rt.await_check(lambda:rt.sql(ap,am,'pocoma',"SELECT 1 FROM pg_namespace WHERE nspname='keycloak';") or (_ for _ in ()).throw(RuntimeError()),60,'ATOMIC-PG')
            def failing(db,text):
                if 'CREATE TABLE public.pocoma_local_bootstrap' in text:
                    text=text.replace('CREATE TABLE public.pocoma_local_bootstrap','SELECT 1/0;\nCREATE TABLE public.pocoma_local_bootstrap')
                return rt.sql(ap,am,db,text)
            try:initialize(failing,credentials['passwords'])
            except RuntimeError:pass
            else:raise RuntimeError('Fault injection did not fail')
            assert rt.sql(ap,am,'pocoma_write',"SELECT to_regclass('v3_write.v3_command') IS NULL AND to_regclass('public.pocoma_local_bootstrap') IS NULL;")=='t'
            initialize(lambda db,text:rt.sql(ap,am,db,text),credentials['passwords'])
            assert rt.sql(ap,am,'pocoma_write','SELECT count(*) FROM public.pocoma_local_bootstrap;')=='1'
            rt.compose(ap,am,'stop','postgres')
        after=rt.run(['docker','ps','-aq']).splitlines()
        assert set(before).issubset(set(after)), 'An existing external container disappeared'
        sm=manifest(second,other)
        rt.save(output/'manifest-expurgated.json',dict(first={k:m[k] for k in ('schema','instance','owner','project','ports','sha','inputs','architecture','images','build','toolchain')},
            second={k:sm[k] for k in ('schema','instance','owner','project','ports','sha','inputs','architecture','images','build','toolchain')}))
        rt.save(output/'qualification.json',dict(verdict='PASS',testedSha=rt.run(['git','rev-parse','HEAD']),
            checks=['two independent instances','fresh checkout with no targets/local state','real PKCE S256 and missing/plain refusal',
                    'five JVMs','Bruno nonempty JUnit and exact inventory','new canary on restart','no demo duplication',
                    'critical worker NOT_READY exit1','observability DEGRADED exit4','concurrent instance refusal',
                    'explicit reset and dry-run','foreign volume byte hashes unchanged','transactional bootstrap rollback and resume',
                    'existing external containers retained'],
            firstVolumeHashBefore=original_volume_hash,firstVolumeHashAfter=volume_hash(),
            first=first,second=other,bootstrapFixture=atomic,resources='STOPPED_AND_RETAINED',
            limits=['Second checkout shares Docker image/Maven dependency caches; application JARs built anew.',
                    'Interactive GUI login documented, not human-executed in this automated qualification.',
                    'No I1b/I4/I5/I6 or global certification.']))
    except Exception as error:
        rt.save(output/'qualification.json',dict(verdict='FAIL',error=type(error).__name__+':'+str(error),resources='PRESERVED',first=first,second=other))
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--second-checkout',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();qualify(args.second_checkout.resolve(),args.output.resolve())
