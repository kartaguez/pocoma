#!/usr/bin/env python3
"""Fresh bounded I5 evidence; no inherited PASS or inferred physical resolution."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'docs/v3/L2_I5_Proof_Manifest.json'
ANCHORS = {'C': 'runtime-command-consumption-worker', 'R': 'infra-persistence-read-jdbc',
           'M': 'runtime-monolith', 'A': 'architecture-tests', 'F': None}
NAMES = {'C':'command','R':'read','M':'monolith','A':'architecture','F':'architecture'}
SPEC = importlib.util.spec_from_file_location('i4', Path(__file__).with_name('verify-v3-l2-i4.py'))
I4 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(I4)


def methods(manifest, gate):
    required = {}
    for name, values in manifest['required_methods'].items():
        module = manifest['method_modules'][name]
        if gate == 'F' or module in manifest['gate_modules'][gate]:
            required[manifest['class_names'][name]] = set(values)
    for filename in manifest['inherited_manifests']:
        inherited = json.loads((MANIFEST.parent / filename).read_text())
        for group in inherited['gate_groups'].get(NAMES[gate], []):
            for name, values in inherited['required_groups'][group].items():
                required.setdefault(name, set()).update(values)
    return {name:sorted(values) for name,values in required.items()}


def physical_problems(folder, requirements, started):
    errors = []
    for item in requirements:
        matches = list(folder.glob(item['glob']))
        if not matches:
            errors.append('Missing physical variant ' + item['id'])
        for file in matches:
            if file.stat().st_mtime < started - 1:
                errors.append('Stale physical variant ' + item['id'])
            content = file.read_text(errors='replace')
            for marker in item.get('contains', []):
                if marker not in content:
                    errors.append('Missing physical marker ' + item['id'] + ':' + marker)
    return errors


def run(gate, destination):
    dirty = subprocess.check_output(['git','status','--porcelain','--','app','scripts'],cwd=ROOT,text=True)
    if dirty:
        raise ValueError('Freeze app/scripts before evidence gate')
    destination.mkdir(parents=True, exist_ok=False)
    sha = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    inputs = I4.input_hashes()
    manifest = json.loads(MANIFEST.read_text())
    inherited_hashes = {name:I4.digest(MANIFEST.parent/name) for name in manifest['inherited_manifests']}
    started = time.time()
    command = ['./mvnw'] + (['-pl', ANCHORS[gate], '-am'] if ANCHORS[gate] else []) + ['test']
    log = destination/'maven.log'
    with log.open('w') as output:
        code = subprocess.run(command,cwd=ROOT/'app',stdout=output,stderr=subprocess.STDOUT).returncode
    cases, artifacts = [], []
    modules = set()
    for file in (ROOT/'app').glob('*/target/surefire-reports/TEST-*.xml'):
        if file.stat().st_mtime < started - 1:
            continue
        modules.add(file.parent.parent.parent.name)
        cases.extend(I4.qualified_cases(file))
        target = destination/'xml'/file.parent.parent.parent.name/file.name
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
    for module in (ROOT/'app').iterdir():
        for source in (module/'target').glob('v3-*-evidence/**/*'):
            if source.is_file() and source.stat().st_mtime >= started - 1:
                target = destination/'physical'/module.name/source.relative_to(module/'target')
                target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    required = methods(manifest,gate)
    problems = I4.require_methods(cases,required) + I4.require_sources(ROOT,inputs)
    problems += physical_problems(destination/'physical', [r for r in manifest['physical_requirements'] if gate in r['gates']],started)
    for filename in manifest['inherited_manifests']:
        inherited = json.loads((MANIFEST.parent/filename).read_text())
        problems += I4.require_physical(ROOT/'app',inherited.get('physical_requirements',[]),inherited['gate_groups'].get(NAMES[gate],[]),started)
    skipped = [c for c in cases if c['state']=='SKIP']
    for case in skipped:
        if not (case['class'].endswith('.TerminalSequencingPostgresTest') and case['method']=='zExploratoryContentionSeparateFromCorrectness'):
            problems.append('Unexpected skip '+str(case))
    if any(c['state']=='FAIL' for c in cases):problems.append('JUnit failures/errors')
    changed = subprocess.check_output(['git','diff','--name-only',manifest['baseline'],'HEAD','--','app'],cwd=ROOT,text=True).splitlines()
    compilation, binaries = I4.require_compiled(ROOT,changed,modules)
    problems += compilation
    if subprocess.check_output(['git','status','--porcelain','--','app','scripts'],cwd=ROOT,text=True):problems.append('Sources mutated during gate')
    for file in sorted(destination.rglob('*')):
        if file.is_file():artifacts.append({'path':str(file.relative_to(destination)),'sha256':I4.digest(file)})
    result = {'gate':gate,'sha':sha,'command':command,'cwd':'app/','started_epoch':started,
              'elapsed_seconds':round(time.time()-started,3),'exit_code':code,'tests':len(cases),
              'required_methods':sum(map(len,required.values())),'cases':cases,'skips':skipped,
              'problems':problems,'verdict':'PASS' if code==0 and not problems else 'FAIL',
              'input_sha256':inputs,'compiled_sha256':binaries,'manifest_sha256':I4.digest(MANIFEST),
              'inherited_manifest_sha256':inherited_hashes,'artifacts':artifacts,
              'limit':'Local I5 proof. No global L2 or deferred business certification; independent audit required.'}
    (destination/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('cases','input_sha256','compiled_sha256','artifacts')},indent=2))
    return 0 if result['verdict']=='PASS' else 1


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gate',choices=ANCHORS);parser.add_argument('--destination',type=Path,required=True)
    args=parser.parse_args();sys.exit(run(args.gate,args.destination.resolve()))
