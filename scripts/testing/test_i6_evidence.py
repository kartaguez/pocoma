"""Negative checks for accidental promotion or loss of an I6 allocation."""
import copy
import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('i6', Path(__file__).with_name('check-v3-l2-i6.py'))
I6 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(I6)


class AttributionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with (I6.FOLDER / 'Coverage.tsv').open() as stream:
            cls.rows = list(csv.DictReader(stream, delimiter='\t'))
        cls.source = I6.allocations()
        cls.evidence = json.loads((I6.FOLDER / 'Proof-index.json').read_text())['evidence']

    def test_complete_overlay_preserves_normative_rows(self):
        self.assertEqual(380, I6.validate_rows(self.rows, self.source, self.evidence))

    def test_missing_or_duplicate_allocation_is_rejected(self):
        for rows in (self.rows[:-1], self.rows[:-1] + [self.rows[0]]):
            with self.assertRaisesRegex(ValueError, 'allocation'):
                I6.validate_rows(rows, self.source, self.evidence)

    def test_fixture_or_availability_cannot_be_promoted_by_observed_pass(self):
        for key in ('L2M-R10-08', 'L2M-C06-C-POTVERSION'):
            rows = copy.deepcopy(self.rows)
            row = next(r for r in rows if r['proof_id'] == key)
            row['verdict'] = 'PASS'
            with self.assertRaises(ValueError):
                I6.validate_rows(rows, self.source, self.evidence)

    def test_deferred_business_requirement_cannot_be_promoted(self):
        rows = copy.deepcopy(self.rows)
        next(r for r in rows if r['allocation'] == 'reporté')['verdict'] = 'PASS'
        with self.assertRaisesRegex(ValueError, 'Deferred'):
            I6.validate_rows(rows, self.source, self.evidence)

    def test_failed_observation_does_not_support_a_pass(self):
        rows = copy.deepcopy(self.rows)
        next(r for r in rows if r['verdict'] == 'PASS')['evidence'] = 'I5-FINAL:r12'
        with self.assertRaisesRegex(ValueError, 'without a successful'):
            I6.validate_rows(rows, self.source, self.evidence)

    def test_archive_failure_and_skip_stay_distinct(self):
        import io
        import tarfile
        xml = b'<testsuite name="C"><testcase name="ok"/><testcase name="bad"><failure/></testcase><testcase name="absent"><skipped/></testcase></testsuite>'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'test.tar.gz'
            with tarfile.open(path, 'w:gz') as archive:
                member = tarfile.TarInfo('xml/TEST-C.xml')
                member.size = len(xml)
                archive.addfile(member, io.BytesIO(xml))
            cases, _, _, _ = I6.scan_archive(path)
        self.assertEqual({'C#ok': ['PASS'], 'C#bad': ['FAIL'], 'C#absent': ['SKIP']}, cases)

    def test_only_windows_wrapper_may_use_explicit_crlf_representation(self):
        data = b'first\nsecond\n'
        expected = I6.sha(b'first\r\nsecond\r\n')
        self.assertEqual('CRLF_WINDOWS_WRAPPER', I6.source_encoding('app/mvnw.cmd', data, expected))
        self.assertIsNone(I6.source_encoding('app/Oracle.java', data, expected))
        self.assertIsNone(I6.source_encoding('app/mvnw.cmd', b'changed\n', expected))


if __name__ == '__main__':
    unittest.main()
