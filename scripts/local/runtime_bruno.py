"""Bruno playground execution and JUnit/JSONL receipt validation."""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def _junit_suite_matches(suite, item):
    if suite.get('file') != item['file'] or suite.get('tests') != str(len(item['tests'])):
        return False
    if any(suite.get(key) != '0' for key in ('failures', 'errors', 'skipped')):
        return False
    return [c.get('name') for c in suite.findall('testcase')] == item['tests']


def _junit_cases_passed(suites):
    cases = [c for suite in suites for c in suite.findall('testcase')]
    passed = all(
        c.get('status') == 'pass' and all(c.find(tag) is None for tag in ('failure', 'error', 'skipped'))
        for c in cases
    )
    return passed, len(cases)


def _receipt_matches_item(record, item):
    if record.get('request') != item['id'] or record.get('verdict') != 'PASS':
        return False
    if type(record.get('assertions')) is not int or record['assertions'] < 3:
        return False
    trace = record.get('requests', [])
    if not trace:
        return False
    pattern = re.sub(r'\\\{\\\{.*?\\\}\\\}', '[^/]+', re.escape(item['pathTemplate']))
    last = trace[-1]
    if last.get('method') != item['method'] or last.get('status') != item['status']:
        return False
    return re.fullmatch(pattern, last.get('path', '')) is not None


def _all_junit_suites_match(expected, suites):
    return len(suites) == len(expected) and all(_junit_suite_matches(s, item) for s, item in zip(suites, expected))


def _all_receipts_match(expected, actual):
    return len(actual) == len(expected) and all(_receipt_matches_item(rec, item) for rec, item in zip(actual, expected))


def validate_bruno_results(inventory, actual, suites, exit_code):
    """No empty, duplicate, reordered, partial, skipped or wrong-route proof passes."""
    expected = inventory['requests']
    cases_ok, named_tests = _junit_cases_passed(suites)
    junit_pass = bool(expected) and cases_ok and _all_junit_suites_match(expected, suites)
    receipts_pass = bool(expected) and _all_receipts_match(expected, actual)
    return dict(
        success=junit_pass and receipts_pass and exit_code == 0,
        junitPass=junit_pass,
        namedTests=named_tests,
        assertions=sum(r.get('assertions', 0) for r in actual),
        httpRequests=sum(len(r.get('requests', [])) for r in actual),
    )


def _prepare_bruno_tokens(rt, path, m, interactive_login):
    credentials = rt.private(path)
    tokens = {name: rt.login(m['issuer'], name, credentials['users'][name]) for name in ('third', 'guest')}
    if interactive_login:
        tokens['third'] = rt.interactive(m['issuer'])
    return tokens


def _ensure_bruno_cli(rt, evidence):
    cli = rt.ROOT / 'scripts/bruno-v3/node_modules/.bin/bru'
    if not cli.exists():
        rt.run(['npm', 'ci', '--prefix', rt.ROOT / 'scripts/bruno-v3'], timeout=240, log=evidence / 'npm.log')
    if rt.run([cli, '--version']) != rt.prerequisites.contract(rt.ROOT)['brunoVersion']:
        raise rt.Refusal('PRE-BRUNO-VERSION')
    return cli


def bruno(rt, ctx, interactive_login=False):
    path, m, evidence = ctx
    rt.ownership(m)
    rt.run([sys.executable, '-B', rt.ROOT / 'scripts/local/sync_bruno_playground.py', '--check'])
    for name in ('scenarios.jsonl', 'bruno.xml', 'bruno-receipt.json'):
        if (evidence / name).exists():
            raise rt.Refusal('PRE-BRUNO-EVIDENCE-NOT-FRESH')
    tokens = _prepare_bruno_tokens(rt, path, m, interactive_login)
    cli = _ensure_bruno_cli(rt, evidence)
    collection = rt.ROOT / 'scripts/bruno-playground'
    environment = {
        **os.environ,
        'POCOMA_PG_WRITE': rt.urls(m)[0],
        'POCOMA_PG_READ': rt.urls(m)[1],
        'POCOMA_PG_ISSUER': m['issuer'],
        'POCOMA_PG_OWNER': tokens['third'],
        'POCOMA_PG_GUEST': tokens['guest'],
        'POCOMA_PG_RECEIPT': str(evidence / 'scenarios.jsonl'),
    }
    if 'http-read' in m['ports']:
        environment['POCOMA_PG_MANAGEMENT'] = 'http://127.0.0.1:' + str(m['ports']['http-read'])
    command = [
        str(cli), 'run', '90 - End-to-End Journeys', '-r', '--env', 'Local',
        '--sandbox=developer', '--bail', '--reporter-junit', str(evidence / 'bruno.xml'),
    ]
    result = subprocess.run(
        command, cwd=collection, env=environment,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600,
    )
    file = evidence / 'scenarios.jsonl'
    actual = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
    inventory = rt.read(collection / 'inventory.json')
    report = evidence / 'bruno.xml'
    suites = ET.parse(report).findall('.//testsuite') if report.exists() else []
    qualification = validate_bruno_results(inventory, actual, suites, result.returncode)
    success = qualification['success']
    rt.save(evidence / 'bruno-receipt.json', dict(
        command=command,
        expected=[r['id'] for r in inventory['requests']],
        actual=[r['request'] for r in actual],
        **qualification,
        journeys=inventory['journeys'],
        exit=result.returncode,
        testedSha=rt.run(['git', 'rev-parse', 'HEAD']),
        dirty=bool(rt.run(['git', 'status', '--porcelain'])),
        inputs=rt.source_hash(),
    ))
    return dict(state='BRUNO_PASS' if success else 'BRUNO_FAIL', exit=0 if success else 1)
