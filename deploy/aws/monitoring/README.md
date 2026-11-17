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

This is an incremental runbook. The current Compose version includes the app
server mTLS scrape: a fresh install must generate the Step 5a identities before
starting the monitoring Compose stack, even if reading from Step 2.

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

## Step 4: check public HTTPS uptime

`blackbox.yml` defines `https_2xx`: a 10-second HTTP probe requiring a successful
2xx response over TLS with normal certificate validation. Redirects are followed,
and a final non-HTTPS response fails. It prefers IPv4, with IPv6 fallback.
Blackbox Exporter 0.29.1 runs on the monitoring EC2 with a 64 MiB memory limit.
It joins the default network for outbound requests and `telemetry` for Prometheus
access; port 9115 is not published. No additional inbound security-group rule is
needed. Outbound HTTPS must reach the public application URL.

Prometheus job `public-uptime` invokes the probe every 30 seconds with a 15-second
scrape timeout. The confirmed target is `https://theeverthread.com/`, recorded in
`prometheus.yml`. Relabeling passes this URL to Blackbox as `target`, preserves it
as `instance`, then directs the scrape to `blackbox:9115`. Job `blackbox` collects
the exporter's own process metrics separately.

Repeat the Step 2 rsync command from your computer. On the monitoring EC2:

```bash
cd ~/monitoring
sudo docker compose config --quiet
sudo docker compose up -d blackbox
sudo docker compose restart prometheus
sudo docker compose ps
```

After 30–60 seconds, use Grafana Explore:

```promql
up{job="public-uptime"}
probe_success{job="public-uptime"}
probe_duration_seconds{job="public-uptime"}
```

`up=1` means Prometheus received the probe report. `probe_success=1` means the
website actually passed the HTTPS check; a failed website can still have `up=1`.
Duration is the total probe time in seconds, including network connection work.
To inspect an unsuccessful probe on EC2:

```bash
sudo docker compose exec prometheus wget -qO- 'http://blackbox:9115/probe?module=https_2xx&target=https%3A%2F%2Ftheeverthread.com%2F&debug=true'
```

This verifies the public root URL from the monitoring EC2's location. It does
not yet verify database readiness, browser journeys, or every user location.
No notification rules or channels are enabled.

## Step 5a: prepare cross-host mTLS identities

Confirmed private addresses: application `172.31.45.83`, monitoring
`172.31.41.48`. First prepare certificates; do not change the working Compose
stack yet. Subsequent steps will expose the application's Node Exporter on its
private IP port 9100 and add its HTTPS scrape to Prometheus.

Copy updated tracked files using the Step 2 rsync command. **On monitoring EC2**:

```bash
cd ~/monitoring
python3 init-mtls.py --app-ip 172.31.45.83
openssl x509 -in runtime/pki/app-node/cert.pem -noout -dates -ext subjectAltName
openssl verify -CAfile runtime/pki/ca/cert.pem -purpose sslserver \
  -verify_ip 172.31.45.83 runtime/pki/app-node/cert.pem
openssl verify -CAfile runtime/pki/ca/cert.pem -purpose sslclient \
  runtime/pki/prometheus/cert.pem
```

The Python script uses the system OpenSSL binary, with no pip dependencies.
It creates three protected directories under ignored `runtime/pki/`:

| Directory | Role | Destination |
| --- | --- | --- |
| `ca/` | Signs and verifies monitoring identities | Keep on monitoring; never mount or transfer its private key |
| `prometheus/` | Client certificate, key and public CA certificate | Keep on monitoring; future Prometheus mount |
| `app-node/` | Server certificate, key and public CA certificate | Transfer only this directory to application EC2 in the next step |

The app certificate identifies `172.31.45.83` and allows server authentication
only. Prometheus's certificate allows client authentication only. Each leaf
certificate expires after 90 days; the private CA lasts 10 years. CA key mode is
0600. Container credential files are 0444 inside host directories of mode 0700,
so Docker can mount individual files for non-root services without making the
host directory accessible to other users. The operator who generates them can
read them. No credentials are printed by the script.

For this small installation, both identity keypairs are initially generated on
monitoring; its operator therefore also has access to the application exporter
key. The app bundle must later be transferred through authenticated SSH, never
through Git, chat or a public download. Back up the CA privately.

