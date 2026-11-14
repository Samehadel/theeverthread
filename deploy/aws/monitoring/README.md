# Dedicated monitoring EC2: incremental setup

All setup scripts, Docker Compose definitions, and service configuration belong
in this directory in Git. Keep generated certificates, private keys, passwords,
and host-specific state under ignored `runtime/`; keep container data in persistent
volumes. Never commit credentials.

The selected topology has Prometheus pull Spring and Node Exporter metrics,
Alloy ship application logs to Loki, Blackbox probe public HTTPS, and Grafana
query Prometheus and Loki. Monitoring runs on a separate EC2. Cross-host telemetry
will use mTLS. Step 2 starts only Grafana and Prometheus; cross-host collection
and the remaining services will be added in subsequent steps.

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

## Step 2: start Prometheus and Grafana

Copy the tracked monitoring directory from your local checkout to EC2. Exclude
local `runtime/` and `.env` files; never overwrite the server's runtime directory.
For example, from the repository root on your computer:

```bash
rsync -av --exclude runtime/ --exclude .env \
  -e 'ssh -i /path/to/key.pem' deploy/aws/monitoring/ \
  ubuntu@MONITORING_PUBLIC_IP:~/monitoring/
```

On the monitoring EC2:

```bash
cd ~/monitoring
bash init.sh
sudo docker compose config --quiet
sudo docker compose up -d --wait --wait-timeout 180
sudo docker compose ps
curl -fsS http://127.0.0.1:3000/api/health
```

`compose.yaml` defines the two services, persistent named volumes and 512 MiB
memory limits per service. Go's soft memory target is 384 MiB; these limits are
initial budgets, not a guarantee against memory exhaustion under higher load.
Container logs rotate at 5 MiB with two files. Prometheus is pinned to 3.13.5 LTS
and Grafana to 13.3.0; upgrade image tags deliberately in Git.

`prometheus.yml` scrapes Prometheus itself every 30 seconds. It does not yet
collect application or host metrics. Retention is seven days or 5 GiB, whichever
is reached first, plus working-space overhead. Prometheus has no published port.
The `telemetry` Docker network is internal; Grafana also joins the default network
to support its loopback-published port.

`grafana/provisioning/datasources/prometheus.yaml` automatically registers the
Prometheus data source. Grafana has no anonymous access or public signup, and
notification alerting is disabled. `init.sh` creates a random bootstrap admin
password under ignored `runtime/`. Its file is readable by the non-root Grafana
container but protected on the host by its owner-only parent directory. Running
the script again preserves the password. Grafana stores users in its volume:
editing the bootstrap file later does not rotate an existing user's password.

From your computer, leave this tunnel running:

```bash
ssh -i /path/to/key.pem -N -o ExitOnForwardFailure=yes \
  -L 3000:127.0.0.1:3000 ubuntu@MONITORING_PUBLIC_IP
```

Open http://localhost:3000. Username: `admin`. Read the initial password **on EC2**
using `cat ~/monitoring/runtime/grafana_password`; do not send it in chat.
In Grafana Explore, select Prometheus and query `up`. Expect one healthy target,
`job="prometheus"`. Application metrics will arrive in a later step.

To inspect resource usage, run `sudo docker stats --no-stream`. To stop services
without deleting their data, run `sudo docker compose down`. Do not add `-v`
unless you intentionally want to delete the stored metrics and Grafana state.

## Step 3: monitor the monitoring EC2

The `monitoring-node` service runs Node Exporter 1.12.1 on the monitoring host.
It reads host `/proc`, `/sys`, and the root filesystem through read-only mounts,
with the host PID namespace, dropped capabilities, and a 96 MiB memory limit.
These mounts provide host CPU, memory, and filesystem measurements rather than
only the container's view. They expose host information to this trusted exporter;
it is not privileged and has no Docker socket.

It joins only the internal `telemetry` network and publishes no host port.
Prometheus scrapes `monitoring-node:9100` as job `monitoring-server` every 30
seconds. Both services are on this same EC2, so this hop uses internal HTTP;
no new security-group rule or cross-host certificate is needed for this step.
The separate application Node Exporter will use mTLS in a later step.

Repeat the Step 2 rsync command from your computer, preserving `runtime/`.
Then on EC2:

```bash
cd ~/monitoring
sudo docker compose config --quiet
sudo docker compose up -d monitoring-node
sudo docker compose restart prometheus
sudo docker compose ps
sudo docker stats --no-stream
```

Prometheus needs a restart to read its changed scrape configuration; stored
metrics remain in its persistent volume. Wait about 30–60 seconds. In Grafana
Explore, run each query:

```promql
up{job="monitoring-server"}
node_memory_MemTotal_bytes{job="monitoring-server"} / 1024 / 1024 / 1024
100 * (1 - node_memory_MemAvailable_bytes{job="monitoring-server"} / node_memory_MemTotal_bytes{job="monitoring-server"})
node_filesystem_avail_bytes{job="monitoring-server",mountpoint="/",fstype!="rootfs"} / 1024 / 1024 / 1024
```

Expect `up` to be 1, memory total roughly matching `free -h`, memory used as a
percentage, and root filesystem available space roughly matching `df -h /`.
Linux tools use GiB here despite Grafana queries returning unlabelled numbers.
Node Exporter itself has no container healthcheck; its `Up` status shows process
liveness, while the Prometheus `up` query verifies successful metric collection.
