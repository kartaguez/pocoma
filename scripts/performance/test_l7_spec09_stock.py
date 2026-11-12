"""Offline contract checks for the F01-G stock producer."""
from collections import Counter
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

SCRIPT = Path(__file__).with_name("stock-l7-spec09-backlog.py")
SPEC = importlib.util.spec_from_file_location("l7_spec09_stock", SCRIPT)
stock = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stock)


class StockContract(unittest.TestCase):
    def setUp(self):
        self.run_id = uuid.uuid4()
        self.members = tuple(str(uuid.UUID(int=i)) for i in range(1, 21))
        self.fixtures = [(str(uuid.UUID(int=i)), 202, self.members) for i in range(21, 31)]

    def test_keys_and_intentions_are_stable_and_unique(self):
        result = [stock.command(self.run_id, fixture, "fixture-binding") for fixture in self.fixtures]
        self.assertEqual(len(result), len({key for key, _ in result}))
        self.assertEqual(len(result), len({body["payload"]["id"] for _, body in result}))
        self.assertEqual(result[0], stock.command(self.run_id, self.fixtures[0], "fixture-binding"))
        self.assertNotEqual(result[0][0], stock.command(uuid.uuid4(), self.fixtures[0], "fixture-binding")[0])
        for _, body in result:
            self.assertEqual(20, len(body["payload"]["participants"]))
            self.assertEqual(self.members[0], body["payload"]["payerId"])
            self.assertEqual(202, body["observedVersion"])

    def test_unknown_admission_stops_before_the_next_batch(self):
        positions = {fixture[0]: i for i, fixture in enumerate(self.fixtures)}
        observed = []

        def submit(fixture):
            i = positions[fixture[0]]
            if i == 3:
                return {"outcome": "UNKNOWN_ADMISSION"}
            return {"httpStatus": 202, "requestId": str(uuid.UUID(int=i + 1))}

        self.assertIsNone(stock.collect(self.fixtures, 2, submit, observed))
        self.assertEqual(Counter({"UNKNOWN_ADMISSION": 1, 202: 3}),
                         Counter(x.get("outcome", x.get("httpStatus")) for x in observed))

    def test_duplicate_durable_request_is_rejected(self):
        observed = []
        request = str(uuid.uuid4())
        self.assertIsNone(stock.collect(self.fixtures, 2,
                                        lambda _: {"httpStatus": 202, "requestId": request},
                                        observed))
        self.assertEqual(2, len(observed))

    def test_full_static_plan_never_opens_a_connection_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            fixture_file = path / "fixtures.json"
            report = path / "receipt.json"
            fixture_file.write_text(json.dumps([
                {"potId": str(uuid.UUID(int=i + 100)), "version": 202,
                 "expenses": 200, "shareholders": self.members}
                for i in range(10_000)
            ]))
            with patch("sys.argv", [str(SCRIPT), "--fixtures", str(fixture_file), "--report", str(report)]), \
                    patch.object(stock.urllib.request, "build_opener", side_effect=AssertionError("network")):
                self.assertEqual(0, stock.main())
                self.assertEqual("STATIC_PLAN_ONLY", json.loads(report.read_text())["status"])
                with self.assertRaises(FileExistsError):
                    stock.main()

    def test_fixture_validation_rejects_wrong_population_and_duplicate_pots(self):
        row = {"potId": self.fixtures[0][0], "version": 202, "expenses": 200,
               "shareholders": self.members}
        with self.assertRaisesRegex(ValueError, "exactly 10000"):
            stock.parse_fixtures(json.dumps([row]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            stock.parse_fixtures(json.dumps([row] * 10_000))

    def test_write_origin_cannot_carry_credentials_or_path(self):
        self.assertEqual("https://write.example:8443/api/v3/commands",
                         stock.write_endpoint("https://write.example:8443/"))
        for origin in ("https://user@write.example", "https://write.example/debug",
                       "https://write.example?token=opaque", "file:///tmp/socket"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                stock.write_endpoint(origin)


if __name__ == "__main__":
    unittest.main()
