# L3/I3 evidence

`manifest.json` records exact commands, per-run provenance, completed-case totals,
exit codes, skips, preserved failures, and limits. `change-inventory.json` describes
the implementation at its frozen code commit; later documentary updates are in Git.
A filtered PASS is a bounded contribution, never the unfiltered canonical verdict.

The two full consumer/architecture gates are distinguished by their code inputs:
970609cd before the ACL correction and 3ae0966b after the correction. The first
FAIL i5_r12 remains preserved. The final run also FAILs i5_r12: 1,859 cases,
1 failure, 0 errors, 1 historic skip, Architecture SKIPPED. All 37 new I3 cases
pass without skips in that final run. i5_r05/i5_r12 historical
reservations remain open. Full reactor, real Pot authority, READ projections and
external consent certification are outside I3.

Logs and PostgreSQL/JUnit evidence use deterministic gzip (mtime=0). Each entry
contains SHA-256 of compressed bytes and restored public bytes; when a diagnostic
was redacted, the separate original private SHA does not claim recoverability of
that private content. No credentials, configuration files or token files are included.
The public-ACL counterexample selects only `count(binding_token)` on an empty table.

Verify the archives from the repository root:

```python
import gzip, hashlib, json
from pathlib import Path
root = Path.cwd()
m = json.loads((root / 'docs/testing/evidence/L3-I3/manifest.json').read_text())
items = m['runs'] + m['postgres_evidence'] + m['junit_evidence'] + m.get('historical_gate_diagnostics', [])
if m.get('read_only_diagnostic'):
    items.append(m['read_only_diagnostic'])
for item in items:
    compressed = (root / item['path']).read_bytes()
    assert hashlib.sha256(compressed).hexdigest() == item['compressed_sha256']
    restored = gzip.decompress(compressed)
    assert hashlib.sha256(restored).hexdigest() == item['archived_plain_sha256']
```

The execution report and DEV runbook are in `docs/v3/`. The final publication
commit changes only documentation/evidence relative to the frozen implementation.
