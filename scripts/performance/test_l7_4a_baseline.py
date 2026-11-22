#!/usr/bin/env python3
"""Unit tests for L7.4-A baseline qualification bench helpers."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT_DIR = Path(__file__).resolve().parent


def load_bench_module():
    spec = importlib.util.spec_from_file_location("run_l7_4a", SCRIPT_DIR / "run-l7-4a-baseline.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class TestL74ABaseline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_bench_module()

    def test_profiles_config_contains_required_campaigns(self):
        cfg = self.mod.load_profiles_config()
        self.assertEqual(cfg["profiles"]["medium"]["shareholders"], 20)
        self.assertEqual(cfg["profiles"]["medium"]["expenses"], 200)
        self.assertEqual(cfg["profiles"]["large"]["shareholders"], 100)
        self.assertEqual(cfg["profiles"]["large"]["expenses"], 2000)
        for name in ("smoke-medium", "med-1", "med-5", "smoke-large", "large-1", "large-5"):
            self.assertIn(name, cfg["campaigns"])

    def test_collect_runtime_config_summary_reads_all_runtimes(self):
        cfg = self.mod.load_profiles_config()
        summary = self.mod.collect_runtime_config_summary(cfg["topology"]["runtimes"])
        self.assertEqual(summary["http-write"]["pocoma.v3.http.admission.capacity"], "4")
        self.assertEqual(summary["command-worker"]["pocoma.v3.worker.min-poll"], "2")
        self.assertIn("MaterializePotBalance=2", summary["business-read-worker"]["pocoma.v3.worker.consumption-limits"])

    def test_stats_summary_computes_quantiles_and_max(self):
        s = self.mod.stats_summary([10.0, 20.0, 30.0, 40.0, 50.0, None])
        self.assertEqual(s["n"], 5)
        self.assertEqual(s["p50"], 30.0)
        self.assertEqual(s["max"], 50.0)
        empty = self.mod.stats_summary([])
        self.assertEqual(empty, {"n": 0, "p50": None, "p95": None, "p99": None, "max": None})

    def test_verify_snapshot_metadata_checks_integrity_and_bounds(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap_dir = Path(tmp)
            dump = snap_dir / "snapshot.sql.gz"
            dump.write_bytes(b"fake-gzip-bytes")
            sha = self.mod.sha256_file(dump)
            meta = {"profile": "large", "potCount": 2, "snapshotSha256": sha}
            (snap_dir / "snapshot_meta.json").write_text(json.dumps(meta))

            loaded_meta, loaded_dump = self.mod.verify_snapshot_metadata(snap_dir, "large", 2)
            self.assertEqual(loaded_meta["potCount"], 2)
            self.assertEqual(loaded_dump, dump)

            with self.assertRaises(ValueError):
                self.mod.verify_snapshot_metadata(snap_dir, "medium", 2)
            with self.assertRaises(ValueError):
                self.mod.verify_snapshot_metadata(snap_dir, "large", 3)

            dump.write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                self.mod.verify_snapshot_metadata(snap_dir, "large", 2)


if __name__ == "__main__":
    unittest.main()
