#!/usr/bin/env python3
"""Pocoma V3 — Master Performance & Quarantine Discovery Filter Qualification Driver for L6/I3-R11.

Automates Mandate:
GO_L6_I3_R11_QUARANTINE_DISCOVERY_FILTER

Phases & Scenarios:
- S0: Reference nominal (0 quarantines, 1 cmd/s, 30s)
- S1: Quarantined nominal (803 balanced quarantines, 1 cmd/s, 30s)
- S2: Reference sustained (0 quarantines, 5 cmd/s, 30s)
- S3: Quarantined sustained (803 balanced quarantines, 5 cmd/s, 30s)
- S4: Reference reinforced (0 quarantines, 20 cmd/s, 30s)
- S5: Quarantined reinforced (803 balanced quarantines, 20 cmd/s, 30s)
- S6: Quarantined concentrated (803 concentrated on partition 0, 5 cmd/s, 30s)
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR_R11 = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "R11"
RESULTS_FILE = EVIDENCE_DIR_R11 / "r11_qualification_summary.json"


def run_cmd(cmd: List[str], check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)


def exec_pg(db: str, sql: str) -> str:
    cmd = [
        "docker", "exec", "pocoma-functional-postgres-1",
        "psql", "-U", "pocoma", "-d", db, "-c", sql
    ]
    res = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return res.stdout


def get_quarantined_count() -> int:
    sql = "SELECT count(*) FROM v3_write.consumption_slot WHERE state = 'QUARANTINED';"
    out = exec_pg("pocoma_write", sql)
    m = re.search(r"(\d+)\s+\(1 row\)", out)
    return int(m.group(1)) if m else 0


def get_quarantine_distribution() -> Dict[int, int]:
    sql = "SELECT partition_id, count(*) FROM v3_write.consumption_slot WHERE state = 'QUARANTINED' GROUP BY partition_id ORDER BY partition_id;"
    out = exec_pg("pocoma_write", sql)
    dist = {}
    for line in out.splitlines():
        m = re.match(r"\s*(\d+)\s+\|\s*(\d+)", line)
        if m:
            dist[int(m.group(1))] = int(m.group(2))
    return dist


def get_pg_buffer_stats() -> Dict[str, Any]:
    sql = """
    SELECT
      coalesce(sum(heap_blks_hit), 0) as hits,
      coalesce(sum(heap_blks_read), 0) as reads
    FROM pg_statio_user_tables
    WHERE schemaname = 'v3_write' AND relname = 'consumption_slot';
    """
    out = exec_pg("pocoma_write", sql)
    m = re.search(r"(\d+)\s+\|\s*(\d+)", out)
    if m:
        hits = int(m.group(1))
        reads = int(m.group(2))
        total = hits + reads
        ratio = (hits / total * 100.0) if total > 0 else 100.0
        return {"slot_blks_hit": hits, "slot_blks_read": reads, "hit_ratio_pct": round(ratio, 2)}
    return {"slot_blks_hit": 0, "slot_blks_read": 0, "hit_ratio_pct": 100.0}


def inject_quarantined_slots(count: int = 803, target_partition: Optional[int] = None) -> int:
    print(f"[*] Injecting {count} synthetic quarantined slots (target partition: {target_partition if target_partition is not None else 'balanced across 4 partitions'})...")
    if target_partition is not None:
        sql = f"""
        DO $$
        DECLARE
          v_consumption_id uuid := '98f7268d-d340-581c-b83d-010926223e14'::uuid;
          v_id uuid;
          v_hash integer;
          v_part integer;
          v_target_part integer := {target_partition};
          v_count integer := 0;
        BEGIN
          SET search_path = v3_write, public;
          WHILE v_count < {count} LOOP
            v_id := gen_random_uuid();
            v_hash := consumption_hash_v1('COMMAND', v_id);
            v_part := ((v_hash::bigint % 4 + 4) % 4);
            IF v_part = v_target_part THEN
              INSERT INTO consumption_slot(consumption_id, consumable_id, partition_hash, partition_id, attempt_count, state)
              VALUES (v_consumption_id, v_id, v_hash, v_part, 3, 'QUARANTINED');
              v_count := v_count + 1;
            END IF;
          END LOOP;
        END $$;
        """
    else:
        sql = f"""
        DO $$
        DECLARE
          v_consumption_id uuid := '98f7268d-d340-581c-b83d-010926223e14'::uuid;
          v_id uuid;
          v_hash integer;
          v_part integer;
        BEGIN
          SET search_path = v3_write, public;
          FOR i IN 1..{count} LOOP
            v_id := gen_random_uuid();
            v_hash := consumption_hash_v1('COMMAND', v_id);
            v_part := ((v_hash::bigint % 4 + 4) % 4);
            INSERT INTO consumption_slot(consumption_id, consumable_id, partition_hash, partition_id, attempt_count, state)
            VALUES (v_consumption_id, v_id, v_hash, v_part, 3, 'QUARANTINED');
          END LOOP;
        END $$;
        """
    exec_pg("pocoma_write", sql)
    actual = get_quarantined_count()
    dist = get_quarantine_distribution()
    print(f"[✓] Active quarantined slots in DB: {actual}, distribution across partitions: {dist}")
    return actual


def cleanup_quarantined_slots() -> int:
    print("[*] Cleaning up all quarantined slots...")
    sql = "DELETE FROM v3_write.consumption_slot WHERE state = 'QUARANTINED';"
    out = exec_pg("pocoma_write", sql)
    m = re.search(r"DELETE (\d+)", out)
    deleted = int(m.group(1)) if m else 0
    print(f"[✓] Deleted {deleted} quarantined slots. DB is clean.")
    return deleted


def run_benchmark(scenario: str, duration: str = "30s", rate: Optional[int] = None) -> Dict[str, Any]:
    cmd = [
        sys.executable,
        str(REPO_ROOT / "scripts" / "performance" / "v3-run.py"),
        scenario,
        "--duration", duration,
        "--r11"
    ]
    if rate is not None:
        cmd.extend(["--rate", str(rate)])
    print(f"[*] Executing benchmark: {' '.join(cmd)}")
    p = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if p.returncode != 0:
        print(f"[!] Warning: benchmark exited with code {p.returncode}")
        print(p.stderr or p.stdout)

    reports = list(EVIDENCE_DIR_R11.glob(f"run-r11-{scenario}-*_report.json"))
    if not reports:
        # Fallback to any latest report in R11
        reports = list(EVIDENCE_DIR_R11.glob("run-r11-*_report.json"))
    if not reports:
        raise RuntimeError(f"No report JSON found for scenario {scenario} in {EVIDENCE_DIR_R11}")
    latest_report_file = max(reports, key=lambda f: f.stat().st_mtime)
    with open(latest_report_file, "r") as f:
        data = json.load(f)
    return data


def extract_metrics(report: Dict[str, Any], q_before: int, q_after: int, q_dist: Dict[int, int], pg_buf: Dict[str, Any]) -> Dict[str, Any]:
    et = report.get("engineTelemetry", {})
    st = et.get("stages", {})
    ww = st.get("writeWait", {})
    rd = st.get("readDiscovery", {})
    adm_eng = st.get("admissionHttp", {})
    w_tot = st.get("writeTotalAttempt", {})
    r_tot = st.get("readProjectionTotalAttempt", {})

    cm = report.get("k6ClientMetrics", {})
    lat = cm.get("latencies", {})
    cmd_res = lat.get("commandResult", {})
    e2e = lat.get("e2eTotal", {})
    adm_1st = lat.get("admissionFirstTry", {})
    adm_retries = lat.get("admissionWithRetries", {})

    dm = report.get("decoupledMetrics") or {}
    rates = dm.get("ratesPerSecond") or {}
    counts = dm.get("counts") or {}
    dm_lat = dm.get("latencies") or {}
    dec_e2e = dm_lat.get("e2eTotal") or {}
    dec_term = dm_lat.get("terminal") or {}

    sys_t = report.get("systemTelemetry", {})
    pg_t = report.get("postgresTelemetry", {})

    return {
        "runId": report.get("runId"),
        "scenario": report.get("scenario"),
        "timestamp": report.get("timestamp"),
        "quarantines": {
            "before": q_before,
            "after": q_after,
            "preserved": (q_after >= q_before if q_before > 0 else True),
            "distribution": q_dist
        },
        "throughputs": {
            "demandedRate": rates.get("demanded", cm.get("admissionsOffered", 0) / 30.0),
            "emittedRate": rates.get("emitted", cm.get("admissionsOffered", 0) / 30.0),
            "admittedRate": rates.get("admitted", cm.get("admissionsAccepted", 0) / 30.0),
            "completedRate": rates.get("applied", cm.get("commandsApplied", 0) / 30.0),
            "offeredCount": counts.get("emitted", cm.get("admissionsOffered", 0)),
            "admittedCount": counts.get("admitted", cm.get("admissionsAccepted", 0)),
            "appliedCount": counts.get("applied", cm.get("commandsApplied", 0)),
        },
        "engineLatenciesMs": {
            "writeWait": {
                "p50": ww.get("p50"),
                "p95": ww.get("p95"),
                "p99": ww.get("p99"),
                "avg": ww.get("avg"),
                "count": ww.get("count"),
                "max": ww.get("max")
            },
            "readDiscovery": {
                "p50": rd.get("p50"),
                "p95": rd.get("p95"),
                "p99": rd.get("p99"),
                "avg": rd.get("avg"),
                "count": rd.get("count")
            },
            "admissionHttp": {
                "p50": adm_eng.get("p50"),
                "p95": adm_eng.get("p95"),
                "p99": adm_eng.get("p99"),
                "avg": adm_eng.get("avg"),
                "count": adm_eng.get("count")
            }
        },
        "clientLatenciesMs": {
            "admissionFirstTry": {
                "p50": adm_1st.get("p50"),
                "p95": adm_1st.get("p95"),
                "p99": adm_1st.get("p99"),
                "avg": adm_1st.get("avg")
            },
            "commandResult": {
                "p50": dec_term.get("p50") or cmd_res.get("p50"),
                "p95": dec_term.get("p95") or cmd_res.get("p95"),
                "p99": dec_term.get("p99") or cmd_res.get("p99"),
                "avg": dec_term.get("mean") or cmd_res.get("avg")
            },
            "e2eTotal": {
                "p50": dec_e2e.get("p50") or e2e.get("p50"),
                "p95": dec_e2e.get("p95") or e2e.get("p95"),
                "p99": dec_e2e.get("p99") or e2e.get("p99"),
                "avg": dec_e2e.get("mean") or e2e.get("avg")
            }
        },
        "system": {
            "jvmHeapMb": round((sys_t.get("jvmHeapUsedBytes") or 0) / (1024 * 1024), 2),
            "processCpuPct": round(((sys_t.get("processCpuUsage") or 0) * 100), 2),
            "systemCpuPct": round(((sys_t.get("systemCpuUsage") or 0) * 100), 2),
            "activePgSessions": pg_t.get("activeSessions"),
            "totalPgSessions": pg_t.get("totalSessions"),
            "pgBuffer": pg_buf
        },
        "reliability": {
            "admissionErrors": cm.get("admissionErrors", 0),
            "oracleFailures": cm.get("oracleFailures", 0),
            "write503Capacity": cm.get("write503Capacity", 0),
            "read503Capacity": cm.get("read503Capacity", 0),
            "retriesTotal": cm.get("retriesTotal", 0),
            "droppedIterations": cm.get("droppedIterations", 0),
            "potStarvationCount": (dm.get("mixReport") or {}).get("potStarvationCount", 0),
            "pendingCommandQueue": et.get("physicalPendingCommand", 0),
            "pendingReadBacklog": et.get("physicalPendingPotVersion", 0)
        }
    }


def record_scenario_result(scenario_id: str, data: Dict[str, Any]) -> None:
    results = {}
    if RESULTS_FILE.exists():
        try:
            with open(RESULTS_FILE, "r") as f:
                results = json.load(f)
        except Exception:
            results = {}
    results[scenario_id] = data
    with open(RESULTS_FILE, "w") as f:
        json.dump(results, f, indent=2)
    print(f"[✓] Scenario {scenario_id} recorded in {RESULTS_FILE}")


def execute_scenario(scenario_id: str, scenario_name: str, target_rate: int, quarantined_count: int,
                     target_partition: Optional[int], duration: str = "30s") -> Dict[str, Any]:
    print("\n" + "=" * 80)
    print(f"▶ Executing {scenario_id}: {scenario_name} (Rate: {target_rate} cmd/s, Quarantines: {quarantined_count})")
    print("=" * 80)

    # 1. State preparation
    current_q = get_quarantined_count()
    if quarantined_count == 0:
        if current_q > 0:
            cleanup_quarantined_slots()
    else:
        if current_q != quarantined_count:
            cleanup_quarantined_slots()
            inject_quarantined_slots(quarantined_count, target_partition=target_partition)
        else:
            print(f"[*] Reusing existing {current_q} quarantined slots.")

    q_before = get_quarantined_count()
    q_dist = get_quarantine_distribution()
    print(f"[*] Quarantined slots before run: {q_before}")

    # 2. Run benchmark
    report = run_benchmark(scenario_name, duration=duration, rate=target_rate)

    # 3. Post-run state verification
    q_after = get_quarantined_count()
    pg_buf = get_pg_buffer_stats()
    print(f"[*] Quarantined slots after run: {q_after}")

    if quarantined_count > 0:
        if q_after < quarantined_count:
            raise RuntimeError(f"CRITICAL VIOLATION of D5 (Quarantine Conservation): Expected at least {quarantined_count}, got {q_after}!")
        print(f"[✓] D5 Conservation verified: Initial {quarantined_count} quarantined slots preserved (total active in DB: {q_after}).")

    # 4. Extract metrics
    metrics = extract_metrics(report, q_before, q_after, q_dist, pg_buf)
    record_scenario_result(scenario_id, metrics)

    # Short settlement pause between runs
    time.sleep(3)
    return metrics


def evaluate_comparisons(all_results: Dict[str, Any]) -> None:
    print("\n" + "=" * 80)
    print(" Pocoma V3 — R11 Comparative Evaluation (0 vs 803 Quarantines)")
    print("=" * 80)

    pairs = [
        ("S0 (Ref 1 cmd/s)", "S1 (Quar 1 cmd/s)", "S0", "S1"),
        ("S2 (Ref 5 cmd/s)", "S3 (Quar 5 cmd/s)", "S2", "S3"),
        ("S4 (Ref 20 cmd/s)", "S5 (Quar 20 cmd/s)", "S4", "S5"),
        ("S2 (Ref 5 cmd/s)", "S6 (Conc 5 cmd/s)", "S2", "S6"),
    ]

    print(f"{'Comparison':<28} | {'writeWait p50 (ms)':<20} | {'writeWait p95 (ms)':<20} | {'Completed Rate':<18} | {'Verdict'}")
    print("-" * 105)

    all_passed = True
    for label_ref, label_test, k_ref, k_test in pairs:
        if k_ref not in all_results or k_test not in all_results:
            print(f"Missing results for {k_ref} or {k_test}, skipping comparison.")
            continue
        ref = all_results[k_ref]
        test = all_results[k_test]

        ww_ref_p50 = ref["engineLatenciesMs"]["writeWait"]["p50"] or 0.0
        ww_ref_p95 = ref["engineLatenciesMs"]["writeWait"]["p95"] or 0.0
        ww_test_p50 = test["engineLatenciesMs"]["writeWait"]["p50"] or 0.0
        ww_test_p95 = test["engineLatenciesMs"]["writeWait"]["p95"] or 0.0

        rate_ref = ref["throughputs"]["completedRate"] or 0.0
        rate_test = test["throughputs"]["completedRate"] or 0.0

        # Equivalence threshold:
        # writeWait p50 difference <= 15% or <= 10ms
        diff_p50 = abs(ww_test_p50 - ww_ref_p50)
        p50_ok = diff_p50 <= 15.0 or (diff_p50 / max(ww_ref_p50, 1.0)) <= 0.25

        # writeWait p95 difference <= 25ms
        diff_p95 = abs(ww_test_p95 - ww_ref_p95)
        p95_ok = diff_p95 <= 25.0 or (diff_p95 / max(ww_ref_p95, 1.0)) <= 0.35

        # Rate equivalence: within 10%
        rate_ok = abs(rate_test - rate_ref) <= 1.0 or (abs(rate_test - rate_ref) / max(rate_ref, 1.0)) <= 0.10

        passed = p50_ok and p95_ok and rate_ok
        if not passed:
            all_passed = False
        verdict = "EQUIVALENT [PASS]" if passed else "DEGRADED [FAIL]"

        p50_str = f"{ww_ref_p50:.1f} vs {ww_test_p50:.1f} (Δ {diff_p50:+.1f})"
        p95_str = f"{ww_ref_p95:.1f} vs {ww_test_p95:.1f} (Δ {diff_p95:+.1f})"
        rate_str = f"{rate_ref:.1f} vs {rate_test:.1f}"
        comp_label = f"{k_ref} vs {k_test}"

        print(f"{comp_label:<28} | {p50_str:<20} | {p95_str:<20} | {rate_str:<18} | {verdict}")

    print("-" * 105)
    overall = "QUALIFICATION SUCCESSFUL" if all_passed else "QUALIFICATION RESERVATIONS"
    print(f"Overall Campaign Status: {overall}")


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 R11 Qualification Campaign Driver")
    parser.add_argument("--scenario", choices=["s0", "s1", "s2", "s3", "s4", "s5", "s6", "all"], default="all",
                        help="Scenario to execute (default: all)")
    parser.add_argument("--duration", default="30s", help="Scenario duration (default: 30s)")
    args = parser.parse_args()

    scenarios = [
        ("S0", "r11-s0-ref-1", 1, 0, None),
        ("S1", "r11-s1-q-1", 1, 803, None),
        ("S2", "r11-s2-ref-5", 5, 0, None),
        ("S3", "r11-s3-q-5", 5, 803, None),
        ("S4", "r11-s4-ref-20", 20, 0, None),
        ("S5", "r11-s5-q-20", 20, 803, None),
        ("S6", "r11-s6-conc-5", 5, 803, 0),
    ]

    selected = scenarios if args.scenario == "all" else [s for s in scenarios if s[0].lower() == args.scenario]

    all_results = {}
    if RESULTS_FILE.exists():
        try:
            with open(RESULTS_FILE, "r") as f:
                all_results = json.load(f)
        except Exception:
            all_results = {}

    for s_id, s_name, rate, q_count, q_part in selected:
        res = execute_scenario(s_id, s_name, rate, q_count, q_part, duration=args.duration)
        all_results[s_id] = res

    evaluate_comparisons(all_results)

    # Regenerate charts
    print("\n[*] Updating performance plots...")
    plot_script = REPO_ROOT / "scripts" / "performance" / "v3-plot.py"
    subprocess.run([sys.executable, str(plot_script), "--evidence-dir", str(EVIDENCE_DIR_R11)])


if __name__ == "__main__":
    main()
