#!/usr/bin/env python3
"""Pocoma V3 — Metrology & Performance Diagnostic Collector (scripts/performance/v3-collect.py).

Collects and consolidates:
1. k6 client-side execution metrics (throughput, iterations, admissions, latencies p50/95/99, 503 counts).
2. Pocoma V3 engine telemetry (Discovery, Engine, Claims, Consumption latencies, Backlog).
3. Host & JVM system telemetry (CPU, Heap/Non-Heap RSS, GC pauses, Threads, HikariCP pools).
4. PostgreSQL performance metrics (active sessions, lock waits, slots backlog, WAL bytes).

Outputs consolidated RAW JSON artifacts and human-readable summaries.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any, Dict, Optional
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"

POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
READ_DB = os.environ.get("READ_DB", "pocoma_read")


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
                return float(results[0]["value"][1])
    except Exception:
        pass
    return None


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
            "count": v.get("count"),
            "p50": v.get("p(50)") if v.get("p(50)") is not None else v.get("med"),
            "p90": v.get("p(90)"),
            "p95": v.get("p(95)"),
            "p99": v.get("p(99)"),
            "avg": v.get("avg"),
            "min": v.get("min"),
            "max": v.get("max"),
        }

    def extract_counter(name: str) -> int:
        m = metrics.get(name, {})
        v = m.get("values", {})
        return int(v.get("count", 0))

    return {
        "available": True,
        "admissionsAccepted": extract_counter("v3_admissions_accepted"),
        "commandsApplied": extract_counter("v3_commands_applied"),
        "terminalResults": extract_counter("v3_terminal_results_count"),
        "commandsRejected": extract_counter("v3_commands_rejected"),
        "admissionErrors": extract_counter("v3_admission_errors"),
        "oracleFailures": extract_counter("v3_oracle_failures"),
        "censoredTimeouts": extract_counter("v3_censored_timeouts"),
        "droppedIterations": extract_counter("dropped_iterations"),
        "readCapacity503": extract_counter("v3_read_capacity_503"),
        "readMaintenance503": extract_counter("v3_read_maintenance_503"),
        "probeRequests": extract_counter("v3_probe_requests"),
        "latencies": {
            "admission": extract_trend("v3_admission_duration"),
            "commandResult": extract_trend("v3_command_result_duration"),
            "balanceAvailable": extract_trend("v3_balance_available_duration"),
            "potReadAvailable": extract_trend("v3_pot_read_available_duration"),
        },
    }


def collect_engine_telemetry(prom_url: str) -> Dict[str, Any]:
    return {
        "admissionRequestsTotal": query_prometheus(prom_url, "sum(pocoma_v3_admission_requests_total)"),
        "attemptOutcomesCommitted": query_prometheus(prom_url, 'sum(pocoma_v3_attempt_outcomes_total{outcome="committed"})'),
        "attemptOutcomesRetried": query_prometheus(prom_url, 'sum(pocoma_v3_attempt_outcomes_total{outcome="retried"})'),
        "discoveryReferencesTotal": query_prometheus(prom_url, "sum(pocoma_v3_discovery_references_total)"),
        "claimOperationsTotal": query_prometheus(prom_url, "sum(pocoma_v3_claim_operations_total)"),
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
                                 run_id: Optional[str] = None) -> Dict[str, Any]:
    rid = run_id or f"run-{int(time.time())}"
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    k6_data = collect_k6_summary(k6_summary_file)
    engine_data = collect_engine_telemetry(prom_url)
    system_data = collect_system_telemetry(prom_url)
    postgres_data = collect_postgres_telemetry()

    report = {
        "runId": rid,
        "scenario": scenario_name,
        "timestamp": timestamp,
        "k6ClientMetrics": k6_data,
        "engineTelemetry": engine_data,
        "systemTelemetry": system_data,
        "postgresTelemetry": postgres_data,
    }

    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    out_file = EVIDENCE_DIR / f"{rid}_report.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Metrology & Diagnostic Collector")
    parser.add_argument("--scenario", default="smoke", help="Executed scenario name")
    parser.add_argument("--summary-file", default=str(EVIDENCE_DIR / "k6-summary.json"),
                        help="k6 JSON summary file path")
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:9090", help="Prometheus URL")
    parser.add_argument("--run-id", help="Unique benchmark run identifier")

    args = parser.parse_args()
    report = generate_consolidated_report(
        scenario_name=args.scenario,
        k6_summary_file=Path(args.summary_file),
        prom_url=args.prometheus_url,
        run_id=args.run_id,
    )

    k6_res = report["k6ClientMetrics"]
    lat = k6_res.get("latencies", {})

    print("================================================================================")
    print(f" Pocoma V3 — Consolidated Metrology Report [Run: {report['runId']}]")
    print("================================================================================")
    print(f"  Scenario:               {report['scenario']}")
    print(f"  Timestamp:              {report['timestamp']}")
    if k6_res.get("available"):
        print(f"  Admissions Accepted:    {k6_res['admissionsAccepted']}")
        print(f"  Commands Applied:       {k6_res['commandsApplied']}")
        print(f"  Terminal Results:       {k6_res['terminalResults']}")
        print(f"  Oracle Failures:        {k6_res['oracleFailures']}")
        print(f"  Admission Errors:       {k6_res['admissionErrors']}")
        print(f"  Censored Timeouts:      {k6_res['censoredTimeouts']}")
        print(f"  Dropped Iterations:     {k6_res['droppedIterations']}")
        print(f"  READ Capacity 503:      {k6_res.get('readCapacity503', 0)}")
        print(f"  READ Maintenance 503:   {k6_res.get('readMaintenance503', 0)}")
        print("  --- Latencies (p50 / p95 / p99 ms) ---")
        for k in ["admission", "commandResult", "balanceAvailable", "potReadAvailable"]:
            t = lat.get(k, {})
            p50 = f"{t.get('p50'):.2f}" if t.get('p50') is not None else "N/A"
            p95 = f"{t.get('p95'):.2f}" if t.get('p95') is not None else "N/A"
            p99 = f"{t.get('p99'):.2f}" if t.get('p99') is not None else "N/A"
            print(f"    {k:<22} p50={p50:>8} ms | p95={p95:>8} ms | p99={p99:>8} ms")
    else:
        print("  k6 Summary Data:        NOT AVAILABLE")

    sys_t = report["systemTelemetry"]
    heap_mb = (sys_t.get("jvmHeapUsedBytes") or 0) / (1024 * 1024)
    pg_t = report["postgresTelemetry"]
    print("  --- System & Database ---")
    print(f"  JVM Heap Used:          {heap_mb:.1f} MB")
    print(f"  JVM Active Threads:     {sys_t.get('jvmLiveThreads')}")
    print(f"  Hikari Active Conns:    {sys_t.get('hikariActiveConnections')}")
    print(f"  PostgreSQL Active Sess: {pg_t.get('activeSessions')} / {pg_t.get('totalSessions')}")
    print(f"  WRITE Slots Available:  {pg_t.get('writeSlotsAvailable')}")
    print(f"  READ Slots Available:   {pg_t.get('slotsPending')}")
    print("================================================================================")


if __name__ == "__main__":
    main()
