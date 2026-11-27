#!/usr/bin/env python3
"""Verify all application log sources through Grafana without printing log contents."""

import base64
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parent


def grafana(path):
    password_file = Path(os.environ.get('GRAFANA_PASSWORD_FILE', ROOT / 'runtime/grafana_password'))
    password = password_file.read_text().strip()
    token = base64.b64encode(('admin:' + password).encode()).decode()
    request = urllib.request.Request('http://127.0.0.1:3000' + path,
                                     headers={'Authorization': 'Basic ' + token})
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with client.open(request, timeout=40) as response:
        return json.load(response)


def verify():
    health = grafana('/api/datasources/uid/loki/health')
    if health.get('status') != 'OK':
        raise RuntimeError('Grafana could not verify its Loki data source')
    print('PASS: Grafana connects to Loki using its provisioned mTLS identity.')
    for service, selector in [
            ('backend', '{host="application",job="application-containers",service_name="backend"}'),
            ('frontend', '{host="application",job="application-containers",service_name="frontend"}'),
            ('host-journal', '{host="application",job="host-journal"}')]:
        query = urllib.parse.urlencode({
            'query': selector, 'start': str(time.time_ns() - 3600 * 10**9), 'limit': 1})
        result = grafana('/api/datasources/proxy/uid/loki/loki/api/v1/query_range?' + query)
        if not result.get('data', {}).get('result'):
            raise RuntimeError(f'No recent log found for {service}; inspect Alloy and generate activity')
        print(f'PASS: recent {service} logs are queryable through Grafana.')


if __name__ == '__main__':
    verify()
