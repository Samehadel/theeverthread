import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("monitoring_init", Path(__file__).with_name("init.py"))
monitoring_init = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitoring_init)


class MonitoringInitializationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        (self.directory / "prometheus.yml.template").write_text("url: '@PUBLIC_MONITOR_URL@/'\n")

    def monitor(self, address="10.20.0.10", **kwargs):
        monitoring_init.initialize(self.directory, "monitoring", address, **kwargs)
        return self.directory / "runtime/monitoring"

    def test_monitoring_reinitialization_preserves_credentials_and_ca(self):
        runtime = self.monitor()
        original = {name: (runtime / name).read_bytes() for name in (
            "ingestion_token", "grafana_password", "ca.key", "ca.crt", "tls.key", "tls.crt"
        )}
        self.monitor(public_url="https://other.example.com/")
        for name, value in original.items():
            self.assertEqual((runtime / name).read_bytes(), value)
        self.assertEqual(len(original["ingestion_token"]), 64)
        self.assertNotEqual(original["ingestion_token"], original["grafana_password"])
        self.assertEqual(runtime.stat().st_mode & 0o777, 0o700)
        self.assertEqual((runtime / "ca.key").stat().st_mode & 0o777, 0o600)
        self.assertEqual((runtime / "prometheus.yml").read_text(), "url: 'https://other.example.com/'\n")
        self.assertFalse((runtime / "prometheus_token").exists())
        monitoring_init.openssl("verify", "-CAfile", runtime / "ca.crt", "-verify_ip",
                                "10.20.0.10", runtime / "tls.crt")

    def test_app_requires_transferred_trust_and_never_generates_ingestion_credentials(self):
        with self.assertRaises(ValueError):
            monitoring_init.initialize(self.directory, "app", "10.20.0.10")
        app = self.directory / "runtime/app"
        self.assertFalse((app / "ingestion_token").exists())
        monitor = self.monitor()
        for name in ("ingestion_token", "ca.crt"):
            shutil.copyfile(monitor / name, app / name)
        monitoring_init.initialize(self.directory, "app", "10.20.0.10")
        self.assertEqual((app / "ingestion_token").read_text(), (monitor / "ingestion_token").read_text())
        self.assertNotEqual((app / "prometheus_token").read_text(), (app / "ingestion_token").read_text())
        self.assertFalse((app / "ca.key").exists())
        self.assertFalse((app / "grafana_password").exists())
        self.assertEqual((app / "host.env").read_text(), "MONITORING_INGEST_URL=https://10.20.0.10:9443\n")

    def test_certificate_address_change_requires_explicit_renewal_without_rotating_ca(self):
        runtime = self.monitor()
        ca = (runtime / "ca.crt").read_bytes()
        token = (runtime / "ingestion_token").read_bytes()
        with self.assertRaises(subprocess.CalledProcessError):
            self.monitor("10.20.0.11")
        self.monitor("10.20.0.11", renew=True)
        self.assertEqual((runtime / "ca.crt").read_bytes(), ca)
        self.assertEqual((runtime / "ingestion_token").read_bytes(), token)
        monitoring_init.openssl("verify", "-CAfile", runtime / "ca.crt", "-verify_ip",
                                "10.20.0.11", runtime / "tls.crt")

    def test_rejects_public_bind_addresses_and_unsafe_probe_origins(self):
        for address in ("", "0.0.0.0", "127.0.0.1", "8.8.8.8", "169.254.169.254", "::1"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                self.monitor(address)
        for origin in (
            "http://example.com", "https://user:password@example.com", "https://example.com/path",
            "https://example.com/?token=secret", "https://example.com/#fragment",
            'https://example.com/"\ninjected: value', "https://example.com'",
        ):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                self.monitor(public_url=origin)
        self.assertFalse((self.directory / "runtime").exists())


if __name__ == "__main__":
    unittest.main()
