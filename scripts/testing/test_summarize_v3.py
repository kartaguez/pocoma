"""Evidence collector failure modes, using synthetic reports rather than a Maven rerun."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("summarize_v3", Path(__file__).with_name("summarize-v3.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class EvidenceCollectorTest(unittest.TestCase):
    def report(self, extra="", attributes='tests="1" failures="0" errors="0" skipped="0"'):
        return f'<testsuite name="proof" {attributes}><testcase classname="proof" name="required">{extra}</testcase></testsuite>'.encode()

    def assess(self, reports, exit_code=0):
        return collector.assess_reports(reports, {("proof", "required")}, 100, exit_code)

    def test_complete_fresh_report_passes(self):
        self.assertEqual("PASS", self.assess([("fresh", self.report(), 101)])["verdict"])

    def test_stale_report_cannot_supply_a_missing_test(self):
        result = self.assess([("old", self.report(), 99)])
        self.assertEqual("INCONCLUSIVE", result["verdict"])
        self.assertEqual(["old"], result["excluded_stale_reports"])

    def test_skip_is_preserved_and_cannot_pass(self):
        result = self.assess([("skip", self.report('<skipped message="no Docker"/>',
                'tests="1" failures="0" errors="0" skipped="1"'), 101)])
        self.assertEqual("INCONCLUSIVE", result["verdict"])
        self.assertEqual("no Docker", result["skips"][0]["reason"])

    def test_failed_assertion_and_failed_build_are_failures(self):
        self.assertEqual("FAIL", self.assess([("fail", self.report('<failure/>',
                'tests="1" failures="1" errors="0" skipped="0"'), 101)])["verdict"])
        self.assertEqual("FAIL", self.assess([], exit_code=1)["verdict"])

    def test_empty_missing_and_malformed_reports_are_inconclusive(self):
        for reports in ([], [("invalid", b"broken XML", 101)],
                        [("other", self.report().replace(b'required', b'other'), 101)]):
            self.assertEqual("INCONCLUSIVE", self.assess(reports)["verdict"])

    def test_duplicate_evidence_and_inconsistent_counts_cannot_pass(self):
        self.assertEqual("INCONCLUSIVE", self.assess([("one", self.report(), 101), ("two", self.report(), 102)])["verdict"])
        self.assertEqual("INCONCLUSIVE", self.assess([("wrong", self.report(attributes='tests="0"'), 101)])["verdict"])

    def test_missing_environment_tool_is_recorded_without_losing_evidence(self):
        with patch.object(collector.subprocess, "run", side_effect=FileNotFoundError("missing Docker")):
            result = collector.capture(["docker", "version"], Path("."))
        self.assertEqual(127, result["exit_code"])
        self.assertIn("missing Docker", result["stderr"])


if __name__ == "__main__":
    unittest.main()
