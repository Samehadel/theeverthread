#!/usr/bin/env python3
"""Render secret-bearing Loki provisioning only into ignored, protected runtime/."""

import json
import os
from pathlib import Path
import tempfile


def render(root):
    runtime = root / 'runtime'
    credentials = runtime / 'pki/grafana-loki'
    datasource = json.loads((root / 'grafana/loki-datasource.json').read_text())
    datasource['secureJsonData'] = {
        field: (credentials / filename).read_text()
        for field, filename in [('tlsCACert', 'ca.pem'),
                                ('tlsClientCert', 'cert.pem'), ('tlsClientKey', 'key.pem')]
    }
    if any(not value.strip() for value in datasource['secureJsonData'].values()):
        raise ValueError('Grafana Loki TLS credentials must not be empty')
    runtime.chmod(0o700)
    payload = {'apiVersion': 1, 'datasources': [datasource]}
    # JSON is valid YAML. Atomic replacement also supports credential rotation;
    # recreate Grafana afterwards so its single-file mount reads the new inode.
    with tempfile.NamedTemporaryFile(mode='w', dir=runtime, delete=False) as temporary:
        path = Path(temporary.name)
        try:
            json.dump(payload, temporary, indent=2)
            temporary.write('\n')
            temporary.flush()
            path.chmod(0o444)
            os.replace(path, runtime / 'grafana-loki.yaml')
        finally:
            path.unlink(missing_ok=True)
    print('Loki provisioning prepared in runtime/grafana-loki.yaml; credentials were not printed.')


if __name__ == '__main__':
    os.umask(0o077)
    render(Path(__file__).resolve().parent)
