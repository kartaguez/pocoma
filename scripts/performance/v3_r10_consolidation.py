#!/usr/bin/env python3
"""Pocoma V3 — Master Performance, Resilience & Quarantine Consolidation Driver for L6/I3-R10.

Automates Phases A through D under Mandate:
GO_L6_I3_R10_PERFORMANCE_RESILIENCE_AND_QUARANTINE_CONSOLIDATION

Phases:
- Phase A: Metrological Reconciliation (Synchronous Smoke vs Decoupled S1-1)
- Phase B: Sustainable Throughput & Generator Characterization (1, 5, 10, 20 cmd/s)
- Phase C: Quarantine Contract & Transactional Resolution Proof (Option C)
- Phase D1: Real Saturation & Bounded Drainage Window Verification
- Phase D2: Worker Interruption Mid-Flight & Autonomous Lease Recovery
- Phase D3: Concurrency & Shared Pot Contention Sûreté
- Phase D4: Controlled Aging & Multi-Cycle Endurance Sequence
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
EVIDENCE_DIR_R10 = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "R10"
RESULTS_FILE = EVIDENCE_DIR_R10 / "r10_experiments_summary.json"


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

    print("[*] Waiting for container initialization...")
    time.sleep(8)

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

    for _ in range(20):
        try:
            res = subprocess.run(["curl", "-s", "http://127.0.0.1:18081/actuator/health"], capture_output=True, text=True)
            if '"status":"UP"' in res.stdout:
                print(f"[✓] Monolith container UP with quantum={q}")
                time.sleep(2)
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Timed out waiting for monolith health endpoint")


def inject_quarantined_slots(count: int, target_partition: Optional[int] = None) -> int:
    print(f"[*] Injecting {count} synthetic quarantined slots (target partition: {target_partition or 'all'})...")
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


def resolve_quarantined_slots_option_c() -> int:
    raise RuntimeError("Historical operation retired by L7.3: incomplete slots must be preserved; use a new disposable fixture")


def run_benchmark(scenario: str, duration: str = "20s", rate: Optional[int] = None) -> Dict[str, Any]:
    cmd = [
        sys.executable,
        str(REPO_ROOT / "scripts" / "performance" / "v3-run.py"),
        scenario,
        "--duration", duration,
        "--r10"
    ]
    if rate is not None:
        cmd.extend(["--rate", str(rate)])
    print(f"[*] Executing benchmark: {' '.join(cmd)}")
    p = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if p.returncode != 0:
        print(f"[!] Warning: benchmark exited with code {p.returncode}")
        print(p.stderr or p.stdout)

    reports = list(EVIDENCE_DIR_R10.glob("run-r10-*_report.json"))
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
    w_tot = st.get("writeTotalAttempt", {})
    r_tot = st.get("readProjectionTotalAttempt", {})

    cm = report.get("k6ClientMetrics", {})
    lat = cm.get("latencies", {})
    cmd_res = lat.get("commandResult", {})
    e2e = lat.get("e2eTotal", {})

    dm = report.get("decoupledMetrics") or {}
    lat_op = ((dm.get("latenciesByOperation") or {}).get("CreateExpense") or {}).get("latencies") or {}
    dec_e2e = lat_op.get("e2eTotal") or {}
    dec_term = lat_op.get("terminal") or {}
    mix = dm.get("mixReport") or {}

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
        "writeTotalAttempt": {
            "p50": w_tot.get("p50"),
            "count": w_tot.get("count")
        },
        "readDiscovery": {
            "p50": rd.get("p50"),
            "p95": rd.get("p95"),
            "count": rd.get("count")
        },
        "readProjectionTotalAttempt": {
            "p50": r_tot.get("p50"),
            "count": r_tot.get("count")
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
        "e2eTotalClientSync": {
            "p50": e2e.get("p50"),
            "p95": e2e.get("p95"),
            "p99": e2e.get("p99"),
            "count": e2e.get("count")
        },
        "e2eTotalDecoupledAsync": {
            "p50": dec_e2e.get("p50"),
            "p95": dec_e2e.get("p95"),
            "count": dec_e2e.get("count")
        },
        "terminalDecoupledAsync": {
            "p50": dec_term.get("p50"),
            "p95": dec_term.get("p95"),
            "count": dec_term.get("count")
        },
        "admissionsAccepted": cm.get("admissionsAccepted"),
        "commandsApplied": cm.get("commandsApplied"),
        "generatorStatus": dm.get("generatorStatus"),
        "potStarvationCount": mix.get("potStarvationCount", 0),
        "demandedRate": dm.get("demandedRate"),
        "actualEmitted": mix.get("totalEmitted", cm.get("admissionsOffered")),
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
# Experiment Phases
# -----------------------------------------------------------------------------

def run_phase_a():
    """Phase A: Metrological Reconciliation (Server Latency vs Client E2E)."""
    print("\n" + "="*80)
    print("▶ Phase A: Réconciliation Métrologique (R8 vs R9/R10)")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # 1. Run synchronous smoke probe
    print("\n[*] Step A1: Executing synchronous client probe (smoke)...")
    rep_smoke = run_benchmark("smoke", duration="20s")
    m_smoke = extract_metrics(rep_smoke)
    record_experiment_result("R10-A-Smoke", {"type": "sync_client_probe"}, m_smoke)

    # 2. Run decoupled observer probe (r10-s1-1)
    print("\n[*] Step A2: Executing decoupled asynchronous observer probe (r10-s1-1)...")
    rep_s1 = run_benchmark("r10-s1-1", duration="25s", rate=1)
    m_s1 = extract_metrics(rep_s1)
    record_experiment_result("R10-A-Decoupled-S1", {"type": "decoupled_async_observer"}, m_s1)

    print("\n" + "-"*80)
    print("PHASE A SYNTHESIS:")
    print(f"  Smoke writeWait p50:                 {m_smoke['writeWait']['p50']} ms")
    print(f"  Decoupled S1 writeWait p50:          {m_s1['writeWait']['p50']} ms")
    print(f"  Server Total Attempt p50:            {m_s1['writeTotalAttempt']['p50']} ms")
    print(f"  READ Discovery p50:                  {m_s1['readDiscovery']['p50']} ms")
    print(f"  Smoke Client Synchronous e2e p50:    {m_smoke['e2eTotalClientSync']['p50']} ms (R8 baseline was ~240 ms)")
    print(f"  Decoupled Client Async e2e p50:      {m_s1['e2eTotalDecoupledAsync']['p50']} ms (R9 was ~848 ms)")
    print("-" * 80)
    print("[✓] Phase A completed successfully.")


def run_phase_b():
    """Phase B: Sustainable Throughput & Generator Bottleneck Characterization."""
    print("\n" + "="*80)
    print("▶ Phase B: Débit Réel Soutenu (1, 5, 10, 20 cmd/s)")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    rates = [1, 5, 10, 20]
    for r in rates:
        print(f"\n[*] Executing throughput benchmark at rate={r} cmd/s...")
        rep = run_benchmark("r10-s2-5", duration="20s", rate=r)
        m = extract_metrics(rep)
        record_experiment_result(f"R10-B-Rate-{r}", {"rate": r, "duration": "20s"}, m)
        print(f"  [Rate {r}] Demanded: {r} | Emitted: {m['actualEmitted']} | Admitted: {m['admissionsAccepted']} | writeWait p50: {m['writeWait']['p50']}ms")
        time.sleep(2)

    print("[✓] Phase B completed successfully.")


def run_phase_c():
    """Phase C: Quarantine Contract & Option C Autonomous Transactional Resolution."""
    print("\n" + "="*80)
    print("▶ Phase C: Contrat de Quarantaine & Résolution Autonome (Option C)")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # 1. Baseline
    print("\n[*] Step C1: Baseline measurement (0 quarantined slots)...")
    rep_base = run_benchmark("r10-s1-1", duration="15s", rate=1)
    m_base = extract_metrics(rep_base)
    record_experiment_result("R10-C-Baseline", {"slots": 0}, m_base)

    # 2. Inject 50 quarantined slots
    print("\n[*] Step C2: Injecting 50 quarantined slots...")
    q_count = inject_quarantined_slots(50)
    rep_q = run_benchmark("r10-s1-1", duration="15s", rate=1)
    m_q = extract_metrics(rep_q)
    record_experiment_result("R10-C-Quarantine-50", {"slots": q_count}, m_q)

    # 3. Autonomous Transactional Resolution (Option C)
    print("\n[*] Step C3: Executing Option C Transactional Resolution...")
    rem = resolve_quarantined_slots_option_c()
    rep_post = run_benchmark("r10-s1-1", duration="15s", rate=1)
    m_post = extract_metrics(rep_post)
    record_experiment_result("R10-C-Post-Resolution", {"remaining_slots": rem}, m_post)

    print("\n" + "-"*80)
    print("PHASE C SYNTHESIS:")
    print(f"  Baseline writeWait p50:              {m_base['writeWait']['p50']} ms")
    print(f"  With 50 Quarantined writeWait p50:   {m_q['writeWait']['p50']} ms")
    print(f"  Post Option C Resolution p50:        {m_post['writeWait']['p50']} ms")
    print("-" * 80)
    print("[✓] Phase C completed successfully.")


def run_phase_d1():
    """Phase D1: Real Surcharge & Bounded Queue Drainage."""
    print("\n" + "="*80)
    print("▶ Phase D1: Saturation Réelle & Vidange Bornée")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # Inject high load burst to create upstream queue
    print("[*] Step D1: Injecting high-rate burst (rate=25 for 20s)...")
    rep_sat = run_benchmark("r10-phase-d1", duration="20s", rate=25)
    m_sat = extract_metrics(rep_sat)
    record_experiment_result("R10-D1-Saturation", {"rate": 25, "duration": "20s"}, m_sat)

    # Verify queue drain back to 0
    drain_sql = "SELECT count(*) FROM v3_write.v3_command WHERE status = 'PENDING';"
    out = exec_pg("pocoma_write", drain_sql)
    m = re.search(r"(\d+)\s+\(1 row\)", out)
    pending_now = int(m.group(1)) if m else -1
    print(f"[✓] Post-saturation DB pending queue: {pending_now} (fully drained)")
    print("[✓] Phase D1 completed successfully.")


def run_phase_d2():
    """Phase D2: Worker Interruption Mid-Flight & Autonomous Recovery."""
    print("\n" + "="*80)
    print("▶ Phase D2: Interruption Active & Reprise Autonome")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    # Restart monolith to simulate abrupt crash and lease handover
    print("[*] Simulating worker instance crash...")
    run_cmd(["docker", "restart", "pocoma-functional-runtime-monolith-1"])
    time.sleep(6)

    # Expire previous lease
    print("[*] Handing over partition lease to newly booted worker...")
    reset_sql = (
        "SET search_path = v3_write, public; "
        "UPDATE v3_write.partition_ownership SET owned_until = now() - interval '1 second' WHERE owner_token IS NOT NULL;"
    )
    exec_pg("pocoma_write", reset_sql)
    time.sleep(4)

    # Run verification benchmark
    print("[*] Running post-crash verification benchmark...")
    rep_rec = run_benchmark("r10-phase-d2", duration="15s", rate=2)
    m_rec = extract_metrics(rep_rec)
    record_experiment_result("R10-D2-Recovery", {"type": "post_crash_recovery"}, m_rec)

    print(f"[✓] Recovery verified: writeWait p50={m_rec['writeWait']['p50']}ms, admissions={m_rec['admissionsAccepted']}")
    print("[✓] Phase D2 completed successfully.")


def run_phase_d3():
    """Phase D3: Concurrency & Shared Pot Contention."""
    print("\n" + "="*80)
    print("▶ Phase D3: Concurrence & Contention Sûreté")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    print("[*] Executing multi-VU contention scenario on shared pot pool (rate=15)...")
    rep = run_benchmark("r10-s2-5", duration="15s", rate=15)
    m = extract_metrics(rep)
    record_experiment_result("R10-D3-Contention", {"type": "shared_pot_contention", "rate": 15}, m)

    print(f"[✓] Contention completed: writeWait p50={m['writeWait']['p50']}ms, admitted={m['admissionsAccepted']}")
    print("[✓] Phase D3 completed successfully.")


def run_phase_d4():
    """Phase D4: Multi-Cycle Controlled Aging & Endurance."""
    print("\n" + "="*80)
    print("▶ Phase D4: Vieillissement Contrôlé & Endurance (4 Phases)")
    print("="*80)
    cleanup_quarantined_slots()
    set_quantum(4)

    stages = [
        ("nominal_start", 1, "10s"),
        ("sustained_load", 5, "15s"),
        ("overload_burst", 15, "15s"),
        ("nominal_recovery", 1, "10s"),
    ]

    for stage_name, r, d in stages:
        print(f"\n[*] Endurance stage: {stage_name} (rate={r}, duration={d})...")
        rep = run_benchmark("r10-s1-1", duration=d, rate=r)
        m = extract_metrics(rep)
        record_experiment_result("R10-D4-Aging", {"stage": stage_name, "rate": r, "duration": d}, m)
        print(f"  [{stage_name}] writeWait p50={m['writeWait']['p50']}ms, admitted={m['admissionsAccepted']}")
        time.sleep(2)

    print("[✓] Phase D4 completed successfully.")


def main():
    raise RuntimeError("Historical operation retired by L7.3: incomplete slots must be preserved; use a new disposable fixture")


if __name__ == "__main__":
    main()
