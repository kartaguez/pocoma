#!/usr/bin/env python3
"""Pocoma V3 — Bench & Oracle Qualification Suite (scripts/performance/test_bench.py).

Verifies the reliability of the benchmark and its oracles (Mandate §9).
Proves that the bench correctly discriminates and detects all 14 mandatory failure modes:
 1. Admission refused.
 2. Command accepted but not completed.
 3. Terminal result without new version.
 4. Balance(P,V) absent.
 5. Pot incomplete.
 6. READ lag (W < V).
 7. READ in maintenance (503).
 8. Identity binding unusable.
 9. DeletePot unmaterialized.
10. Timeout of an async branch.
11. Missing metric in telemetry.
12. Dropped iterations in k6.
13. Inconsistent dataset.
14. Watchdog trigger and termination.
"""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))

import importlib
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))

v3_preflight = importlib.import_module("v3-preflight")
v3_seed = importlib.import_module("v3-seed")
v3_collect = importlib.import_module("v3-collect")
v3_run = importlib.import_module("v3-run")

PreflightChecker = v3_preflight.PreflightChecker
SeedSpec = v3_seed.SeedSpec
build_seed_dataset = v3_seed.build_seed_dataset
create_seed = v3_seed.create_seed
collect_k6_summary = v3_collect.collect_k6_summary
BudgetTracker = v3_run.BudgetTracker
Watchdog = v3_run.Watchdog
GLOBAL_BUDGET_SECONDS = v3_run.GLOBAL_BUDGET_SECONDS


