#!/usr/bin/env python3
"""Prepare role-specific configuration and private HTTPS ingestion credentials."""

import argparse
import ipaddress
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
from urllib.parse import urlsplit


PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
))


def private_address(value: str) -> str:
    address = ipaddress.IPv4Address(value)
    if not any(address in network for network in PRIVATE_NETWORKS):
        raise ValueError("MONITORING_PRIVATE_IP must be an RFC1918 private IPv4 address")
    return str(address)


def https_origin(value: str) -> str:
    url = urlsplit(value)
    if (
        url.scheme != "https" or not url.hostname or url.username or url.password
        or url.path not in ("", "/") or url.query or url.fragment
        or any(character.isspace() or character in "'\"\\" for character in value)
    ):
        raise ValueError("PUBLIC_MONITOR_URL must be an HTTPS origin without credentials or a path")
    return value.rstrip("/")


def write_config(path: Path, content: str, mode: int = 0o444) -> None:
    if path.exists():
        path.chmod(0o600)
    path.write_text(content)
    path.chmod(mode)


def ensure_secret(path: Path) -> str:
    if not path.exists():
        with path.open("x") as output:
            output.write(secrets.token_hex(32))
    value = path.read_text()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError(f"Invalid credential file: {path.name}; expected 64 hexadecimal characters")
    path.chmod(0o444)
    return value


def openssl(*arguments: str) -> None:
    subprocess.run(["openssl", *map(str, arguments)], check=True, capture_output=True, text=True)


def certificates(runtime: Path, address: str, renew: bool) -> None:
    ca_key, ca_cert = runtime / "ca.key", runtime / "ca.crt"
    key, cert = runtime / "tls.key", runtime / "tls.crt"
    if ca_key.exists() != ca_cert.exists():
        raise ValueError("Incomplete CA files; restore the original pair before continuing")
    if not ca_key.exists():
        openssl("req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "3650",
                "-subj", "/CN=theeverthread monitoring CA", "-keyout", ca_key, "-out", ca_cert,
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        ca_key.chmod(0o600)
        ca_cert.chmod(0o444)
    if cert.exists() and key.exists() and not renew:
        openssl("verify", "-CAfile", ca_cert, "-verify_ip", address, cert)
        return
    with tempfile.TemporaryDirectory(dir=runtime) as temporary:
        csr, extensions = Path(temporary) / "server.csr", Path(temporary) / "server.ext"
        new_key, new_cert = Path(temporary) / "server.key", Path(temporary) / "server.crt"
        extensions.write_text(
            f"subjectAltName=IP:{address}\nextendedKeyUsage=serverAuth\n"
            "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
        )
        openssl("req", "-new", "-newkey", "rsa:3072", "-nodes",
                "-subj", "/CN=theeverthread monitoring", "-keyout", new_key, "-out", csr)
        openssl("x509", "-req", "-in", csr, "-CA", ca_cert, "-CAkey", ca_key,
                "-CAcreateserial", "-days", "397", "-out", new_cert, "-extfile", extensions)
        write_config(key, new_key.read_text())
        write_config(cert, new_cert.read_text())
    key.chmod(0o444)
    cert.chmod(0o444)


def initialize(directory: Path, role: str, address: str,
               public_url: str = "https://theeverthread.com", renew: bool = False) -> None:
    if role not in ("app", "monitoring"):
        raise ValueError("Role must be app or monitoring")
    address, public_url = private_address(address), https_origin(public_url)
    if renew and role != "monitoring":
        raise ValueError("Certificates are renewed only on the monitoring server")
    parent = directory / "runtime"
    parent.mkdir(mode=0o700, exist_ok=True)
    parent.chmod(0o700)
    runtime = parent / role
    runtime.mkdir(mode=0o700, exist_ok=True)
    runtime.chmod(0o700)
    if role == "monitoring":
        token = ensure_secret(runtime / "ingestion_token")
        ensure_secret(runtime / "grafana_password")
        certificates(runtime, address, renew)
        write_config(runtime / "ingestion.map", f'"Bearer {token}" 1;\n')
        template = (directory / "prometheus.yml.template").read_text()
        write_config(runtime / "prometheus.yml", template.replace("@PUBLIC_MONITOR_URL@", public_url))
        write_config(runtime / "host.env", f"MONITORING_PRIVATE_IP={address}\n")
    else:
        if not (runtime / "ingestion_token").is_file() or not (runtime / "ca.crt").is_file():
            raise ValueError("Install ingestion_token and ca.crt from the monitoring server into runtime/app first")
        ensure_secret(runtime / "ingestion_token")
        openssl("x509", "-in", runtime / "ca.crt", "-noout")
        (runtime / "ca.crt").chmod(0o444)
        ensure_secret(runtime / "prometheus_token")
        write_config(runtime / "host.env", f"MONITORING_INGEST_URL=https://{address}:9443\n")


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("app", "monitoring"))
    parser.add_argument("--renew-certificate", action="store_true")
    arguments = parser.parse_args()
    initialize(Path(__file__).resolve().parent, arguments.role,
               os.environ.get("MONITORING_PRIVATE_IP", ""),
               os.environ.get("PUBLIC_MONITOR_URL", "https://theeverthread.com"),
               arguments.renew_certificate)
    print(f"{arguments.role.capitalize()} configuration initialized; credentials were not printed.")
