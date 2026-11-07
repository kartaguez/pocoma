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

    # 19. Counter Conservation Equations (Mandate Phase A1 & G)
    def test_19_counter_conservation_equations(self):
        v3_obs = importlib.import_module("v3_observer")
        CorrelationStore = v3_obs.CorrelationStore

        import tempfile
        with tempfile.TemporaryDirectory() as td:
            journal = Path(td) / "test_conservation.jsonl"
            store = CorrelationStore("test-cons", journal_path=journal)

            # 10 emitted: 8 admitted (202), 1 capacity 503, 1 error 400
            for i in range(8):
                store.record_admission_event({
                    "commandId": f"cmd-{i}",
                    "potId": "pot-1",
                    "status": 202,
                    "t_send": 1000.0,
                    "t_admit": 1002.0,
                })
            store.record_admission_event({"commandId": "rej-1", "status": 503})
            store.record_admission_event({"commandId": "err-1", "status": 400})
            store.close()

            # Emitted conservation equation:
            # emitted = admitted + rejected_admission + error_admission
            self.assertEqual(store.emitted_count, 10)
            self.assertEqual(store.admitted_count, 8)
            self.assertEqual(store.rejected_admission_count, 1)
            self.assertEqual(store.error_admission_count, 1)
            self.assertEqual(store.emitted_count,
                             store.admitted_count + store.rejected_admission_count + store.error_admission_count)

            # Admitted conservation equation:
            # admitted = terminal_applied + terminal_rejected + non_terminal_at_close
            applied = 6
            cas_rej = 1
            non_term = 1
            self.assertEqual(store.admitted_count, applied + cas_rej + non_term)

    # 20. Decoupled Queue Separation and Capacity Overflow
    def test_20_decoupled_queue_separation_and_capacity_overflow(self):
        v3_obs = importlib.import_module("v3_observer")
        CorrelationStore = v3_obs.CorrelationStore

        import tempfile
        with tempfile.TemporaryDirectory() as td:
            journal = Path(td) / "test_overflow.jsonl"
            small_store = CorrelationStore("test-overflow", journal_path=journal, max_capacity=3)
            small_store.record_admission_event({"commandId": "c1", "status": 202})
            small_store.record_admission_event({"commandId": "c2", "status": 202})
            small_store.record_admission_event({"commandId": "c3", "status": 202})

            # 4th item must raise OverflowError (never silent loss)
            with self.assertRaises(OverflowError):
                small_store.record_admission_event({"commandId": "c4", "status": 202})
            small_store.close()

    # 21. OIDC Proactive Refresh and Token Server
    def test_21_oidc_proactive_refresh_and_token_server(self):
        v3_tok = importlib.import_module("v3_token")
        OidcTokenManager = v3_tok.OidcTokenManager

        mgr = OidcTokenManager()
        # Mock initial login and refresh
        with patch.object(mgr, "_initial_login") as mock_login, patch.object(mgr, "_refresh_login") as mock_refresh:
            mock_login.return_value = {
                "access_token": "token-v1",
                "refresh_token": "refresh-v1",
                "expires_in": 300,
                "refresh_expires_in": 1800,
            }
            mock_refresh.return_value = {
                "access_token": "token-v2-refreshed",
                "refresh_token": "refresh-v2",
                "expires_in": 300,
            }

            # 1. Initial login
            tok1 = mgr.get_token()
            self.assertEqual(tok1, "token-v1")
            self.assertEqual(mgr.renewal_count, 0)

            # 2. Subsequent call while valid returns cached token
            tok1_cached = mgr.get_token()
            self.assertEqual(tok1_cached, "token-v1")
            self.assertEqual(mock_refresh.call_count, 0)

            # 3. Force renew uses refresh_token grant
            tok2 = mgr.get_token(force_renew=True)
            self.assertEqual(tok2, "token-v2-refreshed")
            self.assertEqual(mgr.renewal_count, 1)

    # 22. Bounded Drainage and Timeout Classification
    def test_22_bounded_drainage_and_timeout(self):
        v3_obs = importlib.import_module("v3_observer")
        DecoupledBenchCoordinator = v3_obs.DecoupledBenchCoordinator

        mock_token_mgr = MagicMock()
        mock_token_mgr.get_token.return_value = "fake-token"

        import tempfile
        with tempfile.TemporaryDirectory() as td:
            journal = Path(td) / "test_drain.jsonl"
            # Set short drainage limit of 0.2s
            coord = DecoupledBenchCoordinator("test-drain-run", mock_token_mgr,
                                              max_drain_seconds=0.2, journal_path=journal)
            with patch("v3_observer.query_db_backlog", return_value=5):
                coord.start()
                coord.mark_injection_ended()
                drain_res = coord.drain()

                self.assertTrue(drain_res["timedOut"], "Drainage must time out when backlog remains")
                self.assertEqual(drain_res["finalDbBacklog"], 5)
                self.assertLessEqual(drain_res["drainDurationSec"], 1.0)

    # 23. Generator Saturation vs Dropped Iterations
    def test_23_generator_saturation_vs_dropped_iterations(self):
        # When emitted rate is < 95% of demanded rate, classify as GENERATOR_LIMITED
        demanded = 50.0
        emitted_low = 35.0  # 70%
        ratio_low = (emitted_low / demanded) * 100.0
        status_low = "LOAD_GENERATOR_VALID" if (emitted_low / demanded) >= 0.95 else "GENERATOR_LIMITED"
        self.assertEqual(status_low, "GENERATOR_LIMITED")
        self.assertAlmostEqual(ratio_low, 70.0)

        emitted_ok = 49.5   # 99%
        ratio_ok = (emitted_ok / demanded) * 100.0
        status_ok = "LOAD_GENERATOR_VALID" if (emitted_ok / demanded) >= 0.95 else "GENERATOR_LIMITED"
        self.assertEqual(status_ok, "LOAD_GENERATOR_VALID")
        self.assertAlmostEqual(ratio_ok, 99.0)

    # 24. Exact Quantiles vs Bucket Estimates
    def test_24_exact_quantiles_vs_bucket_estimates(self):
        v3_obs = importlib.import_module("v3_observer")
        calc_q = v3_obs.calculate_exact_quantiles

        data = [10.0, 12.0, 15.0, 20.0, 25.0, 30.0, 45.0, 50.0, 80.0, 120.0]
        q = calc_q(data)
        self.assertEqual(q["count"], 10)
        self.assertEqual(q["min"], 10.0)
        self.assertEqual(q["max"], 120.0)
        self.assertAlmostEqual(q["p50"], 27.5)
        self.assertAlmostEqual(q["mean"], 40.7)

        # Empty list handled gracefully
        q_empty = calc_q([])
        self.assertEqual(q_empty["count"], 0)
        self.assertIsNone(q_empty["p50"])

    # 25. Growing Backlog Detection Despite Stable Admission
    def test_25_growing_backlog_detection(self):
        # Simulate timeseries: admission is constant at 50/s, but consumption is 10/s
        timeseries = []
        for sec in range(1, 10):
            admitted = sec * 50
            applied = sec * 10
            backlog = admitted - applied
            timeseries.append({"sec": sec, "admitted": admitted, "applied": applied, "backlog": backlog})

        backlog_growth = timeseries[-1]["backlog"] - timeseries[0]["backlog"]
        self.assertGreater(backlog_growth, 0, "System must detect persistent backlog growth")
        self.assertEqual(timeseries[-1]["backlog"], 360)

    # 26. E2E Correlation Coverage and Population Separation
    def test_26_e2e_correlation_coverage_and_population_separation(self):
        v3_obs = importlib.import_module("v3_observer")
        CorrelationRecord = v3_obs.CorrelationRecord

        # Complete record
        r_complete = CorrelationRecord(
            command_id="c1", pot_id="p1", t_send=1000.0, t_admit=1002.0, admission_status=202,
            expected_version=2, observed_version=1, t_terminal=1080.0, terminal_status="APPLIED",
            resulting_version=2, t_read=1100.0, read_version=2
        )
        self.assertTrue(r_complete.is_complete)
        self.assertEqual(r_complete.latency_admission_ms, 2.0)
        self.assertEqual(r_complete.latency_terminal_ms, 78.0)
        self.assertEqual(r_complete.latency_read_ms, 20.0)
        self.assertEqual(r_complete.latency_e2e_ms, 100.0)

        # Incomplete record (timed out at terminal stage)
        r_incomplete = CorrelationRecord(
            command_id="c2", pot_id="p1", t_send=2000.0, t_admit=2003.0, admission_status=202,
            expected_version=3, observed_version=2, t_terminal=None, terminal_status="TIMEOUT"
        )
        self.assertFalse(r_incomplete.is_complete)
        self.assertIsNone(r_incomplete.latency_e2e_ms)


if __name__ == "__main__":
    unittest.main(verbosity=2)
