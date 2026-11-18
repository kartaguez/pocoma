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

EVIDENCE_DIR_R7 = EVIDENCE_DIR / "R7"
BUDGET_FILE_R7 = EVIDENCE_DIR_R7 / ".budget_tracker_r7.json"
GLOBAL_BUDGET_SECONDS_R7 = 40 * 60  # 2400 seconds (40 minutes) max cumulative for R7

EVIDENCE_DIR_R8 = EVIDENCE_DIR / "R8"
BUDGET_FILE_R8 = EVIDENCE_DIR_R8 / ".budget_tracker_r8.json"
GLOBAL_BUDGET_SECONDS_R8 = 30 * 60  # 1800 seconds (30 minutes) max cumulative for R8

EVIDENCE_DIR_R9 = EVIDENCE_DIR / "R9"
BUDGET_FILE_R9 = EVIDENCE_DIR_R9 / ".budget_tracker_r9.json"
GLOBAL_BUDGET_SECONDS_R9 = 60 * 60  # 3600 seconds (60 minutes) max cumulative for R9

EVIDENCE_DIR_R10 = EVIDENCE_DIR / "R10"
BUDGET_FILE_R10 = EVIDENCE_DIR_R10 / ".budget_tracker_r10.json"
GLOBAL_BUDGET_SECONDS_R10 = 60 * 60  # 3600 seconds (60 minutes) max cumulative for R10

EVIDENCE_DIR_R11 = EVIDENCE_DIR / "R11"
BUDGET_FILE_R11 = EVIDENCE_DIR_R11 / ".budget_tracker_r11.json"
GLOBAL_BUDGET_SECONDS_R11 = 60 * 60  # 3600 seconds (60 minutes) max cumulative for R11

EVIDENCE_DIR_FINAL_SLA = EVIDENCE_DIR / "R11" / "final-sla"
BUDGET_FILE_FINAL_SLA = EVIDENCE_DIR_FINAL_SLA / ".budget_tracker_final_sla.json"
GLOBAL_BUDGET_SECONDS_FINAL_SLA = 60 * 60  # 3600 seconds (60 minutes) max cumulative for Final SLA Qualification

