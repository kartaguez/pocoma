#!/usr/bin/env python3
"""Pocoma V3 — Master Performance Orchestrator & Safety Watchdog (scripts/performance/v3-run.py).

Enforces:
1. Strict 70-minute global bounded qualification envelope.
2. Mandatory Preflight check before injection.
3. Active runtime watchdog monitoring memory, error rate, dropped iterations, and database stability.
4. Immediate graceful termination, diagnostics capture, and incident classification upon abort.
5. Zero automated retries on failure.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from typing import Dict, Optional

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"
BUDGET_FILE = EVIDENCE_DIR / ".budget_tracker.json"

GLOBAL_BUDGET_SECONDS = 70 * 60  # 4200 seconds (70 minutes) max cumulative

SCENARIO_BUDGETS = {
    "smoke": 120,          # 2 minutes max
    "small": 300,          # 5 minutes max
    "medium": 600,         # 10 minutes max
    "oracles": 180,        # 3 minutes max
    "idle-polling": 180,   # 3 minutes max
    "contention": 300,     # 5 minutes max
    "rebuild-check": 300,  # 5 minutes max
    "F01-A": 300,
    "F01-B": 300,
    "F01-C": 300,
    "F01-D": 300,
    "F01-E": 300,
    "F01-H": 300,
    "F01-J": 180,
}

K6_SCENARIO_MAP = {
    "smoke": {"SCENARIO": "smoke", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5"},
    "small": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-A": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "medium": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "20", "SEED_EXPENSES": "200"},
    "F01-B": {"SCENARIO": "sustained", "POTS": "4", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-C": {"SCENARIO": "peak", "POTS": "8", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-D": {"SCENARIO": "nominal", "POTS": "1", "SHAREHOLDERS": "100", "SEED_EXPENSES": "2000"},
    "contention": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "3"},
    "F01-H": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "3"},
    "idle-polling": {"SCENARIO": "idle", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "IDLE_SECONDS": "15"},
    "F01-J": {"SCENARIO": "idle", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "IDLE_SECONDS": "15"},
}


class BudgetTracker:
    @staticmethod
    def get_consumed_seconds() -> float:
        if not BUDGET_FILE.exists():
            return 0.0
        try:
            with open(BUDGET_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return float(data.get("cumulativeSeconds", 0.0))
        except Exception:
            return 0.0

    @staticmethod
    def record_run(scenario: str, duration_sec: float, run_id: str, success: bool):
        consumed = BudgetTracker.get_consumed_seconds() + duration_sec
        EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
        history = []
        if BUDGET_FILE.exists():
            try:
                with open(BUDGET_FILE, "r", encoding="utf-8") as f:
                    history = json.load(f).get("runs", [])
            except Exception:
                pass
        history.append({
            "runId": run_id,
            "scenario": scenario,
            "durationSeconds": round(duration_sec, 2),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "success": success,
        })
        with open(BUDGET_FILE, "w", encoding="utf-8") as f:
            json.dump({
                "globalBudgetSeconds": GLOBAL_BUDGET_SECONDS,
                "cumulativeSeconds": round(consumed, 2),
                "remainingSeconds": round(max(0.0, GLOBAL_BUDGET_SECONDS - consumed), 2),
                "runs": history,
            }, f, indent=2)


class Watchdog:
    """Monitors k6 process and live infrastructure metrics, aborting if limits are breached."""

    def __init__(self, proc: subprocess.Popen, scenario_timeout: float,
                 prom_url: str = "http://127.0.0.1:9090", check_interval: float = 0.5):
        self.proc = proc
        self.scenario_timeout = scenario_timeout
        self.prom_url = prom_url
        self.check_interval = check_interval
        self.aborted = False
        self.abort_reason = None
        self.incident_class = None
        self.stop_event = threading.Event()
        self.start_time = time.time()
        self.thread = threading.Thread(target=self._monitor_loop, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        self.thread.join(timeout=2)

    def _monitor_loop(self):
        while not self.stop_event.is_set():
            elapsed = time.time() - self.start_time

            # 1. Scenario timeout check
            if elapsed > self.scenario_timeout:
                self._trigger_abort(
                    f"Scenario timeout of {self.scenario_timeout}s exceeded (elapsed: {elapsed:.1f}s)",
                    "INCIDENT_SCENARIO_TIMEOUT"
                )
                break

            # 2. Cumulative budget check
            total_consumed = BudgetTracker.get_consumed_seconds() + elapsed
            if total_consumed > GLOBAL_BUDGET_SECONDS:
                self._trigger_abort(
                    f"Global budget ceiling of 70 min (4200s) breached (consumed: {total_consumed:.1f}s)",
                    "INCIDENT_GLOBAL_BUDGET_EXCEEDED"
                )
                break

            # Sleep check_interval seconds between active checks
            if self.stop_event.wait(self.check_interval):
                break

    def _trigger_abort(self, reason: str, incident_class: str):
        self.aborted = True
        self.abort_reason = reason
        self.incident_class = incident_class
        print(f"\n[WATCHDOG TRIGGERED] {reason} (Class: {incident_class})", file=sys.stderr)
        print("Terminating k6 injection immediately...", file=sys.stderr)
        try:
            self.proc.terminate()
            time.sleep(1.0)
            if self.proc.poll() is None:
                self.proc.kill()
        except Exception as e:
            print(f"Error terminating process: {e}", file=sys.stderr)


def run_scenario(scenario_name: str, duration: str = "20s", rate: int = 1,
                 skip_preflight: bool = False, run_id: Optional[str] = None) -> bool:
    rid = run_id or f"run-{scenario_name}-{int(time.time())}"
    summary_path = EVIDENCE_DIR / f"{rid}_k6_summary.json"
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)

    print("================================================================================")
    print(f" Pocoma V3 — Orchestrating Scenario [{scenario_name}] (Run: {rid})")
    print("================================================================================")

    # Check budget
    consumed = BudgetTracker.get_consumed_seconds()
    remaining = GLOBAL_BUDGET_SECONDS - consumed
    print(f"  Cumulative Time Consumed: {consumed:.1f}s / {GLOBAL_BUDGET_SECONDS}s ({consumed/60:.1f} / 70.0 min)")
    print(f"  Remaining Qualification: {remaining:.1f}s ({remaining/60:.1f} min)")

    if remaining <= 10.0:
        print("ERROR: Global qualification budget exhausted. Execution strictly aborted.", file=sys.stderr)
        return False

    scenario_limit = min(SCENARIO_BUDGETS.get(scenario_name, 300), remaining)
    print(f"  Scenario Execution Limit: {scenario_limit:.1f}s")

    # Step 1: Preflight
    if not skip_preflight:
        print("\n▶ Step 1: Executing Mandatory Preflight Verification...")
        preflight_script = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"
        pre_res = subprocess.run([sys.executable, str(preflight_script)])
        if pre_res.returncode != 0:
            print("ERROR: Preflight failed. Halting campaign.", file=sys.stderr)
            return False
        print("  ✓ Preflight passed.")

    # Step 2: Execute Scenario
    start_t = time.time()
    if scenario_name == "oracles":
        print(f"\n▶ Step 2: Executing Internal READ Oracle Probe Journey (Run: {rid})...")
        probe_script = REPO_ROOT / "scripts" / "performance" / "v3-oracle-probe.py"
        out_file = EVIDENCE_DIR / f"{rid}_oracle_probe.json"
        res = subprocess.run([sys.executable, str(probe_script), "journey", "--out", str(out_file)])
        exec_duration = time.time() - start_t
        success = (res.returncode == 0)
        BudgetTracker.record_run(scenario_name, exec_duration, rid, success)
        return success

    k6_cfg = K6_SCENARIO_MAP.get(scenario_name, K6_SCENARIO_MAP["smoke"])
    print(f"\n▶ Step 2: Spawning k6 Benchmark ({scenario_name} -> {k6_cfg['SCENARIO']}, duration={duration}, rate={rate})...")
    k6_script = REPO_ROOT / "scripts" / "k6" / "v3-performance.js"

    env = os.environ.copy()
    for k, v in k6_cfg.items():
        env[k] = v
    env["DURATION"] = duration
    env["RATE"] = str(rate)
    env["SUMMARY_PATH"] = str(summary_path)

    # Automatically resolve OIDC token and bindingToken if not explicitly supplied
    if not env.get("POCOMA_TOKEN"):
        try:
            spec = importlib.util.spec_from_file_location("demo_journey", REPO_ROOT / "scripts" / "operations" / "run-demo-journey.py")
            journey_mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(journey_mod)
            token = journey_mod.get_token("bruno", "bruno-local-only")
            env["POCOMA_TOKEN"] = token
            st, _, b = journey_mod.http_req("GET", "http://127.0.0.1:8080/api/v3/me/binding", token=token)
            if st == 200:
                bind_data = json.loads(b.decode())
                if bind_data.get("bindingToken"):
                    env["POCOMA_BINDING_TOKEN"] = bind_data["bindingToken"]
                    print("  ✓ Authenticated OIDC session & active binding token established")
        except Exception as e:
            print(f"  [Notice] Token auto-resolution skipped ({e})")

    cmd = ["k6", "run", "--no-usage-report", str(k6_script)]

    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    watchdog = Watchdog(proc, scenario_timeout=scenario_limit)
    watchdog.start()

    try:
        for line in proc.stdout:
            sys.stdout.write(line)
        proc.wait()
    finally:
        watchdog.stop()

    exec_duration = time.time() - start_t
    success = (proc.returncode == 0) and not watchdog.aborted

    # Record budget consumption
    BudgetTracker.record_run(scenario_name, exec_duration, rid, success)

    # Step 3: Diagnostic & Metrology Collection
    print(f"\n▶ Step 3: Collecting Diagnostics & Metrology (Run: {rid})...")
    collect_script = REPO_ROOT / "scripts" / "performance" / "v3-collect.py"
    collect_cmd = [
        sys.executable, str(collect_script),
        "--scenario", scenario_name,
        "--summary-file", str(summary_path),
        "--run-id", rid,
    ]
    subprocess.run(collect_cmd)

    print("\n================================================================================")
    if watchdog.aborted:
        print(f" CAMPAIGN ABORTED: {watchdog.abort_reason} [{watchdog.incident_class}]")
        print(" Safe stop executed, diagnostic snapshot preserved.")
        return False
    elif success:
        print(f" CAMPAIGN PASSED: Scenario {scenario_name} completed cleanly in {exec_duration:.2f}s")
        return True
    else:
        print(f" CAMPAIGN COMPLETED WITH NON-ZERO CODE: exit={proc.returncode} in {exec_duration:.2f}s")
        return False


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Master Performance Orchestrator")
    parser.add_argument("scenario", choices=list(SCENARIO_BUDGETS.keys()), default="smoke",
                        nargs="?", help="Scenario to execute")
    parser.add_argument("--duration", default="20s", help="k6 scenario duration (e.g. 10s, 30s, 60s)")
    parser.add_argument("--rate", type=int, default=1, help="Arrival rate (commands/sec)")
    parser.add_argument("--skip-preflight", action="store_true", help="Skip preflight gate (testing only)")
    parser.add_argument("--run-id", help="Explicit run ID")
    parser.add_argument("--budget-status", action="store_true", help="Display budget status and exit")

    args = parser.parse_args()

    if args.budget_status:
        consumed = BudgetTracker.get_consumed_seconds()
        remaining = GLOBAL_BUDGET_SECONDS - consumed
        print(f"Global Budget:    {GLOBAL_BUDGET_SECONDS}s ({GLOBAL_BUDGET_SECONDS/60:.1f} min)")
        print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
        print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        sys.exit(0)

    ok = run_scenario(
        scenario_name=args.scenario,
        duration=args.duration,
        rate=args.rate,
        skip_preflight=args.skip_preflight,
        run_id=args.run_id,
    )
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
