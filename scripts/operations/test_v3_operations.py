#!/usr/bin/env python3
"""Unit tests for V4 operational scripts and SQL diagnostics (L7-C2-B2.4)."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class TestV4OperationalScripts(unittest.TestCase):
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

    def test_setup_v3_database_refuses_non_empty_v3_schema_and_applies_v4_on_fresh(self):
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

        # Fresh install path (no read_current_pot) applies V1..V4
        applied.clear()

        def fake_psql_fresh(db, sql_command, check=True):
            return ""

        with patch.object(self.setup_mod, "psql", side_effect=fake_psql_fresh), \
             patch.object(self.setup_mod, "psql_script", return_value=""), \
             patch.object(self.setup_mod, "apply_migrations", side_effect=lambda db, sc, ad, rt, migs: applied.extend(migs)):
            self.setup_mod.initialize_v3_read()
        self.assertEqual(len(applied), 4)
        self.assertTrue(applied[-1].endswith("V4__pot_balance_shared_storage.sql"))


if __name__ == "__main__":
    unittest.main()
