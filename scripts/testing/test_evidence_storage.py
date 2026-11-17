"""Storage-only regression checks: refuse tampering and protect existing files."""
import base64
import gzip
import hashlib
import importlib.util
import lzma
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('storage', Path(__file__).with_name('evidence-storage.py'))
STORAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STORAGE)


class StorageTest(unittest.TestCase):
    def test_git_restore_exact_bytes_tampering_missing_commit_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            payload = b'Unique failed physical observation\n'
            (root / 'proof.bin').write_bytes(payload)
            subprocess.run(['git', 'add', 'proof.bin'], cwd=root, check=True)
            subprocess.run(['git', '-c', 'user.name=Storage test', '-c',
                            'user.email=storage@example.invalid', 'commit', '-qm', 'proof'],
                           cwd=root, check=True)
            commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
            entry = {'commit': commit, 'path': 'proof.bin', 'bytes': len(payload),
                     'sha256': hashlib.sha256(payload).hexdigest()}
            (root / 'proof.bin').unlink()
            destination = root / 'restored.bin'
            STORAGE.restore_historical(entry, destination, root)
            self.assertEqual(destination.read_bytes(), payload)
            with self.assertRaises(FileExistsError):
                STORAGE.restore_historical(entry, destination, root)
            for delta in ({'sha256': '0' * 64}, {'bytes': 1}, {'commit': '0' * 40}):
                rejected = root / 'rejected.bin'
                with self.assertRaises(ValueError):
                    STORAGE.restore_historical({**entry, **delta}, rejected, root)
                self.assertFalse(rejected.exists())

    def test_exact_envelope_roundtrip_tampering_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'old.tar.gz'
            payload = b'Physical observation\n' * 2000
            with original.open('wb') as raw:
                with gzip.GzipFile(filename='historic.tar', mode='wb', fileobj=raw, mtime=123, compresslevel=9) as encoded:
                    encoded.write(payload)
            header, footer = STORAGE.gzip_parts(original)
            replacement = root / 'new.tar.xz'
            replacement.write_bytes(lzma.compress(payload, preset=3))
            entry = {'path': 'old.tar.gz', 'sha256': STORAGE.digest(original), 'replacement': {
                'path': str(replacement), 'sha256': STORAGE.digest(replacement),
                'tar_bytes': len(payload), 'tar_sha256': hashlib.sha256(payload).hexdigest(),
                'gzip_header_b64': base64.b64encode(header).decode(),
                'gzip_footer_b64': base64.b64encode(footer).decode(), 'gzip_level': 9}}
            restored = root / 'restored.tar.gz'
            STORAGE.restore(entry, restored)
            self.assertEqual(original.read_bytes(), restored.read_bytes())
            with self.assertRaises(FileExistsError):
                STORAGE.restore(entry, restored)
            entry['sha256'] = '0' * 64
            rejected = root / 'rejected.tar.gz'
            with self.assertRaisesRegex(ValueError, 'exactly'):
                STORAGE.restore(entry, rejected)
            self.assertFalse(rejected.exists())
            replacement.write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'Changed XZ'):
                STORAGE.historical_gzip(entry)

    def test_missing_and_truncated_headers_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'invalid.gz'
            for data in (b'', b'\x1f\x8b\x08', b'not gzip',
                         b'\x1f\x8b\x08\x08' + b'\0' * 6 + b'unterminated'):
                file.write_bytes(data)
                with self.assertRaises(ValueError):
                    STORAGE.gzip_parts(file)


if __name__ == '__main__':
    unittest.main()
