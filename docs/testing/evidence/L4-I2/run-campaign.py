#!/usr/bin/env python3
"""I2 gate operator: unchanged selector, reviewed attribution of one existing top-level class."""
import hashlib
import importlib.util
from pathlib import Path
import re
import sys

root = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('verification', root / 'scripts/testing/verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
original_sources = v.sources
source_alias = ('app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/'
                'runtime/command/consumption/sequencing/JpaTerminalSequencingPostgresTest.java')
alias_class = ('com.kartaguez.pocoma.runtime.command.consumption.sequencing.'
               'CurrentTransactionManagerPostgresTest')


def reviewed_sources(workspace=None):
    result = original_sources(workspace)
    path = (workspace or root) / source_alias
    assert re.search(r'^class CurrentTransactionManagerPostgresTest\s*\{',
                     v.java_structure(path.read_text()), re.M)
    identity = (alias_class, path)
    entries = result.setdefault('CurrentTransactionManagerPostgresTest', [])
    if identity not in entries:
        entries.append(identity)
    assert entries == [identity]
    return result


v.sources = reviewed_sources
original_controls = v.control_inputs


def controls():
    result = original_controls()
    result[str(Path(__file__).resolve())] = v.digest(Path(__file__))
    return result


v.control_inputs = controls
mode, output = sys.argv[1], Path(sys.argv[2])
d = v.plan('INTEGRATION', 'PROJECTION,COMMAND')
if mode == 'architecture':
    d['scope'] = 'Architecture explicit I2 gate (Reactor policy 17)'
    d['command'] = ['./mvnw', '--batch-mode', '--no-transfer-progress',
                    '-pl', 'architecture-tests', '-am', 'test']
elif mode != 'projection-command':
    raise ValueError(mode)
d['reviewed_source_aliases'] = {alias_class: source_alias}
d['operator_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
d['source_workspace'] = str(root)
sys.exit(v.run(d, output))
