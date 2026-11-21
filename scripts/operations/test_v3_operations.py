#!/usr/bin/env python3
"""Tests for READ bootstrap, reconstruction and PotBalance diagnostics."""

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    with patch.object(sys, "path", [str(path.parent), *sys.path]):
        spec.loader.exec_module(mod)
    return mod


class OperationalScriptsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.setup_mod = _load_module("setup_v3_db", REPO_ROOT / "scripts/operations/setup-v3-database.py")
        cls.rebuild_mod = _load_module("v3_rebuild_read", REPO_ROOT / "scripts/operations/v3-rebuild-read.py")

    def test_grant_http_read_includes_balance_content_and_shareholder_set_and_excludes_aggregates(self):
        sql = (REPO_ROOT / "scripts/v3/grant-l7-http-read.sql").read_text(encoding="utf-8")
        self.assertIn("'read_pot_shareholder_set'", sql)
        self.assertIn("'read_pot_balance'", sql)
        self.assertIn("'read_pot_balance_content'", sql)
        self.assertNotIn("read_pot_balance_aggregate", sql)

    def test_diagnose_pot_balance_unreachable_is_strictly_read_only(self):
        sql = (REPO_ROOT / "scripts/v3/diagnose-pot-balance-unreachable.sql").read_text(encoding="utf-8")
        self.assertIn("BEGIN TRANSACTION READ ONLY;", sql)
        self.assertIn("read_pot_shareholder_set", sql)
        self.assertIn("read_pot_balance_content", sql)
        self.assertIn("read_pot_balance_aggregate_content", sql)
        upper = sql.upper()
        for forbidden in ("DELETE ", "UPDATE ", "TRUNCATE ", "INSERT ", "DROP "):
            self.assertNotIn(forbidden, upper)

    def test_rebuild_read_includes_v4_tables_and_integrity_checks(self):
        self.assertIn("read_pot_shareholder_set", self.rebuild_mod.CANONICAL_MIGRATION_TABLES)
        self.assertIn("read_pot_balance_content", self.rebuild_mod.CANONICAL_MIGRATION_TABLES)
        self.assertIn("read_pot_balance_aggregate_content", self.rebuild_mod.CANONICAL_MIGRATION_TABLES)
        src = (REPO_ROOT / "scripts/operations/v3-rebuild-read.py").read_text(encoding="utf-8")
        self.assertIn("V4__pot_balance_shared_storage.sql", src)
        self.assertIn("read_pot_shareholder_set", src)
        self.assertIn("read_pot_balance_content", src)
        self.assertIn("read_pot_balance_aggregate_content", src)

    def test_setup_refuses_in_place_balance_upgrade_and_installs_availability_on_fresh_read(self):
        applied = []

        def fake_psql(db, sql_command, check=True):
            if "to_regclass('v3_read.read_current_pot')" in sql_command:
                return "v3_read.read_current_pot"
            if "to_regclass('v3_read.read_pot_balance_aggregate')" in sql_command:
                return "v3_read.read_pot_balance_aggregate"
            if "to_regclass('v3_read.read_pot_version_node')" in sql_command:
                return "v3_read.read_pot_version_node"
            if "to_regclass('v3_read.read_pot_shareholder_set')" in sql_command:
                return ""
            if "to_regclass('v3_read.read_pot_balance_content')" in sql_command:
                return ""
            if "count(*) FROM v3_read.read_pot_balance;" in sql_command:
                return "42"
            if "count(*) FROM v3_read.read_pot_balance_aggregate;" in sql_command:
                return "42"
            return ""

        with patch.object(self.setup_mod, "psql", side_effect=fake_psql), \
             patch.object(self.setup_mod, "psql_script", return_value=""), \
             patch.object(self.setup_mod, "apply_migrations", side_effect=lambda *a: applied.append(a)):
            with self.assertRaises(RuntimeError) as ctx:
                self.setup_mod.initialize_v3_read()
            self.assertIn("INCOMPATIBLE-V3-READ-SCHEMA", str(ctx.exception))
            self.assertIn("v3-rebuild-read.py", str(ctx.exception))
            self.assertEqual(applied, [])

        # Fresh install path (no read_current_pot) applies V1..V5
        applied.clear()

        def fake_psql_fresh(db, sql_command, check=True):
            return ""

        with patch.object(self.setup_mod, "psql", side_effect=fake_psql_fresh), \
             patch.object(self.setup_mod, "psql_script", return_value=""), \
             patch.object(self.setup_mod, "apply_migrations", side_effect=lambda db, sc, ad, rt, migs: applied.extend(migs)):
            self.setup_mod.initialize_v3_read()
        self.assertEqual(len(applied), 5)
        self.assertTrue(applied[-2].endswith("V4__pot_balance_shared_storage.sql"))
        self.assertTrue(applied[-1].endswith("V5__projection_availability.sql"))

    def test_partial_v5_is_refused_before_another_migration(self):
        with patch.object(self.setup_mod, "psql", side_effect=["t", "f"]), \
             patch.object(self.setup_mod, "apply_migrations") as apply:
            with self.assertRaisesRegex(RuntimeError, "Incomplete READ V5"):
                self.setup_mod.initialize_read_availability()
            apply.assert_not_called()

    def test_reopen_refuses_incomplete_c3_catalog(self):
        with patch.object(self.rebuild_mod, "psql_admin", return_value="v3_read"), \
             patch.object(self.rebuild_mod, "check_tables_exist", return_value=True), \
             patch.object(self.rebuild_mod, "availability_schema_complete", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "availability catalog"):
                self.rebuild_mod._ensure_physical_schema()


if __name__ == "__main__":
    unittest.main()
