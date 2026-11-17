#!/usr/bin/env python3
"""Explicit L4 recovery selection using the existing isolated-producer verifier; no retry."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--workspace', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--selection', choices=['counterexamples', 'qualification'], required=True)
a = p.parse_args()
root = a.workspace.resolve()
spec = importlib.util.spec_from_file_location('verification', root / 'scripts/testing/verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
methods = ['potVersionRecoveryRequiresCurrentPot', 'visibilityRecoverySupersession',
           'currentBindingRecoverySupersession']
if a.selection == 'qualification':
    methods += ['recoveryAffectedParticipantConformance', 'uncertainCommitHasNoSpeculativeSettlement']
config = json.loads(v.CONFIG.read_text())
scope = 'L4-RECOVERY-' + a.selection.upper()
config['campaigns'][scope] = dict(
    level='INTEGRATION', modules=['runtime-task-consumption-worker'],
    expected_invocations=len(methods),
    tests={'com.kartaguez.pocoma.runtime.task.v3.ReadParticipantsIntegrationPostgresTest': methods},
    forecast=dict(seconds=None, basis='UNKNOWN; targeted run, no deadline', uncertainty='HIGH'))
definition = v.plan('INTEGRATION', scope, config)
definition['bounded_variants'] = ['POT_VERSION', 'POT_VISIBILITY', 'CURRENT_BINDING']
original = v.control_inputs
def controls():
    result = original()
    result[str(Path(__file__).resolve())] = v.digest(Path(__file__))
    return result
v.control_inputs = controls
sys.exit(v.run(definition, a.output.resolve()))
