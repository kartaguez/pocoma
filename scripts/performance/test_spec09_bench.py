"""Discriminating tests of ownership, no false PASS, SQL and collection failures."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import spec09_bench as bench


class BenchControls(unittest.TestCase):
    def row(self, request="00000000-0000-0000-0000-000000000001"):
        return dict(status="OBSERVED", requestId=request, potId=request, version=3,
                    admissionMs=10, completeLowerMs=70, completeUpperMs=80, balanceUpperMs=90)

    def snapshot(self):
        db = dict(maintenance=dict(state="READY"), checkpoints=[dict(consumption_id="id", partition_id=0, position=3)],
                  slots=[], transactions=dict(deadlocks=0))
        return dict(databases=dict(read=deepcopy(db), write=dict(**db, pendingSources=0,
                    positions=[dict(family="POT", first=1, last=2, n=2)], counters=dict(POT=2))))

    def test_no_local_measurement_certifies_spec09(self):
        result = bench.summarize([self.row()], 1, 1)
        self.assertEqual("PASS", result["safety"])
        self.assertEqual("INCONCLUSIVE", result["performance"])
        self.assertEqual("NOT_RUN", result["contractual"])

    def test_timeout_missing_and_dropped_samples_cannot_pass(self):
        for rows in ([], [dict(status="CENSORED")], [dict(status="DROPPED_OFFER")], [self.row()]):
            with self.subTest(rows=rows):
                result = bench.summarize(rows, 2, 2)
                self.assertEqual("INCONCLUSIVE", result["safety"])
        result = bench.summarize([dict(status="CENSORED")], 1, 1)
        self.assertIsNone(result["latenciesMs"]["completeUpperMs"]["p95"])
        self.assertEqual(1, result["retainedNonSuccess"])

    def test_fast_performance_never_masks_invariant_failure_or_duplicate(self):
        self.assertEqual("FAIL", bench.summarize([self.row(), dict(status="INVARIANT_VIOLATION")], 2, 2)["safety"])
        self.assertEqual("FAIL", bench.summarize([self.row(), self.row()], 2, 2)["safety"])

    def test_following_phase_timeout_cannot_keep_preceding_pass(self):
        receipt = dict(status="PASS", safety="PASS", summary=dict(safety="PASS"))
        bench.record_failure(receipt, TimeoutError("E3 incomplete"))
        self.assertEqual("INCONCLUSIVE", receipt["status"])
        self.assertEqual("PASS", receipt["summary"]["safety"])
        bench.record_failure(receipt, AssertionError("double effect"))
        self.assertEqual(("FAIL", "FAIL"), (receipt["status"], receipt["safety"]))

    def test_ownership_refuses_existing_containers_before_sql(self):
        with patch.object(bench, "command", return_value=json.dumps([dict(Config=dict(Labels={}))])) as cmd:
            with self.assertRaises(ValueError):
                bench.sql("existing", "pocoma_read", "SELECT 1")
            self.assertEqual(1, cmd.call_count)

    def test_sql_is_server_enforced_read_only_bounded_and_not_exported(self):
        with patch.object(bench, "owned"), patch.object(bench, "command", return_value='{"n":1}') as cmd:
            self.assertEqual(dict(n=1), bench.sql("owned", "pocoma_read", "SELECT json_build_object('n',1)"))
            query = cmd.call_args.kwargs["input"]
            self.assertTrue(query.startswith("BEGIN READ ONLY;"))
            self.assertIn("statement_timeout='3s'", query)
            self.assertNotIn(query, cmd.call_args.args[0])

    def test_missing_metrics_and_failed_collector_propagate(self):
        with patch.object(bench, "fetch_metrics", side_effect=ValueError("metric missing")), \
                patch.object(bench, "database_snapshot", return_value={}), tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                bench.snapshot("owned", {"web": "http://localhost"}, Path(directory), 0)

    def test_checkpoint_regression_missing_partition_and_deadlock_rejected(self):
        for mutation in (lambda d: d["read"]["checkpoints"][0].update(position=2),
                         lambda d: d["read"].update(checkpoints=[]),
                         lambda d: d["write"]["transactions"].update(deadlocks=1)):
            before, after = self.snapshot(), self.snapshot()
            mutation(after["databases"])
            with self.assertRaises(AssertionError):
                bench.validate_progress(before, after)

    def test_incomplete_slots_and_sources_are_never_convergence(self):
        for authority, key, value in (("read", "slots", [dict(state="QUARANTINED")]),
                                      ("write", "pendingSources", 1),
                                      ("read", "maintenance", dict(state="RUNNING"))):
            before, after = self.snapshot(), self.snapshot()
            after["databases"][authority][key] = value
            with self.assertRaises(ValueError):
                bench.validate_progress(before, after)

    def test_dense_counter_oracle_rejects_hole_or_counter_drift(self):
        for key, value in (("n", 1), ("last", 3)):
            before, after = self.snapshot(), self.snapshot()
            after["databases"]["write"]["positions"][0][key] = value
            with self.assertRaises(AssertionError):
                bench.validate_progress(before, after)
        bench.validate_progress(self.snapshot(), self.snapshot())

    def test_matching_totals_with_foreign_request_never_reconcile(self):
        foreign = dict(request_id="foreign", pot_id="foreign", resulting_pot_version=3)
        with patch.object(bench, "sql", return_value=[foreign]):
            with self.assertRaises(AssertionError):
                bench.reconcile("owned", [self.row()])
        with patch.object(bench, "sql") as sql:
            with self.assertRaises(ValueError):
                bench.reconcile("owned", [self.row("'; DELETE FROM slots; --")])
            sql.assert_not_called()


if __name__ == "__main__":
    unittest.main()
