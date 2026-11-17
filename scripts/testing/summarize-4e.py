#!/usr/bin/env python3
"""Archive only fresh Surefire reports for one executed gate, retaining all skips."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET
app, evidence, name, started, status, *arguments = sys.argv[1:]
app, evidence, started = Path(app), Path(evidence), int(started)
report_dir = evidence / name
report_dir.mkdir(exist_ok=True)
counts = dict(tests=0, failures=0, errors=0, skipped=0)
skips = []
reports = []
for source in sorted(app.glob('*/target/surefire-reports/TEST-*.xml')):
    if source.stat().st_mtime < started:
        continue
    suite = ET.parse(source).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, '0'))
    for case in suite.findall('testcase'):
        if case.find('skipped') is not None:
            skips.append(dict(classname=case.get('classname'), name=case.get('name'), reason=case.find('skipped').get('message')))
    target = report_dir / source.parts[-4]
    target.mkdir(exist_ok=True)
    shutil.copy2(source, target / source.name)
    reports.append(str(source.relative_to(app)))
log = evidence / (name + '.log')
result = dict(command=['./mvnw', *arguments], exit_code=int(status), counts=counts, skips=skips,
              reports=reports, log=str(log), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest())
(evidence / (name + '.json')).write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(dict(gate=name, exit_code=int(status), counts=counts, reports=len(reports))))
