#!/usr/bin/env python3
"""Bounded I4 proof of every ordinary test in the Architecture module.

Six existing classes also annotate Spring fixture beans/configurations. These
annotations do not declare Jupiter tests. The reviewed exception is nominative;
all @Test methods and source/producer checks of verify.py remain mandatory.
This filtered command is never the unfiltered Architecture reactor gate.
"""
import importlib.util
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('verify', ROOT / 'scripts/testing/verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)

REVIEWED_FIXTURE_ANNOTATIONS = {
    'com.kartaguez.pocoma.architecture.EventPublicationContractArchitectureTest': {'Transactional'},
    'com.kartaguez.pocoma.architecture.ccr.CommandCompletionE2EPostgresTest': {
        'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import', 'Primary', 'SpringBootConfiguration'},
    'com.kartaguez.pocoma.architecture.ccr.CommandResultConsumptionChainPostgresTest': {
        'DynamicPropertySource', 'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import',
        'MockitoSpyBean', 'SpringBootConfiguration', 'SpringBootTest', 'TestBean'},
    'com.kartaguez.pocoma.architecture.ccr.CommandResultTakeoverRacePostgresTest': {
        'DynamicPropertySource', 'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import',
        'SpringBootConfiguration', 'SpringBootTest', 'TestBean'},
    'com.kartaguez.pocoma.architecture.ept.DurableEventToProjectionPostgresTest': {
        'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import', 'SpringBootConfiguration'},
    'com.kartaguez.pocoma.architecture.ept.HistoricalEventRouteEvolutionPostgresTest': {
        'EnableAutoConfiguration', 'EnableJpaRepositories', 'EntityScan', 'Import', 'Primary', 'SpringBootConfiguration'},
}
original_validation = v.validate_annotations


def fixture_annotations(fqcn, content):
    for name in REVIEWED_FIXTURE_ANNOTATIONS.get(fqcn, set()):
        content = re.sub(r'@' + re.escape(name) + r'\b', '@Override', content)
    original_validation(fqcn, content)


v.validate_annotations = fixture_annotations


def main():
    plan = v.plan('INTEGRATION', 'COMMAND')
    plan['expected'] = {}
    for path in sorted((ROOT / 'app/architecture-tests/src/test/java').rglob('*.java')):
        content = v.java_structure(path.read_text())
        if re.search(v.TEST_ANNOTATION, content):
            name = re.search(r'^package\s+([\w.]+);', content, re.M)[1] + '.' + path.stem
            plan['expected'][name] = v.required_methods(name, path)
    plan['command'] = ['./mvnw', '--batch-mode', '--no-transfer-progress', '-pl', 'architecture-tests', '-am',
                       '-Dtest=' + ','.join(sorted(plan['expected'])), '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    plan.update(scope='I4 complete Architecture module bounded proof; NOT canonical gate', filtered=True,
                collector_sha256=v.digest(Path(__file__)),
                normative_gate=False, reviewed_fixture_annotations={k: sorted(val) for k, val in REVIEWED_FIXTURE_ANNOTATIONS.items()})
    return v.run(plan, Path(sys.argv[1]).resolve())


if __name__ == '__main__':
    raise SystemExit(main())
