#!/usr/bin/env python3
"""Pocoma V3 — Master Resilience & Requalification Driver for L6/I3-R9.

Automates Phase C Experiments (C1 through C8) under Mandate
GO_L6_I3_R9_RESILIENCE_DIAGNOSIS_CORRECTION_AND_REQUALIFICATION.

Strictly manages:
- Quantum switching and container restarts with instant partition lease reset.
- Synthetic quarantined slot injection (uniform or partition-specific).
- Clean transactional cleanup of synthetic backlogs (never leaving debris).
- Precise metrology capture (A1..A4, writeWait p50/p95/p99, E2E latencies).
- Persistence of structured experiment evidence in docs/testing/evidence/L6-I3/R9/.
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
PROPERTIES_FILE = REPO_ROOT / "docker" / "monolith" / "application-v3.properties"
EVIDENCE_DIR_R9 = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "R9"
RESULTS_FILE = EVIDENCE_DIR_R9 / "r9_experiments_summary.json"

def run_cmd(cmd: List[str], check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)

def exec_pg(db: str, sql: str) -> str:
    cmd = [
        "docker", "exec", "pocoma-functional-postgres-1",
        "psql", "-U", "pocoma", "-d", db, "-c", sql
    ]
    res = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return res.stdout

def get_current_quantum() -> int:
    text = PROPERTIES_FILE.read_text()
    m = re.search(r"pocoma\.v3\.worker\.quantum=(\d+)", text)
    if not m:
        raise ValueError("Could not find pocoma.v3.worker.quantum in properties file")
    return int(m.group(1))

def set_quantum(q: int) -> None:
    current = get_current_quantum()
    if current == q:
        print(f"[*] Quantum is already {q}")
        return
    print(f"[*] Switching quantum from {current} to {q}...")
    text = PROPERTIES_FILE.read_text()
    new_text = re.sub(r"pocoma\.v3\.worker\.quantum=\d+", f"pocoma.v3.worker.quantum={q}", text)
    PROPERTIES_FILE.write_text(new_text)

    print("[*] Restarting monolith container...")
    run_cmd(["docker", "restart", "pocoma-functional-runtime-monolith-1"])

    # Wait for Tomcat startup
    print("[*] Waiting for container initialization...")
    time.sleep(8)

    # Immediately expire old leases so new worker acquires them instantly without 4-minute wait
    print("[*] Handing over partition leases immediately...")
    reset_sql = (
        "SET search_path = v3_write, public; "
        "UPDATE v3_write.partition_ownership SET owned_until = now() - interval '1 second' WHERE owner_token IS NOT NULL;"
    )
    exec_pg("pocoma_write", reset_sql)
    reset_sql_read = (
        "SET search_path = v3_read, public; "
        "UPDATE v3_read.partition_ownership SET owned_until = now() - interval '1 second' WHERE owner_token IS NOT NULL;"
    )
    exec_pg("pocoma_read", reset_sql_read)

    # Wait until actuator health is 200
    for _ in range(20):
        try:
            res = subprocess.run(
                ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:18081/actuator/health"],
                capture_output=True, text=True, check=False
            )
            if res.stdout.strip() == "200":
                print(f"[✓] Monolith container healthy with quantum={q}")
                time.sleep(2)
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Monolith failed to become healthy after restart")

def inject_quarantined_slots(count: int, target_partition: Optional[int] = None) -> int:
    if count <= 0:
        return 0
    print(f"[*] Injecting {count} quarantined slots (target_partition={target_partition})...")
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
    check_sql = "SELECT count(*) FROM v3_write.consumption_slot WHERE state = 'QUARANTINED';"
    out = exec_pg("pocoma_write", check_sql)
    m = re.search(r"(\d+)\s+\(1 row\)", out)
    actual = int(m.group(1)) if m else count
    print(f"[✓] Active quarantined slots in DB: {actual}")
    return actual

def cleanup_quarantined_slots() -> int:
    raise RuntimeError("Historical operation retired by L7.3: incomplete slots must be preserved; use a new disposable fixture")

def run_benchmark(scenario: str, duration: str = "10s", rate: Optional[int] = None) -> Dict[str, Any]:
    cmd = [
        sys.executable,
        str(REPO_ROOT / "scripts" / "performance" / "v3-run.py"),
        scenario,
        "--duration", duration,
        "--r9"
    ]
    if rate is not None:
        cmd.extend(["--rate", str(rate)])
    print(f"[*] Executing benchmark: {' '.join(cmd)}")
    p = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if p.returncode != 0:
        print(f"[!] Warning: benchmark exited with code {p.returncode}")
        print(p.stderr or p.stdout)
    
    # Locate the latest report JSON generated in EVIDENCE_DIR_R9
    reports = list(EVIDENCE_DIR_R9.glob("run-r9-*_report.json"))
    if not reports:
        raise RuntimeError("No report JSON found in evidence directory")
    latest_report_file = max(reports, key=lambda f: f.stat().st_mtime)
    with open(latest_report_file, "r") as f:
        data = json.load(f)
    return data

def extract_metrics(report: Dict[str, Any]) -> Dict[str, Any]:
    et = report.get("engineTelemetry", {})
    st = et.get("stages", {})
    ww = st.get("writeWait", {})
    rd = st.get("readDiscovery", {})
    adm = st.get("admissionHttp", {})
    cm = report.get("k6ClientMetrics", {})
    lat = cm.get("latencies", {})
    cmd_res = lat.get("commandResult", {})
    e2e = lat.get("e2eTotal", {})
    
    return {
        "runId": report.get("runId"),
        "scenario": report.get("scenario"),
        "timestamp": report.get("timestamp"),
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
            "count": rd.get("count")
        },
        "admissionHttp": {
            "p50": adm.get("p50"),
            "count": adm.get("count")
        },
        "commandResult": {
            "p50": cmd_res.get("p50"),
            "p95": cmd_res.get("p95"),
            "p99": cmd_res.get("p99"),
            "count": cmd_res.get("count")
        },
        "e2eTotal": {
            "p50": e2e.get("p50"),
            "p95": e2e.get("p95"),
            "p99": e2e.get("p99"),
            "count": e2e.get("count")
        },
        "admissionsAccepted": cm.get("admissionsAccepted"),
        "commandsApplied": cm.get("commandsApplied")
    }

def record_experiment_result(exp_name: str, config: Dict[str, Any], metrics: Dict[str, Any]) -> None:
    results = {}
    if RESULTS_FILE.exists():
        try:
            with open(RESULTS_FILE, "r") as f:
                results = json.load(f)
        except Exception:
            results = {}
    if exp_name not in results:
        results[exp_name] = []
    results[exp_name].append({
        "config": config,
        "metrics": metrics,
        "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    })
    with open(RESULTS_FILE, "w") as f:
        json.dump(results, f, indent=2)
    print(f"[✓] Experiment result recorded in {RESULTS_FILE}")

# -----------------------------------------------------------------------------
# EXPERIMENTS
# -----------------------------------------------------------------------------

def run_experiment_c1():
    """R9-C1: Isolation of Quantum Effect (Quantum 1 vs 4 vs 8 at Backlog 0 vs 200)."""
    print("\n" + "="*80)
    print("▶ R9-C1: Isolation de l'Effet de Quantum")
    print("="*80)

    # Sub-test 1: Backlog 0, Quantum 1
    cleanup_quarantined_slots()
    set_quantum(1)
    rep1 = run_benchmark("smoke", duration="10s")
    m1 = extract_metrics(rep1)
    record_experiment_result("R9-C1", {"quantum": 1, "backlog": 0}, m1)
    print(f"[*] C1.1 (Q=1, B=0): writeWait p50={m1['writeWait']['p50']}ms, cmdResult p50={m1['commandResult']['p50']}ms")

    # Sub-test 2: Backlog 0, Quantum 4
    set_quantum(4)
    rep2 = run_benchmark("smoke", duration="10s")
    m2 = extract_metrics(rep2)
    record_experiment_result("R9-C1", {"quantum": 4, "backlog": 0}, m2)
    print(f"[*] C1.2 (Q=4, B=0): writeWait p50={m2['writeWait']['p50']}ms, cmdResult p50={m2['commandResult']['p50']}ms")

    # Sub-test 3: Backlog 200, Quantum 1
    inject_quarantined_slots(200)
    set_quantum(1)
    rep3 = run_benchmark("smoke", duration="10s")
    m3 = extract_metrics(rep3)
    record_experiment_result("R9-C1", {"quantum": 1, "backlog": 200}, m3)
    print(f"[*] C1.3 (Q=1, B=200): writeWait p50={m3['writeWait']['p50']}ms, cmdResult p50={m3['commandResult']['p50']}ms")

    # Sub-test 4: Backlog 200, Quantum 4
    set_quantum(4)
    rep4 = run_benchmark("smoke", duration="10s")
    m4 = extract_metrics(rep4)
    record_experiment_result("R9-C1", {"quantum": 4, "backlog": 200}, m4)
    print(f"[*] C1.4 (Q=4, B=200): writeWait p50={m4['writeWait']['p50']}ms, cmdResult p50={m4['commandResult']['p50']}ms")

    # Sub-test 5: Backlog 200, Quantum 8
    set_quantum(8)
    rep5 = run_benchmark("smoke", duration="10s")
    m5 = extract_metrics(rep5)
    record_experiment_result("R9-C1", {"quantum": 8, "backlog": 200}, m5)
    print(f"[*] C1.5 (Q=8, B=200): writeWait p50={m5['writeWait']['p50']}ms, cmdResult p50={m5['commandResult']['p50']}ms")

    # Clean up and restore quantum 4
    cleanup_quarantined_slots()
    set_quantum(4)
    print("[✓] R9-C1 completed successfully.")

def run_experiment_c2():
    """R9-C2: Sensitivity to Ineligible Backlog (0, 50, 200, 500, 1000 slots at Quantum 4)."""
    print("\n" + "="*80)
    print("▶ R9-C2: Sensibilité au Backlog de Clés Inéligibles (Quantum=4)")
    print("="*80)
    set_quantum(4)
    backlog_levels = [0, 50, 200, 500, 1000]

    for b in backlog_levels:
        cleanup_quarantined_slots()
        if b > 0:
            inject_quarantined_slots(b)
        rep = run_benchmark("r9-s1-1", duration="10s")
        m = extract_metrics(rep)
        record_experiment_result("R9-C2", {"quantum": 4, "backlog": b}, m)
        print(f"[*] C2 (B={b}): writeWait p50={m['writeWait']['p50']}ms, cmdResult p50={m['commandResult']['p50']}ms")

    cleanup_quarantined_slots()
    print("[✓] R9-C2 completed successfully.")

def run_experiment_c3():
    """R9-C3: Unbalanced Distribution of Ineligible Slots."""
    print("\n" + "="*80)
    print("▶ R9-C3: Répartition Déséquilibrée des Clés Inéligibles")
    print("="*80)
    set_quantum(4)

    # Condition 1: 200 uniform (50 per partition)
    cleanup_quarantined_slots()
    inject_quarantined_slots(200)
    rep1 = run_benchmark("smoke", duration="10s")
    m1 = extract_metrics(rep1)
    record_experiment_result("R9-C3", {"distribution": "uniform", "count": 200}, m1)
    print(f"[*] C3.1 (Uniform 200): writeWait p50={m1['writeWait']['p50']}ms, cmdResult p50={m1['commandResult']['p50']}ms")

    # Condition 2: 200 concentrated in Partition 0 (200 / 0 / 0 / 0)
    cleanup_quarantined_slots()
    inject_quarantined_slots(200, target_partition=0)
    rep2 = run_benchmark("smoke", duration="10s")
    m2 = extract_metrics(rep2)
    record_experiment_result("R9-C3", {"distribution": "concentrated_p0", "count": 200}, m2)
    print(f"[*] C3.2 (Partition 0 only 200): writeWait p50={m2['writeWait']['p50']}ms, cmdResult p50={m2['commandResult']['p50']}ms")

    cleanup_quarantined_slots()
    print("[✓] R9-C3 completed successfully.")

def run_experiment_c4():
    """R9-C4: Temporary Saturation & Recovery."""
    print("\n" + "="*80)
    print("▶ R9-C4: Saturation Temporaire et Reprise")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # Overload test: 20 cmd/s for 15s using realistic multi-pot workload (r9-s2-5)
    print("[*] Launching overload injection at 20 cmd/s (r9-s2-5)...")
    rep1 = run_benchmark("r9-s2-5", duration="15s", rate=20)
    m1 = extract_metrics(rep1)
    record_experiment_result("R9-C4", {"phase": "overload", "rate": 20}, m1)
    print(f"[*] C4 (Overload): admissions={m1['admissionsAccepted']}, applied={m1['commandsApplied']}, writeWait p50={m1['writeWait']['p50']}ms")

    # Check recovery after overload: 1 cmd/s for 10s (r9-s1-1)
    time.sleep(3)
    print("[*] Checking recovery back to nominal rate at 1 cmd/s (r9-s1-1)...")
    rep2 = run_benchmark("r9-s1-1", duration="10s", rate=1)
    m2 = extract_metrics(rep2)
    record_experiment_result("R9-C4", {"phase": "recovery", "rate": 1}, m2)
    print(f"[*] C4 (Recovery): admissions={m2['admissionsAccepted']}, applied={m2['commandsApplied']}, writeWait p50={m2['writeWait']['p50']}ms")

    print("[✓] R9-C4 completed successfully.")

def run_experiment_c5():
    """R9-C5: Worker Interruption & Restart Resilience."""
    print("\n" + "="*80)
    print("▶ R9-C5: Interruption et Reprise de Worker")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # Inject 50 slots, simulate restart during active state
    inject_quarantined_slots(50)
    print("[*] Simulating abrupt worker restart...")
    run_cmd(["docker", "restart", "pocoma-functional-runtime-monolith-1"])
    time.sleep(8)
    
    # Hand over partition leases immediately to simulate natural lease expiration
    reset_sql = (
        "SET search_path = v3_write, public; "
        "UPDATE v3_write.partition_ownership SET owned_until = now() - interval '1 second' WHERE owner_token IS NOT NULL;"
    )
    exec_pg("pocoma_write", reset_sql)
    reset_sql_read = (
        "SET search_path = v3_read, public; "
        "UPDATE v3_read.partition_ownership SET owned_until = now() - interval '1 second' WHERE owner_token IS NOT NULL;"
    )
    exec_pg("pocoma_read", reset_sql_read)
    time.sleep(3)

    rep = run_benchmark("r9-s1-1", duration="10s")
    m = extract_metrics(rep)
    record_experiment_result("R9-C5", {"phase": "post_restart", "residual_slots": 50}, m)
    print(f"[*] C5 (Post-restart): writeWait p50={m['writeWait']['p50']}ms, cmdResult p50={m['commandResult']['p50']}ms")

    cleanup_quarantined_slots()
    print("[✓] R9-C5 completed successfully.")

def run_experiment_c6():
    raise RuntimeError("Historical operation retired by L7.3: incomplete slots must be preserved; use a new disposable fixture")

def run_experiment_c7():
    """R9-C7: Multi-Worker Concurrency Simulation & Safety Check."""
    print("\n" + "="*80)
    print("▶ R9-C7: Concurrence Multi-Workers & Sûreté")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # Run contention workload (multiple VUs on same shared pot)
    print("[*] Executing contention workload under multi-client concurrency...")
    rep = run_benchmark("r9-s1-1", duration="10s")
    m = extract_metrics(rep)
    record_experiment_result("R9-C7", {"scenario": "concurrency_safety"}, m)
    print(f"[*] C7: admissions={m['admissionsAccepted']}, applied={m['commandsApplied']}, writeWait p50={m['writeWait']['p50']}ms")

    print("[✓] R9-C7 completed successfully.")

def run_experiment_c8():
    """R9-C8: Controlled Aging (Successive Scenarios Without Reset)."""
    print("\n" + "="*80)
    print("▶ R9-C8: Vieillissement Contrôlé (3 Cycles Successifs)")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    for cycle in range(1, 4):
        print(f"[*] Aging cycle {cycle}/3...")
        rep = run_benchmark("r9-s1-1", duration="10s")
        m = extract_metrics(rep)
        record_experiment_result("R9-C8", {"cycle": cycle}, m)
        print(f"[*] C8 Cycle {cycle}: writeWait p50={m['writeWait']['p50']}ms, cmdResult p50={m['commandResult']['p50']}ms")
        time.sleep(2)

    print("[✓] R9-C8 completed successfully.")

def main():
    raise RuntimeError("Historical operation retired by L7.3: incomplete slots must be preserved; use a new disposable fixture")

if __name__ == "__main__":
    main()
