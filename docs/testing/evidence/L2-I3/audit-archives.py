#!/usr/bin/env python3
"""Read-only independent archive audit: hashes, exact commands, fresh XML and physical subvariants."""
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
CODE = '1d7cf51c13671c7c85a5c9c1281f526be2b2abfb'
spec = importlib.util.spec_from_file_location('i3_collector', ROOT / 'scripts/testing/verify-v3-l2-i3.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
manifest = json.loads((ROOT / 'docs/v3/L2_I3_Proof_Manifest.json').read_text())
index = json.loads((HERE / 'Gate-index.json').read_text())
assert {x['gate'] for x in index} == set(manifest['gate_commands'])
results = []
for item in index:
    archive = HERE / item['archive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == item['sha256'], archive
    with tarfile.open(archive) as tar:
        prefix = item['gate'] + '-1d7cf51c/'
        read = lambda path: tar.extractfile(prefix + path).read()
        result = json.loads(read('result.json'))
        assert result['sha'] == CODE and result['verdict'] == 'PASS' and result['exit_code'] == 0
        assert result['command'] == manifest['gate_commands'][item['gate']]
        assert hashlib.sha256(read('maven.log')).hexdigest() == result['log_sha256']
        cases = []
        for entry in result['artifacts']:
            data = read(entry['path'])
            assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry
            assert tar.getmember(prefix + entry['path']).mtime >= result['started_epoch'] - 1
            if entry['path'].startswith('xml/'):
                for case in ET.fromstring(data).iter('testcase'):
                    state = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'
                    cases.append({'class':case.get('classname'),'method':case.get('name'),'state':state})
        assert len(cases) == result['tests']
        assert not [c for c in cases if c['state'] == 'FAIL']
        allowed_skip = ('com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest', 'zExploratoryContentionSeparateFromCorrectness')
        assert all((c['class'],c['method']) == allowed_skip for c in cases if c['state'] == 'SKIP')
        required = {}
        groups = manifest['gate_groups'][item['gate']]
        for group in groups:
            for name, methods in manifest['required_groups'][group].items():
                required.setdefault(name,[]).extend(methods)
        assert not collector.require_methods(cases,required)
        for requirement in manifest['physical_requirements']:
            if requirement['group'] not in groups:
                continue
            module, suffix = requirement['path'].split('/target/',1)
            data = read('physical/' + module + '/' + suffix).decode()
            assert all(needle in data for needle in requirement['contains']), requirement
        results.append({'gate':item['gate'],'tests':len(cases),'required_methods':result['required_methods'],'artifacts':len(result['artifacts']),'verdict':'PASS'})
environment = json.loads((HERE / 'Environment-and-inputs.json').read_text())
for entry in environment['files']:
    data = subprocess.check_output(['git','show',CODE + ':' + entry['path']],cwd=ROOT)
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['path']
assert not subprocess.check_output(['git','diff',CODE,'HEAD','--','app','scripts'],cwd=ROOT)
print(json.dumps({'code_sha':CODE,'gates':results,'input_hashes':'PASS','delivery_code_diff':'EMPTY'},indent=2))
