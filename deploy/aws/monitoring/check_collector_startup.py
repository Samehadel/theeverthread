#!/usr/bin/env python3
"""Check the collector graph without exposing ports or permitting network egress."""

from pathlib import Path
import subprocess
import time
import uuid


def check_startup(directory: Path) -> None:
    name = "theeverthread-alloy-check-" + uuid.uuid4().hex[:12]
    runtime = directory / "runtime/app"
    command = [
        "docker", "run", "--detach", "--pull=never", "--name", name,
        "--network", "none", "--env-file", str(runtime / "host.env"),
    ]
    mounts = {
        directory / "config.alloy": "/etc/alloy/config.alloy",
        runtime / "prometheus_token": "/run/secrets/prometheus_token",
        runtime / "ingestion_token": "/run/secrets/ingestion_token",
        runtime / "ca.crt": "/run/secrets/monitoring_ca",
    }
    for source, target in mounts.items():
        if not source.is_file():
            raise FileNotFoundError(f"Initialize the role fixtures first: {source.name}")
        command.extend(["--volume", f"{source}:{target}:ro"])
    command.extend([
        "grafana/alloy:v1.21.3", "run", "--disable-reporting",
        "--storage.path=/tmp/alloy", "--server.http.listen-addr=127.0.0.1:12345",
        "/etc/alloy/config.alloy",
    ])
    try:
        subprocess.run(command, check=True, capture_output=True, text=True)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = subprocess.check_output([
                "docker", "inspect", "--format", "{{.State.Running}}", name,
            ], text=True).strip()
            if state != "true":
                raise RuntimeError("Collector exited during initial graph evaluation")
            # Share the isolated loopback namespace. Neither container can reach
            # app/monitoring hosts or send generated telemetry outside this check.
            ready = subprocess.run([
                "docker", "run", "--rm", "--pull=never", "--network", f"container:{name}",
                "--entrypoint", "/bin/wget", "prom/prometheus:v3.13.5", "-qO-",
                "http://127.0.0.1:12345/-/ready",
            ], capture_output=True, text=True, timeout=10)
            if ready.returncode == 0:
                print("Collector graph started and is ready; no network egress permitted")
                return
            time.sleep(0.5)
        raise TimeoutError("Collector did not become ready within 30 seconds")
    except Exception:
        subprocess.run(["docker", "logs", "--tail", "40", name], check=False)
        raise
    finally:
        subprocess.run(["docker", "rm", "--force", "--volumes", name],
                       check=False, capture_output=True)


if __name__ == "__main__":
    check_startup(Path(__file__).resolve().parent)
