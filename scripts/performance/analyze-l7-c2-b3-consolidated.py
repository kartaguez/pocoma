#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B3 Consolidated Evidence & Worker Resource Profile Analyzer.

Extracts and consolidates:
  1. Storage, WAL, and latency comparisons across A3+A4, C2-B1.4, and C2-B2.4.
  2. Full worker resource profiles (CPU, JVM heap, GC, HikariCP, PG TPS/WAL/IO,
     discovery/polling, attempt duration by projection, worker occupancy, contention)
     from Prometheus `.prom` snapshots, `resources.json`, and `manifest.json`.
"""

import json
from pathlib import Path
import re
import statistics

ROOT = Path(__file__).resolve().parents[2]
LINE_RE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+]?[0-9eE.+naninf]+)$")
LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"')


def parse_prom(path: Path):
    metrics = []
    if not path.exists():
        return metrics
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        m = LINE_RE.match(raw.strip())
        if not m:
            continue
        name, labels_raw, val_raw = m.groups()
        try:
            val = float(val_raw)
        except ValueError:
            continue
        labels = dict(LABEL_RE.findall(labels_raw)) if labels_raw else {}
        metrics.append((name, labels, val))
    return metrics


def sum_metric(metrics, name, **label_filter):
    total = 0.0
    found = False
    for m_name, labels, val in metrics:
        if m_name != name:
            continue
        if all(labels.get(k) == v for k, v in label_filter.items()):
            total += val
            found = True
    return total if found else None


def group_metric(metrics, name, group_key):
    out = {}
    for m_name, labels, val in metrics:
        if m_name != name:
            continue
        k = labels.get(group_key, "<none>")
        out[k] = out.get(k, 0.0) + val
    return out


def parse_cpu_pct(s):
    return float(s.rstrip("%"))


def parse_mem_mib(s):
    part = s.split("/")[0].strip()
    if part.endswith("GiB"):
        return float(part[:-3]) * 1024.0
    if part.endswith("MiB"):
        return float(part[:-3])
    if part.endswith("KiB"):
        return float(part[:-3]) / 1024.0
    if part.endswith("B"):
        return float(part[:-1]) / (1024.0 * 1024.0)
    return 0.0


def analyze_resources_json(res_path: Path):
    if not res_path.exists():
        return {}
    samples = json.loads(res_path.read_text(encoding="utf-8"))
    by_container = {}
    for s in samples:
        for st in s.get("stats", []):
            name = st["Name"].split("-")
            # e.g. pocoma-l74a-xxxx-business-read-worker-1 -> business-read-worker
            short = "-".join(name[3:-1]) if len(name) >= 5 else st["Name"]
            by_container.setdefault(short, {"cpu": [], "mem_mib": [], "block_io": [], "net_io": []})
            by_container[short]["cpu"].append(parse_cpu_pct(st["CPUPerc"]))
            by_container[short]["mem_mib"].append(parse_mem_mib(st["MemUsage"]))
            by_container[short]["block_io"].append(st["BlockIO"])
            by_container[short]["net_io"].append(st["NetIO"])
    summary = {}
    for k, v in by_container.items():
        summary[k] = {
            "samples": len(v["cpu"]),
            "cpuAvgPct": round(statistics.mean(v["cpu"]), 2),
            "cpuMaxPct": round(max(v["cpu"]), 2),
            "memAvgMiB": round(statistics.mean(v["mem_mib"]), 1),
            "memMaxMiB": round(max(v["mem_mib"]), 1),
            "lastBlockIO": v["block_io"][-1] if v["block_io"] else None,
            "lastNetIO": v["net_io"][-1] if v["net_io"] else None,
        }
    return summary


def delta(fin, init):
    if fin is None:
        return None
    if init is None:
        return fin
    return fin - init


def analyze_worker_prom(run_dir: Path, worker_name: str):
    init_m = parse_prom(run_dir / f"metrics-initial-{worker_name}.prom")
    fin_m = parse_prom(run_dir / f"metrics-final-{worker_name}.prom")
    if not fin_m:
        return None

    heap_used = sum_metric(fin_m, "jvm_memory_used_bytes", area="heap")
    heap_max = sum_metric(fin_m, "jvm_memory_max_bytes", area="heap")
    gc_count = delta(sum_metric(fin_m, "jvm_gc_pause_seconds_count"), sum_metric(init_m, "jvm_gc_pause_seconds_count"))
    gc_sum = delta(sum_metric(fin_m, "jvm_gc_pause_seconds_sum"), sum_metric(init_m, "jvm_gc_pause_seconds_sum"))
    cpu_time_s = delta(sum_metric(fin_m, "process_cpu_time_ns_total"), sum_metric(init_m, "process_cpu_time_ns_total"))
    if cpu_time_s is not None:
        cpu_time_s /= 1e9

    hikari_pools = {}
    for pool in sorted(group_metric(fin_m, "hikaricp_connections_max", "pool").keys()):
        acq_cnt = delta(
            sum_metric(fin_m, "hikaricp_connections_acquire_seconds_count", pool=pool),
            sum_metric(init_m, "hikaricp_connections_acquire_seconds_count", pool=pool),
        )
        acq_sum = delta(
            sum_metric(fin_m, "hikaricp_connections_acquire_seconds_sum", pool=pool),
            sum_metric(init_m, "hikaricp_connections_acquire_seconds_sum", pool=pool),
        )
        usage_sum = delta(
            sum_metric(fin_m, "hikaricp_connections_usage_seconds_sum", pool=pool),
            sum_metric(init_m, "hikaricp_connections_usage_seconds_sum", pool=pool),
        )
        hikari_pools[pool] = {
            "max": sum_metric(fin_m, "hikaricp_connections_max", pool=pool),
            "activeEnd": sum_metric(fin_m, "hikaricp_connections_active", pool=pool),
            "pendingEnd": sum_metric(fin_m, "hikaricp_connections_pending", pool=pool),
            "acquireCountDelta": acq_cnt,
            "acquireTotalMsDelta": round(acq_sum * 1000.0, 2) if acq_sum is not None else None,
            "acquireAvgMs": round((acq_sum * 1000.0) / acq_cnt, 4) if (acq_cnt and acq_sum is not None) else 0.0,
            "usageTotalMsDelta": round(usage_sum * 1000.0, 2) if usage_sum is not None else None,
            "timeoutTotal": sum_metric(fin_m, "hikaricp_connections_timeout_total", pool=pool),
        }

    # Attempt durations by consumption
    attempts = {}
    fin_att_sum = group_metric(fin_m, "pocoma_v3_attempt_duration_seconds_sum", "consumption")
    init_att_sum = group_metric(init_m, "pocoma_v3_attempt_duration_seconds_sum", "consumption")
    fin_att_cnt = group_metric(fin_m, "pocoma_v3_attempt_duration_seconds_count", "consumption")
    init_att_cnt = group_metric(init_m, "pocoma_v3_attempt_duration_seconds_count", "consumption")
    for cons in sorted(fin_att_sum.keys()):
        dc = fin_att_cnt.get(cons, 0.0) - init_att_cnt.get(cons, 0.0)
        ds = fin_att_sum.get(cons, 0.0) - init_att_sum.get(cons, 0.0)
        attempts[cons] = {
            "countDelta": int(dc),
            "totalMsDelta": round(ds * 1000.0, 2),
            "avgMs": round((ds * 1000.0) / dc, 3) if dc > 0 else 0.0,
        }

    # Discovery durations by consumption
    discovery = {}
    fin_dis_sum = group_metric(fin_m, "pocoma_v3_discovery_duration_seconds_sum", "consumption")
    init_dis_sum = group_metric(init_m, "pocoma_v3_discovery_duration_seconds_sum", "consumption")
    fin_dis_cnt = group_metric(fin_m, "pocoma_v3_discovery_duration_seconds_count", "consumption")
    init_dis_cnt = group_metric(init_m, "pocoma_v3_discovery_duration_seconds_count", "consumption")
    for cons in sorted(fin_dis_sum.keys()):
        dc = fin_dis_cnt.get(cons, 0.0) - init_dis_cnt.get(cons, 0.0)
        ds = fin_dis_sum.get(cons, 0.0) - init_dis_sum.get(cons, 0.0)
        discovery[cons] = {
            "countDelta": int(dc),
            "totalMsDelta": round(ds * 1000.0, 2),
            "avgMs": round((ds * 1000.0) / dc, 3) if dc > 0 else 0.0,
        }

    # Worker cycles
    cycles = {}
    fin_cyc_sum = group_metric(fin_m, "pocoma_v3_worker_cycle_duration_seconds_sum", "loop")
    init_cyc_sum = group_metric(init_m, "pocoma_v3_worker_cycle_duration_seconds_sum", "loop")
    fin_cyc_cnt = group_metric(fin_m, "pocoma_v3_worker_cycle_duration_seconds_count", "loop")
    init_cyc_cnt = group_metric(init_m, "pocoma_v3_worker_cycle_duration_seconds_count", "loop")
    for loop in sorted(fin_cyc_sum.keys()):
        dc = fin_cyc_cnt.get(loop, 0.0) - init_cyc_cnt.get(loop, 0.0)
        ds = fin_cyc_sum.get(loop, 0.0) - init_cyc_sum.get(loop, 0.0)
        cycles[loop] = {
            "countDelta": int(dc),
            "totalMsDelta": round(ds * 1000.0, 2),
        }

    sat_delta = delta(sum_metric(fin_m, "pocoma_v3_saturation_total"), sum_metric(init_m, "pocoma_v3_saturation_total"))
    cap_limit = sum_metric(fin_m, "pocoma_v3_capacity_limit")

    return {
        "heapUsedMiB": round(heap_used / (1024.0 * 1024.0), 1) if heap_used is not None else None,
        "heapMaxMiB": round(heap_max / (1024.0 * 1024.0), 1) if heap_max is not None else None,
        "gcCountDelta": int(gc_count) if gc_count is not None else None,
        "gcPauseMsDelta": round(gc_sum * 1000.0, 2) if gc_sum is not None else None,
        "processCpuSecDelta": round(cpu_time_s, 3) if cpu_time_s is not None else None,
        "capacityLimit": int(cap_limit) if cap_limit is not None else None,
        "saturationDelta": int(sat_delta) if sat_delta is not None else None,
        "hikariPools": hikari_pools,
        "attemptsByConsumption": attempts,
        "discoveryByConsumption": discovery,
        "workerCycles": cycles,
    }


def analyze_run(run_dir: Path):
    manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
    init_db = manifest.get("initialSnapshot", {}).get("databases", {})
    fin_db = manifest.get("finalSnapshot", {}).get("databases", {})
    db_deltas = {}
    for db_name in ("write", "read"):
        i_d = init_db.get(db_name, {})
        f_d = fin_db.get(db_name, {})
        i_tx = i_d.get("transactions", {})
        f_tx = f_d.get("transactions", {})
        i_wal = i_d.get("wal", {}).get("wal_bytes")
        f_wal = f_d.get("wal", {}).get("wal_bytes")
        db_deltas[db_name] = {
            "initBytes": i_d.get("databaseBytes"),
            "finalBytes": f_d.get("databaseBytes"),
            "walDeltaBytes": (f_wal - i_wal) if (i_wal is not None and f_wal is not None) else None,
            "xactCommitDelta": (f_tx.get("xact_commit", 0) - i_tx.get("xact_commit", 0)) if f_tx and i_tx else None,
            "xactRollbackDelta": (f_tx.get("xact_rollback", 0) - i_tx.get("xact_rollback", 0)) if f_tx and i_tx else None,
            "blksReadDelta": (f_tx.get("blks_read", 0) - i_tx.get("blks_read", 0)) if f_tx and i_tx else None,
            "blksHitDelta": (f_tx.get("blks_hit", 0) - i_tx.get("blks_hit", 0)) if f_tx and i_tx else None,
        }

    workers = {}
    for w in ("http-write", "http-read", "command-worker", "results-worker", "business-read-worker"):
        res = analyze_worker_prom(run_dir, w)
        if res is not None:
            workers[w] = res

    return {
        "runDir": str(run_dir.relative_to(ROOT)),
        "git": manifest.get("git"),
        "profile": manifest.get("profile"),
        "latenciesMs": manifest.get("latenciesMs"),
        "dbDeltas": db_deltas,
        "containerResources": analyze_resources_json(run_dir / "resources.json"),
        "workers": workers,
    }


def main():
    targets = {
        "L7-4A4_restore-large-1": ROOT / "docs/testing/evidence/L7-4A4/restore-large-1",
        "L7-C2-B1.4_restore-large-1": ROOT / "docs/testing/evidence/L7-C2-B1-4/restore-large-1",
        "L7-C2-B2.4_restore-large-1": ROOT / "docs/testing/evidence/L7-C2-B2/B2_4_requalification/restore-large-1",
        "L7-C2-B2.4_med-1": ROOT / "docs/testing/evidence/L7-C2-B2/B2_4_requalification/med-1",
        "L7-C2-B2.4_multibucket": ROOT / "docs/testing/evidence/L7-C2-B2/B2_4_requalification/multibucket-realistic",
    }
    report = {}
    for name, path in targets.items():
        if (path / "manifest.json").exists():
            report[name] = analyze_run(path)
    out_dir = ROOT / "docs/testing/evidence/L7-C2-B3"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "worker_resources_and_db_comparison.json"
    out_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out_path}")

    # Print concise summary for business-read-worker on B2.4 restore-large-1
    b2 = report.get("L7-C2-B2.4_restore-large-1", {})
    brw = b2.get("workers", {}).get("business-read-worker", {})
    print("B2.4 restore-large-1 business-read-worker attempts:", json.dumps(brw.get("attemptsByConsumption"), indent=2))
    print("B2.4 restore-large-1 containers:", json.dumps(b2.get("containerResources"), indent=2))


if __name__ == "__main__":
    main()
