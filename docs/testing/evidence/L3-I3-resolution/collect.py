"""Collect one sequential Maven contribution; never infer freshness from file names."""
import argparse, hashlib, json, tarfile, tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('label')
parser.add_argument('log', type=Path)
parser.add_argument('--since', type=float, required=True)
parser.add_argument('--exit-code', type=int, required=True)
parser.add_argument('--workspace', type=Path)
args = parser.parse_args()
base = Path(__file__).resolve().parent
root = args.workspace.resolve() if args.workspace else base.parents[3]
freeze = 'c1172bf4a758a9077415d827ca88b359b6360ebb'
# Documentary edits may continue while a gate runs; executable inputs may not.
inputs = json.loads((base / 'Proof-inputs.json').read_text())
for entry in inputs['source_files']:
    if entry['path'].startswith(('app/', 'scripts/')):
        assert hashlib.sha256((root / entry['path']).read_bytes()).hexdigest() == entry['sha256_at_code_freeze'], entry['path']
summary = {'workspace': str(root), 'label': args.label, 'tested_code_sha': freeze, 'exit_code': args.exit_code,
           'fresh_since_epoch': args.since, 'classes': [], 'counts': {}, 'raw_log_sha256': hashlib.sha256(args.log.read_bytes()).hexdigest()}
with tempfile.TemporaryDirectory(prefix='i3-collect-') as temporary:
    output = Path(temporary) / args.label
    output.mkdir()
    for path in sorted((root / 'app').glob('*/target/surefire-reports/TEST-*.xml')):
        if path.stat().st_mtime < args.since:
            continue
        xml = ET.parse(path).getroot()
        module = path.parts[-4]
        record = {'module': module, 'class': xml.get('name'), **{key: int(xml.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')},
                  'cases': [{'name': case.get('name'), 'seconds': case.get('time'),
                             'outcome': 'FAIL' if case.find('failure') is not None else 'ERROR' if case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'success'}
                            for case in xml.findall('testcase')]}
        summary['classes'].append(record)
        destination = output / 'junit' / module / path.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(path.read_bytes())
    for path in sorted((root / 'app').glob('*/target/l3-i3-evidence/*.txt')):
        if path.stat().st_mtime < args.since:
            continue
        destination = output / 'postgres' / path.parts[-4] / path.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(path.read_bytes())
    # Preserve fresh long-test diagnostics if the transitive historical gate fails.
    for path in sorted((root / 'app').glob('*/target/v3-i5-evidence/temporal-*')):
        if not path.is_file() or path.stat().st_mtime < args.since:
            continue
        destination = output / 'historical-transitive-diagnostics' / path.parts[-4] / path.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(path.read_bytes())
    assert summary['classes'], 'No freshly executed JUnit results; stale XML cannot establish a proof'
    for key in ('tests', 'failures', 'errors', 'skipped'):
        summary['counts'][key] = sum(row[key] for row in summary['classes'])
    (output / 'maven.log').write_bytes(args.log.read_bytes())
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    members = [{'path': str(path.relative_to(Path(temporary))), 'bytes': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
               for path in sorted(output.rglob('*')) if path.is_file()]
    with tarfile.open(base / (args.label + '.tar.gz'), 'w:gz') as archive:
        archive.add(output, arcname=args.label)
    (base / (args.label + '-members.json')).write_text(json.dumps(members, indent=2) + '\n')
(base / (args.label + '-summary.json')).write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary['counts']))
