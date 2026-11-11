"""Select the current L7 reactor for the former L2 qualification entry points."""

import json

import verify


SCOPES = {
    'command': 'COMMAND',
    'transactions': 'FOUNDATION',
    'read': 'PROJECTION',
    'monolith': 'MONOLITH',
    'architecture': 'ARCHITECTURE',
}


def plan(gate):
    if gate == 'full':
        return verify.plan('CERTIFICATION', None)
    config = json.loads(verify.CONFIG.read_text())
    config['gates']['MONOLITH'] = ['runtime-monolith']
    return verify.plan('CHANGE', SCOPES[gate], config)


def run(gate, destination):
    definition = plan(gate)
    print('L7 reactor verification. Reproduce milestone receipts at their recorded Git SHA.', flush=True)
    return verify.run(definition, destination.resolve())