Rerunning preserves credentials and verifies trust, key matching, server IP,
certificate purpose and at least 30 days remaining validity. A changed app IP or
expiring certificate causes an error. Run with `--renew` to replace both leaf
identities while retaining the CA. Transfer the renewed app bundle and recreate
affected containers after renewal, because single-file mounts can retain old
inodes. Do not delete the CA to renew a leaf certificate. Automatic renewal is
not implemented yet; complete renewal scheduling before depending on unattended
operation. Existing connections are not immediately revoked by certificate
expiry, and replacing a leaf does not revoke the old certificate; compromise
requires a separate trust-replacement procedure. A failed run may leave partial
files; inspect errors rather than deleting or silently replacing trust.

This checkpoint creates and verifies credentials only. No new ports are opened,
services started, or application containers changed.

## Step 5b: start application Node Exporter

After transferring `runtime/pki/app-node/{ca,cert,key}.pem` to the application
EC2's `~/monitoring/` directory, copy only `app-node.compose.yaml` and
`node-web.yml` from this directory into that directory on the application host.
Do not transfer monitoring's CA private key or Prometheus client key.

On **application EC2**:

```bash
cd ~/monitoring
chmod 700 runtime runtime/pki runtime/pki/app-node
chmod 444 runtime/pki/app-node/*.pem
sudo docker compose -f app-node.compose.yaml config --quiet
sudo docker compose -f app-node.compose.yaml up -d
sudo docker compose -f app-node.compose.yaml ps
sudo docker compose -f app-node.compose.yaml logs --tail 30 app-node
```

`app-node.compose.yaml` is a separate project from both monitoring and the
application deployment. Always pass its `-f` argument on the app host. It does
not recreate backend/frontend containers. Docker host networking and the host
PID namespace give the exporter access to host network/process measurements;
read-only `/proc`, `/sys`, and root mounts provide CPU, memory and filesystem
measurements. Capabilities are dropped and memory is limited to 96 MiB.

The listener is exactly `172.31.45.83:9100`, not the public interface. There is
no Docker bridge port forwarding. The app security group must allow TCP 9100
only from the monitoring security group. Outbound access from monitoring must
also permit the connection.

`node-web.yml` requires TLS 1.2 or newer and a client certificate signed by the
monitoring CA. The server uses its own certificate/key. HTTPS without a client
certificate is rejected; this is intentional. No healthcheck is configured
because a normal unauthenticated HTTP check cannot authenticate. Process `Up`
is not proof of successful metric collection.

## Step 5c: connect monitoring Prometheus

Copy the updated tracked configuration to monitoring using the Step 2 rsync
command, excluding `runtime/` so existing credentials are preserved. Confirm
`runtime/pki/prometheus/{ca,cert,key}.pem` still exists there. Then on monitoring:

```bash
cd ~/monitoring
sudo docker compose config --quiet
sudo docker compose up -d --no-deps --force-recreate --wait prometheus
```

Recreation is required to add its certificate mounts and outbound network. It
preserves the metrics volume. Prometheus now joins the default Docker network
for outbound private-IP access as well as internal `telemetry`; it still has no
published host port. `prometheus.yml` defines the HTTPS `application-server` job
with the CA, client certificate and key. Server certificate/IP verification is
enabled. Never set `insecure_skip_verify` to bypass errors.

From monitoring, test the actual connection before relying on the dashboard:

```bash
curl --fail --silent --show-error --connect-timeout 10 --max-time 20 \
  --cacert runtime/pki/prometheus/ca.pem \
  --cert runtime/pki/prometheus/cert.pem \
  --key runtime/pki/prometheus/key.pem \
  https://172.31.45.83:9100/metrics -o /tmp/app-node-metrics.txt
head -n 5 /tmp/app-node-metrics.txt
```

Repeat without `--cert` and `--key`: TLS must fail. A timeout indicates a
connectivity/listener issue; an explicit certificate error indicates trust,
expiry, IP matching or missing identity. Do not open access to the internet to
work around either failure.

After 30–60 seconds, Grafana Explore should return `1` for:

```promql
up{job="application-server"}
```

Verify host memory and root disk too:

```promql
node_memory_MemTotal_bytes{job="application-server"} / 1024 / 1024 / 1024
node_filesystem_avail_bytes{job="application-server",mountpoint="/",fstype!="rootfs"} / 1024 / 1024 / 1024
```

Compare to `free -h` and `df -h /` on the application EC2. Application JVM/HTTP
metrics and log delivery are separate upcoming steps.
