import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("i2_evidence", Path(__file__).with_name("verify-v3-l2-i2.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)

class I2EvidenceTest(unittest.TestCase):
    def test_missing_method_cannot_pass(self):
        self.assertTrue(collector.require_methods([], {"Proof": ["mandatory"]}))
    def test_skip_is_not_a_pass(self):
        self.assertTrue(collector.require_methods([{"class":"Proof","method":"mandatory","state":"SKIP"}], {"Proof":["mandatory"]}))
    def test_duplicate_xml_cannot_satisfy_provenance(self):
        case={"class":"Proof","method":"mandatory","state":"PASS"}
        self.assertTrue(collector.require_methods([case,case], {"Proof":["mandatory"]}))
    def test_actual_xml_distinguishes_failure_skip_and_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"TEST-proof.xml"
            path.write_text('<testsuite><testcase classname="Proof" name="pass"/><testcase classname="Proof" name="skip"><skipped/></testcase><testcase classname="Proof" name="fail"><failure/></testcase></testsuite>')
            self.assertEqual(["PASS","SKIP","FAIL"],[x["state"] for x in collector.qualified_cases(path)])
            self.assertFalse(collector.require_methods(collector.qualified_cases(path), {"Proof":["pass"]}))
