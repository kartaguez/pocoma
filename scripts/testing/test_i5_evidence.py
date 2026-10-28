"""Negative tests of evidence acceptance; no application certification."""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest

SPEC=importlib.util.spec_from_file_location('i5',Path(__file__).with_name('verify-v3-l2-i5.py'))
I5=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I5)

class EvidenceTest(unittest.TestCase):
    def test_missing_duplicate_skip_fail_rejected(self):
        good={'class':'a.Exact','method':'i5_w17','state':'PASS'}
        for cases in ([],[good,good],[dict(good,state='SKIP')],[dict(good,state='FAIL')]):
            self.assertTrue(I5.I4.require_methods(cases,{'a.Exact':['i5_w17']}))
        self.assertFalse(I5.I4.require_methods([good],{'a.Exact':['i5_w17']}))

    def test_missing_variant_stale_and_wrong_marker_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);rule=[{'id':'H4','glob':'H4-WRITE.txt','contains':['physical rollback','no repeated']}]
            self.assertTrue(I5.physical_problems(root,rule,0))
            file=root/'H4-WRITE.txt';file.write_text('physical rollback')
            self.assertTrue(I5.physical_problems(root,rule,0))
            file.write_text('physical rollback no repeated')
            self.assertFalse(I5.physical_problems(root,rule,0))
            self.assertTrue(I5.physical_problems(root,rule,time.time()+5))

    def test_inheritance_not_replaced_and_business_never_inferred(self):
        manifest=json.loads(I5.MANIFEST.read_text());required=I5.methods(manifest,'F')
        self.assertGreater(sum(map(len,required.values())),300)
        self.assertEqual('TECHNICAL_ONLY',manifest['read_probes'])
        self.assertEqual('NOT_CERTIFIED',manifest['global_L2'])
        for method in ('i5_w17','i5_w18','i5_r09'):
            self.assertTrue(manifest['required_variants'][method])

if __name__=='__main__':unittest.main()
