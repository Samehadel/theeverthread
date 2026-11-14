# Dedicated monitoring EC2: incremental setup

All setup scripts, Docker Compose definitions, and service configuration belong
in this directory in Git. Keep generated certificates, private keys, passwords,
and host-specific state under ignored `runtime/`; keep container data in persistent
volumes. Never commit credentials.

The selected topology has Prometheus pull Spring and Node Exporter metrics,
Alloy ship application logs to Loki, Blackbox probe public HTTPS, and Grafana
query Prometheus and Loki. Monitoring runs on a separate EC2. Cross-host telemetry
will use mTLS. Those service definitions will be added in subsequent steps;
this first step prepares the host only.

## Step 1: prepare the monitoring host

Target: dedicated Ubuntu 24.04 EC2, approximately 2 GiB RAM and 50 GiB encrypted
gp3 storage. This is a constrained initial deployment: start with 7-day metric
retention, 3-day log retention, 30-second metric collection, and measure resource
usage before increasing load. Swap helps short spikes but cannot replace RAM.

From your local checkout, copy the versioned script to the monitoring server
(substitute your SSH key path and the instance's public IP):

```bash
scp -i /path/to/key.pem deploy/aws/monitoring/prepare-host.sh ubuntu@MONITORING_PUBLIC_IP:~/prepare-host.sh
ssh -i /path/to/key.pem ubuntu@MONITORING_PUBLIC_IP
sudo bash ~/prepare-host.sh
```

The script:

- Adds a private 2 GiB swap file and an `/etc/fstab` entry, unless active swap
  already exists. Existing swap is preserved. An inactive pre-existing file at
  `/swapfile-monitoring` requires manual inspection rather than being overwritten.
- Installs Docker Engine and the Compose plugin using Docker's official Ubuntu
  apt repository. Initial installation selects current stable packages; existing
  Docker/Compose versions are preserved on rerun. Conflicting distro packages
  cause a failure rather than automatic removal.
- Enables Docker at boot and prints installed versions, swap, RAM and disk usage.
  Use `sudo docker ...`; the script does not grant the login user Docker access.

It does not change security groups, install monitoring services, restart the
application, or publish any container ports. Run it only on the monitoring host.
Review errors before retrying; host setup is not transactional.

Verify after completion:

```bash
sudo docker run --rm hello-world
sudo docker compose version
sudo swapon --show
```

Expect a successful Docker test, a Compose version, and approximately 2 GiB of
swap (or the host's existing swap). Share those results before the next step.

Reference: [Docker Engine installation on Ubuntu](https://docs.docker.com/engine/install/ubuntu/).
