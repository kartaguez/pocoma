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
import sys
import re
sys.dont_write_bytecode = True
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EXCLUSIONS = ('docs/testing/evidence/L1-AB/', 'docs/v3/L1_AB_Execution_Report.md', 'docs/v3/V3_Canon_and_Roadmap.md', 'docs/testing/evidence/L1-C/', 'docs/v3/L1_C_Execution_Report.md', 'docs/testing/evidence/L1-DE/', 'docs/v3/L1_D_Execution_Report.md', 'docs/v3/L1_E_Execution_Report.md')

def excluded(name):
    return any(name == item or (item.endswith('/') and name.startswith(item)
        and Path(name).suffix in ('.json','.md','.log','.txt','.xml','.gz','.sha256','.csv')) for item in EXCLUSIONS)

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
    dirty=git('diff','--name-only').decode().splitlines()
    if any(not excluded(name) for name in dirty): raise RuntimeError('Tracked input changes, including file mode, are not allowed')
    staged = git('diff','--cached','--name-only').decode().splitlines()
    if any(not excluded(n) for n in staged):
        raise RuntimeError('Executable index changes are not allowed')
    return {'revision': revision, 'verified_files':len(expected), 'input_digest':digest(json.dumps(expected,sort_keys=True).encode()), 'verdict':'PASS'}

def capture(command):
    result = subprocess.run(command,cwd=ROOT,text=True,capture_output=True)
    return dict(command=command,exit_code=result.returncode,stdout=result.stdout,stderr=result.stderr)

def external_inputs():
    """Identity of tool binaries, distributions, configuration and cached resolved libraries."""
    java=capture(['java','-XshowSettings:properties','-version'])
    maven=capture(['./app/mvnw','-version'])
    if java['exit_code'] or maven['exit_code']: raise RuntimeError('Unavailable build tool')
    home=re.search(r'java.home = (.+)',java['stderr']).group(1).strip()
    mhome=re.search(r'Maven home: (.+)',maven['stdout']).group(1).strip()
    files=[Path(home)/'release',Path(home)/'bin/java',Path(home)/'lib/modules',
           Path(mhome)/'bin/mvn',Path(mhome)/'conf/settings.xml']
    files+=list((Path(mhome)/'lib').glob('*.jar'))
    files+=[path for folder in ('bin','conf','lib') for path in (Path(home)/folder).rglob('*') if path.is_file() and (folder!='lib' or path.suffix in ('.so','.dylib'))]
    files.append(Path(sys.executable).resolve())
    files+=[Path.home()/'.m2/settings.xml',Path.home()/'.m2/toolchains.xml',Path.home()/'.testcontainers.properties']
    files+=list((Path.home()/'.m2/repository').rglob('*.jar'))
    files+=list((Path.home()/'.m2/repository').rglob('*.pom'))
    result={str(path):digest(path.read_bytes()) if path.is_file() else 'ABSENT' for path in files}
    result['docker:postgres:17-alpine']=digest(json.dumps(capture(['docker','image','inspect','postgres:17-alpine','--format','{{json .Id}}']),sort_keys=True).encode())
    return result

def verify_external(before,after,used_jars):
    # A newly resolved dependency needs a warm-up followed by a fresh gate: never attest a changed input.
    changes=[name for name in set(before)|set(after) if before.get(name)!=after.get(name)]
    if changes: raise RuntimeError('External build inputs changed: '+str(sorted(changes)))
    if any(before.get(name)!=value for name,value in used_jars.items()):
        raise RuntimeError('Classpath dependency differs from the before-gate inventory')
    return {'verdict':'PASS','external_files':len(before),'used_jars':len(used_jars)}

