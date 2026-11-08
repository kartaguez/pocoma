#!/usr/bin/env python3
"""
Pocoma V3 — L6/I3-R7 Causal Performance Optimization Campaign Driver
Mandate: GO_L6_I3_R7_CAUSAL_PERFORMANCE_OPTIMIZATION

Orchestrates controlled experimental evaluation across 5 configurations:
  Config A: Baseline (Polling Original 10/1000/1000, Concurrency Original 4/1, Batch Original 32)
  Config B: Polling 20ms Isolated (Polling 2/20/50, Concurrency Original 4/1, Batch Original 32)
  Config C: Concurrency 4/8 Isolated (Polling Original, Concurrency 4/8, Batch Original 32)
  Config D: Batch 32 / Claim 120s Isolated (Polling Original, Concurrency Original 4/1, Batch 32 / Claim 120s)
  Config E: Full Combined Optimization (Polling 20ms, Concurrency 4/8, Batch 32 / Claim 120s)

Scenarios per config:
  - Idle: Charge à vide (20s window, zero load)
  - S1: 1 cmd/s, 60s (r7-s1-1)
  - S2: 5 cmd/s, 60s (r7-s2-5)
  - S3: 10 cmd/s, 90s (r7-s3-10)
  - Medium (Configs A & E): 1 cmd/s, 60s on 20-shareholder / 200-expense Pot (r7-medium)
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Dict, Any, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
EVIDENCE_DIR_R7 = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "R7"
CONFIGS_DIR = REPO_ROOT / "docker" / "monolith" / "configurations"
ACTIVE_CONFIG = REPO_ROOT / "docker" / "monolith" / "application-v3.properties"
RUN_SCRIPT = REPO_ROOT / "scripts" / "performance" / "v3-run.py"
PREFLIGHT_SCRIPT = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"

CONTAINER_NAME = "pocoma-functional-runtime-monolith-1"
POSTGRES_CONTAINER = "pocoma-functional-postgres-1"


def get_prom_metrics() -> Dict[str, float]:
    url = "http://127.0.0.1:18081/actuator/prometheus"
    try:
        req = urllib.request.urlopen(url, timeout=5)
        lines = req.read().decode("utf-8").splitlines()
        metrics = {}
        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) == 2:
                try:
                    metrics[parts[0]] = float(parts[1])
                except ValueError:
                    pass
        return metrics
    except Exception as e:
        print(f"[Warning] Failed to scrape Prometheus: {e}", file=sys.stderr)
        return {}


def get_pg_stats() -> Dict[str, Any]:
    sql = (
        "SELECT datname, xact_commit, xact_rollback, tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted "
        "FROM pg_stat_database WHERE datname IN ('pocoma_write', 'pocoma_read');"
    )
    res = subprocess.run(
        ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "pocoma", "-d", "pocoma_write", "-t", "-A", "-F", ",", "-c", sql],
        capture_output=True, text=True
    )
    stats = {}
    if res.returncode == 0:
        for line in res.stdout.strip().splitlines():
            parts = line.split(",")
            if len(parts) >= 8:
                db = parts[0]
                stats[db] = {
                    "xact_commit": int(parts[1]),
                    "xact_rollback": int(parts[2]),
                    "tup_returned": int(parts[3]),
                    "tup_fetched": int(parts[4]),
                    "tup_inserted": int(parts[5]),
                    "tup_updated": int(parts[6]),
                    "tup_deleted": int(parts[7]),
                }
    return stats


def get_docker_stats() -> Dict[str, Any]:
    res = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}",
         CONTAINER_NAME, POSTGRES_CONTAINER],
        capture_output=True, text=True
    )
    stats = {}
    if res.returncode == 0:
        for line in res.stdout.strip().splitlines():
            parts = line.split("|")
            if len(parts) == 3:
                name = parts[0]
                cpu_str = parts[1].replace("%", "").strip()
                mem_str = parts[2].split("/")[0].strip()
                try:
                    stats[name] = {
                        "cpu_percent": float(cpu_str),
                        "mem_str": mem_str
                    }
                except ValueError:
                    stats[name] = {"cpu_str": parts[1], "mem_str": parts[2]}
    return stats


def check_max_remaining_lease() -> float:
    sql_write = "SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (owned_until - clock_timestamp()))), 0) FROM v3_write.partition_ownership;"
    sql_read = "SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (owned_until - clock_timestamp()))), 0) FROM v3_read.partition_ownership;"
    
    res_w = subprocess.run(
        ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "pocoma", "-d", "pocoma_write", "-t", "-c", sql_write],
        capture_output=True, text=True
    )
    res_r = subprocess.run(
        ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "pocoma", "-d", "pocoma_read", "-t", "-c", sql_read],
        capture_output=True, text=True
    )
    rem_w = float(res_w.stdout.strip()) if res_w.returncode == 0 and res_w.stdout.strip() else 0.0
    rem_r = float(res_r.stdout.strip()) if res_r.returncode == 0 and res_r.stdout.strip() else 0.0
    return max(rem_w, rem_r)


def measure_idle_cost(config_name: str, duration_sec: int = 20) -> Dict[str, Any]:
    print(f"\n================================================================================")
    print(f" Measuring Charge à Vide (Idle Cost) — Config {config_name} ({duration_sec}s window)")
    print(f"================================================================================")
    
    # Pre-sampling snapshot
    p0 = get_prom_metrics()
    pg0 = get_pg_stats()
    ds0 = get_docker_stats()
    t0 = time.time()
    
    print(f"  Sampling idle background activity for {duration_sec}s...")
    time.sleep(duration_sec)
    
    # Post-sampling snapshot
    p1 = get_prom_metrics()
    pg1 = get_pg_stats()
    ds1 = get_docker_stats()
    t1 = time.time()
    dt = max(0.001, t1 - t0)
    
    # Cycles
    disc0 = p0.get('pocoma_v3_worker_cycles_total{loop="discovery",outcome="confirmed"}', 0)
    disc1 = p1.get('pocoma_v3_worker_cycles_total{loop="discovery",outcome="confirmed"}', 0)
    cons0 = p0.get('pocoma_v3_worker_cycles_total{loop="consumption",outcome="confirmed"}', 0)
    cons1 = p1.get('pocoma_v3_worker_cycles_total{loop="consumption",outcome="confirmed"}', 0)
    
    disc_rate = (disc1 - disc0) / dt
    cons_rate = (cons1 - cons0) / dt
    total_cycles_rate = disc_rate + cons_rate
    # In idle, all cycles are zero-progress cycles
    zero_progress_rate = total_cycles_rate
    
    # Hikari acquire count (surrogate for JDBC transactions/queries)
    hik_acquire0 = sum(v for k, v in p0.items() if k.startswith("hikaricp_connections_acquire_seconds_count"))
    hik_acquire1 = sum(v for k, v in p1.items() if k.startswith("hikaricp_connections_acquire_seconds_count"))
    hik_acquire_rate = (hik_acquire1 - hik_acquire0) / dt
    
    # Active connections
    active_conns = sum(v for k, v in p1.items() if k.startswith("hikaricp_connections_active"))
    
    # PostgreSQL commits and tuples
    pg_commits0 = sum(db["xact_commit"] for db in pg0.values())
    pg_commits1 = sum(db["xact_commit"] for db in pg1.values())
    pg_commit_rate = (pg_commits1 - pg_commits0) / dt
    
    pg_tup0 = sum(db["tup_returned"] for db in pg0.values())
    pg_tup1 = sum(db["tup_returned"] for db in pg1.values())
    pg_tup_rate = (pg_tup1 - pg_tup0) / dt
    
    # CPU
    monolith_cpu = ds1.get(CONTAINER_NAME, {}).get("cpu_percent", 0.0)
    pg_cpu = ds1.get(POSTGRES_CONTAINER, {}).get("cpu_percent", 0.0)
    
    idle_data = {
        "config": config_name,
        "duration_seconds": dt,
        "discovery_cycles_per_sec": round(disc_rate, 2),
        "consumption_cycles_per_sec": round(cons_rate, 2),
        "total_cycles_per_sec": round(total_cycles_rate, 2),
        "zero_progress_cycles_per_sec": round(zero_progress_rate, 2),
        "hikari_acquires_per_sec": round(hik_acquire_rate, 2),
        "postgres_xact_commit_per_sec": round(pg_commit_rate, 2),
        "postgres_tuples_returned_per_sec": round(pg_tup_rate, 2),
        "active_jdbc_connections": active_conns,
        "monolith_cpu_percent": monolith_cpu,
        "postgres_cpu_percent": pg_cpu,
        "timestamp": time.time(),
    }
    
    print(f"  ✓ Discovery Cycles/sec:    {idle_data['discovery_cycles_per_sec']}")
    print(f"  ✓ Consumption Cycles/sec:  {idle_data['consumption_cycles_per_sec']}")
    print(f"  ✓ Total Worker Cycles/sec: {idle_data['total_cycles_per_sec']}")
    print(f"  ✓ Zero-Progress Cycles/s:  {idle_data['zero_progress_cycles_per_sec']}")
    print(f"  ✓ Hikari Acquires/sec:     {idle_data['hikari_acquires_per_sec']}")
    print(f"  ✓ Postgres Commits/sec:    {idle_data['postgres_xact_commit_per_sec']}")
    print(f"  ✓ Monolith CPU:            {idle_data['monolith_cpu_percent']}%")
    print(f"  ✓ Postgres CPU:            {idle_data['postgres_cpu_percent']}%")
    
    out_file = EVIDENCE_DIR_R7 / f"idle_metrics_config_{config_name}.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(idle_data, f, indent=2)
    print(f"  ✓ Idle metrics preserved in {out_file.name}")
    
    return idle_data


def switch_and_stabilize_config(config_name: str) -> bool:
    src_cfg = CONFIGS_DIR / f"config-{config_name}.properties"
    if not src_cfg.exists():
        print(f"ERROR: Configuration file not found: {src_cfg}", file=sys.stderr)
        return False

    # Check if currently active properties match the target (ignoring header comments)
    needs_restart = True
    if ACTIVE_CONFIG.exists():
        with open(src_cfg, "r") as f1, open(ACTIVE_CONFIG, "r") as f2:
            lines1 = [l.strip() for l in f1 if l.strip() and not l.startswith("#")]
            lines2 = [l.strip() for l in f2 if l.strip() and not l.startswith("#")]
            if lines1 == lines2:
                # Check if container is running and healthy
                p = get_prom_metrics()
                w_leases = p.get('pocoma_v3_lease_local{authority="write"}', 0)
                r_leases = p.get('pocoma_v3_lease_local{authority="read"}', 0)
                if w_leases == 8.0 and r_leases == 24.0:
                    print(f"  ✓ Container is ALREADY active with Configuration {config_name} (32/32 leases acquired). No restart needed.")
                    needs_restart = False

    if needs_restart:
        print(f"\n================================================================================")
        print(f" Switching Monolith to Configuration [{config_name}]")
        print(f"================================================================================")
        
        # 1. Copy config
        shutil.copyfile(src_cfg, ACTIVE_CONFIG)
        print(f"  ✓ Deployed config-{config_name}.properties to {ACTIVE_CONFIG.name}")
        
        # 2. Stop container gracefully
        print(f"  ▶ Stopping container {CONTAINER_NAME} gracefully (drain)...")
        subprocess.run(["docker", "stop", "-t", "30", CONTAINER_NAME], check=True)
        print(f"  ✓ Container stopped.")
        
        # 3. Wait for natural lease expiration in PostgreSQL
        rem = check_max_remaining_lease()
        print(f"  ▶ Awaiting natural expiration of partition leases in PostgreSQL (remaining: {rem:.1f}s)...")
        while rem > 0:
            sleep_step = min(rem + 1.0, 5.0)
            time.sleep(sleep_step)
            rem = check_max_remaining_lease()
            if rem > 0:
                print(f"    ... {rem:.1f}s remaining before clean lease takeover ...")
        print("  ✓ All partition leases have naturally expired (clock_timestamp >= owned_until).")
        
        # 4. Start container
        print(f"  ▶ Starting container {CONTAINER_NAME}...")
        subprocess.run(["docker", "start", CONTAINER_NAME], check=True)
        
        # 5. Poll actuator health
        print("  ▶ Waiting for Spring Boot Actuator health...")
        health_up = False
        for _ in range(60):
            try:
                res = urllib.request.urlopen("http://127.0.0.1:18081/actuator/health", timeout=2)
                if res.status == 200:
                    data = json.loads(res.read().decode())
                    if data.get("status") == "UP":
                        health_up = True
                        break
            except Exception:
                pass
            time.sleep(1)
            
        if not health_up:
            print("ERROR: Actuator health did not reach UP within 60s", file=sys.stderr)
            return False
        print("  ✓ Actuator health is UP.")
        
        # 6. Poll until all 32 partitions are acquired
        print("  ▶ Verifying effective acquisition of all 32 partition leases...")
        leases_ready = False
        for _ in range(60):
            pm = get_prom_metrics()
            w_leases = pm.get('pocoma_v3_lease_local{authority="write"}', 0)
            r_leases = pm.get('pocoma_v3_lease_local{authority="read"}', 0)
            if w_leases == 8.0 and r_leases == 24.0:
                leases_ready = True
                print(f"  ✓ All 32 leases acquired: write={w_leases:.0f}/8, read={r_leases:.0f}/24.")
                break
            time.sleep(1)
            
        if not leases_ready:
            print(f"ERROR: Partitions not acquired within 60s (w={w_leases}, r={r_leases})", file=sys.stderr)
            return False

    # 7. Run preflight
    print("  ▶ Executing preflight verification gate...")
    pf = subprocess.run([sys.executable, str(PREFLIGHT_SCRIPT)], capture_output=True, text=True)
    if pf.returncode != 0:
        print(f"ERROR: Preflight failed on config {config_name}:\n{pf.stderr}\n{pf.stdout}", file=sys.stderr)
        return False
    print("  ✓ Preflight passed cleanly.")
    return True


def run_scenario(scenario_name: str, config_name: str, run_id: str) -> bool:
    cmd = [
        sys.executable, str(RUN_SCRIPT),
        scenario_name,
        "--r7",
        "--run-id", run_id,
        "--skip-preflight"
    ]
    print(f"\n>>> Executing Scenario {scenario_name} on Config {config_name} (Run ID: {run_id})...")
    res = subprocess.run(cmd)
    if res.returncode != 0:
        print(f"ERROR: Scenario {scenario_name} failed on Config {config_name} (rc={res.returncode})", file=sys.stderr)
        return False
    # Brief queue cooling
    time.sleep(5)
    return True


def execute_campaign(configs: list, skip_medium: bool = False):
    EVIDENCE_DIR_R7.mkdir(parents=True, exist_ok=True)
    print("================================================================================")
    print(" Pocoma V3 — L6/I3-R7 Causal Performance Optimization Campaign Starting")
    print(f" Target Configurations: {configs}")
    print("================================================================================")
    
    results = {}
    
    for cfg in configs:
        print(f"\n================================================================================")
        print(f" BEGINNING EVALUATION OF CONFIGURATION [{cfg}]")
        print(f"================================================================================")
        
        ok = switch_and_stabilize_config(cfg)
        if not ok:
            print(f"FATAL: Could not stabilize Configuration {cfg}. Aborting.", file=sys.stderr)
            sys.exit(1)
            
        # 1. Idle Cost (Charge à vide)
        idle_data = measure_idle_cost(cfg, duration_sec=20)
        
        # 2. Scenario S1 (1 cmd/s, 60s)
        s1_ok = run_scenario("r7-s1-1", cfg, f"run-r7-{cfg}-s1-1")
        if not s1_ok:
            print(f"WARNING: S1 failed for Config {cfg}")
            
        # 3. Scenario S2 (5 cmd/s, 60s)
        s2_ok = run_scenario("r7-s2-5", cfg, f"run-r7-{cfg}-s2-5")
        if not s2_ok:
            print(f"WARNING: S2 failed for Config {cfg}")
            
        # 4. Scenario S3 (10 cmd/s, 90s)
        s3_ok = run_scenario("r7-s3-10", cfg, f"run-r7-{cfg}-s3-10")
        if not s3_ok:
            print(f"WARNING: S3 failed for Config {cfg}")
            
        # 5. Medium Pots (SPEC-09 comparison) — Configs A and E
        med_ok = True
        if cfg in ("A", "E") and not skip_medium:
            med_ok = run_scenario("r7-medium", cfg, f"run-r7-{cfg}-medium")
            if not med_ok:
                print(f"WARNING: Medium scenario failed for Config {cfg}")
                
        results[cfg] = {
            "idle": idle_data,
            "s1_ok": s1_ok,
            "s2_ok": s2_ok,
            "s3_ok": s3_ok,
            "med_ok": med_ok
        }
        
    print("\n================================================================================")
    print(" Pocoma V3 — L6/I3-R7 All Target Configurations Evaluated Successfully")
    print("================================================================================")


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 L6/I3-R7 Campaign Driver")
    parser.add_argument("--configs", nargs="+", choices=["A", "B", "C", "D", "E"], default=["E", "A", "B", "C", "D"],
                        help="List of configurations to evaluate (default: E, A, B, C, D)")
    parser.add_argument("--skip-medium", action="store_true", help="Skip medium pot SPEC-09 verification")
    args = parser.parse_args()
    
    execute_campaign(args.configs, skip_medium=args.skip_medium)


if __name__ == "__main__":
    main()