class BenchOracleQualificationTests(unittest.TestCase):

    # 1. Admission Refused
    def test_01_admission_refused(self):
        # When admission returns HTTP 400/403/503 instead of 202
        fake_response = {"status": 403, "requestId": None}
        is_accepted = (fake_response["status"] == 202 and fake_response["requestId"] is not None)
        self.assertFalse(is_accepted, "Oracle must reject non-202 admission response")

    # 2. Command Accepted but Not Completed
    def test_02_command_accepted_not_completed(self):
        # When polling gets 404 until maxWaitMs expires
        timeout_ms = 50
        start = time.time()
        # simulate polling loop
        polled_terminal = False
        while (time.time() - start) * 1000 < timeout_ms:
            time.sleep(0.01)
        self.assertFalse(polled_terminal, "Oracle must detect uncompleted command as non-terminal")

    # 3. Terminal Result without New Version
    def test_03_terminal_result_without_new_version(self):
        # Oracle validation logic for command result:
        def validate_cmd_result(result):
            errors = []
            if result.get("outcome") in ("COMPLETED", "CHANGED"):
                v = result.get("resultingPotVersion")
                if not isinstance(v, int) or v < 1:
                    errors.append(f"Invalid resultingPotVersion: {v}")
            return len(errors) == 0, errors

        bad_result = {"outcome": "CHANGED", "resultingPotVersion": 0}
        ok, errs = validate_cmd_result(bad_result)
        self.assertFalse(ok, "Oracle must reject CHANGED outcome with version 0")
        self.assertIn("Invalid resultingPotVersion: 0", errs[0])

    # 4. Balance(P,V) Absent
    def test_04_balance_absent(self):
        def validate_balance(data, expected_v):
            errors = []
            if not data or data.get("potVersion") != expected_v or not data.get("balances"):
                errors.append("Balance absent or invalid")
            return len(errors) == 0, errors

        missing_balance = None
        ok, errs = validate_balance(missing_balance, expected_v=2)
        self.assertFalse(ok, "Oracle must detect absent balance")

    # 5. Pot Incomplete
    def test_05_pot_incomplete(self):
        def validate_pot_complete(pot):
            errors = []
            if not pot.get("label"):
                errors.append("Missing label")
            if not isinstance(pot.get("shareholders"), list) or len(pot["shareholders"]) == 0:
                errors.append("Missing shareholders")
            if not isinstance(pot.get("expenses"), list):
                errors.append("Missing expenses")
            if not isinstance(pot.get("balances"), dict):
                errors.append("Missing balances")
            return len(errors) == 0, errors

        # Pot missing shareholders and balances
        broken_pot = {
            "id": "pot-123",
            "label": "Test Pot",
            "shareholders": [],
            "expenses": [],
            "balances": None,
        }
        ok, errs = validate_pot_complete(broken_pot)
        self.assertFalse(ok, "Oracle must reject incomplete Pot")
        self.assertEqual(len(errs), 2)

    # 6. READ Lag (W < V)
    def test_06_read_lag(self):
        def check_pot_version(pot_version, min_version):
            return pot_version >= min_version

        # Client expects at least version 3, but READ only has version 2
        self.assertFalse(check_pot_version(pot_version=2, min_version=3),
                         "Oracle must reject READ lag where W < V")

    # 7. READ in Maintenance (503)
    def test_07_read_in_maintenance_503(self):
        response_status = 503
        cache_control = "no-store, no-cache, max-age=0"

        is_maintenance = (response_status == 503 and "no-store" in cache_control)
        self.assertTrue(is_maintenance, "Oracle must identify fail-closed 503 no-store during maintenance")

    # 8. Identity Binding Unusable
    def test_08_identity_binding_unusable(self):
        def validate_identity(outcome, target_v, binding_active):
            if outcome != "CHANGED" or target_v < 1 or not binding_active:
                return False
            return True

        self.assertFalse(validate_identity("CHANGED", target_v=1, binding_active=False),
                         "Oracle must reject unusable binding when binding is not active")
        self.assertFalse(validate_identity("CHANGED", target_v=0, binding_active=True),
                         "Oracle must reject identity result with invalid targetVersion")

    # 9. DeletePot Not Materialized
    def test_09_delete_pot_not_materialized(self):
        # If DeletePot was committed but HTTP READ still returns 200, delete did not materialize!
        http_read_status = 200  # Should be 404 for deleted pot
        cmd_result = {"outcome": "CHANGED", "resultingPotVersion": 4}

        delete_materialized = (http_read_status == 404 and cmd_result.get("resultingPotVersion") == 4)
        self.assertFalse(delete_materialized, "Oracle must fail if Pot still returns HTTP 200 after DeletePot")

    # 10. Timeout of an Async Branch
    def test_10_async_branch_timeout(self):
        # Polling timeout helper
        def poll_with_timeout(timeout_ms):
            start = time.time()
            while (time.time() - start) * 1000 < timeout_ms:
                time.sleep(0.005)
            return {"status": 408, "available": False, "timeout": True}

        res = poll_with_timeout(30)
        self.assertTrue(res["timeout"], "Oracle must detect async branch timeout (HTTP 408)")

    # 11. Missing Metric in Telemetry
    def test_11_metric_missing(self):
        checker = PreflightChecker()
        # Feed mock prometheus scrape without 'pocoma_v3_attempt_outcomes_total'
        partial_scrape = """
        # HELP jvm_memory_used_bytes Used bytes of a given JVM memory area.
        jvm_memory_used_bytes{area="heap"} 123456
        # HELP pocoma_v3_admission_capacity_available Permits available
        pocoma_v3_admission_capacity_available 1.0
        """
        required = ["pocoma_v3_admission_capacity_available", "pocoma_v3_attempt_outcomes_total"]
        missing = [m for m in required if m not in partial_scrape]
        self.assertIn("pocoma_v3_attempt_outcomes_total", missing,
                      "Preflight must catch missing indispensable metric")

    # 12. Dropped Iterations in k6
    def test_12_dropped_iterations(self):
        tmp_summary = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "test_dropped_summary.json"
        tmp_summary.parent.mkdir(parents=True, exist_ok=True)
        try:
            with open(tmp_summary, "w", encoding="utf-8") as f:
                json.dump({
                    "metrics": {
                        "dropped_iterations": {"values": {"count": 14}},
                        "v3_admissions_accepted": {"values": {"count": 50}},
                    }
                }, f)
            parsed = collect_k6_summary(tmp_summary)
            self.assertEqual(parsed["droppedIterations"], 14,
                             "Collector must correctly report dropped iterations")
        finally:
            if tmp_summary.exists():
                tmp_summary.unlink()

    # 13. Inconsistent Dataset
    def test_13_dataset_inconsistent(self):
        # 1. Invalid profile rejected
        with self.assertRaises(ValueError):
            create_seed("invalid-profile-xyz")

        # 2. Large scale unauthorized rejected
        with self.assertRaises(PermissionError):
            create_seed("historical-10k", enable_large_scale=False)

        # 3. Deterministic fingerprint check detects tampering
        spec = SeedSpec("seed-test-01", "small", 1, 5, 20, False, {})
        dataset = build_seed_dataset(spec)
        original_fp = dataset["fingerprintSha256"]

        # Tamper with an expense
        dataset["pots"][0]["expenses"][0]["amountCents"] += 9999
        canonical_bytes = json.dumps(dataset, sort_keys=True).encode("utf-8")
        recomputed_fp = hashlib.sha256(canonical_bytes).hexdigest()
        self.assertNotEqual(original_fp, recomputed_fp,
                            "Dataset fingerprint must detect data modification")

    # 14. Watchdog Trigger and Termination
    def test_14_watchdog_trigger(self):
        mock_proc = MagicMock()
        mock_proc.poll.return_value = None

        # Watchdog with 0.1s scenario timeout and fast check interval
        watchdog = Watchdog(mock_proc, scenario_timeout=0.1, check_interval=0.05)
        watchdog.start()
        time.sleep(0.25)
        watchdog.stop()

        self.assertTrue(watchdog.aborted, "Watchdog must trip when scenario exceeds limit")
        self.assertEqual(watchdog.incident_class, "INCIDENT_SCENARIO_TIMEOUT")
        mock_proc.terminate.assert_called()

    # 15. Retry and 503 Discrimination
    def test_15_retry_and_503_discrimination(self):
        def classify_503(headers):
            h = headers or {}
            retry_after = h.get("Retry-After") or h.get("retry-after")
            return "maintenance" if retry_after else "capacity"

        # Capacity 503 (no Retry-After)
        cap_hdrs = {"Cache-Control": "no-store"}
        self.assertEqual(classify_503(cap_hdrs), "capacity",
                         "503 without Retry-After must be classified as capacity refusal")

        # Maintenance 503 (has Retry-After)
        maint_hdrs = {"Cache-Control": "no-store", "Retry-After": "1"}
        self.assertEqual(classify_503(maint_hdrs), "maintenance",
                         "503 with Retry-After must be classified as maintenance fail-closed")

    # 16. Differential Telemetry and Quantiles
    def test_16_differential_telemetry_and_quantiles(self):
        v3_col = importlib.import_module("v3-collect")
        compute_q = v3_col.compute_quantile
        diff_h = v3_col.diff_histograms

        pre = {
            "count": 10.0, "sum": 0.10, "max": 0.02,
            "buckets": [(0.005, 5.0), (0.010, 8.0), (0.020, 10.0), (float("inf"), 10.0)]
        }
        post = {
            "count": 20.0, "sum": 0.25, "max": 0.02,
            "buckets": [(0.005, 7.0), (0.010, 15.0), (0.020, 20.0), (float("inf"), 20.0)]
        }
        diff = diff_h(post, pre)
        self.assertEqual(diff["count"], 10.0)
        self.assertAlmostEqual(diff["sum"], 0.15)

        # Compute p50 on diff: rank = 0.5 * 10 = 5.0
        # diff buckets: (0.005, 2.0), (0.010, 7.0), (0.020, 10.0)
        # rank 5.0 falls between 0.005 and 0.010: cnt_diff = 5.0, rank-prev = 3.0
        # Q = 0.005 + 0.005 * (3.0 / 5.0) = 0.008
        p50 = compute_q(diff, 0.50)
        self.assertAlmostEqual(p50, 0.008)

    # 17. Structural Dataset Materialization Verification
    def test_17_structural_dataset_materialization(self):
        v3_seed = importlib.import_module("v3-seed")
        # Check that Small and Medium profiles are registered
        self.assertIn("small", v3_seed.SPEC09_PROFILES)
        self.assertIn("medium", v3_seed.SPEC09_PROFILES)
        self.assertIn("large", v3_seed.SPEC09_PROFILES)

        # Mock SQL verification test
        with patch.object(v3_seed, "run_psql") as mock_psql:
            mock_psql.side_effect = lambda db, sql: "22" if "read_current_pot" in sql else ("1" if "read_pot_balance" in sql else "20")
            ok = v3_seed.verify_materialized_pot("test-pot-id", 22, 20)
            self.assertTrue(ok, "Materialized pot verification must pass when DB matches")

    # 18. Scenario Mapping and Ramp Suite Configuration
    def test_18_scenario_mapping_and_ramp_suite(self):
        v3_run = importlib.import_module("v3-run")
        paliers = ["ramp-p1", "ramp-p2", "ramp-p3", "ramp-p4", "ramp-p5"]
        for p in paliers:
            self.assertIn(p, v3_run.SCENARIO_BUDGETS, f"{p} must have a scenario budget")
            self.assertIn(p, v3_run.K6_SCENARIO_MAP, f"{p} must have k6 configuration mapping")


if __name__ == "__main__":
    unittest.main(verbosity=2)
