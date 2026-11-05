#!/usr/bin/env python3
"""Read-only effective-model proof of I2 gate mutualization; never executes tests."""
import argparse
import importlib.util
import json
from pathlib import Path
import time
import uuid

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('verify.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    v.reject_implicit_options()
    output = args.output.resolve()
    lock = v.ROOT / 'app/.verification.lock'
    lock.mkdir()  # Same single-owner discipline as verify.py, no concurrent Maven target writer.
    try:
        output.mkdir(parents=True, exist_ok=False)
        started = time.monotonic()
        before = v.app_inputs()
        controls = v.control_inputs()
        definitions = {}
        for name, modules in [
            ('WEB', 'runtime-web-api'),
            ('COMMAND', 'runtime-command-consumption-worker'),
            ('Architecture', 'architecture-tests'),
            ('Monolith', 'runtime-monolith'),
            ('Combined', 'runtime-web-api,runtime-command-consumption-worker,architecture-tests,runtime-monolith'),
        ]:
            destination = output / name
            destination.mkdir()
            invocation = uuid.uuid4().hex
            definition = dict(level='INTEGRATION', scope=name, filtered=False,
                              command=['./mvnw', '--batch-mode', '--no-transfer-progress', '-pl', modules, '-am',
                                       '-Dpocoma.verification.id=' + invocation,
                                       '-Dpocoma.verification.reports=' + str(destination / 'reports'), 'test'],
                              invocation_id=invocation, reports_root=str(destination / 'reports'), expected={})
            model, issues = v.effective_inventory(definition, destination)
            if issues:
                raise ValueError(name + ': ' + '; '.join(issues))
            definition['model'] = model
            definitions[name] = definition
            (destination / 'inventory.json').write_text(json.dumps(definition, indent=2) + '\n')
        identities = lambda definition: {(c, m) for c, methods in definition['expected'].items() for m in methods}
        union = set().union(*(identities(definitions[name]) for name in ['WEB', 'COMMAND', 'Architecture', 'Monolith']))
        combined = identities(definitions['Combined'])
        module_union = set().union(*(set(p['module'] for p in definitions[name]['model'])
                                    for name in ['WEB', 'COMMAND', 'Architecture', 'Monolith']))
        combined_modules = {p['module'] for p in definitions['Combined']['model']}
        if not union or union != combined or module_union != combined_modules:
            raise ValueError('Effective reactor/source union differs from combined gate')
        architecture = {i for i in identities(definitions['Architecture']) if '.architecture.' in i[0]}
        if not architecture or not architecture <= combined:
            raise ValueError('Missing complete Architecture oracle inventory')
        if before != v.app_inputs() or controls != v.control_inputs():
            raise ValueError('Qualification inputs changed during preflight')
        receipt = dict(verdict='EQUIVALENT', tested_head=v.capture(['git', 'rev-parse', 'HEAD']),
                       dirty_state=v.capture(['git', 'status', '--short']),
                       effective_modules=sorted(combined_modules), expected_invocations=len(combined),
                       architecture_invocations=len(architecture),
                       expected=definitions['Combined']['expected'], app_inputs=before, control_inputs=controls,
                       external_sql_inputs=v.external_sql_inputs(definitions['Combined']['expected']),
                       java=v.capture(['java', '-version']), maven=v.capture(['./mvnw', '-v']),
                       monotonic_seconds=time.monotonic()-started,
                       command=definitions['Combined']['command'],
                       meaning='Effective canonical union including Monolith; no test execution, filter or skip')
        (output / 'equivalence.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps({k: receipt[k] for k in ['verdict', 'expected_invocations', 'architecture_invocations', 'monotonic_seconds']}))
    finally:
        lock.rmdir()


if __name__ == '__main__':
    main()