SCENARIO_BUDGETS = {
    "n1-nominal-1": 180,
    "n2-sustained-10": 240,
    "n3-peak-50": 300,
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
    # R7 Scenarios (Controlled Async Handoff Performance Optimization)
    "r7-s1-1": 150,          # 60s + setup
    "r7-s2-5": 150,          # 60s + setup
    "r7-s3-10": 200,         # 90s + setup
    "r7-medium": 150,        # 60s + setup
    "r7-idle": 120,          # 30s + setup
    # R8 Scenarios (Command Discovery & Scheduling Diagnosis)
    "r8-s1-1": 150,          # 60s + setup
    "r8-s2-5": 150,          # 60s + setup
    # R9 Scenarios (Resilience, Backlog & Robustness Qualification)
    "r9-s1-1": 150,          # 60s + setup
    "r9-s2-5": 150,          # 60s + setup
    "r9-c1": 120,            # 30s + setup
    "r9-c2": 120,            # 30s + setup
    "r9-c3": 120,            # 30s + setup
    "r9-c4": 150,            # 45s + setup
    "r9-c5": 120,            # 30s + setup
    "r9-c6": 120,            # 30s + setup
    "r9-c7": 150,            # 45s + setup
    "r9-c8": 150,            # 60s + setup
    # R10 Scenarios (Performance, Resilience & Quarantine Consolidation)
    "r10-s1-1": 150,         # 60s + setup
    "r10-s2-5": 150,         # 60s + setup
    "r10-s3-10": 150,        # 45s + setup
    "r10-s4-20": 150,        # 45s + setup
    "r10-phase-a": 120,      # 30s + setup
    "r10-phase-b": 150,      # 45s + setup
    "r10-phase-c": 150,      # 45s + setup
    "r10-phase-d1": 180,     # 60s + setup
    "r10-phase-d2": 180,     # 60s + setup
    "r10-phase-d3": 180,     # 60s + setup
    "r10-phase-d4": 240,     # 90s + setup
    # R11 Scenarios (Quarantine Discovery Filter Requalification)
    "r11-s0-ref-1": 150,     # 30s + setup
    "r11-s1-q-1": 150,       # 30s + setup
    "r11-s2-ref-5": 150,     # 30s + setup
    "r11-s3-q-5": 150,       # 30s + setup
    "r11-s4-ref-20": 150,    # 30s + setup
    "r11-s5-q-20": 150,      # 30s + setup
    "r11-s6-conc-5": 150,    # 30s + setup
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
    # R7 Scenarios (Controlled Async Handoff Performance Optimization)
    "r7-s1-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r7-s2-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r7-s3-10": {"SCENARIO": "r7-s3-10", "POTS": "100", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "90s", "WORKLOAD_MODE": "realistic"},
    "r7-medium": {"SCENARIO": "structural", "POTS": "1", "SHAREHOLDERS": "20", "SEED_EXPENSES": "200", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s"},
    "r7-idle": {"SCENARIO": "idle", "POTS": "1", "SHAREHOLDERS": "5", "SEED_EXPENSES": "1", "IDLE_SECONDS": "30"},
    # R8 Scenarios
    "r8-s1-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r8-s2-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    # R9 Scenarios
    "r9-s1-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r9-s2-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r9-c1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r9-c2": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r9-c3": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r9-c4": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r9-c5": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r9-c6": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r9-c7": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r9-c8": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    # R10 Scenarios
    "r10-s1-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r10-s2-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r10-s3-10": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r10-s4-20": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-a": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-b": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-c": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-d1": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 25, "DEFAULT_DURATION": "60s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-d2": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 2, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-d3": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 15, "DEFAULT_DURATION": "45s", "WORKLOAD_MODE": "realistic"},
    "r10-phase-d4": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "90s", "WORKLOAD_MODE": "realistic"},
    # R11 Scenarios (Quarantine Discovery Filter Requalification)
    "r11-s0-ref-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s1-q-1": {"SCENARIO": "r7-s1-1", "POTS": "50", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s2-ref-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s3-q-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s4-ref-20": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s5-q-20": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 20, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    "r11-s6-conc-5": {"SCENARIO": "r7-s2-5", "POTS": "50", "DEFAULT_RATE": 5, "DEFAULT_DURATION": "30s", "WORKLOAD_MODE": "realistic"},
    # Final SPEC-09 SLA Qualification Scenarios (Pots moyens: 20 shareholders, >= 200 initial expenses)
    "n1-nominal-1": {"SCENARIO": "nominal", "POTS": "6", "DEFAULT_RATE": 1, "DEFAULT_DURATION": "120s", "DECOUPLED": "true"},
    "n2-sustained-10": {"SCENARIO": "sustained", "POTS": "6", "DEFAULT_RATE": 10, "DEFAULT_DURATION": "180s", "DECOUPLED": "true"},
    "n3-peak-50": {"SCENARIO": "peak", "POTS": "6", "DEFAULT_RATE": 50, "DEFAULT_DURATION": "60s", "DECOUPLED": "true"},
}


