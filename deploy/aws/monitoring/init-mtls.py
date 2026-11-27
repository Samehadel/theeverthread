#!/usr/bin/env python3
"""Create telemetry identities on monitoring EC2, preserving the existing CA."""

import argparse
import ipaddress
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def openssl(*args):
    return subprocess.run(
        ['openssl', *map(str, args)], check=True, capture_output=True
    ).stdout


def private_ip(value):
    address = ipaddress.IPv4Address(value)
    networks = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
    if not any(address in ipaddress.ip_network(network) for network in networks):
        raise ValueError('Application address must be an RFC1918 private IPv4 address')
    return str(address)


def private_directory(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def validate_pair(directory, ca, purpose, address=None):
    key, cert = directory / 'key.pem', directory / 'cert.pem'
    arguments = ['verify', '-CAfile', ca, '-purpose', purpose]
    if address:
        arguments += ['-verify_ip', address]
    openssl(*arguments, cert)
    openssl('x509', '-in', cert, '-noout', '-checkend', '2592000')
    certificate_key = openssl('x509', '-in', cert, '-pubkey', '-noout')
    private_key = openssl('pkey', '-in', key, '-pubout')
    if certificate_key != private_key:
        raise ValueError(f'Certificate and key do not match in {directory.name}')


def initialize(root, address, renew=False, logs=False, monitoring_address='172.31.41.48'):
    address = private_ip(address)
    monitoring_address = private_ip(monitoring_address)
    private_directory(root)
    pki = root / 'pki'
    private_directory(pki)
    ca = pki / 'ca'
    private_directory(ca)
    ca_key, ca_cert = ca / 'key.pem', ca / 'cert.pem'
    if ca_key.exists() != ca_cert.exists():
        raise ValueError('Incomplete CA; restore its original certificate/key pair')
    if not ca_key.exists():
        with tempfile.TemporaryDirectory(dir=pki) as temporary:
            temporary = Path(temporary)
            openssl('req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3650',
                    '-subj', '/CN=theeverthread monitoring CA',
                    '-addext', 'basicConstraints=critical,CA:TRUE',
                    '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
                    '-keyout', temporary / 'key.pem', '-out', temporary / 'cert.pem')
            shutil.copyfile(temporary / 'key.pem', ca_key)
            shutil.copyfile(temporary / 'cert.pem', ca_cert)
    ca_key.chmod(0o600)
    ca_cert.chmod(0o444)
    openssl('verify', '-CAfile', ca_cert, ca_cert)
    if openssl('x509', '-in', ca_cert, '-pubkey', '-noout') != openssl('pkey', '-in', ca_key, '-pubout'):
        raise ValueError('CA certificate and key do not match')
    openssl('x509', '-in', ca_cert, '-noout', '-checkend', str(91 * 86400))

    identities = [('app-node', 'serverAuth', 'sslserver', address),
                  ('prometheus', 'clientAuth', 'sslclient', None)]
    if logs:
        identities += [('loki', 'serverAuth', 'sslserver', monitoring_address),
                       ('alloy', 'clientAuth', 'sslclient', None),
                       ('grafana-loki', 'clientAuth', 'sslclient', None)]
    for name, usage, purpose, ip in identities:
        destination = pki / name
        private_directory(destination)
        files = [destination / 'key.pem', destination / 'cert.pem']
        if any(path.exists() for path in files) and not all(path.exists() for path in files):
            raise ValueError(f'Incomplete identity: {name}; restore or inspect it')
        if not all(path.exists() for path in files) or renew:
            with tempfile.TemporaryDirectory(dir=pki) as temporary:
                temporary = Path(temporary)
                extensions = temporary / 'extensions'
                subject_alt_name = ''
                if ip:
                    names = f'IP:{ip}' + (',DNS:loki' if name == 'loki' else '')
                    subject_alt_name = f'subjectAltName={names}\n'
                extensions.write_text(
                    'basicConstraints=critical,CA:FALSE\n'
                    'keyUsage=critical,digitalSignature,keyEncipherment\n'
                    f'extendedKeyUsage={usage}\n' + subject_alt_name
                )
                openssl('req', '-new', '-newkey', 'rsa:3072', '-nodes',
                        '-subj', f'/CN=theeverthread {name}',
                        '-keyout', temporary / 'key.pem', '-out', temporary / 'request.pem')
                openssl('x509', '-req', '-in', temporary / 'request.pem',
                        '-CA', ca_cert, '-CAkey', ca_key,
                        '-set_serial', str(int.from_bytes(os.urandom(16), 'big') or 1),
                        '-days', '90', '-extfile', extensions, '-out', temporary / 'cert.pem')
                validate_pair(temporary, ca_cert, purpose, ip)
                for path in files:
                    # Replace only after validation. Running containers must be recreated on renewal.
                    staged = temporary / path.name
                    staged.chmod(0o444)
                    os.replace(staged, path)
        validate_pair(destination, ca_cert, purpose, ip)
        if (destination / 'ca.pem').exists():
            (destination / 'ca.pem').chmod(0o600)
        shutil.copyfile(ca_cert, destination / 'ca.pem')
        (destination / 'ca.pem').chmod(0o444)
    print('mTLS identities verified. Private keys were not printed.')
    print('CA: runtime/pki/ca (keep on monitoring only; never mount its private key)')
    print('Prometheus client: runtime/pki/prometheus (keep on monitoring only)')
    print('App Node Exporter: runtime/pki/app-node (transfer securely to application EC2)')
    if logs:
        print('Loki server: runtime/pki/loki (keep on monitoring only)')
        print('Alloy client: runtime/pki/alloy (transfer securely to application EC2)')
        print('Grafana log client: runtime/pki/grafana-loki (keep on monitoring only)')


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-ip', default='172.31.45.83')
    parser.add_argument('--logs', action='store_true', help='Also prepare Loki and Alloy identities')
    parser.add_argument('--monitoring-ip', default='172.31.41.48')
    parser.add_argument('--renew', action='store_true', help='Replace selected leaf identities, preserving the CA')
    options = parser.parse_args()
    initialize(Path(__file__).resolve().parent / 'runtime', options.app_ip,
               options.renew, options.logs, options.monitoring_ip)
