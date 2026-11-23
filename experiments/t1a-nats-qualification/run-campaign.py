#!/usr/bin/env python3
"""Differential qualification campaign for Pocoma V3 T1A + Core NATS."""
import argparse
import hashlib
import json
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = Path(__file__).resolve().parent / "run-t1a-bench.py"
EVIDENCE_DIR = ROOT / "docs/testing/evidence/t1a-nats-qualification"

PLAN = [
    # 1. Central differential matrix (R=3): T1A+polling vs T1A+NATS
    ("T1A", "A-20", "c1-idle", 3),
    ("T1A", "D-nats", "c1-idle", 3),
    ("T1A", "A-20", "c1-med-1", 3),
    ("T1A", "D-nats", "c1-med-1", 3),
    ("T1A", "A-20", "c2-med-10", 3),
    ("T1A", "D-nats", "c2-med-10", 3),
    # 2. Pilot anomaly & stress reproduction on T1A
    ("T1A", "A-20", "c3-int-pot-vs-id", 2),
    ("T1A", "D-nats", "c3-int-pot-vs-id", 2),
    ("T1A", "A-20", "c2-ramp", 1),
    ("T1A", "D-nats", "c2-ramp", 1),
    ("T1A", "A-20", "c4-backlog-10k", 1),
    ("T1A", "D-nats", "c4-backlog-10k", 1),
    # 3. Resilience matrix on T1A + Core NATS
    ("T1A", "D-nats", "fail-bus-absent", 1),
    ("T1A", "D-nats", "fail-bus-kill-restart", 1),
    ("T1A", "D-nats", "fail-silent-loss", 1),
]


def run_one(topology, variant, scenario, rep, out_root):
    run_id = f"{topology}-{variant}-{scenario}-r{rep}"
    out_dir = out_root / run_id
    cmd = [
        sys.executable, "-B", str(BENCH),
        "--topology", topology,
        "--variant", variant,
        "--scenario", scenario,
        "--max-poll-ms", "20",
        "--reconcile-ms", "2000",
        "--fallback-ms", "20",
        "--output", str(out_dir),
    ]
    print(f"\n>>> [{run_id}] starting...", flush=True)
    proc = subprocess.run(cmd, cwd=ROOT)
    receipt_path = out_dir / "receipt.json"
    if not receipt_path.is_file():
        return {"runId": run_id, "topology": topology, "variant": variant, "scenario": scenario,
                "rep": rep, "verdict": "FAIL", "error": f"missing receipt (exit={proc.returncode})"}
    receipt = json.loads(receipt_path.read_text())
    receipt["runId"] = run_id
    receipt["rep"] = rep
    res = receipt.get("results", {})
    obs = res.get("observed")
    if obs is None and isinstance(res.get("pot"), dict):
        obs = res["pot"].get("observed")
    viol = res.get("invariantViolations", 0)
    if scenario in ("c1-med-1", "c2-med-10", "fail-bus-absent", "fail-bus-kill-restart", "fail-silent-loss"):
        if not obs or obs <= 0 or viol != 0:
            receipt["verdict"] = "FAIL"
            receipt["error"] = f"invalid observation: observed={obs}, violations={viol}"
    print(f"<<< [{run_id}] verdict={receipt.get('verdict')} obs={obs} avail={receipt.get('availabilityValid')}", flush=True)
    return receipt


def extract_summary(receipts):
    groups = {}
    for r in receipts:
        key = f"{r['topology']}|{r['variant']}|{r['scenario']}"
        groups.setdefault(key, []).append(r)
    summary = {}
    for key, runs in groups.items():
        passes = sum(1 for r in runs if r.get("verdict") == "PASS")
        cg_cpus = []
        pg_tx = []
        c03_p50, c03_p95, c03_p99 = [], [], []
        rss_total = []
        for r in runs:
            cg = r.get("cgroupDelta", {}).get("containers", {})
            if cg:
                cg_cpus.append(round(sum(v.get("avg_cpus", 0) for v in cg.values()), 4))
                rss_total.append(round(sum(v.get("memory_mib", 0) for k, v in cg.items() if k != "postgres"), 1))
            pgw = r.get("pgDelta", {})
            if pgw:
                tx = sum(pgw.get(db, {}).get("xact_commit_per_s", 0) for db in ("pocoma_write", "pocoma_read"))
                pg_tx.append(round(tx, 1))
            res = r.get("results", {})
            c03 = res.get("c03E2eMs") or res.get("pot", {}).get("c03E2eMs")
            if isinstance(c03, dict):
                if c03.get("p50") is not None: c03_p50.append(c03["p50"])
                if c03.get("p95") is not None: c03_p95.append(c03["p95"])
                if c03.get("p99") is not None: c03_p99.append(c03["p99"])
        entry = {
            "runs": len(runs),
            "pass": passes,
            "availabilityAllValid": all(r.get("availabilityValid") is True for r in runs),
        }
        if cg_cpus: entry["totalCpuMedian"] = round(statistics.median(cg_cpus), 4)
        if cg_cpus: entry["totalCpuRange"] = [min(cg_cpus), max(cg_cpus)]
        if pg_tx: entry["pgTxPerSecMedian"] = round(statistics.median(pg_tx), 1)
        if rss_total: entry["nonPgRssMibMedian"] = round(statistics.median(rss_total), 1)
        if c03_p50: entry["c03P50MedianMs"] = round(statistics.median(c03_p50), 1)
        if c03_p95: entry["c03P95MedianMs"] = round(statistics.median(c03_p95), 1)
        if c03_p95: entry["c03P95RangeMs"] = [min(c03_p95), max(c03_p95)]
        if c03_p99: entry["c03P99MedianMs"] = round(statistics.median(c03_p99), 1)
        summary[key] = entry
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work-dir", default="/tmp/pocoma-t1a-nats-qualif")
    ap.add_argument("--filter", default=None, help="Optional substring filter on scenario name")
    args = ap.parse_args()
    out_root = Path(args.work_dir)
    out_root.mkdir(parents=True, exist_ok=True)
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)

    receipts = []
    for topo, variant, scenario, reps in PLAN:
        if args.filter and args.filter not in scenario:
            continue
        for rep in range(1, reps + 1):
            receipts.append(run_one(topo, variant, scenario, rep, out_root))

    results_path = EVIDENCE_DIR / "t1a-nats-results.json"
    summary_path = EVIDENCE_DIR / "t1a-nats-summary.json"
    results_path.write_text(json.dumps(receipts, indent=2) + "\n")
    summary = extract_summary(receipts)
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")

    lines = []
    for f in sorted((results_path, summary_path)):
        h = hashlib.sha256(f.read_bytes()).hexdigest()
        lines.append(f"{h}  {f.name}")
    (EVIDENCE_DIR / "manifest.sha256").write_text("\n".join(lines) + "\n")

    total = len(receipts)
    passed = sum(1 for r in receipts if r.get("verdict") == "PASS")
    print(f"\n=== CAMPAIGN COMPLETE: {passed}/{total} PASS ===", flush=True)
    print(json.dumps(summary, indent=2), flush=True)
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
