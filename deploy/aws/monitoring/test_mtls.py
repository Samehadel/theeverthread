"""Exercise real OpenSSL identity generation without creating repo credentials."""
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


class MtlsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = pathlib.Path(self.temporary.name)
        self.script = self.directory / 'init-mtls.py'
        shutil.copyfile(pathlib.Path(__file__).with_name('init-mtls.py'), self.script)

    def run_script(self, *arguments, success=True):
        result = subprocess.run([sys.executable, str(self.script), *arguments], capture_output=True)
        self.assertEqual(result.returncode == 0, success, result.stderr.decode())

    def test_identity_lifecycle_and_purpose(self):
        self.run_script()
        pki = self.directory / 'runtime/pki'
        before = {str(path.relative_to(pki)): path.read_bytes()
                  for path in pki.rglob('*.pem')}
        self.run_script()
        self.assertEqual(before, {str(path.relative_to(pki)): path.read_bytes()
                                  for path in pki.rglob('*.pem')})
        self.assertEqual((pki / 'ca/key.pem').stat().st_mode & 0o777, 0o600)
        self.assertEqual((pki / 'app-node').stat().st_mode & 0o777, 0o700)
        wrong_purpose = subprocess.run(
            ['openssl', 'verify', '-CAfile', str(pki / 'ca/cert.pem'),
             '-purpose', 'sslclient', str(pki / 'app-node/cert.pem')], capture_output=True)
        self.assertNotEqual(wrong_purpose.returncode, 0)
        self.run_script('--app-ip', '172.31.45.84', success=False)
        self.run_script('--renew')
        self.assertEqual(before['ca/key.pem'], (pki / 'ca/key.pem').read_bytes())
        self.assertEqual(before['ca/cert.pem'], (pki / 'ca/cert.pem').read_bytes())
        self.assertNotEqual(before['app-node/key.pem'], (pki / 'app-node/key.pem').read_bytes())

    def test_reject_public_ip_without_creating_credentials(self):
        self.run_script('--app-ip', '8.8.8.8', success=False)
        self.assertFalse((self.directory / 'runtime').exists())

    def test_reject_incomplete_ca(self):
        self.run_script()
        (self.directory / 'runtime/pki/ca/key.pem').unlink()
        self.run_script(success=False)


if __name__ == '__main__':
    unittest.main()
