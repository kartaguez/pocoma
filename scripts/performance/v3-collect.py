#!/usr/bin/env python3
"""Pocoma V3 — Metrology & Performance Diagnostic Collector (scripts/performance/v3-collect.py).

Collects and consolidates:
1. k6 client-side execution metrics (throughput, iterations, admissions, latencies p50/95/99, retries, 503 counts).
2. Pocoma V3 engine telemetry (Admission, WRITE queue wait, Attempt prepare/finalize, Discovery, Projection, Read HTTP).
3. Host & JVM system telemetry (CPU, Heap/Non-Heap RSS, GC pauses, Threads, HikariCP pools).
4. PostgreSQL performance metrics (active sessions, lock waits, slots backlog, WAL bytes).

Supports differential metrics snapshots to measure exact scenario windows.
Outputs consolidated RAW JSON artifacts and human-readable summaries.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"

POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
READ_DB = os.environ.get("READ_DB", "pocoma_read")
ACTUATOR_URL = os.environ.get("POCOMA_ACTUATOR_URL", "http://127.0.0.1:18081")


def run_psql(db: str, sql_command: str) -> str:
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        "-e", "PGOPTIONS=-c default_transaction_read_only=on",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return ""
    return res.stdout.strip()


def query_prometheus(prometheus_url: str, promql: str) -> Optional[float]:
    url = f"{prometheus_url.rstrip('/')}/api/v1/query?query={urllib.parse.quote(promql)}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Pocoma-Collector/1.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            results = data.get("data", {}).get("result", [])
            if results and "value" in results[0]:
                val = float(results[0]["value"][1])
                return val if not (val != val) else None  # Filter NaN
    except Exception:
        pass
    return None


def fetch_actuator_prometheus_text(actuator_url: str = ACTUATOR_URL) -> str:
    url = f"{actuator_url.rstrip('/')}/actuator/prometheus"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Pocoma-Collector/1.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.read().decode("utf-8")
    except Exception:
        return ""


def parse_prometheus_text(text: str) -> Dict[str, List[Tuple[Dict[str, str], float]]]:
    metrics: Dict[str, List[Tuple[Dict[str, str], float]]] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(.+)$", line)
        if not m:
            continue
        name, raw_tags, val_str = m.groups()
        try:
            val = float(val_str)
        except ValueError:
            continue
        tags = {}
        if raw_tags:
            for part in re.finditer(r"([a-zA-Z_0-9]+)=\"([^\"]*)\"", raw_tags):
                tags[part.group(1)] = part.group(2)
        metrics.setdefault(name, []).append((tags, val))
    return metrics


def match_tags(series_tags: Dict[str, str], required_tags: Dict[str, str]) -> bool:
    for k, v in required_tags.items():
        if series_tags.get(k) != v:
            return False
    return True


def extract_histogram(metrics: Dict[str, Any], base_name: str,
                      tags_filter: Dict[str, str]) -> Dict[str, Any]:
    bucket_series = metrics.get(f"{base_name}_bucket", [])
    count_series = metrics.get(f"{base_name}_count", [])
    sum_series = metrics.get(f"{base_name}_sum", [])
    max_series = metrics.get(f"{base_name}_max", [])

    total_count = 0.0
    for tags, val in count_series:
        if match_tags(tags, tags_filter):
            total_count += val

    total_sum = 0.0
    for tags, val in sum_series:
        if match_tags(tags, tags_filter):
            total_sum += val

    max_val = 0.0
    for tags, val in max_series:
        if match_tags(tags, tags_filter):
            max_val = max(max_val, val)

    buckets: List[Tuple[float, float]] = []
    for tags, count in bucket_series:
        if match_tags(tags, tags_filter) and "le" in tags:
            le_str = tags["le"]
            le_val = float("inf") if le_str == "+Inf" else float(le_str)
            buckets.append((le_val, count))

    buckets.sort(key=lambda b: b[0])
    return {"count": total_count, "sum": total_sum, "max": max_val, "buckets": buckets}


def compute_quantile(hist: Dict[str, Any], q: float) -> float:
    buckets = hist.get("buckets", [])
    total = hist.get("count", 0.0)
    if total <= 0 or not buckets:
        return 0.0
    rank = q * total
    prev_le = 0.0
    prev_count = 0.0
    for le, count in buckets:
        if count >= rank:
            if le == float("inf"):
                return prev_le
            cnt_diff = count - prev_count
            if cnt_diff <= 0:
                return le
            return prev_le + (le - prev_le) * ((rank - prev_count) / cnt_diff)
        prev_le = le
        prev_count = count
    return prev_le


def diff_histograms(post: Dict[str, Any], pre: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not pre:
        return post
    d_count = max(0.0, post.get("count", 0.0) - pre.get("count", 0.0))
    d_sum = max(0.0, post.get("sum", 0.0) - pre.get("sum", 0.0))
    d_max = post.get("max", 0.0)

    post_b = {b[0]: b[1] for b in post.get("buckets", [])}
    pre_b = {b[0]: b[1] for b in pre.get("buckets", [])}

    diff_buckets = []
    for le in sorted(post_b.keys()):
        diff_cnt = max(0.0, post_b[le] - pre_b.get(le, 0.0))
        diff_buckets.append((le, diff_cnt))

    return {"count": d_count, "sum": d_sum, "max": d_max, "buckets": diff_buckets}


def summarize_histogram(hist: Dict[str, Any], unit_multiplier: float = 1000.0) -> Dict[str, Any]:
    count = hist.get("count", 0.0)
    total_sum = hist.get("sum", 0.0)
    avg = (total_sum / count * unit_multiplier) if count > 0 else 0.0
    p50 = compute_quantile(hist, 0.50) * unit_multiplier if count > 0 else 0.0
    p95 = compute_quantile(hist, 0.95) * unit_multiplier if count > 0 else 0.0
    p99 = compute_quantile(hist, 0.99) * unit_multiplier if count > 0 else 0.0
    max_val = hist.get("max", 0.0) * unit_multiplier
    return {
        "count": int(count),
        "avg": round(avg, 2),
        "p50": round(p50, 2),
        "p95": round(p95, 2),
        "p99": round(p99, 2),
        "max": round(max_val, 2),
    }


def collect_k6_summary(summary_path: Path) -> Dict[str, Any]:
    if not summary_path.exists():
        return {"available": False, "error": f"Summary file not found: {summary_path}"}

    try:
        with open(summary_path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception as e:
        return {"available": False, "error": f"Failed to parse k6 summary: {e}"}

    metrics = raw.get("metrics", {})

    def extract_trend(name: str) -> Dict[str, Optional[float]]:
        m = metrics.get(name, {})
        v = m.get("values", {})
        return {
            "count": v.get("count", 0),
            "p50": round(v.get("p(50)"), 2) if v.get("p(50)") is not None else (round(v.get("med"), 2) if v.get("med") is not None else None),
            "p90": round(v.get("p(90)"), 2) if v.get("p(90)") is not None else None,
            "p95": round(v.get("p(95)"), 2) if v.get("p(95)") is not None else None,
            "p99": round(v.get("p(99)"), 2) if v.get("p(99)") is not None else None,
            "avg": round(v.get("avg"), 2) if v.get("avg") is not None else None,
            "min": round(v.get("min"), 2) if v.get("min") is not None else None,
            "max": round(v.get("max"), 2) if v.get("max") is not None else None,
        }

    def extract_counter(name: str) -> int:
        m = metrics.get(name, {})
        v = m.get("values", {})
        return int(v.get("count", 0))

    offered = extract_counter("v3_admissions_offered")
    attempted = extract_counter("v3_admissions_attempted")
    accepted = extract_counter("v3_admissions_accepted")
    first_success = extract_counter("v3_admissions_first_attempt_success")
    retried_success = extract_counter("v3_admissions_retried_success")
    cap_rejected = extract_counter("v3_admissions_capacity_rejected")
    retries_total = extract_counter("v3_admission_retries_total")

    first_rate = (first_success / offered * 100.0) if offered > 0 else 100.0
    final_rate = (accepted / offered * 100.0) if offered > 0 else 100.0
    avg_retries = (retries_total / offered) if offered > 0 else 0.0

    return {
        "available": True,
        "admissionsOffered": offered,
        "admissionsAttempted": attempted,
        "admissionsAccepted": accepted,
        "firstAttemptSuccess": first_success,
        "retriedSuccess": retried_success,
        "capacityRejected": cap_rejected,
        "firstAttemptSuccessRate": round(first_rate, 2),
        "finalSuccessRate": round(final_rate, 2),
        "admissionRetriesTotal": retries_total,
        "avgRetriesPerCommand": round(avg_retries, 3),
        "commandsApplied": extract_counter("v3_commands_applied"),
        "terminalResults": extract_counter("v3_terminal_results_count"),
        "commandsRejected": extract_counter("v3_commands_rejected"),
        "admissionErrors": extract_counter("v3_admission_errors"),
        "oracleFailures": extract_counter("v3_oracle_failures"),
        "censoredTimeouts": extract_counter("v3_censored_timeouts"),
        "droppedIterations": extract_counter("dropped_iterations"),
        "writeCapacity503": extract_counter("v3_write_capacity_503"),
        "readCapacity503": extract_counter("v3_read_capacity_503"),
        "readMaintenance503": extract_counter("v3_read_maintenance_503"),
        "probeRequests": extract_counter("v3_probe_requests"),
        "readQueriesTotal": extract_counter("v3_read_queries_total"),
        "readQueries200": extract_counter("v3_read_queries_200"),
        "readQueriesErrors": extract_counter("v3_read_queries_errors"),
        "latencies": {
            "admissionFirstTry": extract_trend("v3_admission_duration"),
            "admissionWithRetries": extract_trend("v3_admission_duration_with_retries"),
            "commandResult": extract_trend("v3_command_result_duration"),
            "balanceAvailable": extract_trend("v3_balance_available_duration"),
            "potReadAvailable": extract_trend("v3_pot_read_available_duration"),
            "readPropagationLag": extract_trend("v3_read_propagation_lag"),
            "e2eTotal": extract_trend("v3_e2e_total_duration"),
            "retriesPerCommand": extract_trend("v3_admission_retries_per_command"),
            "readQueryHttp": extract_trend("v3_read_query_http_duration"),
        },
    }


def collect_engine_telemetry(prom_url: str, post_metrics: Dict[str, Any],
                             pre_metrics: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    # Extract differential stage timers
    def stage(base: str, tags: Dict[str, str]) -> Dict[str, Any]:
        post_h = extract_histogram(post_metrics, base, tags)
        pre_h = extract_histogram(pre_metrics, base, tags) if pre_metrics else None
        dh = diff_histograms(post_h, pre_h)
        return summarize_histogram(dh)

    stages = {
        "admissionHttp": stage("pocoma_v3_admission_http_duration_seconds", {"endpoint": "command", "status": "202"}),
        "writeWait": stage("pocoma_v3_consumption_opportunity_wait_seconds", {"consumption": "Command"}),
        "writePrepare": stage("pocoma_v3_attempt_phase_duration_seconds", {"consumption": "Command", "phase": "prepare"}),
        "writeFinalize": stage("pocoma_v3_attempt_phase_duration_seconds", {"consumption": "Command", "phase": "finalize"}),
        "writeTotalAttempt": stage("pocoma_v3_attempt_duration_seconds", {"consumption": "Command"}),
        "readDiscovery": stage("pocoma_v3_discovery_duration_seconds", {"consumption": "PotVersion"}),
        "readProjectionPrepare": stage("pocoma_v3_attempt_phase_duration_seconds", {"consumption": "PotVersion", "phase": "prepare"}),
        "readProjectionFinalize": stage("pocoma_v3_attempt_phase_duration_seconds", {"consumption": "PotVersion", "phase": "finalize"}),
        "readProjectionTotalAttempt": stage("pocoma_v3_attempt_duration_seconds", {"consumption": "PotVersion"}),
        "readHttp": stage("pocoma_v3_read_http_duration_seconds", {}),
        "readQuery": stage("pocoma_v3_read_query_duration_seconds", {}),
    }

    return {
        "stages": stages,
        "admissionRequestsTotal": query_prometheus(prom_url, "sum(pocoma_v3_admission_requests_total)"),
        "attemptOutcomesCommitted": query_prometheus(prom_url, 'sum(pocoma_v3_attempt_outcomes_total{outcome="committed"})'),
        "attemptOutcomesRetried": query_prometheus(prom_url, 'sum(pocoma_v3_attempt_outcomes_total{outcome="retried"})'),
        "discoveryReferencesTotal": query_prometheus(prom_url, "sum(pocoma_v3_discovery_references_total)"),
        "claimOperationsTotal": query_prometheus(prom_url, "sum(pocoma_v3_claim_operations_total)"),
        "physicalPendingCommand": query_prometheus(prom_url, 'pocoma_v3_physical_pending{consumption="Command"}'),
        "physicalPendingPotVersion": query_prometheus(prom_url, 'pocoma_v3_physical_pending{consumption="PotVersion"}'),
        "physicalPendingTotal": query_prometheus(prom_url, "sum(pocoma_v3_physical_pending)"),
        "readQuotaPermitsAvailable": query_prometheus(prom_url, "sum(pocoma_v3_read_quota_permits)"),
        "readQuotaRejectionsTotal": query_prometheus(prom_url, "sum(pocoma_v3_read_quota_rejections_total)"),
        "admissionCapacityAvailable": query_prometheus(prom_url, "sum(pocoma_v3_admission_capacity_available)"),
        "workerCyclesTotal": query_prometheus(prom_url, "sum(pocoma_v3_worker_cycles_total)"),
    }


def collect_system_telemetry(prom_url: str) -> Dict[str, Any]:
    return {
        "jvmHeapUsedBytes": query_prometheus(prom_url, 'sum(jvm_memory_used_bytes{area="heap"})'),
        "jvmHeapMaxBytes": query_prometheus(prom_url, 'sum(jvm_memory_max_bytes{area="heap"} > 0)'),
        "jvmNonHeapUsedBytes": query_prometheus(prom_url, 'sum(jvm_memory_used_bytes{area="nonheap"})'),
        "jvmGcPauseCount": query_prometheus(prom_url, "sum(jvm_gc_pause_seconds_count)"),
        "jvmGcPauseSumSeconds": query_prometheus(prom_url, "sum(jvm_gc_pause_seconds_sum)"),
        "processCpuUsage": query_prometheus(prom_url, "process_cpu_usage"),
        "systemCpuUsage": query_prometheus(prom_url, "system_cpu_usage"),
        "jvmLiveThreads": query_prometheus(prom_url, "jvm_threads_live_threads"),
        "hikariActiveConnections": query_prometheus(prom_url, "sum(hikaricp_connections_active)"),
        "hikariIdleConnections": query_prometheus(prom_url, "sum(hikaricp_connections_idle)"),
        "hikariPendingThreads": query_prometheus(prom_url, "sum(hikaricp_connections_pending)"),
    }


def collect_postgres_telemetry() -> Dict[str, Any]:
    try:
        active_sessions = run_psql(WRITE_DB, "SELECT count(*) FROM pg_stat_activity WHERE state='active';")
        total_sessions = run_psql(WRITE_DB, "SELECT count(*) FROM pg_stat_activity;")
        lock_waits = run_psql(WRITE_DB, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock';")
        wal_bytes = run_psql(WRITE_DB, "SELECT wal_bytes FROM pg_stat_wal;")
        write_slots_avail = run_psql(WRITE_DB, "SELECT count(*) FROM v3_write.consumption_slot WHERE state='AVAILABLE';")
        write_slots_quar = run_psql(WRITE_DB, "SELECT count(*) FROM v3_write.consumption_slot WHERE state='QUARANTINED';")
        read_slots_avail = run_psql(READ_DB, "SELECT count(*) FROM v3_read.consumption_slot WHERE state='AVAILABLE';")
        read_slots_quar = run_psql(READ_DB, "SELECT count(*) FROM v3_read.consumption_slot WHERE state='QUARANTINED';")
        read_maintenance = run_psql(READ_DB, "SELECT state FROM public.read_maintenance_state LIMIT 1;")

        return {
            "activeSessions": int(active_sessions) if active_sessions.isdigit() else 0,
            "totalSessions": int(total_sessions) if total_sessions.isdigit() else 0,
            "lockWaits": int(lock_waits) if lock_waits.isdigit() else 0,
            "walBytes": int(wal_bytes) if wal_bytes.isdigit() else 0,
            "writeSlotsAvailable": int(write_slots_avail) if write_slots_avail.isdigit() else 0,
            "writeSlotsQuarantined": int(write_slots_quar) if write_slots_quar.isdigit() else 0,
            "slotsPending": int(read_slots_avail) if read_slots_avail.isdigit() else 0,
            "slotsQuarantined": int(read_slots_quar) if read_slots_quar.isdigit() else 0,
            "readMaintenanceState": read_maintenance,
        }
    except Exception as e:
        return {"error": str(e)}


def generate_consolidated_report(scenario_name: str, k6_summary_file: Path,
                                 prom_url: str = "http://127.0.0.1:9090",
                                 actuator_url: str = ACTUATOR_URL,
                                 pre_snapshot_file: Optional[Path] = None,
                                 run_id: Optional[str] = None,
                                 decoupled_file: Optional[Path] = None,
                                 evidence_dir: Optional[Path] = None) -> Dict[str, Any]:
    rid = run_id or f"run-{int(time.time())}"
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    # Fetch post actuator metrics
    post_text = fetch_actuator_prometheus_text(actuator_url)
    post_metrics = parse_prometheus_text(post_text)

    pre_metrics = None
    if pre_snapshot_file and pre_snapshot_file.exists():
        try:
            with open(pre_snapshot_file, "r", encoding="utf-8") as f:
                pre_text = f.read()
            pre_metrics = parse_prometheus_text(pre_text)
        except Exception:
            pass

    k6_data = collect_k6_summary(k6_summary_file)
    engine_data = collect_engine_telemetry(prom_url, post_metrics, pre_metrics)
    system_data = collect_system_telemetry(prom_url)
    postgres_data = collect_postgres_telemetry()

    decoupled_data = None
    if decoupled_file and Path(decoupled_file).exists():
        try:
            with open(decoupled_file, "r", encoding="utf-8") as df:
                decoupled_data = json.load(df)
        except Exception as e:
            print(f"Warning: could not read decoupled metrics file: {e}", file=sys.stderr)

    report = {
        "runId": rid,
        "scenario": scenario_name,
        "timestamp": timestamp,
        "k6ClientMetrics": k6_data,
        "decoupledMetrics": decoupled_data,
        "engineTelemetry": engine_data,
        "systemTelemetry": system_data,
        "postgresTelemetry": postgres_data,
    }

    target_dir = Path(evidence_dir) if evidence_dir else (k6_summary_file.parent if k6_summary_file else EVIDENCE_DIR)
    target_dir.mkdir(parents=True, exist_ok=True)
    out_file = target_dir / f"{rid}_report.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Metrology & Diagnostic Collector")
    parser.add_argument("--scenario", default="smoke", help="Executed scenario name")
    parser.add_argument("--summary-file", default=str(EVIDENCE_DIR / "k6-summary.json"),
                        help="k6 JSON summary file path")
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090", help="Prometheus URL")
    parser.add_argument("--actuator-url", default=ACTUATOR_URL, help="Actuator URL")
    parser.add_argument("--pre-snapshot", help="Pre-scenario actuator metrics text snapshot path")
    parser.add_argument("--take-snapshot", help="Save current actuator metrics text snapshot to path and exit")
    parser.add_argument("--run-id", help="Unique benchmark run identifier")
    parser.add_argument("--decoupled-file", help="Path to decoupled JSON metrics file")
    parser.add_argument("--evidence-dir", help="Target evidence directory for reports")

    args = parser.parse_args()

    if args.take_snapshot:
        dest = Path(args.take_snapshot)
        dest.parent.mkdir(parents=True, exist_ok=True)
        raw = fetch_actuator_prometheus_text(args.actuator_url)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(raw)
        print(f"Snapshot written to {dest} ({len(raw)} bytes)")
        sys.exit(0)

    report = generate_consolidated_report(
        scenario_name=args.scenario,
        k6_summary_file=Path(args.summary_file),
        prom_url=args.prometheus_url,
        actuator_url=args.actuator_url,
        pre_snapshot_file=Path(args.pre_snapshot) if args.pre_snapshot else None,
        run_id=args.run_id,
        decoupled_file=Path(args.decoupled_file) if args.decoupled_file else None,
        evidence_dir=Path(args.evidence_dir) if args.evidence_dir else None,
    )

    k6_res = report["k6ClientMetrics"]
    lat = k6_res.get("latencies", {})
    stages = report["engineTelemetry"].get("stages", {})

    print("================================================================================")
    print(f" Pocoma V3 — Consolidated Deep Metrology Report [Run: {report['runId']}]")
    print("================================================================================")
    print(f"  Scenario:               {report['scenario']}")
    print(f"  Timestamp:              {report['timestamp']}")
    if k6_res.get("available"):
        offered = k6_res.get('admissionsOffered', 0)
        admitted = k6_res.get('admissionsAccepted', 0)
        first_try = k6_res.get('firstAttemptSuccess', 0)
        retried = k6_res.get('retriedSuccess', 0)
        cap_rej = k6_res.get('capacityRejected', 0)
        print(f"  Admissions Offered:     {offered}")
        print(f"  Admissions Admitted:    {admitted} (1st_try: {first_try}, retried: {retried}, cap_rejected: {cap_rej})")
        print(f"  First-Attempt Rate:     {k6_res.get('firstAttemptSuccessRate')}%")
        print(f"  Final Success Rate:     {k6_res.get('finalSuccessRate')}%")
        print(f"  Total Retries:          {k6_res.get('admissionRetriesTotal', 0)} (avg: {k6_res.get('avgRetriesPerCommand', 0)}/cmd)")
        print(f"  Commands Applied:       {k6_res.get('commandsApplied')}")
        print(f"  Commands Rejected:      {k6_res.get('commandsRejected')} (CAS / business)")
        print(f"  Oracle Failures:        {k6_res.get('oracleFailures')}")
        print(f"  Admission Errors (4xx): {k6_res.get('admissionErrors')}")
        print(f"  Write 503 (capacity):   {k6_res.get('writeCapacity503', 0)}")
        print(f"  Read 503 (capacity):    {k6_res.get('readCapacity503', 0)}")
        print(f"  Read 503 (maintenance): {k6_res.get('readMaintenance503', 0)}")
        print(f"  Dropped Iterations:     {k6_res.get('droppedIterations')}")

        print("\n  --- Client-Side Latencies (ms) ---")
        for k in ["admissionFirstTry", "admissionWithRetries", "commandResult", "balanceAvailable",
                  "potReadAvailable", "readPropagationLag", "e2eTotal", "readQueryHttp"]:
            t = lat.get(k, {})
            if t.get("count", 0) > 0:
                p50 = f"{t.get('p50'):.2f}" if t.get('p50') is not None else "N/A"
                p95 = f"{t.get('p95'):.2f}" if t.get('p95') is not None else "N/A"
                p99 = f"{t.get('p99'):.2f}" if t.get('p99') is not None else "N/A"
                avg = f"{t.get('avg'):.2f}" if t.get('avg') is not None else "N/A"
                print(f"    {k:<26} n={t.get('count', 0):>4} | p50={p50:>7} | p95={p95:>7} | p99={p99:>7} | avg={avg:>7}")
    else:
        print("  k6 Summary Data:        NOT AVAILABLE")

    dm = report.get("decoupledMetrics")
    if dm:
        print("\n  === Decoupled Metrology & Correlation Results ===")
        print(f"  Generator Status:       {dm.get('generatorStatus')} ({dm.get('generatorRateRatio')}%)")
        print(f"  Demanded Rate:          {dm['ratesPerSecond'].get('demanded')} cmd/s")
        print(f"  Emitted Rate:           {dm['ratesPerSecond'].get('emitted')} cmd/s ({dm['counts'].get('emitted')} commands in {dm.get('injectionActualSeconds')}s)")
        print(f"  Admission Rate:         {dm['ratesPerSecond'].get('admitted')} cmd/s ({dm['counts'].get('admitted')} admitted)")
        print(f"  Applied Rate:           {dm['ratesPerSecond'].get('applied')} cmd/s ({dm['counts'].get('applied')} applied)")
        print(f"  READ Rate:              {dm['ratesPerSecond'].get('readMaterialized')} cmd/s ({dm['counts'].get('readMaterialized')} materialized)")
        print(f"  Complete E2E:           {dm['counts'].get('completeE2E')} / {dm['counts'].get('admitted')} (Coverage: {dm['coverage'].get('percentage')}%)")
        print(f"  Conservation Emitted:   {dm['conservations'].get('emittedEquation')}")
        print(f"  Conservation Admitted:  {dm['conservations'].get('admittedEquation')}")
        drain = dm.get("drainage", {})
        bl = drain.get("finalDbBacklog")
        if isinstance(bl, dict):
            print(f"  Drainage Duration:      {drain.get('drainDurationSec')}s (Upstream Pending: {bl.get('upstreamPending')}, Slot Buffer: {bl.get('slotBufferTotal')})")
        else:
            print(f"  Drainage Duration:      {drain.get('drainDurationSec')}s (Final DB Backlog: {bl})")

        sql_rec = dm.get("sqlReconciliation")
        if sql_rec:
            print("\n  --- SQL Server-Side Reconciliation ---")
            print(f"  Server Admitted Count:  {sql_rec.get('serverTotalAdmitted')}")
            print(f"  Server Terminal Verif:  {sql_rec.get('serverTerminalVerified')} (Applied: {sql_rec.get('serverApplied')}, CAS: {sql_rec.get('serverCasRejected')}, Failed: {sql_rec.get('serverFailed')})")
            print(f"  Server Pending (DB):    {sql_rec.get('serverPending')}")
            print(f"  Server Read Material:   {sql_rec.get('readMaterializedVerified')}")
            print(f"  Reconciliation Exact:   {sql_rec.get('reconciliationExact')}")
        print("\n  --- Exact Decoupled Latencies (ms) ---")
        for lk, lv in dm.get("latencies", {}).items():
            if lv.get("count", 0) > 0:
                print(f"    {lk:<26} n={lv['count']:>4} | p50={lv['p50']:>7} | p95={lv['p95']:>7} | p99={lv['p99']:>7} | mean={lv['mean']:>7}")

        mix = dm.get("mixReport")
        if mix:
            print("\n  --- Statistical Mix Breakdown (Demanded vs Actual) ---")
            families = mix.get("families", [])
            for fam_info in families:
                fname = fam_info.get("family")
                tgt = fam_info.get("demandedRatio", 0.0) * 100.0
                app_pct = fam_info.get("appliedRatio", 0.0) * 100.0
                n_app = fam_info.get("appliedCount", 0)
                n_emit = fam_info.get("emittedCount", 0)
                dev_pct = fam_info.get("relativeDeviationPercent", 0.0)
                print(f"    {fname:<28} Target: {tgt:>5.1f}% | Applied: {app_pct:>5.1f}% (n={n_app}, emitted={n_emit}, dev={dev_pct:+.1f}%)")
            rem = mix.get("remainingEligibleEntities", {})
            if rem:
                print(f"    Uncorrected Expenses: {rem.get('uncorrectedExpenses')} | Updatable Weights: {rem.get('updatableShareholderWeights')} | Unattached SH: {rem.get('unattachedShareholders')}")
            print(f"    Pot Starvation Count: {mix.get('potStarvationCount', 0)}")

        op_lat = dm.get("latenciesByOperation")
        if op_lat:
            print("\n  --- Per-Operation End-to-End Latency Breakdown ---")
            for op_name, op_val in op_lat.items():
                e2e = op_val.get("latencies", {}).get("e2eTotal", {})
                if e2e.get("count", 0) > 0:
                    print(f"    {op_name:<28} n={e2e['count']:>4} | p50={e2e['p50']:>7} | p95={e2e['p95']:>7} | p99={e2e['p99']:>7} | mean={e2e['mean']:>7}")

    print("\n  --- Engine Internal Stage Latencies (ms) ---")
    for s_name, s_val in stages.items():
        if s_val.get("count", 0) > 0:
            print(f"    {s_name:<26} n={s_val['count']:>4} | p50={s_val['p50']:>7} | p95={s_val['p95']:>7} | p99={s_val['p99']:>7} | avg={s_val['avg']:>7}")

    sys_t = report["systemTelemetry"]
    heap_mb = (sys_t.get("jvmHeapUsedBytes") or 0) / (1024 * 1024)
    pg_t = report["postgresTelemetry"]
    print("\n  --- System & Infrastructure ---")
    print(f"  JVM Heap Used:          {heap_mb:.1f} MB")
    print(f"  Process CPU Usage:      {((sys_t.get('processCpuUsage') or 0)*100):.1f}%")
    print(f"  System CPU Usage:       {((sys_t.get('systemCpuUsage') or 0)*100):.1f}%")
    print(f"  JVM Live Threads:       {sys_t.get('jvmLiveThreads')}")
    print(f"  Hikari Active Conns:    {sys_t.get('hikariActiveConnections')}")
    print(f"  PostgreSQL Active Sess: {pg_t.get('activeSessions')} / {pg_t.get('totalSessions')}")
    print(f"  Pending Command Queue:  {report['engineTelemetry'].get('physicalPendingCommand')}")
    print(f"  Pending Read Backlog:   {report['engineTelemetry'].get('physicalPendingPotVersion')}")
    print("================================================================================")


if __name__ == "__main__":
    main()
