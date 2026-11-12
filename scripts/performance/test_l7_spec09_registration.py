"""Offline checks for the F01-E observable and its p95 admission bounds."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

SCRIPT = Path(__file__).with_name("run-l7-spec09-registration.py")
SPEC = importlib.util.spec_from_file_location("l7_spec09_registration", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
REQUEST = str(uuid.UUID(int=42))


class RegistrationContract(unittest.TestCase):
    def observe(self, responses):
        ticks = 0
        seen = []
        responses = iter(responses)

        def request(base, token, path, **kwargs):
            nonlocal ticks
            status, payload, maintenance, elapsed_ms = next(responses)
            ticks += elapsed_ms * 1_000_000
            seen.append((base, path, kwargs))
            return status, payload, maintenance, ticks

        def wait(seconds):
            nonlocal ticks
            ticks += round(seconds * 1e9)

        return runner.observe_one("write", "read", "sample-jwt", "key",
                                  request, lambda: ticks, wait), seen

    def test_new_binding_is_used_on_separate_authorized_http_read(self):
        observed, seen = self.observe([
            (202, {"requestId": REQUEST}, False, 20),
            (404, None, False, 10),
            (200, {"active": True, "euiVersion": 1, "bindingToken": "new-binding"}, False, 45),
            (200, {"potIds": [], "nextCursor": None}, False, 100),
        ])
        self.assertEqual("AUTHORIZED", observed["outcome"])
        self.assertEqual((180, 200), (observed["lowerMs"], observed["upperMs"]))
        self.assertEqual(("write", "/identity/requests", {"body": {"operation": "Registration"}, "key": "key"}), seen[0])
        self.assertEqual(("read", "/me/pots?limit=1", {"binding": "new-binding"}), seen[-1])
        self.assertNotIn("new-binding", json.dumps(observed))

    def test_nonempty_list_or_maintenance_never_counts_as_success(self):
        admission = (202, {"requestId": REQUEST}, False, 20)
        binding = (200, {"active": True, "euiVersion": 1, "bindingToken": "binding"}, False, 50)
        observed, _ = self.observe([admission, binding, (200, {"potIds": [REQUEST]}, False, 30)])
        self.assertEqual("UNEXPECTED_VISIBILITY", observed["outcome"])
        observed, _ = self.observe([admission, binding, (503, None, True, 30)])
        self.assertEqual("PROBE_ERROR", observed["outcome"])

    def test_response_after_window_is_censored(self):
        observed, _ = self.observe([
            (202, {"requestId": REQUEST}, False, 5),
            (200, {"active": True, "euiVersion": 1, "bindingToken": "binding"}, False, 10),
            (200, {"potIds": []}, False, 8000),
        ])
        self.assertEqual("CENSORED", observed["outcome"])
        self.assertEqual((8000, None), (observed["lowerMs"], observed["upperMs"]))

    def test_quantile_uses_bounds_and_keeps_censored_samples(self):
        def rows(upper, lower, censored=0):
            return [{"requestId": str(uuid.UUID(int=i)), "offerLagMs": 0,
                     "outcome": "CENSORED" if i < censored else "AUTHORIZED",
                     "lowerMs": 8000 if i < censored else lower,
                     "upperMs": None if i < censored else upper}
                    for i in range(100)]
        self.assertEqual("PASS", runner.summarize(rows(400, 200, 5))["status"])
        self.assertEqual("FAIL", runner.summarize(rows(400, 200, 6))["status"])
        self.assertEqual("INCONCLUSIVE", runner.summarize(rows(540, 450))["status"])
        self.assertEqual("FAIL", runner.summarize(rows(540, 510))["status"])
        invalid = rows(500, 350)
        invalid[0]["offerLagMs"] = 51
        self.assertEqual("INCONCLUSIVE", runner.summarize(invalid)["status"])

    def test_sql_requires_matching_users_on_two_authorities(self):
        one = {"complete": 1, "distinctUsers": 1, "userDigest": "a"}
        other = {**one, "userDigest": "b"}
        with patch.object(runner, "observe_sql", side_effect=[one, other, one, one]) as sql:
            self.assertFalse(runner.reconcile("write", "read", [REQUEST])["complete"])
            self.assertTrue(runner.reconcile("write", "read", [REQUEST])["complete"])
        self.assertEqual(["v3_write", "v3_read"] * 2, [call.args[1] for call in sql.call_args_list])
        self.assertIn("TERMINAL", sql.call_args_list[0].args[2])
        self.assertIn("read_current_binding", sql.call_args_list[1].args[2])

    def test_static_plan_contains_1380_unique_subjects_and_never_calls_http(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            subjects, report = path / "subjects.json", path / "receipt.json"
            args = [str(SCRIPT), "--run-id", str(uuid.uuid4()),
                    "--subjects", str(subjects), "--report", str(report)]
            with patch("sys.argv", args), patch.object(runner, "http_call", side_effect=AssertionError("HTTP")):
                self.assertEqual(0, runner.main())
            self.assertEqual(1380, len(set(json.loads(subjects.read_text()))))
            self.assertEqual("STATIC_PLAN_ONLY", json.loads(report.read_text())["status"])


if __name__ == "__main__":
    unittest.main()
