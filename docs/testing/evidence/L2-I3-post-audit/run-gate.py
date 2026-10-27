#!/usr/bin/env python3
"""Reuse the I3 fresh-evidence collector with the post-audit superset manifest."""
import argparse
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location("i3_collector", ROOT / "scripts/testing/verify-v3-l2-i3.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
collector.MANIFEST = ROOT / "docs/v3/L2_I3_Post_Audit_Proof_Manifest.json"

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=collector.ANCHORS)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    raise SystemExit(collector.run_gate(args.gate, args.destination))
