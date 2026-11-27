"""Verify credential provisioning stays private and rotation replaces its output."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class GrafanaLokiTests(unittest.TestCase):
    def test_render_rotation_and_init_preservation(self):
        source = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copyfile(source / 'init.sh', root / 'init.sh')
            shutil.copyfile(source / 'init-loki-grafana.py', root / 'init-loki-grafana.py')
            (root / 'grafana').mkdir()
            shutil.copyfile(source / 'grafana/loki-datasource.json',
                            root / 'grafana/loki-datasource.json')
            subprocess.run(['bash', str(root / 'init.sh')], check=True, capture_output=True)
            provision = root / 'runtime/grafana-loki.yaml'
            self.assertIn('datasources: []', provision.read_text())
            credentials = root / 'runtime/pki/grafana-loki'
            credentials.mkdir(parents=True)
            for name in ['ca', 'cert', 'key']:
                (credentials / (name + '.pem')).write_text('secret-' + name)
            result = subprocess.run([sys.executable, str(root / 'init-loki-grafana.py')],
                                    check=True, capture_output=True, text=True)
            self.assertNotIn('secret-', result.stdout + result.stderr)
            payload = json.loads(provision.read_text())
            datasource = payload['datasources'][0]
            self.assertEqual(datasource['uid'], 'loki')
            self.assertFalse(datasource['jsonData']['tlsSkipVerify'])
            self.assertEqual(datasource['secureJsonData']['tlsClientKey'], 'secret-key')
            self.assertEqual(provision.stat().st_mode & 0o777, 0o444)
            self.assertEqual(provision.parent.stat().st_mode & 0o777, 0o700)
            subprocess.run(['bash', str(root / 'init.sh')], check=True, capture_output=True)
            self.assertEqual(json.loads(provision.read_text()), payload)
            (credentials / 'key.pem').write_text('rotated-private-key')
            subprocess.run([sys.executable, str(root / 'init-loki-grafana.py')],
                           check=True, capture_output=True)
            self.assertEqual(json.loads(provision.read_text())['datasources'][0]
                             ['secureJsonData']['tlsClientKey'], 'rotated-private-key')


if __name__ == '__main__':
    unittest.main()
