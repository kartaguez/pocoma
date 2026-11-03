#!/usr/bin/env python3
"""Bounded helper proof; reuse the normative collector, no global recipe."""
import importlib.util
import json
from pathlib import Path
import signal
import time

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('verification', ROOT / 'scripts/testing/verify.py')
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
config = json.loads(verification.CONFIG.read_text())
config['recipes']['waiterpid'] = dict(
    anchor='COMMAND', fast_modules=['infra-persistence-primary-jpa'],
    FAST=['IdentityPotAuthorityPostgresTest'], CHANGE=[], INTEGRATION=[])
definition = verification.plan('FAST', 'waiterpid', config=config)
definition['scope_declaration'] = dict(
    primary='COMMAND (filtered test helper contribution only)', secondary=[],
    production_impact='NONE', unexpected_crossing='NONE',
    global_architecture='NOT REQUIRED / NOT RUN', full_reactor='NOT REQUIRED / NOT RUN',
    database='CURRENT V3 fixture migrations only; no historical V1–V23 campaign',
    budget_seconds=300, acceptance='local helper proof only; no L3/L4 global qualification')
print(json.dumps(definition, ensure_ascii=False, indent=2), flush=True)
# The deadline includes discovery, toolchain capture and test execution. Leave
# 10 seconds to terminate/reap the Maven process group and preserve diagnostics.
signal.signal(signal.SIGALRM, verification.interrupt)
started = time.monotonic()
signal.alarm(290)
output = Path(__file__).resolve().parent / 'targeted'
try:
    rc = verification.run(definition, output)
finally:
    signal.alarm(0)
    (output / 'budget.json').write_text(json.dumps(dict(
        total_seconds=time.monotonic() - started, budget_seconds=300,
        interruption_at_seconds=290), indent=2) + '\n')
raise SystemExit(rc)
