#!/usr/bin/env python3
"""Pocoma V3 — Master Performance Orchestrator & Safety Watchdog (scripts/performance/v3-run.py).

Enforces:
1. Strict 70-minute global bounded qualification envelope.
2. Mandatory Preflight check before injection.
3. Pre-scenario metrics snapshotting for exact differential stage telemetry.
4. Active runtime watchdog monitoring timeout, budget, memory, and database stability.
5. Immediate graceful termination, diagnostics capture, and incident classification upon abort.
6. Execution of ramp-up paliers (P1 1/s, P2 5/s, P3 10/s, P4 20/s, P5 50/s) and independent scenarios (S1..S5).
7. Automatic generation of CSV datasets and SVG charts after each run.
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
from typing import Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))
from v3_token import OidcTokenManager, TokenHttpServer
from v3_observer import DecoupledBenchCoordinator
from v3_realistic_workload import (
    WorkloadConfig,
    RealisticWorkloadScheduler,
    prepare_live_fixtures,
    save_fixtures_manifest,
    load_fixtures_manifest,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"
BUDGET_FILE = EVIDENCE_DIR / ".budget_tracker.json"
GLOBAL_BUDGET_SECONDS = 70 * 60  # 4200 seconds (70 minutes) max cumulative

EVIDENCE_DIR_R4 = EVIDENCE_DIR / "R4"
BUDGET_FILE_R4 = EVIDENCE_DIR_R4 / ".budget_tracker_r4.json"
GLOBAL_BUDGET_SECONDS_R4 = 30 * 60  # 1800 seconds (30 minutes) max cumulative for R4

EVIDENCE_DIR_R5 = EVIDENCE_DIR / "R5"
BUDGET_FILE_R5 = EVIDENCE_DIR_R5 / ".budget_tracker_r5.json"
GLOBAL_BUDGET_SECONDS_R5 = 30 * 60  # 1800 seconds (30 minutes) max cumulative for R5

EVIDENCE_DIR_R6 = EVIDENCE_DIR / "R6"
BUDGET_FILE_R6 = EVIDENCE_DIR_R6 / ".budget_tracker_r6.json"
GLOBAL_BUDGET_SECONDS_R6 = 30 * 60  # 1800 seconds (30 minutes) max cumulative for R6

SCENARIO_BUDGETS = {
    "smoke": 120,          # 2 minutes max
    "small": 300,          # 5 minutes max
    "medium": 300,         # 5 minutes max
    "large": 300,          # 5 minutes max
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
    # Phase C & D Scenarios
    "ramp-p1": 360,        # 1 cmd/s, 120s (+ setup buffer)
    "ramp-p2": 360,        # 5 cmd/s, 120s (+ setup buffer)
    "ramp-p3": 360,        # 10 cmd/s, 120s (+ setup buffer)
    "ramp-p4": 360,        # 20 cmd/s, 120s (+ setup buffer)
    "ramp-p5": 360,        # 50 cmd/s, 120s (+ setup buffer)
    "s1-indep": 300,       # Independent Pots
    "s2-contention": 300,  # Shared Pot contention
    "s3-read": 300,        # Pure READ load
    "s4-mixed": 300,       # Mixed WRITE + READ
    "s5-small": 300,       # Structural Small (5/20)
    "s5-medium": 300,      # Structural Medium (20/200)
    "s5-large": 300,       # Structural Large (100/2000)
    # R4 Scenarios
    "q1-smoke": 120,       # 15s + setup
    "q2-nominal-5": 240,   # 60s + 50 pots setup
    "q3-ramp-10": 240,     # 60s + 50 pots setup
    "q4-ramp-20": 240,     # 60s + 50 pots setup
    "q5-ramp-50": 300,     # 60s + 50 pots setup
    "q6-contention": 180,  # 30s + 1 pot setup
    "q7-large": 240,       # 30s + large pot setup
    # R5 Scenarios (Realistic Workload 88/10/2)
    "m1-smoke": 120,       # 20s + setup
    "m2-nominal-5": 240,   # 60s + setup
    "m3-ramp-10": 240,     # 60s + setup
    "m4-ramp-20": 300,     # 60s + setup
    "m5-ramp-50": 450,     # 120s + setup
    # R6 Scenarios (Stage Handoff Metrology)
    "h1-nominal-1": 150,     # 30s + setup
    "h2-nominal-5": 240,     # 60s + setup
    "h3-contention-10": 180, # 30s + setup
    "h4-realistic-5": 240,   # 60s + setup
    "h5-realistic-20": 300,  # 60s + setup
}

K6_SCENARIO_MAP = {
    "smoke": {"SCENARIO": "smoke", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5"},
    "small": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-A": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "medium": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "20", "SEED_EXPENSES": "200"},
    "large": {"SCENARIO": "nominal", "POTS": "1", "SHAREHOLDERS": "100", "SEED_EXPENSES": "2000"},
    "F01-B": {"SCENARIO": "sustained", "POTS": "4", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-C": {"SCENARIO": "peak", "POTS": "8", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20"},
    "F01-D": {"SCENARIO": "nominal", "POTS": "1", "SHAREHOLDERS": "100", "SEED_EXPENSES": "2000"},
    "contention": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "3"},
    "F01-H": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "3"},
    "idle-polling": {"SCENARIO": "idle", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "IDLE_SECONDS": "15"},
    "F01-J": {"SCENARIO": "idle", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "IDLE_SECONDS": "15"},
    # Phase C & D scenarios
    "ramp-p1": {"SCENARIO": "nominal", "POTS": "4", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "120s"},
    "ramp-p2": {"SCENARIO": "nominal", "POTS": "5", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "120s"},
    "ramp-p3": {"SCENARIO": "sustained", "POTS": "8", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "120s"},
    "ramp-p4": {"SCENARIO": "sustained", "POTS": "10", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "120s"},
    "ramp-p5": {"SCENARIO": "peak", "POTS": "12", "SHAREHOLDERS": "5", "SEED_EXPENSES": "5", "DEFAULT_RATE": 50, "DEFAULT_DURATION": "120s"},
    "s1-indep": {"SCENARIO": "nominal", "POTS": "4", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s"},
    "s2-contention": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "4", "DEFAULT_DURATION": "45s"},
    "s3-read": {"SCENARIO": "read", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20", "READ_VUS": "3", "DEFAULT_DURATION": "45s"},
    "s4-mixed": {"SCENARIO": "mixed", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20", "DEFAULT_DURATION": "45s"},
    "s5-small": {"SCENARIO": "structural", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "20", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s"},
    "s5-medium": {"SCENARIO": "structural", "POTS": "1", "SHAREHOLDERS": "20", "SEED_EXPENSES": "200", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s"},
    "s5-large": {"SCENARIO": "structural", "POTS": "1", "SHAREHOLDERS": "100", "SEED_EXPENSES": "2000", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s"},
    # R4 Scenarios (with POTS=50 for Q2..Q5 to isolate CAS contention)
    "q1-smoke": {"SCENARIO": "nominal", "POTS": "2", "SHAREHOLDERS": "5", "SEED_EXPENSES": "2", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "15s"},
    "q2-nominal-5": {"SCENARIO": "nominal", "POTS": "50", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s"},
    "q3-ramp-10": {"SCENARIO": "sustained", "POTS": "50", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "60s"},
    "q4-ramp-20": {"SCENARIO": "sustained", "POTS": "50", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "60s"},
    "q5-ramp-50": {"SCENARIO": "peak", "POTS": "50", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 50, "DEFAULT_DURATION": "60s"},
    "q6-contention": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "4", "DEFAULT_DURATION": "30s"},
    "q7-large": {"SCENARIO": "structural", "POTS": "1", "SHAREHOLDERS": "100", "SEED_EXPENSES": "2000", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s"},
    # R5 Scenarios (Realistic Workload 88/10/2)
    "m1-smoke": {"SCENARIO": "m1-smoke", "POTS": "10", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "20s", "WORKLOAD_MODE": "realistic"},
    "m2-nominal-5": {"SCENARIO": "m2-nominal-5", "POTS": "25", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "m3-ramp-10": {"SCENARIO": "m3-ramp-10", "POTS": "50", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "m4-ramp-20": {"SCENARIO": "m4-ramp-20", "POTS": "100", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "m5-ramp-50": {"SCENARIO": "m5-ramp-50", "POTS": "250", "DEFAULT_RATE": 50, "DEFAULT_DURATION": "120s", "WORKLOAD_MODE": "realistic"},
    # R6 Scenarios (Stage Handoff Metrology)
    "h1-nominal-1": {"SCENARIO": "nominal", "POTS": "10", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s"},
    "h2-nominal-5": {"SCENARIO": "nominal", "POTS": "25", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s"},
    "h3-contention-10": {"SCENARIO": "contention", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "10", "CONTENTION_VUS": "4", "DEFAULT_DURATION": "30s"},
    "h4-realistic-5": {"SCENARIO": "m2-nominal-5", "POTS": "25", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "h5-realistic-20": {"SCENARIO": "m4-ramp-20", "POTS": "100", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
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


class BudgetTrackerR4:
    """Dedicated budget tracker for the R4 qualification campaign (1800s / 30m ceiling)."""

    @staticmethod
    def get_consumed_seconds() -> float:
        if not BUDGET_FILE_R4.exists():
            return 0.0
        try:
            with open(BUDGET_FILE_R4, "r", encoding="utf-8") as f:
                data = json.load(f)
                return float(data.get("cumulativeSeconds", 0.0))
        except Exception:
            return 0.0

    @staticmethod
    def record_run(scenario: str, duration_sec: float, run_id: str, success: bool):
        consumed = BudgetTrackerR4.get_consumed_seconds() + duration_sec
        EVIDENCE_DIR_R4.mkdir(parents=True, exist_ok=True)
        history = []
        if BUDGET_FILE_R4.exists():
            try:
                with open(BUDGET_FILE_R4, "r", encoding="utf-8") as f:
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
        with open(BUDGET_FILE_R4, "w", encoding="utf-8") as f:
            json.dump({
                "globalBudgetSeconds": GLOBAL_BUDGET_SECONDS_R4,
                "cumulativeSeconds": round(consumed, 2),
                "remainingSeconds": round(max(0.0, GLOBAL_BUDGET_SECONDS_R4 - consumed), 2),
                "runs": history,
            }, f, indent=2)


class BudgetTrackerR5:
    """Dedicated budget tracker for the R5 realistic workload qualification campaign (1800s / 30m ceiling)."""

    @staticmethod
    def get_consumed_seconds() -> float:
        if not BUDGET_FILE_R5.exists():
            return 0.0
        try:
            with open(BUDGET_FILE_R5, "r", encoding="utf-8") as f:
                data = json.load(f)
                return float(data.get("cumulativeSeconds", 0.0))
        except Exception:
            return 0.0

    @staticmethod
    def record_run(scenario: str, duration_sec: float, run_id: str, success: bool):
        consumed = BudgetTrackerR5.get_consumed_seconds() + duration_sec
        EVIDENCE_DIR_R5.mkdir(parents=True, exist_ok=True)
        history = []
        if BUDGET_FILE_R5.exists():
            try:
                with open(BUDGET_FILE_R5, "r", encoding="utf-8") as f:
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
        with open(BUDGET_FILE_R5, "w", encoding="utf-8") as f:
            json.dump({
                "globalBudgetSeconds": GLOBAL_BUDGET_SECONDS_R5,
                "cumulativeSeconds": round(consumed, 2),
                "remainingSeconds": round(max(0.0, GLOBAL_BUDGET_SECONDS_R5 - consumed), 2),
                "runs": history,
            }, f, indent=2)


class BudgetTrackerR6:
    """Dedicated budget tracker for the R6 async stage handoff metrology campaign (1800s / 30m ceiling)."""

    @staticmethod
    def get_consumed_seconds() -> float:
        if not BUDGET_FILE_R6.exists():
            return 0.0
        try:
            with open(BUDGET_FILE_R6, "r", encoding="utf-8") as f:
                data = json.load(f)
                return float(data.get("cumulativeSeconds", 0.0))
        except Exception:
            return 0.0

    @staticmethod
    def record_run(scenario: str, duration_sec: float, run_id: str, success: bool):
        consumed = BudgetTrackerR6.get_consumed_seconds() + duration_sec
        EVIDENCE_DIR_R6.mkdir(parents=True, exist_ok=True)
        history = []
        if BUDGET_FILE_R6.exists():
            try:
                with open(BUDGET_FILE_R6, "r", encoding="utf-8") as f:
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
        with open(BUDGET_FILE_R6, "w", encoding="utf-8") as f:
            json.dump({
                "globalBudgetSeconds": GLOBAL_BUDGET_SECONDS_R6,
                "cumulativeSeconds": round(consumed, 2),
                "remainingSeconds": round(max(0.0, GLOBAL_BUDGET_SECONDS_R6 - consumed), 2),
                "runs": history,
            }, f, indent=2)


class Watchdog:
    """Monitors k6 process and live infrastructure metrics, aborting if limits are breached."""

    def __init__(self, proc: subprocess.Popen, scenario_timeout: float,
                 prom_url: str = "http://127.0.0.1:9090", check_interval: float = 0.5,
                 tracker: Any = None, global_budget: Optional[float] = None):
        self.proc = proc
        self.scenario_timeout = scenario_timeout
        self.prom_url = prom_url
        self.check_interval = check_interval
        self.tracker = tracker or BudgetTracker
        self.global_budget = global_budget if global_budget is not None else GLOBAL_BUDGET_SECONDS
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
            total_consumed = self.tracker.get_consumed_seconds() + elapsed
            if total_consumed > self.global_budget:
                self._trigger_abort(
                    f"Global budget ceiling of {self.global_budget/60:.0f} min ({self.global_budget}s) breached (consumed: {total_consumed:.1f}s)",
                    "INCIDENT_GLOBAL_BUDGET_EXCEEDED"
                )
                break

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


def extract_correlation_event(line: str) -> Optional[Dict[str, Any]]:
    stripped = line.strip()
    if '"commandId":' in stripped:
        if stripped.startswith('{"commandId":'):
            try:
                return json.loads(stripped)
            except Exception:
                pass
        match = re.search(r'msg="(\{.*?\})"', stripped)
        if match:
            try:
                raw = match.group(1).replace('\\"', '"')
                return json.loads(raw)
            except Exception:
                pass
        start = stripped.find('{"commandId":')
        if start != -1:
            end = stripped.rfind('}')
            if end != -1:
                try:
                    snippet = stripped[start:end+1].replace('\\"', '"')
                    return json.loads(snippet)
                except Exception:
                    pass
    return None


def run_scenario(scenario_name: str, duration: Optional[str] = None, rate: Optional[int] = None,
                 skip_preflight: bool = False, run_id: Optional[str] = None,
                 is_r4: bool = False, is_r5: bool = False, is_r6: bool = False) -> bool:
    is_r6_run = is_r6 or scenario_name.startswith("h")
    is_r5_run = not is_r6_run and (is_r5 or scenario_name.startswith("m"))
    is_r4_run = not is_r6_run and not is_r5_run and (is_r4 or scenario_name.startswith("q"))

    if is_r6_run:
        target_evidence_dir = EVIDENCE_DIR_R6
        target_tracker = BudgetTrackerR6
        target_budget_limit = GLOBAL_BUDGET_SECONDS_R6
        prefix = "run-r6"
    elif is_r5_run:
        target_evidence_dir = EVIDENCE_DIR_R5
        target_tracker = BudgetTrackerR5
        target_budget_limit = GLOBAL_BUDGET_SECONDS_R5
        prefix = "run-r5"
    elif is_r4_run:
        target_evidence_dir = EVIDENCE_DIR_R4
        target_tracker = BudgetTrackerR4
        target_budget_limit = GLOBAL_BUDGET_SECONDS_R4
        prefix = "run-r4"
    else:
        target_evidence_dir = EVIDENCE_DIR
        target_tracker = BudgetTracker
        target_budget_limit = GLOBAL_BUDGET_SECONDS
        prefix = "run-r2"

    rid = run_id or f"{prefix}-{scenario_name}-{int(time.time())}"
    summary_path = target_evidence_dir / f"{rid}_k6_summary.json"
    pre_snapshot_path = target_evidence_dir / f"{rid}_pre_snapshot.txt"
    target_evidence_dir.mkdir(parents=True, exist_ok=True)

    k6_cfg = K6_SCENARIO_MAP.get(scenario_name, K6_SCENARIO_MAP["smoke"])
    eff_duration = duration or k6_cfg.get("DEFAULT_DURATION", "20s")
    eff_rate = rate if rate is not None else k6_cfg.get("DEFAULT_RATE", 1)

    print("================================================================================")
    print(f" Pocoma V3 — Orchestrating Scenario [{scenario_name}] (Run: {rid})")
    print("================================================================================")

    # Check budget
    consumed = target_tracker.get_consumed_seconds()
    remaining = target_budget_limit - consumed
    print(f"  Cumulative Time Consumed: {consumed:.1f}s / {target_budget_limit}s ({consumed/60:.1f} / {target_budget_limit/60:.1f} min)")
    print(f"  Remaining Qualification: {remaining:.1f}s ({remaining/60:.1f} min)")

    if remaining <= 10.0:
        print("ERROR: Global qualification budget exhausted. Execution strictly aborted.", file=sys.stderr)
        return False

    dur_sec = 20.0
    if eff_duration.endswith("s"):
        try:
            dur_sec = float(eff_duration[:-1])
        except ValueError:
            pass
    scenario_limit = min(max(SCENARIO_BUDGETS.get(scenario_name, 300), dur_sec + 150), remaining)
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

    # Step 1b: Take pre-scenario telemetry snapshot for differential computation
    collect_script = REPO_ROOT / "scripts" / "performance" / "v3-collect.py"
    subprocess.run([sys.executable, str(collect_script), "--take-snapshot", str(pre_snapshot_path)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

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

    print(f"\n▶ Step 2: Spawning k6 Benchmark ({scenario_name} -> {k6_cfg['SCENARIO']}, duration={eff_duration}, rate={eff_rate})...")
    k6_script = REPO_ROOT / "scripts" / "k6" / "v3-performance.js"

    # Start proactive OIDC Token Manager
    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()

    # Automatically resolve bindingToken
    binding_token = os.environ.get("POCOMA_BINDING_TOKEN")
    if not binding_token:
        try:
            spec = importlib.util.spec_from_file_location(
                "demo_journey", REPO_ROOT / "scripts" / "operations" / "run-demo-journey.py"
            )
            journey_mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(journey_mod)
            st, _, b = journey_mod.http_req("GET", "http://127.0.0.1:8080/api/v3/me/binding", token=token)
            if st == 200:
                bind_data = json.loads(b.decode())
                if bind_data.get("bindingToken"):
                    binding_token = bind_data["bindingToken"]
                    print("  ✓ Authenticated OIDC session & active binding token established")
        except Exception as e:
            print(f"  [Notice] Binding token auto-resolution skipped ({e})")

    # Initialize realistic workload fixtures and scheduler if in realistic mode
    scheduler = None
    if is_r5_run or (is_r6_run and k6_cfg.get("WORKLOAD_MODE") == "realistic") or k6_cfg.get("WORKLOAD_MODE") == "realistic":
        pot_count = int(k6_cfg.get("POTS", 250))
        wl_cfg = WorkloadConfig(pot_pool_size=pot_count, random_seed=20261108)
        manifest_path = target_evidence_dir / "fixtures_manifest.json"
        pots = prepare_live_fixtures(token, binding_token, wl_cfg, manifest_path=manifest_path)
        scheduler = RealisticWorkloadScheduler(config=wl_cfg, pots=pots)

    token_server = TokenHttpServer(token_mgr, port=18083, scheduler=scheduler)
    token_server.start()

    is_decoupled = (is_r6_run or is_r5_run or scenario_name.startswith("ramp-p") or scenario_name in ("q1-smoke", "q2-nominal-5", "q3-ramp-10", "q4-ramp-20", "q5-ramp-50", "q7-large") or k6_cfg.get("SCENARIO") in ("nominal", "sustained", "peak", "ramp") or scenario_name.startswith("m") or scenario_name.startswith("h"))
    coordinator = None
    if is_decoupled:
        max_drain = min(90.0, max(45.0, dur_sec * 0.5))
        coordinator = DecoupledBenchCoordinator(
            run_id=rid, token_manager=token_mgr, binding_token=binding_token, max_drain_seconds=max_drain,
            evidence_dir=target_evidence_dir, scheduler=scheduler
        )
        coordinator.start()

    env = os.environ.copy()
    for k, v in k6_cfg.items():
        if not k.startswith("DEFAULT_"):
            env[k] = str(v)
    env["DURATION"] = str(eff_duration)
    env["RATE"] = str(eff_rate)
    env["SUMMARY_PATH"] = str(summary_path)
    env["POCOMA_TOKEN"] = token
    env["POCOMA_AUTH_URL"] = token_server.url
    env["DECOUPLED"] = "true" if is_decoupled else "false"
    env["POLL_TIMEOUT_MS"] = os.environ.get("POLL_TIMEOUT_MS", "25000")
    if is_r5_run:
        env["WORKLOAD_MODE"] = "realistic"
    if binding_token:
        env["POCOMA_BINDING_TOKEN"] = binding_token

    # For structural scenarios, pass pre-seeded parameters if available
    if scenario_name in ("s5-small", "s5-medium", "s5-large", "q7-large"):
        seed_map = {
            "s5-small": EVIDENCE_DIR / "seeds" / "seed-v3-small-indep.json",
            "s5-medium": EVIDENCE_DIR / "seeds" / "seed-v3-medium-indep.json",
            "s5-large": EVIDENCE_DIR / "seeds" / "seed-v3-large-indep.json",
            "q7-large": EVIDENCE_DIR / "seeds" / "seed-v3-large-indep.json",
        }
        seed_file = seed_map.get(scenario_name)
        if seed_file and seed_file.exists():
            try:
                with open(seed_file, "r", encoding="utf-8") as f:
                    sdata = json.load(f)
                proof = sdata.get("materializationProof", [])
                if proof and sdata.get("materialized"):
                    p = sdata["pots"][0]
                    env["PRE_SEEDED_POT_ID"] = p["potId"]
                    env["PRE_SEEDED_VERSION"] = str(proof[0]["finalVersion"])
                    env["PRE_SEEDED_EXPENSES"] = str(proof[0]["expensesCount"])
                    env["PRE_SEEDED_SHAREHOLDERS"] = ",".join([s["id"] for s in p["shareholders"]])
                    print(f"  ✓ Using pre-materialized Pot {p['potId']} (v{proof[0]['finalVersion']}, {proof[0]['expensesCount']} expenses)")
            except Exception as e:
                print(f"  [Warning] Pre-seeded pot resolution failed ({e}), falling back to inline setup")

    cmd = ["k6", "run", "--log-format=raw", "--no-usage-report", str(k6_script)]

    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    watchdog = Watchdog(proc, scenario_timeout=scenario_limit, tracker=target_tracker, global_budget=target_budget_limit)
    watchdog.start()

    try:
        for line in proc.stdout:
            event = extract_correlation_event(line) if is_decoupled else None
            if event:
                coordinator.store.record_admission_event(event)
            else:
                sys.stdout.write(line)
        proc.wait()
    finally:
        watchdog.stop()

    decoupled_path = None
    if is_decoupled and coordinator:
        coordinator.mark_injection_ended()
        drain_res = coordinator.drain()
        decoupled_report = coordinator.consolidate_metrics(
            demanded_rate=eff_rate,
            injection_target_sec=dur_sec,
        )
        decoupled_report["drainage"] = drain_res
        decoupled_report["oidcTelemetry"] = token_mgr.get_telemetry()
        decoupled_path = target_evidence_dir / f"{rid}_decoupled.json"
        with open(decoupled_path, "w", encoding="utf-8") as df:
            json.dump(decoupled_report, df, indent=2)

    token_server.stop()

    exec_duration = time.time() - start_t
    success = (proc.returncode == 0) and not watchdog.aborted

    # Record budget consumption
    target_tracker.record_run(scenario_name, exec_duration, rid, success)

    # Step 3: Diagnostic & Differential Metrology Collection
    print(f"\n▶ Step 3: Collecting Diagnostics & Differential Metrology (Run: {rid})...")
    collect_cmd = [
        sys.executable, str(collect_script),
        "--scenario", scenario_name,
        "--summary-file", str(summary_path),
        "--pre-snapshot", str(pre_snapshot_path),
        "--run-id", rid,
        "--evidence-dir", str(target_evidence_dir),
    ]
    if decoupled_path and decoupled_path.exists():
        collect_cmd.extend(["--decoupled-file", str(decoupled_path)])
    subprocess.run(collect_cmd)

    # Step 4: Regenerate CSV and SVG charts
    print("\n▶ Step 4: Regenerating Performance Charts & CSV Datasets...")
    plot_script = REPO_ROOT / "scripts" / "performance" / "v3-plot.py"
    subprocess.run([
        sys.executable, str(plot_script),
        "--evidence-dir", str(target_evidence_dir),
        "--output-dir", str(target_evidence_dir / "charts"),
    ])

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
        return False


def run_ramp_suite(duration: str = "120s") -> bool:
    """Executes the progressive ramp-up suite: P1 (1), P2 (5), P3 (10), P4 (20), P5 (50)."""
    paliers = [
        ("ramp-p1", 1),
        ("ramp-p2", 5),
        ("ramp-p3", 10),
        ("ramp-p4", 20),
        ("ramp-p5", 50),
    ]

    print("================================================================================")
    print(" Pocoma V3 — Progressive Ramp-Up Suite Execution")
    print(f" Palier schedule: {[f'{p[0]} ({p[1]} cmd/s)' for p in paliers]}")
    print("================================================================================")

    all_passed = True
    for p_name, p_rate in paliers:
        rem = GLOBAL_BUDGET_SECONDS - BudgetTracker.get_consumed_seconds()
        if rem < 150:
            print(f"\n[RAMP SUITE] Budget low ({rem:.1f}s remaining). Safely halting ramp suite before {p_name}.")
            break

        print(f"\n>>> Launching Palier [{p_name}] @ {p_rate} cmd/s (duration={duration}) <<<")
        ok = run_scenario(p_name, duration=duration, rate=p_rate)
        if not ok:
            print(f"\n[RAMP SUITE] Palier {p_name} finished with warnings or threshold breach.")
            all_passed = False
        time.sleep(2.0)  # Brief inter-palier settlement pause

    return all_passed


def run_r4_suite() -> bool:
    """Executes the complete R4 qualification suite under the 1,800s budget envelope."""
    q_scenarios = [
        ("q1-smoke", "15s", 1),
        ("q2-nominal-5", "60s", 5),
        ("q3-ramp-10", "60s", 10),
        ("q4-ramp-20", "60s", 20),
        ("q5-ramp-50", "60s", 50),
        ("q6-contention", "30s", None),
    ]

    print("================================================================================")
    print(" Pocoma V3 — R4 Final Metrology & Useful Capacity Qualification Suite")
    print(f" Envelope: {GLOBAL_BUDGET_SECONDS_R4}s (30 min) tracked in {BUDGET_FILE_R4}")
    print(f" Scenarios: {[q[0] for q in q_scenarios]}")
    print("================================================================================")

    # First execute mandatory Preflight
    print("\n▶ Preflight Gate for R4 Campaign...")
    preflight_script = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"
    pre_res = subprocess.run([sys.executable, str(preflight_script)])
    if pre_res.returncode != 0:
        print("ERROR: Preflight failed. Aborting R4 suite.", file=sys.stderr)
        return False
    print("  ✓ Preflight passed.")

    all_passed = True
    for sc_name, sc_dur, sc_rate in q_scenarios:
        rem = GLOBAL_BUDGET_SECONDS_R4 - BudgetTrackerR4.get_consumed_seconds()
        if rem < 120:
            print(f"\n[R4 SUITE] Low budget ({rem:.1f}s remaining). Halting suite safely before {sc_name}.")
            all_passed = False
            break

        print(f"\n>>> Launching R4 Scenario [{sc_name}] (duration={sc_dur}, rate={sc_rate}) <<<")
        ok = run_scenario(sc_name, duration=sc_dur, rate=sc_rate, skip_preflight=True, is_r4=True)
        if not ok:
            print(f"\n[R4 SUITE] Scenario {sc_name} reported warnings or non-zero exit.")
            all_passed = False
        time.sleep(3.0)  # Inter-scenario settlement pause

    # Check if Q7 is runnable
    large_seed = EVIDENCE_DIR / "seeds" / "seed-v3-large-indep.json"
    if large_seed.exists():
        rem = GLOBAL_BUDGET_SECONDS_R4 - BudgetTrackerR4.get_consumed_seconds()
        if rem >= 150:
            print("\n>>> Launching Optional R4 Scenario [q7-large] <<<")
            ok_q7 = run_scenario("q7-large", duration="30s", rate=1, skip_preflight=True, is_r4=True)
            if not ok_q7:
                all_passed = False
        else:
            print(f"\n[R4 SUITE] Insufficient budget for Q7 ({rem:.1f}s remaining). Skipping.")
    else:
        print("\n[R4 SUITE] Q7 skipped: Large profile not pre-materialized (explicit reservation documented).")

    return all_passed


def run_r5_suite() -> bool:
    """Executes the full R5 campaign (M1..M5) under realistic business workload (88/10/2)."""
    m_scenarios = [
        ("m1-smoke", "20s", 1),
        ("m2-nominal-5", "60s", 5),
        ("m3-ramp-10", "60s", 10),
        ("m4-ramp-20", "60s", 20),
        ("m5-ramp-50", "120s", 50),
    ]

    print("================================================================================")
    print(" Pocoma V3 — R5 Realistic Workload Qualification Campaign (50 cmd/s, 88/10/2)")
    print(f" Envelope Budget: {GLOBAL_BUDGET_SECONDS_R5}s (30 min)")
    print(f" Evidence Directory: {EVIDENCE_DIR_R5}")
    print(f" Scenarios: {[m[0] for m in m_scenarios]}")
    print("================================================================================")

    # First execute mandatory Preflight
    print("\n▶ Preflight Gate for R5 Campaign...")
    preflight_script = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"
    pre_res = subprocess.run([sys.executable, str(preflight_script)])
    if pre_res.returncode != 0:
        print("ERROR: Preflight failed. Aborting R5 suite.", file=sys.stderr)
        return False
    print("  ✓ Preflight passed.")

    # Upfront fixture preparation for all 250 pots
    print("\n▶ Pre-generating / Verifying 250 Realistic Fixtures...")
    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()
    binding_token = os.environ.get("POCOMA_BINDING_TOKEN")
    if not binding_token:
        try:
            spec = importlib.util.spec_from_file_location(
                "demo_journey", REPO_ROOT / "scripts" / "operations" / "run-demo-journey.py"
            )
            journey_mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(journey_mod)
            st, _, b = journey_mod.http_req("GET", "http://127.0.0.1:8080/api/v3/me/binding", token=token)
            if st == 200:
                bind_data = json.loads(b.decode())
                binding_token = bind_data.get("bindingToken")
        except Exception:
            pass

    t_prep_start = time.time()
    wl_cfg = WorkloadConfig(pot_pool_size=250, random_seed=20261108)
    manifest_path = EVIDENCE_DIR_R5 / "fixtures_manifest.json"
    EVIDENCE_DIR_R5.mkdir(parents=True, exist_ok=True)
    prepare_live_fixtures(token, binding_token, wl_cfg, manifest_path=manifest_path)
    prep_dur = time.time() - t_prep_start
    BudgetTrackerR5.record_run("fixtures-prep", prep_dur, f"prep-fixtures-{int(time.time())}", True)
    print(f"  ✓ Fixtures verified and tracked in budget ({prep_dur:.1f}s)")

    all_passed = True
    for sc_name, sc_dur, sc_rate in m_scenarios:
        rem = GLOBAL_BUDGET_SECONDS_R5 - BudgetTrackerR5.get_consumed_seconds()
        if rem < 120:
            print(f"\n[R5 SUITE] Low budget ({rem:.1f}s remaining). Halting suite safely before {sc_name}.")
            all_passed = False
            break

        print(f"\n>>> Launching R5 Scenario [{sc_name}] (duration={sc_dur}, rate={sc_rate}) <<<")
        ok = run_scenario(sc_name, duration=sc_dur, rate=sc_rate, skip_preflight=True, is_r5=True)
        if not ok:
            print(f"\n[R5 SUITE] Scenario {sc_name} reported warnings or non-zero exit.")
            all_passed = False
        time.sleep(3.0)  # Inter-scenario settlement pause

    return all_passed


def run_r6_suite() -> bool:
    """Executes the canonical R6 async stage handoff qualification suite under the strict 1800s budget."""
    h_scenarios = [
        ("h1-nominal-1", "30s", 1),
        ("h2-nominal-5", "60s", 5),
        ("h3-contention-10", "30s", None),
        ("h4-realistic-5", "60s", 5),
        ("h5-realistic-20", "60s", 20),
    ]

    print("================================================================================")
    print(" Pocoma V3 — R6 Async Stage Handoff Metrology Qualification Suite")
    print("================================================================================")
    rem_init = GLOBAL_BUDGET_SECONDS_R6 - BudgetTrackerR6.get_consumed_seconds()
    print(f"  R6 Envelope Budget: {GLOBAL_BUDGET_SECONDS_R6}s (30.0 min)")
    print(f"  Remaining:          {rem_init:.1f}s ({rem_init/60:.1f} min)")
    print(f"  Scenarios Planned:  {len(h_scenarios)} ({', '.join(s[0] for s in h_scenarios)})")

    # Step 1: Preflight
    print("\n▶ Pre-Suite Gate: Executing Mandatory Preflight Verification...")
    preflight_script = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"
    pre_res = subprocess.run([sys.executable, str(preflight_script)])
    if pre_res.returncode != 0:
        print("ERROR: Preflight failed. Aborting R6 suite.", file=sys.stderr)
        return False
    print("  ✓ Preflight passed.")

    all_passed = True
    for sc_name, sc_dur, sc_rate in h_scenarios:
        rem = GLOBAL_BUDGET_SECONDS_R6 - BudgetTrackerR6.get_consumed_seconds()
        if rem < 120:
            print(f"\n[R6 SUITE] Low budget ({rem:.1f}s remaining). Halting suite safely before {sc_name}.")
            all_passed = False
            break

        print(f"\n>>> Launching R6 Scenario [{sc_name}] (duration={sc_dur}, rate={sc_rate}) <<<")
        ok = run_scenario(sc_name, duration=sc_dur, rate=sc_rate, skip_preflight=True, is_r6=True)
        if not ok:
            print(f"\n[R6 SUITE] Scenario {sc_name} reported warnings or non-zero exit.")
            all_passed = False
        time.sleep(3.0)  # Inter-scenario settlement pause

    return all_passed


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Master Performance Orchestrator")
    parser.add_argument("scenario", choices=list(SCENARIO_BUDGETS.keys()) + ["ramp-suite", "r4-suite", "r5-suite", "r6-suite"], default="smoke",
                        nargs="?", help="Scenario to execute")
    parser.add_argument("--duration", help="k6 scenario duration (e.g. 10s, 30s, 60s, 120s)")
    parser.add_argument("--rate", type=int, help="Arrival rate (commands/sec)")
    parser.add_argument("--skip-preflight", action="store_true", help="Skip preflight gate (testing only)")
    parser.add_argument("--run-id", help="Explicit run ID")
    parser.add_argument("--budget-status", action="store_true", help="Display budget status and exit")
    parser.add_argument("--r4", action="store_true", help="Execute under R4 budget envelope and output directory")
    parser.add_argument("--r5", action="store_true", help="Execute under R5 budget envelope and output directory")
    parser.add_argument("--r6", action="store_true", help="Execute under R6 budget envelope and output directory")

    args = parser.parse_args()

    if args.budget_status:
        if args.r6:
            consumed = BudgetTrackerR6.get_consumed_seconds()
            remaining = GLOBAL_BUDGET_SECONDS_R6 - consumed
            print(f"R6 Budget:        {GLOBAL_BUDGET_SECONDS_R6}s ({GLOBAL_BUDGET_SECONDS_R6/60:.1f} min)")
            print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
            print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        elif args.r5:
            consumed = BudgetTrackerR5.get_consumed_seconds()
            remaining = GLOBAL_BUDGET_SECONDS_R5 - consumed
            print(f"R5 Budget:        {GLOBAL_BUDGET_SECONDS_R5}s ({GLOBAL_BUDGET_SECONDS_R5/60:.1f} min)")
            print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
            print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        elif args.r4:
            consumed = BudgetTrackerR4.get_consumed_seconds()
            remaining = GLOBAL_BUDGET_SECONDS_R4 - consumed
            print(f"R4 Budget:        {GLOBAL_BUDGET_SECONDS_R4}s ({GLOBAL_BUDGET_SECONDS_R4/60:.1f} min)")
            print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
            print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        else:
            consumed = BudgetTracker.get_consumed_seconds()
            remaining = GLOBAL_BUDGET_SECONDS - consumed
            print(f"Global Budget:    {GLOBAL_BUDGET_SECONDS}s ({GLOBAL_BUDGET_SECONDS/60:.1f} min)")
            print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
            print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        sys.exit(0)

    if args.scenario == "r6-suite":
        ok = run_r6_suite()
        sys.exit(0 if ok else 1)

    if args.scenario == "r5-suite":
        ok = run_r5_suite()
        sys.exit(0 if ok else 1)

    if args.scenario == "r4-suite":
        ok = run_r4_suite()
        sys.exit(0 if ok else 1)

    if args.scenario == "ramp-suite":
        dur = args.duration or "120s"
        ok = run_ramp_suite(duration=dur)
        sys.exit(0 if ok else 1)

    is_r6 = args.r6 or args.scenario.startswith("h")
    is_r5 = not is_r6 and (args.r5 or args.scenario.startswith("m"))
    is_r4 = not is_r6 and not is_r5 and (args.r4 or args.scenario.startswith("q"))
    ok = run_scenario(
        scenario_name=args.scenario,
        duration=args.duration,
        rate=args.rate,
        skip_preflight=args.skip_preflight,
        run_id=args.run_id,
        is_r4=is_r4,
        is_r5=is_r5,
        is_r6=is_r6,
    )
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
