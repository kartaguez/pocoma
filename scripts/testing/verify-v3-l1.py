#!/usr/bin/env python3
"""Bounded L1 gates, fresh XML and tested-commit correspondence. Does not certify V3 globally."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EXCLUSIONS = ('docs/testing/evidence/L1-AB/', 'docs/v3/L1_AB_Execution_Report.md', 'docs/v3/V3_Canon_and_Roadmap.md')

def excluded(name):
    return any(name == item or (item.endswith('/') and name.startswith(item)) for item in EXCLUSIONS)

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def inputs(revision):
    entries = {}
    for name in git('ls-tree','-r','--name-only',revision).decode().splitlines():
        if not excluded(name):
            entries[name] = digest(git('show', f'{revision}:{name}'))
    return entries

def compare(revision, expected):
    actual = {}
    names = git('ls-files','--cached','--others','--exclude-standard','-z').decode().split('\0')
    for name in names:
        if name and not excluded(name):
            path = ROOT / name
            actual[name] = digest(path.read_bytes().replace(b'\r\n', b'\n')) if path.is_file() and name == 'app/mvnw.cmd' else (digest(path.read_bytes()) if path.is_file() else 'DELETED')
    if actual != expected:
        raise RuntimeError('Input mismatch: ' + str(sorted(k for k in set(actual)|set(expected) if actual.get(k)!=expected.get(k))))
    if git('rev-parse','HEAD').decode().strip() != revision:
        raise RuntimeError('HEAD moved during gate')
    staged = git('diff','--cached','--name-only').decode().splitlines()
    if any(not excluded(n) for n in staged):
        raise RuntimeError('Executable index changes are not allowed')
    return {'revision': revision, 'verified_files':len(expected), 'input_digest':digest(json.dumps(expected,sort_keys=True).encode()), 'verdict':'PASS'}

def capture(command):
    result = subprocess.run(command,cwd=ROOT,text=True,capture_output=True)
    return dict(command=command,exit_code=result.returncode,stdout=result.stdout,stderr=result.stderr)

def run(gate):
    manifest=json.loads((ROOT/'docs/v3/L1_Proof_Manifest.json').read_text())
    definition=manifest['gates'][gate]
    revision=git('rev-parse','HEAD').decode().strip()
    expected_inputs=inputs(revision)
    before=compare(revision,expected_inputs)
    evidence=ROOT/'docs/testing/evidence/L1-AB'/f'{gate}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    (evidence/'inputs.json').write_text(json.dumps(expected_inputs,indent=2)+'\n')
    (evidence/'before.json').write_text(json.dumps(before,indent=2)+'\n')
    shutil.copy2(ROOT/'docs/v3/L1_Proof_Manifest.json',evidence/'proof-manifest.json')
    environment={key:capture(command) for key,command in {
        'java':['java','-version'], 'maven':['./app/mvnw','-version'],
        'docker':['docker','version','--format','{{.Server.Version}}'],
        'postgres_image':['docker','image','inspect','postgres:17-alpine','--format','{{json .RepoDigests}}'],
        'python':['python3','--version']}.items()}
    environment['options']={k:os.environ.get(k) for k in ('JAVA_HOME','MAVEN_OPTS','JAVA_TOOL_OPTIONS','DOCKER_HOST','TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')}
    (evidence/'environment.json').write_text(json.dumps(environment,indent=2)+'\n')
    for directory in (ROOT/'app').glob('*/target/surefire-reports'):
        shutil.rmtree(directory)
    started=time.time_ns()
    print(f'{gate}: {revision}, {evidence}',flush=True)
    with (evidence/'maven.log').open('wb') as log:
        result=subprocess.run(definition['command'],cwd=ROOT/'app',stdout=log,stderr=subprocess.STDOUT)
    ended=time.time_ns()
    after=compare(revision,expected_inputs)
    (evidence/'after.json').write_text(json.dumps(after,indent=2)+'\n')
    reports=[]
    jars={}
    for path in (ROOT/'app').glob('*/target/surefire-reports/TEST-*.xml'):
        content=path.read_bytes(); name=str(path.relative_to(ROOT))
        reports.append((name,content,path.stat().st_mtime_ns))
        target=evidence/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(content)
        for prop in ET.fromstring(content).findall('./properties/property'):
            if prop.get('name') in ('java.class.path','surefire.test.class.path'):
                for entry in prop.get('value','').split(os.pathsep):
                    dependency=Path(entry)
                    if dependency.is_file() and dependency.suffix=='.jar': jars[entry]=digest(dependency.read_bytes())
    (evidence/'dependencies.json').write_text(json.dumps(jars,indent=2)+'\n')
    spec=importlib.util.spec_from_file_location('l0',ROOT/'scripts/testing/summarize-v3.py')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    expected={(suite['class'],method) for key in definition['suites'] for suite in [manifest['suites'][key]] for method in suite['methods']}
    summary=module.assess_reports(reports,expected,started,result.returncode)
    summary.update(gate=gate,command=definition['command'],cwd='app',revision=revision,started_ns=started,ended_ns=ended,exit_code=result.returncode,input_verification=after,exclusions=EXCLUSIONS)
    (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({k:summary[k] for k in ('verdict','counts','problems')},indent=2),flush=True)
    return 0 if summary['verdict']=='PASS' else 1

def publication(tested,published,remote):
    expected=inputs(tested)
    changes=git('diff','--name-only',tested,published).decode().splitlines()
    forbidden=[name for name in changes if not excluded(name)]
    if forbidden or inputs(published)!=expected: raise RuntimeError('Untested publication inputs: '+str(forbidden))
    head=git('ls-remote','origin','refs/heads/feat/v3-architecture').decode().split()[0] if remote else None
    if remote and head!=published: raise RuntimeError('Remote differs from published commit')
    result=dict(tested=tested,published=published,remote=head,changes=changes,exclusions=EXCLUSIONS,verdict='PASS',input_digest=digest(json.dumps(expected,sort_keys=True).encode()))
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--gate'); parser.add_argument('--tested'); parser.add_argument('--published'); parser.add_argument('--remote',action='store_true'); args=parser.parse_args()
    if args.gate: raise SystemExit(run(args.gate))
    publication(args.tested,args.published,args.remote)