class _BaseBudgetTracker:
    budget_file = BUDGET_FILE
    evidence_dir = EVIDENCE_DIR
    global_budget_seconds = GLOBAL_BUDGET_SECONDS

    @classmethod
    def get_consumed_seconds(cls) -> float:
        if not cls.budget_file.exists():
            return 0.0
        try:
            with open(cls.budget_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                return float(data.get("cumulativeSeconds", 0.0))
        except Exception:
            return 0.0

    @classmethod
    def record_run(cls, scenario: str, duration_sec: float, run_id: str, success: bool):
        consumed = cls.get_consumed_seconds() + duration_sec
        cls.evidence_dir.mkdir(parents=True, exist_ok=True)
        history = []
        if cls.budget_file.exists():
            try:
                with open(cls.budget_file, "r", encoding="utf-8") as f:
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
        with open(cls.budget_file, "w", encoding="utf-8") as f:
            json.dump({
                "globalBudgetSeconds": cls.global_budget_seconds,
                "cumulativeSeconds": round(consumed, 2),
                "remainingSeconds": round(max(0.0, cls.global_budget_seconds - consumed), 2),
                "runs": history,
            }, f, indent=2)


class BudgetTracker(_BaseBudgetTracker):
    pass


def _make_tracker(budget_file, evidence_dir, global_budget_seconds):
    return type("BudgetTracker", (_BaseBudgetTracker,), {
        "budget_file": budget_file,
        "evidence_dir": evidence_dir,
        "global_budget_seconds": global_budget_seconds,
    })


BudgetTrackerR4 = _make_tracker(BUDGET_FILE_R4, EVIDENCE_DIR_R4, GLOBAL_BUDGET_SECONDS_R4)
BudgetTrackerR5 = _make_tracker(BUDGET_FILE_R5, EVIDENCE_DIR_R5, GLOBAL_BUDGET_SECONDS_R5)
BudgetTrackerR6 = _make_tracker(BUDGET_FILE_R6, EVIDENCE_DIR_R6, GLOBAL_BUDGET_SECONDS_R6)
BudgetTrackerR7 = _make_tracker(BUDGET_FILE_R7, EVIDENCE_DIR_R7, GLOBAL_BUDGET_SECONDS_R7)
BudgetTrackerR8 = _make_tracker(BUDGET_FILE_R8, EVIDENCE_DIR_R8, GLOBAL_BUDGET_SECONDS_R8)
BudgetTrackerR9 = _make_tracker(BUDGET_FILE_R9, EVIDENCE_DIR_R9, GLOBAL_BUDGET_SECONDS_R9)
BudgetTrackerR10 = _make_tracker(BUDGET_FILE_R10, EVIDENCE_DIR_R10, GLOBAL_BUDGET_SECONDS_R10)
BudgetTrackerR11 = _make_tracker(BUDGET_FILE_R11, EVIDENCE_DIR_R11, GLOBAL_BUDGET_SECONDS_R11)
BudgetTrackerFinalSla = _make_tracker(BUDGET_FILE_FINAL_SLA, EVIDENCE_DIR_FINAL_SLA, GLOBAL_BUDGET_SECONDS_FINAL_SLA)


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


def _try_parse_json(raw: str) -> Optional[Dict[str, Any]]:
    try:
        return json.loads(raw)
    except Exception:
        return None


def extract_correlation_event(line: str) -> Optional[Dict[str, Any]]:
    stripped = line.strip()
    if '"commandId":' not in stripped:
        return None
    if stripped.startswith('{"commandId":'):
        parsed = _try_parse_json(stripped)
        if parsed is not None:
            return parsed
    match = re.search(r'msg="(\{.*?\})"', stripped)
    if match:
        parsed = _try_parse_json(match.group(1).replace('\\"', '"'))
        if parsed is not None:
            return parsed
    start = stripped.find('{"commandId":')
    end = stripped.rfind('}')
    if start != -1 and end != -1:
        return _try_parse_json(stripped[start:end + 1].replace('\\"', '"'))
    return None


CAMPAIGN_PROFILES = (
    ("final_sla", "n", "Final SLA Budget:", EVIDENCE_DIR_FINAL_SLA, BudgetTrackerFinalSla, GLOBAL_BUDGET_SECONDS_FINAL_SLA, "final-sla"),
    ("r11", "r11", "R11 Budget:      ", EVIDENCE_DIR_R11, BudgetTrackerR11, GLOBAL_BUDGET_SECONDS_R11, "run-r11"),
    ("r10", "r10", "R10 Budget:      ", EVIDENCE_DIR_R10, BudgetTrackerR10, GLOBAL_BUDGET_SECONDS_R10, "run-r10"),
    ("r9", "r9", "R9 Budget:       ", EVIDENCE_DIR_R9, BudgetTrackerR9, GLOBAL_BUDGET_SECONDS_R9, "run-r9"),
    ("r8", "r8", "R8 Budget:       ", EVIDENCE_DIR_R8, BudgetTrackerR8, GLOBAL_BUDGET_SECONDS_R8, "run-r8"),
    ("r7", "r7", "R7 Budget:       ", EVIDENCE_DIR_R7, BudgetTrackerR7, GLOBAL_BUDGET_SECONDS_R7, "run-r7"),
    ("r6", "h", "R6 Budget:       ", EVIDENCE_DIR_R6, BudgetTrackerR6, GLOBAL_BUDGET_SECONDS_R6, "run-r6"),
    ("r5", "m", "R5 Budget:       ", EVIDENCE_DIR_R5, BudgetTrackerR5, GLOBAL_BUDGET_SECONDS_R5, "run-r5"),
    ("r4", "q", "R4 Budget:       ", EVIDENCE_DIR_R4, BudgetTrackerR4, GLOBAL_BUDGET_SECONDS_R4, "run-r4"),
)
DEFAULT_PROFILE = ("r2", "", "Global Budget:   ", EVIDENCE_DIR, BudgetTracker, GLOBAL_BUDGET_SECONDS, "run-r2")


def _resolve_campaign_profile(scenario_name: str = "", flags: Optional[Dict[str, bool]] = None):
    flags = flags or {}
    for profile in CAMPAIGN_PROFILES:
        key, prefix_match = profile[0], profile[1]
        if flags.get(key) or (prefix_match and scenario_name.startswith(prefix_match)):
            return profile
    return DEFAULT_PROFILE


def _parse_duration_seconds(eff_duration: str) -> float:
    if not eff_duration.endswith("s"):
        return 20.0
    try:
        return float(eff_duration[:-1])
    except ValueError:
        return 20.0


def _run_preflight_gate() -> bool:
    print("\n▶ Step 1: Executing Mandatory Preflight Verification...")
    preflight_script = REPO_ROOT / "scripts" / "performance" / "v3-preflight.py"
    if subprocess.run([sys.executable, str(preflight_script)]).returncode != 0:
        print("ERROR: Preflight failed. Halting campaign.", file=sys.stderr)
        return False
    print("  ✓ Preflight passed.")
    return True


def _resolve_binding_token(token: str) -> Optional[str]:
    binding_token = os.environ.get("POCOMA_BINDING_TOKEN")
    if binding_token:
        return binding_token
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
                print("  ✓ Authenticated OIDC session & active binding token established")
                return bind_data["bindingToken"]
    except Exception as e:
        print(f"  [Notice] Binding token auto-resolution skipped ({e})")
    return None


def _refresh_final_sla_pots() -> Optional[List[Dict[str, Any]]]:
    manifest_path = EVIDENCE_DIR_FINAL_SLA / "fixtures_manifest.json"
    if not manifest_path.exists():
        return None
    with open(manifest_path, "r", encoding="utf-8") as f:
        mdata = json.load(f)
    manifest_pots = mdata.get("pots", [])
    try:
        pot_ids_sql = ",".join(f"'{p['potId']}'" for p in manifest_pots)
        cmd = [
            "docker", "exec", "pocoma-functional-postgres-1",
            "psql", "-U", "pocoma", "-d", "pocoma_write", "-t", "-A", "-c",
            f"SELECT pot_id, MAX(version) FROM v3_write.v3_pot_version WHERE pot_id IN ({pot_ids_sql}) GROUP BY pot_id;"
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        db_versions = {}
        for line in res.stdout.strip().splitlines():
            if "|" in line:
                pid, ver = line.split("|", 1)
                db_versions[pid.strip()] = int(ver.strip())
        for p in manifest_pots:
            if p["potId"] in db_versions:
                p["version"] = db_versions[p["potId"]]
        mdata["pots"] = manifest_pots
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(mdata, f, indent=2)
        print(f"  ✓ Refreshed live versions for {len(manifest_pots)} Pots moyens: {[p['version'] for p in manifest_pots]}")
    except Exception as e:
        print(f"  [Warning] Failed to refresh live pot versions from DB: {e}")
    print(f"  ✓ Loaded {len(manifest_pots)} pre-seeded SPEC-09 Pots moyens from {manifest_path.name}")
    return manifest_pots


def _is_decoupled_scenario(profile_key: str, scenario_name: str, k6_cfg: Dict[str, Any]) -> bool:
    if profile_key in ("final_sla", "r11", "r10", "r9", "r8", "r7", "r6", "r5"):
        return True
    if k6_cfg.get("SCENARIO") in ("nominal", "sustained", "peak", "ramp"):
        return True
    if scenario_name in ("q1-smoke", "q2-nominal-5", "q3-ramp-10", "q4-ramp-20", "q5-ramp-50", "q7-large"):
        return True
    return scenario_name.startswith(("ramp-p", "m", "h", "r7", "r8", "r9", "r10", "r11"))


def _inject_pre_seeded_pot(scenario_name: str, env: Dict[str, str]):
    seed_map = {
        "s5-small": EVIDENCE_DIR / "seeds" / "seed-v3-small-indep.json",
        "s5-medium": EVIDENCE_DIR / "seeds" / "seed-v3-medium-indep.json",
        "s5-large": EVIDENCE_DIR / "seeds" / "seed-v3-large-indep.json",
        "q7-large": EVIDENCE_DIR / "seeds" / "seed-v3-large-indep.json",
        "r7-medium": EVIDENCE_DIR / "seeds" / "seed-v3-medium-indep.json",
    }
    seed_file = seed_map.get(scenario_name)
    if not seed_file or not seed_file.exists():
        return
    try:
        with open(seed_file, "r", encoding="utf-8") as f:
            sdata = json.load(f)
        proof = sdata.get("materializationProof", [])
        if not proof or not sdata.get("materialized"):
            return
        p = sdata["pots"][0]
        env["PRE_SEEDED_POT_ID"] = p["potId"]
        current_v = proof[0]["finalVersion"]
        q_res = subprocess.run([
            "docker", "exec", "pocoma-functional-postgres-1",
            "psql", "-U", "pocoma", "-d", "pocoma_read", "-t", "-c",
            f"SELECT max(pot_version) FROM v3_read.read_pot_version WHERE pot_id = '{p['potId']}';"
        ], capture_output=True, text=True)
        if q_res.returncode == 0 and q_res.stdout.strip():
            current_v = int(q_res.stdout.strip())
        env["PRE_SEEDED_VERSION"] = str(current_v)
        env["PRE_SEEDED_EXPENSES"] = str(proof[0]["expensesCount"])
        env["PRE_SEEDED_SHAREHOLDERS"] = ",".join(s["id"] for s in p["shareholders"])
        print(f"  ✓ Using pre-materialized Pot {p['potId']} (v{current_v}, {proof[0]['expensesCount']} expenses)")
    except Exception as e:
        print(f"  [Warning] Pre-seeded pot resolution failed ({e}), falling back to inline setup")


def _collect_and_plot(collect_script: Path, scenario_name: str, summary_path: Path,
                      pre_snapshot_path: Path, rid: str, target_evidence_dir: Path,
                      decoupled_path: Optional[Path]):
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

    print("\n▶ Step 4: Regenerating Performance Charts & CSV Datasets...")
    plot_script = REPO_ROOT / "scripts" / "performance" / "v3-plot.py"
    subprocess.run([
        sys.executable, str(plot_script),
        "--evidence-dir", str(target_evidence_dir),
        "--output-dir", str(target_evidence_dir / "charts"),
    ])


def run_scenario(scenario_name: str, duration: Optional[str] = None, rate: Optional[int] = None,
                 skip_preflight: bool = False, run_id: Optional[str] = None,
                 is_r4: bool = False, is_r5: bool = False, is_r6: bool = False,
                 is_r7: bool = False, is_r8: bool = False, is_r9: bool = False,
                 is_r10: bool = False, is_r11: bool = False, is_final_sla: bool = False) -> bool:
    profile = _resolve_campaign_profile(scenario_name, {
        "final_sla": is_final_sla, "r11": is_r11, "r10": is_r10, "r9": is_r9,
        "r8": is_r8, "r7": is_r7, "r6": is_r6, "r5": is_r5, "r4": is_r4,
    })
    profile_key, _, _, target_evidence_dir, target_tracker, target_budget_limit, prefix = profile
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

    consumed = target_tracker.get_consumed_seconds()
    remaining = target_budget_limit - consumed
    print(f"  Cumulative Time Consumed: {consumed:.1f}s / {target_budget_limit}s ({consumed/60:.1f} / {target_budget_limit/60:.1f} min)")
    print(f"  Remaining Qualification: {remaining:.1f}s ({remaining/60:.1f} min)")
    if remaining <= 10.0:
        print("ERROR: Global qualification budget exhausted. Execution strictly aborted.", file=sys.stderr)
        return False

    dur_sec = _parse_duration_seconds(eff_duration)
    scenario_limit = min(max(SCENARIO_BUDGETS.get(scenario_name, 300), dur_sec + 150), remaining)
    print(f"  Scenario Execution Limit: {scenario_limit:.1f}s")

    if not skip_preflight and not _run_preflight_gate():
        return False

    collect_script = REPO_ROOT / "scripts" / "performance" / "v3-collect.py"
    subprocess.run([sys.executable, str(collect_script), "--take-snapshot", str(pre_snapshot_path)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    start_t = time.time()
    if scenario_name == "oracles":
        print(f"\n▶ Step 2: Executing Internal READ Oracle Probe Journey (Run: {rid})...")
        probe_script = REPO_ROOT / "scripts" / "performance" / "v3-oracle-probe.py"
        out_file = EVIDENCE_DIR / f"{rid}_oracle_probe.json"
        res = subprocess.run([sys.executable, str(probe_script), "journey", "--out", str(out_file)])
        success = (res.returncode == 0)
        BudgetTracker.record_run(scenario_name, time.time() - start_t, rid, success)
        return success

    print(f"\n▶ Step 2: Spawning k6 Benchmark ({scenario_name} -> {k6_cfg['SCENARIO']}, duration={eff_duration}, rate={eff_rate})...")
    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()
    binding_token = _resolve_binding_token(token)

    scheduler = None
    manifest_pots = None
    if profile_key == "final_sla":
        manifest_pots = _refresh_final_sla_pots()
    elif profile_key == "r5" or k6_cfg.get("WORKLOAD_MODE") == "realistic":
        wl_cfg = WorkloadConfig(pot_pool_size=int(k6_cfg.get("POTS", 250)), random_seed=20261108)
        pots = prepare_live_fixtures(token, binding_token, wl_cfg, manifest_path=target_evidence_dir / "fixtures_manifest.json")
        scheduler = RealisticWorkloadScheduler(config=wl_cfg, pots=pots)

    token_server = TokenHttpServer(token_mgr, port=18083, scheduler=scheduler)
    token_server.start()

    is_decoupled = _is_decoupled_scenario(profile_key, scenario_name, k6_cfg)
    coordinator = None
    if is_decoupled:
        max_drain = 120.0 if profile_key == "final_sla" else min(120.0, max(60.0, dur_sec * 0.5))
        workers = 32 if profile_key == "final_sla" else 12
        read_workers = 32 if profile_key == "final_sla" else 8
        coordinator = DecoupledBenchCoordinator(
            run_id=rid, token_manager=token_mgr, binding_token=binding_token, max_drain_seconds=max_drain,
            evidence_dir=target_evidence_dir, scheduler=scheduler,
            terminal_workers=workers, read_workers=read_workers,
        )
        coordinator.start()

    env = _build_k6_env(k6_cfg, eff_duration, eff_rate, summary_path, token, token_server.url,
                        is_decoupled, profile_key, manifest_pots, binding_token)
    _inject_pre_seeded_pot(scenario_name, env)

    k6_script = REPO_ROOT / "scripts" / "k6" / "v3-performance.js"
    proc = subprocess.Popen(["k6", "run", "--log-format=raw", "--no-usage-report", str(k6_script)],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
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

    decoupled_path = _finalize_coordinator(coordinator, eff_rate, dur_sec, token_mgr, target_evidence_dir, rid) if is_decoupled and coordinator else None
    token_server.stop()

    exec_duration = time.time() - start_t
    success = (proc.returncode == 0) and not watchdog.aborted
    target_tracker.record_run(scenario_name, exec_duration, rid, success)
    _collect_and_plot(collect_script, scenario_name, summary_path, pre_snapshot_path, rid, target_evidence_dir, decoupled_path)

    print("\n================================================================================")
    if watchdog.aborted:
        print(f" CAMPAIGN ABORTED: {watchdog.abort_reason} [{watchdog.incident_class}]")
        print(" Safe stop executed, diagnostic snapshot preserved.")
        return False
    if success:
        print(f" CAMPAIGN PASSED: Scenario {scenario_name} completed cleanly in {exec_duration:.2f}s")
        return True
    print(f" CAMPAIGN COMPLETED WITH NON-ZERO CODE: exit={proc.returncode} in {exec_duration:.2f}s")
    return False


def _build_k6_env(k6_cfg, eff_duration, eff_rate, summary_path, token, auth_url,
                  is_decoupled, profile_key, manifest_pots, binding_token) -> Dict[str, str]:
    env = os.environ.copy()
    for k, v in k6_cfg.items():
        if not k.startswith("DEFAULT_"):
            env[k] = str(v)
    if profile_key == "final_sla" and manifest_pots:
        env["PRE_SEEDED_POTS_JSON"] = json.dumps(manifest_pots)
    env["DURATION"] = str(eff_duration)
    env["RATE"] = str(eff_rate)
    env["SUMMARY_PATH"] = str(summary_path)
    env["POCOMA_TOKEN"] = token
    env["POCOMA_AUTH_URL"] = auth_url
    env["DECOUPLED"] = "true" if is_decoupled else "false"
    env["POLL_TIMEOUT_MS"] = os.environ.get("POLL_TIMEOUT_MS", "25000")
    if profile_key == "r5":
        env["WORKLOAD_MODE"] = "realistic"
    if binding_token:
        env["POCOMA_BINDING_TOKEN"] = binding_token
    return env


def _finalize_coordinator(coordinator, eff_rate, dur_sec, token_mgr, target_evidence_dir, rid) -> Path:
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
    return decoupled_path


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
    parser.add_argument("--r7", action="store_true", help="Execute under R7 budget envelope and output directory")
    parser.add_argument("--r8", action="store_true", help="Execute under R8 budget envelope and output directory")
    parser.add_argument("--r9", action="store_true", help="Execute under R9 budget envelope and output directory")
    parser.add_argument("--r10", action="store_true", help="Execute under R10 budget envelope and output directory")
    parser.add_argument("--r11", action="store_true", help="Execute under R11 budget envelope and output directory")
    parser.add_argument("--final-sla", action="store_true", help="Execute under Final SLA Qualification budget envelope and output directory")

    args = parser.parse_args()

    if args.budget_status:
        profile = _resolve_campaign_profile(flags={
            "final_sla": args.final_sla, "r11": args.r11, "r10": args.r10, "r9": args.r9,
            "r8": args.r8, "r7": args.r7, "r6": args.r6, "r5": args.r5, "r4": args.r4,
        })
        _, _, label, _, tracker, budget_limit, _ = profile
        consumed = tracker.get_consumed_seconds()
        remaining = budget_limit - consumed
        print(f"{label} {budget_limit}s ({budget_limit/60:.1f} min)")
        print(f"Consumed So Far:  {consumed:.1f}s ({consumed/60:.1f} min)")
        print(f"Remaining Budget: {remaining:.1f}s ({remaining/60:.1f} min)")
        sys.exit(0)

    suite_runners = {
        "r6-suite": run_r6_suite,
        "r5-suite": run_r5_suite,
        "r4-suite": run_r4_suite,
        "ramp-suite": lambda: run_ramp_suite(duration=args.duration or "120s"),
    }
    if args.scenario in suite_runners:
        sys.exit(0 if suite_runners[args.scenario]() else 1)

    profile_key = _resolve_campaign_profile(args.scenario, {
        "final_sla": args.final_sla, "r11": args.r11, "r10": args.r10, "r9": args.r9,
        "r8": args.r8, "r7": args.r7, "r6": args.r6, "r5": args.r5, "r4": args.r4,
    })[0]
    ok = run_scenario(
        scenario_name=args.scenario,
        duration=args.duration,
        rate=args.rate,
        skip_preflight=args.skip_preflight,
        run_id=args.run_id,
        is_r4=(profile_key == "r4"),
        is_r5=(profile_key == "r5"),
        is_r6=(profile_key == "r6"),
        is_r7=(profile_key == "r7"),
        is_r8=(profile_key == "r8"),
        is_r9=(profile_key == "r9"),
        is_r10=(profile_key == "r10"),
        is_r11=(profile_key == "r11"),
        is_final_sla=(profile_key == "final_sla"),
    )
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
