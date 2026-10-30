#!/usr/bin/env python3
"""Preserve one execution: log-derived counts, only its matching XML, and optional I2 traces.

Run from the repository root. Never launches tests, rewrites historical evidence, or counts
unmentioned target XML. Refuses to replace an already captured run.
"""
import argparse
import gzip
import hashlib
import io
import json
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', required=True)
parser.add_argument('--log', type=Path, required=True)
parser.add_argument('--command', required=True)
parser.add_argument('--exit-code', type=int, required=True)
parser.add_argument('--xml', action='store_true')
parser.add_argument('--i2-traces', action='store_true')
args = parser.parse_args()
assert re.fullmatch('[a-z0-9-]+', args.run)
destination = OUT / (args.run + '.json')
assert not destination.exists(), 'Existing proof is immutable'
sha = lambda data: hashlib.sha256(data).hexdigest()
raw = args.log.read_bytes()
log = raw.decode()
rows = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*? -- in ([^\r\n]+)', log)
suites = [dict(name=r[4], **dict(zip(('tests','failures','errors','skipped'), map(int,r[:4])))) for r in rows]
assert len({s['name'] for s in suites}) == len(suites), 'Repeated suite needs explicit allocation'
archive_files = {'maven.log': raw}
cases = []
if args.xml:
    for suite in suites:
        name = 'TEST-' + suite['name'] + '.xml'
        matches = list((ROOT / 'app').glob('*/target/surefire-reports/' + name))
        assert len(matches) == 1, (name, len(matches))
        xml = matches[0].read_bytes()
        root = ET.fromstring(xml)
        assert all(int(root.get(k)) == suite[k] for k in ('tests','failures','errors','skipped')), name
        archive_files[str(matches[0].relative_to(ROOT))] = xml
        for case in root.findall('testcase'):
            status = 'PASS'
            detail = None
            for tag, value in [('failure','FAIL'),('error','ERROR'),('skipped','SKIP')]:
                child = case.find(tag)
                if child is not None:
                    status, detail = value, child.get('message')
                    break
            cases.append(dict(suite=suite['name'], name=case.get('name'), status=status,
                              seconds=case.get('time'), detail=detail))
if args.i2_traces:
    traces = sorted((ROOT / 'app/infra-persistence-primary-jpa/target/l3-i2-evidence').glob('*.txt'))
    assert len(traces) == 23
    for trace in traces:
        archive_files[str(trace.relative_to(ROOT))] = trace.read_bytes()
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode='w') as tar:
    for name, data in sorted(archive_files.items()):
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o644
        tar.addfile(info, io.BytesIO(data))
compressed = gzip.compress(buffer.getvalue(), mtime=0)
archive_name = args.run + '.tar.gz'
(OUT / archive_name).write_bytes(compressed)
with tarfile.open(fileobj=io.BytesIO(compressed), mode='r:gz') as tar:
    assert {m.name: sha(tar.extractfile(m).read()) for m in tar.getmembers()} == {n:sha(d) for n,d in archive_files.items()}
source_paths = list((ROOT/'app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity').glob('*.java'))
source_paths += list((ROOT/'app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity').glob('*.java'))
source_paths += [ROOT/'app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql', ROOT/'app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3IdentityAuthorityArchitectureTest.java']
receipt = dict(name=args.run, command=args.command, cwd='app/', exit_code=args.exit_code,
    build='SUCCESS' if 'BUILD SUCCESS' in log else 'FAILURE' if 'BUILD FAILURE' in log else 'INCOMPLETE',
    head_at_capture=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
    input_sha256={str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in sorted(source_paths)},
    totals={k:sum(s[k] for s in suites) for k in ('tests','failures','errors','skipped')},
    suites=suites, cases=cases,
    completed_at=re.findall(r'Finished at: ([^\r\n]+)',log), duration=re.findall(r'Total time: ([^\r\n]+)',log),
    log_sha256=sha(raw), archive=dict(path=archive_name,sha256=sha(compressed),bytes=len(compressed)),
    members={n:dict(bytes=len(d),sha256=sha(d)) for n,d in sorted(archive_files.items())})
destination.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:receipt[k] for k in ('name','build','totals','archive')}))
