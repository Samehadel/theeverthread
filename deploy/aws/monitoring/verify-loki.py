#!/usr/bin/env python3
"""Check Loki mTLS and write/read a harmless synthetic log (no application logs)."""

import json
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parent
ENDPOINT = 'https://172.31.41.48:3100'


def request(path, context, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {'Content-Type': 'application/json'} if data else {}
    req = urllib.request.Request(ENDPOINT + path, data=data, headers=headers)
    # Private telemetry must not be routed through an operator's HTTP proxy.
    client = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    with client.open(req, timeout=10) as response:
        return response.read()


def verify():
    credentials = ROOT / 'runtime/pki/alloy'
    context = ssl.create_default_context(cafile=str(credentials / 'ca.pem'))
    context.load_cert_chain(credentials / 'cert.pem', credentials / 'key.pem')
    for attempt in range(12):
        try:
            request('/ready', context)
            break
        except (urllib.error.URLError, ssl.SSLError):
            if attempt == 11:
                raise
            time.sleep(2)

    missing_identity = ssl.create_default_context(cafile=str(credentials / 'ca.pem'))
    try:
        request('/ready', missing_identity)
    except (urllib.error.URLError, ssl.SSLError):
        print('PASS: requests without a client certificate are rejected.')
    else:
        raise RuntimeError('Loki accepted a request without a client certificate')

    untrusted_server = ssl.create_default_context()
    untrusted_server.load_cert_chain(credentials / 'cert.pem', credentials / 'key.pem')
    try:
        request('/ready', untrusted_server)
    except urllib.error.URLError as error:
        if not isinstance(error.reason, ssl.SSLCertVerificationError):
            raise
        print('PASS: the client rejects a server outside its trusted CAs.')
    else:
        raise RuntimeError('Loki certificate was unexpectedly trusted by the system CA store')

    marker = f'monitoring-mtls-smoke-{uuid.uuid4()}'
    payload = {'streams': [{'stream': {'job': 'monitoring-smoke', 'host': 'monitoring'},
                            'values': [[str(time.time_ns()), marker]]}]}
    request('/loki/api/v1/push', context, payload)
    query = urllib.parse.urlencode({
        'query': '{job="monitoring-smoke"} |= "' + marker + '"', 'limit': 1})
    for attempt in range(10):
        result = json.loads(request('/loki/api/v1/query_range?' + query, context))
        if any(marker == line for stream in result['data']['result']
               for _, line in stream['values']):
            print('PASS: authenticated log ingestion and query returned the synthetic log.')
            return
        time.sleep(1)
    raise RuntimeError('Synthetic log was not returned by Loki')


if __name__ == '__main__':
    verify()