def run(gate):
    manifest=json.loads((ROOT/'docs/v3/L1_Proof_Manifest.json').read_text())
    definition=manifest['gates'][gate]
    revision=git('rev-parse','HEAD').decode().strip()
    expected_inputs=inputs(revision)
    before=compare(revision,expected_inputs)
    family='L1-DE' if gate.startswith(('d-','e-')) else ('L1-C' if gate.startswith('c-') else 'L1-AB')
    evidence=ROOT/'docs/testing/evidence'/family/f'{gate}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    (evidence/'inputs.json').write_text(json.dumps(expected_inputs,indent=2)+'\n')
    (evidence/'before.json').write_text(json.dumps(before,indent=2)+'\n')
    shutil.copy2(ROOT/'docs/v3/L1_Proof_Manifest.json',evidence/'proof-manifest.json')
    environment={key:capture(command) for key,command in {
        'java':['java','-version'], 'maven':['./app/mvnw','-version'],
        'docker':['docker','version','--format','{{.Server.Version}}'],
        'postgres_image':['docker','image','inspect','postgres:17-alpine','--format','{{json .RepoDigests}}'],
        'python':['python3','--version'],
        'postgres_image_identity':['docker','image','inspect','postgres:17-alpine','--format','{{json .Id}}'],
        'java_properties':['java','-XshowSettings:properties','-version']}.items()}
    environment['options']={k:os.environ.get(k) for k in ('JAVA_HOME','MAVEN_OPTS','MAVEN_ARGS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','DOCKER_HOST','TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE','TZ','LANG','LC_ALL','SPRING_PROFILES_ACTIVE')}
    (evidence/'environment.json').write_text(json.dumps(environment,indent=2)+'\n')
    # Fresh compilation as well as fresh reports: generated bytecode cannot leak from an older run.
    for directory in (ROOT/'app').glob('*/target'):
        shutil.rmtree(directory)
    external_before=external_inputs()
    (evidence/'external-before.json').write_text(json.dumps(external_before,indent=2)+'\n')
    started=time.time_ns()
    print(f'{gate}: {revision}, {evidence}',flush=True)
    with (evidence/'maven.log').open('wb') as log:
        result=subprocess.run(definition['command'],cwd=ROOT/'app',stdout=log,stderr=subprocess.STDOUT)
    ended=time.time_ns()
    after=compare(revision,expected_inputs)
    (evidence/'after.json').write_text(json.dumps(after,indent=2)+'\n')
    reports=[]
    jars={}
    classpaths={}
    for path in (ROOT/'app').glob('*/target/surefire-reports/TEST-*.xml'):
        content=path.read_bytes(); name=str(path.relative_to(ROOT))
        reports.append((name,content,path.stat().st_mtime_ns))
        target=evidence/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(content)
        for prop in ET.fromstring(content).findall('./properties/property'):
            if prop.get('name') in ('java.class.path','surefire.test.class.path'):
                for entry in prop.get('value','').split(os.pathsep):
                    dependency=Path(entry)
                    if entry and not entry.endswith('.jar'):
                        resolved=dependency.resolve()
                        relative=str(resolved.relative_to(ROOT/'app')) if resolved.is_relative_to(ROOT/'app') else ''
                        if '/target/' not in relative or not relative.endswith(('/classes','/test-classes')):
                            raise RuntimeError('Uninventoried classpath directory: '+entry)
                        classpaths[entry]={'origin':'fresh Maven output','exists':dependency.is_dir()}
                    if entry not in jars and dependency.is_file() and dependency.suffix=='.jar': jars[entry]=digest(dependency.read_bytes())
    (evidence/'classpaths.json').write_text(json.dumps(classpaths,indent=2)+'\n')
    external_after=external_inputs()
    external_verdict=verify_external(external_before,external_after,jars)
    (evidence/'external-after.json').write_text(json.dumps(external_after,indent=2)+'\n')
    (evidence/'external-verdict.json').write_text(json.dumps(external_verdict,indent=2)+'\n')
    (evidence/'dependencies.json').write_text(json.dumps(jars,indent=2)+'\n')
    generated={str(path.relative_to(ROOT)):digest(path.read_bytes()) for pattern in ('*/target/classes/**/*','*/target/test-classes/**/*') for path in (ROOT/'app').glob(pattern) if path.is_file()}
    (evidence/'generated-inputs.json').write_text(json.dumps(generated,indent=2)+'\n')
    for directory in (ROOT/'app').glob('*/target/v3-pot-evidence'):
        shutil.copytree(directory,evidence/directory.relative_to(ROOT))
    external={str(path):digest(path.read_bytes()) for path in (Path.home()/'.m2/settings.xml',Path.home()/'.m2/toolchains.xml') if path.is_file()}
    (evidence/'external-configuration-digests.json').write_text(json.dumps(external,indent=2)+'\n')
    spec=importlib.util.spec_from_file_location('l0',ROOT/'scripts/testing/summarize-v3.py')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    expected={(suite['class'],method) for key in definition['suites'] for suite in [manifest['suites'][key]] for method in suite['methods']}
    summary=module.assess_reports(reports,expected,started,result.returncode)
    mandatory={identity:sum(item['classname']==identity[0] and item['method']==identity[1] and item['outcome']=='PASS' for item in summary['executions']) for identity in expected}
    summary['mandatory_verdict']='PASS' if result.returncode==0 and all(number==1 for number in mandatory.values()) and expected else 'FAIL'
    summary['mandatory_methods']=[{'class':identity[0],'method':identity[1],'fresh_pass_executions':number} for identity,number in sorted(mandatory.items())]
    summary.update(gate=gate,command=definition['command'],cwd='app',revision=revision,started_ns=started,ended_ns=ended,exit_code=result.returncode,input_verification=after,exclusions=EXCLUSIONS)
    (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    receipt={str(path.relative_to(evidence)):digest(path.read_bytes()) for path in evidence.rglob('*') if path.is_file()}
    (evidence/'receipt.json').write_text(json.dumps(receipt,sort_keys=True,indent=2)+'\n')
    print(json.dumps({k:summary[k] for k in ('verdict','counts','problems')},indent=2),flush=True)
    return 0 if summary['verdict']=='PASS' else 1

def validate_final_gates(tested, proof_index):
    manifest_path=ROOT/'docs/v3/L1_Proof_Manifest.json'
    if not manifest_path.is_file() or 'e-full' not in json.loads(manifest_path.read_text())['gates']:
        return []
    if proof_index is None: raise RuntimeError('Final L1 publication requires its gate index')
    index=json.loads(Path(proof_index).read_text())
    required={'e-consumers','e-arch','e-full'}
    entries={entry['gate']:entry for entry in index['gates'] if entry.get('retained')}
    if not required<=entries.keys(): raise RuntimeError('Missing final gates')
    expected=inputs(tested); expected_digest=digest(json.dumps(expected,sort_keys=True).encode())
    verified=[]
    for gate in sorted(required):
        directory=ROOT/entries[gate]['directory']
        receipt=json.loads((directory/'receipt.json').read_text())
        for name,value in receipt.items():
            if digest((directory/name).read_bytes())!=value: raise RuntimeError('Altered gate evidence: '+name)
        summary=json.loads((directory/'summary.json').read_text())
        if summary['revision']!=tested or summary['exit_code'] or summary['mandatory_verdict']!='PASS':
            raise RuntimeError('Final gate did not pass on tested revision: '+gate)
        if summary['counts']['failures'] or summary['counts']['errors']: raise RuntimeError('Gate failure')
        if summary['problems'] != (['Skipped tests prevent complete gate coverage'] if summary['counts']['skipped'] else []):
            raise RuntimeError('Unexplained incomplete final gate coverage')
        manifest=json.loads(manifest_path.read_text())
        definition=manifest['gates'][gate]
        if summary['command']!=definition['command']: raise RuntimeError('Noncanonical final gate command')
        expected_methods={(manifest['suites'][key]['class'],method) for key in definition['suites'] for method in manifest['suites'][key]['methods']}
        actual_methods={(method['class'],method['method']) for method in summary['mandatory_methods'] if method['fresh_pass_executions']==1}
        if actual_methods!=expected_methods: raise RuntimeError('Final gate mandatory methods differ from committed manifest')
        for skip in summary['skips']:
            if skip['classname']!='com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest' or skip['method']!='zExploratoryContentionSeparateFromCorrectness':
                raise RuntimeError('Unexpected skip in final proof')
        if json.loads((directory/'inputs.json').read_text())!=expected: raise RuntimeError('Gate inputs differ from tested tree')
        for phase in ('before','after'):
            witness=json.loads((directory/(phase+'.json')).read_text())
            if witness['revision']!=tested or witness['verdict']!='PASS' or witness['input_digest']!=expected_digest:
                raise RuntimeError('Missing input identity '+phase)
        if json.loads((directory/'external-before.json').read_text())!=json.loads((directory/'external-after.json').read_text()):
            raise RuntimeError('External tool or dependency changed')
        verified.append({'gate':gate,'revision':tested,'mandatory_methods':len(summary['mandatory_methods']),'counts':summary['counts'],'global_coverage':summary['verdict'],'receipt_sha256':digest((directory/'receipt.json').read_bytes())})
    return verified

def publication(tested,published,remote,proof_index=None):
    verified_gates=validate_final_gates(tested,proof_index)
    git('merge-base','--is-ancestor',tested,published)
    history_changes={}
    for revision in git('rev-list','--ancestry-path',tested+'..'+published).decode().splitlines():
        names=git('diff-tree','--no-commit-id','--name-only','-r',revision).decode().splitlines()
        if any(not excluded(name) for name in names): raise RuntimeError('Untested publication commit history: '+revision)
        history_changes[revision]=names
    expected=inputs(tested)
    changes=git('diff','--name-only',tested,published).decode().splitlines()
    forbidden=[name for name in changes if not excluded(name)]
    if forbidden or inputs(published)!=expected: raise RuntimeError('Untested publication inputs: '+str(forbidden))
    head=git('ls-remote','origin','refs/heads/feat/v3-architecture').decode().split()[0] if remote else None
    if remote and head!=published: raise RuntimeError('Remote differs from published commit')
    result=dict(tested=tested,published=published,remote=head,verified_final_gates=verified_gates,documentary_commit_history=history_changes,changes=changes,exclusions=EXCLUSIONS,verdict='PASS',input_digest=digest(json.dumps(expected,sort_keys=True).encode()))
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--gate'); parser.add_argument('--tested'); parser.add_argument('--published'); parser.add_argument('--remote',action='store_true'); parser.add_argument('--proof-index'); args=parser.parse_args()
    if args.gate: raise SystemExit(run(args.gate))
    publication(args.tested,args.published,args.remote,args.proof_index)
