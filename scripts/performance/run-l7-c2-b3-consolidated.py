#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B3 Clean Consolidated Verification on Candidate Final (d2530925).

Runs a clean candidate-only consolidated READ reconstruction from the qualified
L7.4 `large` WRITE snapshot, validates ReadinessOracle=READY, HTTP privileges,
both F4 diagnostics (PotVersion + PotBalance), per-table PostgreSQL storage,
warm HTTP READ latencies, and 20 mixed mutations with WAL and post-mutation F4.
"""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

_b2_spec = importlib.util.spec_from_file_location(
    "run_l7_c2_b2_qualification", SCRIPT_DIR / "run-l7-c2-b2-qualification.py"
)
b2 = importlib.util.module_from_spec(_b2_spec)
_b2_spec.loader.exec_module(b2)

import spec09_bench as bench  # noqa: E402

ROOT = bench.ROOT
JAVA_IMAGE = "eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc"
POSTGRES_IMAGE = "postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7"


def main():
    cfg = json.loads((ROOT / "scripts/performance/l7-4a-profiles.json").read_text(encoding="utf-8"))
    out_dir = ROOT / "docs/testing/evidence/L7-C2-B3/large-consolidated"
    out_dir.mkdir(parents=True, exist_ok=True)
    snap_dir = Path("/private/tmp/pocoma-l74a-snapshots/large")

    git_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result, _ = b2.run_side(
        "candidate-v4-b3",
        ROOT / "app",
        cfg,
        snap_dir,
        "large",
        out_dir,
        POSTGRES_IMAGE,
        JAVA_IMAGE,
        is_v4=True,
        verify_second_rebuild=False,
        non_financial_workload=False,
    )
    payload = {
        "campaign": "L7-C2-B3-large-consolidated",
        "gitSha": git_sha,
        "result": result,
    }
    manifest_path = out_dir / "b3_consolidated_manifest.json"
    manifest_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {manifest_path}")


if __name__ == "__main__":
    main()
