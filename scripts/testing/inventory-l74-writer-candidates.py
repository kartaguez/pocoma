#!/usr/bin/env python3
"""Export textual writer/authority candidates, never an exhaustive authority proof."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PATTERNS = {
    "SLOT_DELETE": r"(?i)delete\s+from\s+[^;\n]*consumption_slot",
    "DESTRUCTIVE_DDL": r"(?i)(truncate|drop schema|drop table)",
    "CHECKPOINT_WRITE": r"(?i)(insert into|update|delete from)[^;\n]*discovery_checkpoint",
    "DYNAMIC_SQL": r"(?i)(execute\s+format|createStatement\(|\.execute\(|executeUpdate\()",
    "FINALIZATION": r"(?i)(protectFinal|complete\(|finalize|finalise|finalization)",
    "TRANSACTION_OWNER": r"(TransactionTemplate|DataSourceTransactionManager|SpringAttemptTransactions|AttemptTransactions|\.commit\(|\.rollback\()",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new directory")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    compiled = {k: re.compile(v) for k, v in PATTERNS.items()}
    paths = sorted(p for base in ("app", "scripts/operations", "scripts/performance", "scripts/v3")
                   for p in (ROOT/base).rglob("*") if p.suffix in (".java", ".py", ".sql")
                   and "target" not in p.parts and "__pycache__" not in p.parts)
    inputs, rows = {}, []
    for path in paths:
        data = path.read_bytes()
        relative = str(path.relative_to(ROOT))
        inputs[relative] = hashlib.sha256(data).hexdigest()
        kind = "application-test" if "test" in path.parts else "production" if "main" in path.parts else "tool-or-sql"
        for number, line in enumerate(data.decode().splitlines(), 1):
            for category, pattern in compiled.items():
                if pattern.search(line):
                    rows.append([category, relative, number, inputs[relative], kind, line.strip()[:240]])
    with (args.output/"writer-candidates.csv").open("x") as stream:
        writer = csv.writer(stream)
        writer.writerow(["category", "path", "line", "sha256", "kind", "text"])
        writer.writerows(rows)
    receipt = dict(status="CANDIDATES_ONLY", sha=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                   patterns=PATTERNS, inputs=inputs, references=len(rows), filesWithReferences=len({r[1] for r in rows}),
                   limitation="Textual candidates, including comments/tests; indirect/dynamic/transitive writers and physical guards require semantic audit. Absence of a match is not proof of absence.")
    (args.output/"receipt.json").write_text(json.dumps(receipt, indent=2)+"\n")
    print(json.dumps({k: receipt[k] for k in ("status", "references", "filesWithReferences")}))


if __name__ == "__main__":
    main()
