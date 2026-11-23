#!/usr/bin/env python3
"""Repeated remediation benchmarks for Pocoma V3 T1A + Core NATS:
- c4-backlog-10k (R=3 interleaved A-20 vs D-nats)
- c3-int-pot-vs-id-deep (80 Identity probes per run, R=2 interleaved A-20 vs D-nats)
"""
import argparse
import json
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = Path(__file__).resolve().parent / "run-t1a-bench.py"
EVIDENCE_DIR = ROOT / "docs/testing/evidence/t1a-nats-qualification"

PLAN = [
    # Interleaved c3-int-pot-vs-id-deep (80 Identity probes x 2 reps = 160 probes/config)
    ("T1A", "A-20", "c3-int-pot-vs-id-deep", 1),
    ("T1A", "D-nats", "c3-int-pot-vs-id-deep", 1),
    ("T1A", "A-20", "c3-int-pot-vs-id-deep", 2),
    ("T1A", "D-nats", "c3-int-pot-vs-id-deep", 2),
    # Interleaved c4-backlog-10k (R=3 reps/config)
    ("T1A", "A-20", "c4-backlog-10k", 1),
    ("T1A", "D-nats", "c4-backlog-10k", 1),
    ("T1A", "A-20", "c4-backlog-10k", 2),
    ("T1A", "D-nats", "c4-backlog-10k", 2),
    ("T1A", "A-20", "c4-backlog-10k", 3),
    ("T1A", "D-nats", "c4-backlog-10k", 3),
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
        return {
            "runId": run_id, "topology": topology, "variant": variant,
            "scenario": scenario, "rep": rep, "verdict": "FAIL",
            "error": f"missing receipt (exit={proc.returncode})",
        }
    receipt = json.loads(receipt_path.read_text())
    receipt["runId"] = run_id
    receipt["rep"] = rep
    print(f"<<< [{run_id}] verdict={receipt.get('verdict')} avail={receipt.get('availabilityValid')}", flush=True)
    return receipt


def summarize(receipts):
    groups = {}
    for r in receipts:
        key = f"{r['topology']}|{r['variant']}|{r['scenario']}"
        groups.setdefault(key, []).append(r)
    out = {}
    for key, runs in groups.items():
        entry = {
            "runs": len(runs),
            "pass": sum(1 for r in runs if r.get("verdict") == "PASS"),
            "availabilityAllValid": all(r.get("availabilityValid") is True for r in runs),
        }
        cg_totals, pg_cpus, worker_cpus, nats_cpus = [], [], [], []
        pg_tx_rates = []
        for r in runs:
            cg = r.get("cgroupDelta", {}).get("containers", {})
            wall = r.get("cgroupDelta", {}).get("wall_seconds", 0) or 1.0
            if cg:
                cg_totals.append(round(sum(v.get("avg_cpus", 0) for v in cg.values()), 4))
                pg_cpus.append(round(cg.get("postgres", {}).get("avg_cpus", 0), 4))
                worker_cpus.append(round(sum(v.get("avg_cpus", 0) for k, v in cg.items() if "worker" in k), 4))
                nats_cpus.append(round(cg.get("nats", {}).get("avg_cpus", 0), 4))
            pgtx = r.get("pgTxDelta", {})
            if pgtx:
                total_tx = sum(pgtx.get(db, {}).get("xact_commit", 0) for db in ("pocoma_write", "pocoma_read"))
                pg_tx_rates.append(round(total_tx / wall, 1))
        if cg_totals:
            entry["totalCpuMedian"] = round(statistics.median(cg_totals), 4)
            entry["totalCpuAll"] = cg_totals
            entry["pgCpuMedian"] = round(statistics.median(pg_cpus), 4)
            entry["workersCpuMedian"] = round(statistics.median(worker_cpus), 4)
            entry["natsCpuMedian"] = round(statistics.median(nats_cpus), 4)
        if pg_tx_rates:
            entry["pgTxPerSecMedian"] = round(statistics.median(pg_tx_rates), 1)
            entry["pgTxPerSecAll"] = pg_tx_rates

        if "c4-backlog-10k" in key:
            drains = [r.get("results", {}).get("drainSeconds") for r in runs if r.get("results", {}).get("drainSeconds") is not None]
            tputs = [r.get("results", {}).get("drainThroughputCmdPerSec") for r in runs if r.get("results", {}).get("drainThroughputCmdPerSec") is not None]
            id_p50 = [r.get("results", {}).get("concurrentIdentity", {}).get("usableBindingE2eMs", {}).get("p50") for r in runs]
            id_p95 = [r.get("results", {}).get("concurrentIdentity", {}).get("usableBindingE2eMs", {}).get("p95") for r in runs]
            id_p50 = [x for x in id_p50 if x is not None]
            id_p95 = [x for x in id_p95 if x is not None]
            if drains:
                entry["drainSecondsMedian"] = round(statistics.median(drains), 2)
                entry["drainSecondsAll"] = [round(x, 2) for x in drains]
            if tputs:
                entry["drainThroughputMedian"] = round(statistics.median(tputs), 2)
                entry["drainThroughputAll"] = [round(x, 2) for x in tputs]
            if id_p50:
                entry["concurrentIdP50MedianMs"] = round(statistics.median(id_p50), 1)
            if id_p95:
                entry["concurrentIdP95MedianMs"] = round(statistics.median(id_p95), 1)
                entry["concurrentIdP95AllMs"] = [round(x, 1) for x in id_p95]
            entry["wakeupTotalsPerRun"] = [
                {
                    "published": sum(v.get("wakeup_published", 0) for v in r.get("wakeupAndJvm", {}).values()),
                    "coalesced": sum(v.get("wakeup_coalesced", 0) for v in r.get("wakeupAndJvm", {}).values()),
                    "received": sum(v.get("wakeup_received", 0) for v in r.get("wakeupAndJvm", {}).values()),
                    "useful": sum(v.get("wakeup_useful", 0) for v in r.get("wakeupAndJvm", {}).values()),
                    "spurious": sum(v.get("wakeup_spurious", 0) for v in r.get("wakeupAndJvm", {}).values()),
                    "hikariMaxAcquireMs": {k: v.get("hikari_acquire_max_ms") for k, v in r.get("wakeupAndJvm", {}).items()},
                }
                for r in runs
            ]
        elif "c3-int-pot-vs-id-deep" in key:
            all_usable = []
            all_result = []
            all_outliers = []
            for r in runs:
                ip = r.get("results", {}).get("identityProbe", {})
                all_usable.extend(ip.get("rawUsableBindingMs", []))
                all_result.extend(ip.get("rawResultMs", []))
                all_outliers.extend(ip.get("outliersGe300Ms", []))
            if all_usable:
                s = sorted(all_usable)
                n = len(s)
                entry["pooledIdentityProbes"] = n
                entry["pooledUsableBindingMs"] = {
                    "p50": s[int(0.50 * (n - 1))],
                    "p75": s[int(0.75 * (n - 1))],
                    "p90": s[int(0.90 * (n - 1))],
                    "p95": s[int(0.95 * (n - 1))],
                    "p99": s[int(0.99 * (n - 1))],
                    "max": s[-1],
                }
                sr = sorted(all_result)
                nr = len(sr)
                entry["pooledResultMs"] = {
                    "p50": sr[int(0.50 * (nr - 1))],
                    "p95": sr[int(0.95 * (nr - 1))],
                    "p99": sr[int(0.99 * (nr - 1))],
                    "max": sr[-1],
                }
                entry["outliersGe300MsCount"] = len(all_outliers)
                entry["outliersGe300Ms"] = all_outliers
        out[key] = entry
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work-dir", default="/tmp/pocoma-t1a-remediation-bench")
    args = ap.parse_args()
    out_root = Path(args.work_dir)
    out_root.mkdir(parents=True, exist_ok=True)
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)

    receipts = []
    for topo, variant, scenario, rep in PLAN:
        receipts.append(run_one(topo, variant, scenario, rep, out_root))

    results_path = EVIDENCE_DIR / "t1a-nats-remediation-bench.json"
    summary_path = EVIDENCE_DIR / "t1a-nats-remediation-summary.json"
    results_path.write_text(json.dumps(receipts, indent=2) + "\n")
    summary = summarize(receipts)
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")

    passed = sum(1 for r in receipts if r.get("verdict") == "PASS")
    print(f"\n=== REMEDIATION BENCHMARKS COMPLETE: {passed}/{len(receipts)} PASS ===", flush=True)
    print(json.dumps(summary, indent=2), flush=True)
    sys.exit(0 if passed == len(receipts) else 1)


if __name__ == "__main__":
    main()
