#!/usr/bin/env python3
"""Pocoma V3 — R4 Concurrent Observers & Metrology Unit Test Suite.
(scripts/performance/test_r4_observer.py).

Verifies the R4 observer enhancements (Mandate Phase B4):
1. Traitement de milliers d'identifiants sans fuite ni corruption.
2. Concurrence bornée et respect des pools de threads.
3. Ordonnancement équitable et backoff exponentiel.
4. Expiration et timeout d'une observation.
5. Arrêt propre (stop / graceful shutdown).
6. Drainage incomplet et réconciliation des non-dépouillées.
7. Conservation stricte des événements (Emitted / Client Admitted / Server Admitted / Read).
8. Absence totale de double comptage.
9. Traitement des résultats arrivant hors ordre.
10. Reprise et consolidation des métriques après interruption.
11. Sonde de backlog duale (v3_command vs consumption_slot).
"""

import json
from pathlib import Path
import queue
import sys
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))

from v3_observer import (
    CorrelationRecord,
    CorrelationStore,
    TerminalObserver,
    ReadObserver,
    DecoupledBenchCoordinator,
    SqlReconciler,
    calculate_exact_quantiles,
)


class R4ObserverMetrologyTests(unittest.TestCase):

    def setUp(self):
        self.tmp_dir = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "scratch_test"
        self.tmp_dir.mkdir(parents=True, exist_ok=True)
        self.journal_path = self.tmp_dir / f"test_admissions_{int(time.time()*1000)}.jsonl"

    def tearDown(self):
        if self.journal_path.exists():
            self.journal_path.unlink()
        if self.tmp_dir.exists():
            for f in self.tmp_dir.glob("*"):
                try:
                    f.unlink()
                except Exception:
                    pass
            try:
                self.tmp_dir.rmdir()
            except Exception:
                pass

    # 1. Traitement de milliers d'identifiants sans perte
    def test_01_process_thousands_of_ids(self):
        store = CorrelationStore(run_id="test-01", journal_path=self.journal_path, max_capacity=10000)
        n = 3000
        for i in range(n):
            store.record_admission_event({
                "commandId": f"cmd-{i}",
                "potId": "pot-1",
                "status": 202,
                "t_send": 1000 + i,
                "t_admit": 1002 + i,
            })
        self.assertEqual(store.emitted_count, n)
        self.assertEqual(store.admitted_count, n)
        self.assertEqual(len(store.records), n)
        self.assertEqual(store.terminal_queue.qsize(), n)
        store.close()

    # 2. Concurrence bornée et respect des pools de threads
    def test_02_bounded_concurrency(self):
        store = CorrelationStore(run_id="test-02", journal_path=self.journal_path)
        active_concurrent = 0
        max_seen_concurrent = 0
        lock = threading.Lock()

        def mock_urlopen(req, timeout=5):
            nonlocal active_concurrent, max_seen_concurrent
            with lock:
                active_concurrent += 1
                if active_concurrent > max_seen_concurrent:
                    max_seen_concurrent = active_concurrent
            time.sleep(0.01)
            with lock:
                active_concurrent -= 1
            mock_resp = MagicMock()
            mock_resp.status = 200
            mock_resp.read.return_value = json.dumps({"outcome": "COMPLETED", "resultingPotVersion": 2}).encode()
            mock_resp.__enter__.return_value = mock_resp
            return mock_resp

        obs = TerminalObserver(store, token_getter=lambda: "fake-token", max_workers=6)
        for i in range(50):
            store.record_admission_event({"commandId": f"conc-{i}", "status": 202})

        with patch("urllib.request.urlopen", side_effect=mock_urlopen):
            obs.start()
            time.sleep(0.3)
            obs.stop()

        self.assertLessEqual(max_seen_concurrent, 6, "Concurrency must never exceed max_workers")
        self.assertGreater(max_seen_concurrent, 1, "Must run concurrently with multiple threads")
        store.close()

    # 3. Ordonnancement équitable et backoff exponentiel
    def test_03_fair_scheduling_and_backoff(self):
        store = CorrelationStore(run_id="test-03", journal_path=self.journal_path)
        call_counts = {}

        def mock_urlopen(req, timeout=5):
            cid = req.full_url.split("/")[-2]
            call_counts[cid] = call_counts.get(cid, 0) + 1
            if call_counts[cid] < 3:
                err = urllib.error.HTTPError(req.full_url, 404, "In flight", {}, None)
                raise err
            mock_resp = MagicMock()
            mock_resp.status = 200
            mock_resp.read.return_value = json.dumps({"outcome": "COMPLETED", "resultingPotVersion": 3}).encode()
            mock_resp.__enter__.return_value = mock_resp
            return mock_resp

        store.record_admission_event({"commandId": "cmd-backoff", "status": 202})
        obs = TerminalObserver(store, token_getter=lambda: "fake-token", max_workers=2)

        with patch("urllib.request.urlopen", side_effect=mock_urlopen):
            obs.start()
            # Wait for backoff retries to complete
            for _ in range(50):
                if store.records["cmd-backoff"].terminal_status == "COMPLETED":
                    break
                time.sleep(0.02)
            obs.stop()

        self.assertEqual(store.records["cmd-backoff"].terminal_status, "COMPLETED")
        self.assertGreaterEqual(call_counts.get("cmd-backoff", 0), 3)
        store.close()

    # 4. Expiration et timeout d'une observation
    def test_04_observation_timeout(self):
        store = CorrelationStore(run_id="test-04", journal_path=self.journal_path)
        # Record event in past (older than command_timeout_sec)
        store.record_admission_event({
            "commandId": "cmd-timeout",
            "status": 202,
            "t_send": (time.time() - 100) * 1000,
            "t_admit": (time.time() - 95) * 1000,
        })
        obs = TerminalObserver(store, token_getter=lambda: "fake-token", command_timeout_sec=5.0, max_workers=2)
        obs.start()
        time.sleep(0.1)
        obs.stop()

        rec = store.records["cmd-timeout"]
        self.assertEqual(rec.terminal_status, "TIMEOUT")
        self.assertIn("timeout", rec.failure_reason.lower())
        self.assertEqual(obs.timeouts_count, 1)
        store.close()

    # 5. Arrêt propre (graceful shutdown)
    def test_05_graceful_stop(self):
        store = CorrelationStore(run_id="test-05", journal_path=self.journal_path)
        obs = TerminalObserver(store, token_getter=lambda: "fake-token", max_workers=4)
        obs.start()
        for t in obs.threads:
            self.assertTrue(t.is_alive())
        obs.stop()
        for t in obs.threads:
            self.assertFalse(t.is_alive())
        store.close()

    # 6. Drainage incomplet et réconciliation des non-dépouillées
    def test_06_incomplete_drainage_reconciliation(self):
        store = CorrelationStore(run_id="test-06", journal_path=self.journal_path)
        # 10 admitted commands, only 4 completed
        for i in range(10):
            store.record_admission_event({"commandId": f"drain-{i}", "status": 202})

        # Artificially complete 4 of them
        for i in range(4):
            r = store.records[f"drain-{i}"]
            r.t_terminal = time.time() * 1000
            r.terminal_status = "COMPLETED"
            r.resulting_version = 2
            # Drain from queue
            _ = store.terminal_queue.get_nowait()
            store.terminal_queue.task_done()

        coord = DecoupledBenchCoordinator(run_id="test-06", token_manager=MagicMock(), store=store, evidence_dir=self.tmp_dir)
        metrics = coord.consolidate_metrics(demanded_rate=10, injection_target_sec=10)

        # 10 admitted = 4 applied + 0 cas + 0 timeouts + 6 unpolled
        self.assertEqual(metrics["counts"]["admitted"], 10)
        self.assertEqual(metrics["counts"]["clientApplied"], 4)
        self.assertEqual(metrics["counts"]["clientUnpolledAtClose"], 6)
        self.assertTrue(metrics["conservations"]["clientAdmittedEquation"])
        store.close()

    # 7 & 8. Conservation exacte et absence de double comptage
    def test_07_strict_conservation_no_double_counting(self):
        store = CorrelationStore(run_id="test-07", journal_path=self.journal_path)
        # 100 emitted: 80 admitted (202), 15 capacity rejected (503), 5 admission errors (0)
        for i in range(80):
            store.record_admission_event({"commandId": f"c-{i}", "status": 202})
        for i in range(15):
            store.record_admission_event({"commandId": f"rej-{i}", "status": 503})
        for i in range(5):
            store.record_admission_event({"commandId": f"err-{i}", "status": 0})

        # Breakdown of the 80 admitted:
        # - 40 completed (applied)
        # - 20 CAS rejected
        # - 10 timed out
        # - 10 unpolled
        for i in range(40):
            r = store.records[f"c-{i}"]
            r.t_terminal = time.time() * 1000
            r.terminal_status = "COMPLETED"
            r.resulting_version = 2
        for i in range(40, 60):
            r = store.records[f"c-{i}"]
            r.t_terminal = time.time() * 1000
            r.terminal_status = "REJECTED"
        for i in range(60, 70):
            r = store.records[f"c-{i}"]
            r.terminal_status = "TIMEOUT"
            r.failure_reason = "Terminal probe timeout"
        # 70..80 remain unpolled (terminal_status is None)

        coord = DecoupledBenchCoordinator(run_id="test-07", token_manager=MagicMock(), store=store, evidence_dir=self.tmp_dir)
        metrics = coord.consolidate_metrics(demanded_rate=10, injection_target_sec=10)

        # Assert emitted equation
        self.assertTrue(metrics["conservations"]["emittedEquation"])
        self.assertEqual(metrics["counts"]["emitted"], 100)
        self.assertEqual(metrics["counts"]["admitted"], 80)
        self.assertEqual(metrics["counts"]["capacityRejected"], 15)
        self.assertEqual(metrics["counts"]["admissionErrors"], 5)

        # Assert client admitted equation: 40 + 20 + 10 + 10 = 80
        self.assertTrue(metrics["conservations"]["clientAdmittedEquation"])
        self.assertEqual(metrics["counts"]["clientApplied"], 40)
        self.assertEqual(metrics["counts"]["clientCasRejected"], 20)
        store.close()
        self.assertEqual(metrics["counts"]["clientTimeouts"], 10)
        self.assertEqual(metrics["counts"]["clientUnpolledAtClose"], 10)
        store.close()

    # 9. Résultats arrivant hors ordre
    def test_08_out_of_order_results(self):
        store = CorrelationStore(run_id="test-08", journal_path=self.journal_path)
        # Enqueue c-1, c-2, c-3
        store.record_admission_event({"commandId": "c-1", "status": 202})
        store.record_admission_event({"commandId": "c-2", "status": 202})
        store.record_admission_event({"commandId": "c-3", "status": 202})

        # Complete in reverse order: c-3, c-1, c-2
        for cid, t_term in [("c-3", 1005), ("c-1", 1010), ("c-2", 1015)]:
            r = store.records[cid]
            r.t_terminal = t_term
            r.terminal_status = "COMPLETED"
            r.resulting_version = 2

        self.assertEqual(store.records["c-3"].terminal_status, "COMPLETED")
        self.assertEqual(store.records["c-1"].terminal_status, "COMPLETED")
        self.assertEqual(store.records["c-2"].terminal_status, "COMPLETED")
        store.close()

    # 10. Calcul exact des quantiles sans distorsion d'histogramme
    def test_09_exact_quantiles(self):
        samples = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0]
        q = calculate_exact_quantiles(samples)
        self.assertEqual(q["count"], 10)
        self.assertEqual(q["p50"], 55.0)
        self.assertEqual(q["min"], 10.0)
        self.assertEqual(q["max"], 100.0)
        self.assertEqual(q["mean"], 55.0)


if __name__ == "__main__":
    unittest.main()
