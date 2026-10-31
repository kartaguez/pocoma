#!/usr/bin/env python3
"""Nominative source-attribution review for one existing secondary Java test class.

Keep the original native receipt unchanged. Reassess the exact archived XML bytes;
no Maven execution, provider, framework rule or test is changed by this review.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[4]
SOURCE = 'app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/JpaTerminalSequencingPostgresTest.java'
CLASS = 'com.kartaguez.pocoma.runtime.command.consumption.sequencing.CurrentTransactionManagerPostgresTest'
METHOD = 'effectiveManagerAndSharedJdbcConnection'
MODULE = 'pocoma-runtime-command-consumption-worker'
spec = importlib.util.spec_from_file_location('verify', ROOT / 'scripts/testing/verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def main():
    folder = Path(sys.argv[1]).resolve()
    original = json.loads((folder / 'result.json').read_text())
    plan = json.loads((folder / 'plan.json').read_text())
    provenance = json.loads((folder / 'provenance.json').read_text())
    source = (ROOT / SOURCE).read_bytes()
    assert hashlib.sha256(source).hexdigest() == provenance['app_inputs'][SOURCE]
    assert b'class CurrentTransactionManagerPostgresTest {' in source
    assert b'@Test void effectiveManagerAndSharedJdbcConnection()' in source
    assert CLASS not in plan['expected_modules']  # Explicitly the reviewed secondary-class gap.
    plan['expected_modules'][CLASS] = MODULE
    plan['expected'][CLASS] = [METHOD]
    with tarfile.open(folder / 'raw.tar.gz') as archive:
        reports = [(entry.name, archive.extractfile(entry).read()) for entry in archive
                   if entry.isfile() and entry.name.startswith('reports/')
                   and Path(entry.name).name.startswith('TEST-') and entry.name.endswith('.xml')]
    corrected = v.assess(reports, plan['expected'], original['exit_code'], False, plan)
    corrected['issues'] += plan['coverage_limitations']
    if corrected['verdict'] == 'PASS' and plan['coverage_limitations']:
        corrected['verdict'] = 'INCONCLUSIVE'
    assert not any('Invalid report' in issue for issue in corrected['issues']), corrected['issues']
    assert corrected['counts']['tests'] == original['counts']['tests'] + 1
    assert all(corrected['counts'][key] == original['counts'][key] for key in ['failures', 'errors', 'skipped'])
    added = [entry for entry in corrected['executions'] if entry['classname'] == CLASS]
    assert len(added) == 1 and added[0]['method'] == METHOD and added[0]['outcome'] == 'PASS'
    result = dict(original, **corrected)
    result['source_attribution_review'] = 'source-attribution-review.json'
    result['expected_invocations'] = sum(map(len, plan['expected'].values()))
    review = dict(tested_code_sha=provenance['head']['output'].strip(), source=SOURCE,
                  source_sha256=hashlib.sha256(source).hexdigest(), test_class=CLASS, method=METHOD, module=MODULE,
                  source_class_declaration_line=252,
                  reason='Existing package-private top-level test class in JpaTerminalSequencingPostgresTest.java; standard @Test method, current source and fresh module/producer ID verified.',
                  original_counts=original['counts'], corrected_counts=corrected['counts'], added_execution=added[0],
                  original_receipt_unchanged=True, no_execution_rerun=True,
                  reviewer_script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    (folder / 'qualified-result.json').write_text(json.dumps(result, indent=2) + '\n')
    (folder / 'source-attribution-review.json').write_text(json.dumps(review, indent=2) + '\n')
    print(json.dumps(dict(verdict=result['verdict'], counts=result['counts'], source_review=CLASS)))


if __name__ == '__main__':
    main()
