"""The collector must reject absent, stale, skipped or discordant mandatory evidence."""
import importlib.util
import tempfile
import time
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("i4_evidence", Path(__file__).with_name("verify-v3-l2-i4.py"))
i4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i4)

class EvidenceTest(unittest.TestCase):
    required = {"Proof": ["i4_b18"]}
    good = {"class": "Proof", "method": "i4_b18", "state": "PASS"}

    def test_missing_method(self):
        self.assertTrue(i4.require_methods([], self.required))

    def test_mandatory_skip(self):
        self.assertTrue(i4.require_methods([{**self.good, "state": "SKIP"}], self.required))

    def test_duplicate_xml_method(self):
        self.assertTrue(i4.require_methods([self.good, self.good], self.required))

    def test_failed_xml(self):
        self.assertTrue(i4.require_methods([{**self.good, "state": "FAIL"}], self.required))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"x.xml"
            path.write_text('<testsuite><testcase classname="Proof" name="i4_b18"><error/></testcase></testsuite>')
            self.assertEqual("FAIL", i4.qualified_cases(path)[0]["state"])

    def test_stale_trace(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/"trace").write_text("COMMAND READ AFTER_COMMIT")
            req = [{"group": "i4", "path": "trace", "contains": ["COMMAND", "READ", "AFTER_COMMIT"]}]
            self.assertTrue(i4.require_physical(root, req, ["i4"], time.time()+10))

    def test_missing_family_authority_ack_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for missing in ("COMMAND", "READ", "AFTER_COMMIT"):
                (root/"trace").write_text(" ".join(x for x in ("COMMAND", "READ", "AFTER_COMMIT") if x != missing))
                req = [{"group": "i4", "path": "trace", "contains": ["COMMAND", "READ", "AFTER_COMMIT"]}]
                self.assertTrue(i4.require_physical(root, req, ["i4"], time.time()-1))

    def test_source_discordance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/"source").write_text("before")
            hashes = {"source": i4.digest(root/"source")}
            self.assertFalse(i4.require_sources(root, hashes))
            (root/"source").write_text("after")
            self.assertTrue(i4.require_sources(root, hashes))

    def test_missing_compiled_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root/"app/module/src/main/java/x/Example.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Example {}")
            problems, binaries = i4.require_compiled(root, [str(source.relative_to(root))], {"module"})
            self.assertTrue(problems)
            self.assertFalse(binaries)

    def test_qualified_success(self):
        self.assertFalse(i4.require_methods([self.good], self.required))

if __name__ == "__main__":
    unittest.main()
