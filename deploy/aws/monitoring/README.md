# Dedicated monitoring EC2: incremental setup

For the architecture, rationale, and a fresh-install learning path, read the
[monitoring tutorial](TUTORIAL.md). This file remains the detailed incremental
runbook.

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

Loki and Alloy are opt-in through the `logs` profile; see Steps 7 and 8. Log
credentials are not needed to run the metrics-only stack. Grafana receives an
empty log data-source placeholder from `init.sh` until Step 8 provisions Loki.

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

## Step 6: Spring metrics on private port 8443

**Release prerequisite:** the backend image must contain `MonitoringConfiguration`.
An old image will ignore the new property and never open 8443. Do not enable
the overlay on the current production image until the new backend has been built
and released. The repository's production workflow publishes images from `main`;
committing this branch does not deploy it or update the production image.

The optional configuration is enabled only by `theeverthread.monitoring.enabled`.
It adds a TLS connector to embedded Tomcat on 8443, alongside the existing HTTP
8080 connector. This deliberately does not set `management.server.port`: moving
all Actuator endpoints would otherwise move the working health probes. Existing
HTTP health/readiness URLs and Docker healthchecks remain in place. The new
connector shares the Spring context but has a separate, higher-priority security
chain. Only GET `/actuator/prometheus` is authorized there; all other routes are
denied, even to the monitoring identity. HTTP 8080 still denies metrics.

Reuse `runtime/pki/app-node/cert.pem` and `key.pem` as Spring's server identity,
and `ca.pem` as the trusted client CA. Java loads that existing public PEM CA into
an in-memory trust store for Tomcat's JSSE implementation. No new CA, private key,
or certificate is generated. Tomcat requires client certificates at the TLS
handshake. Spring Security then accepts only certificate CN `theeverthread
prometheus` for metrics; sessions cannot grant access on this stateless connector.
Both the cryptographic trust check and the identity authorization must pass.

`backend.compose.yaml` overlays only the backend service of the existing
`/opt/theeverthread/compose.yaml`. It adds the certificate mounts, enables latency
histograms, and publishes **172.31.45.83:8443**, not the public interface. Paths in
the overlay are absolute because Compose resolves overlay-relative paths against
the base file. It does not alter frontend ports 80/443 or its TLS configuration.
Preserve the live base Compose and Nginx files: the running server's manually
added HTTPS configuration differs from the repository's older frontend setup.
Do not overwrite those live files as part of this monitoring activation.

After releasing the new backend image, copy this overlay to
`/home/ubuntu/monitoring/backend.compose.yaml` and the updated deploy script to
`/opt/theeverthread/deploy.sh`. Retain the app private-key directory protection.
Then, on application EC2, using its currently deployed image references:

```bash
sudo bash <<'SH'
set -euo pipefail
cd /opt/theeverthread
exec 9>deploy.lock
flock -n 9
docker compose --env-file secrets.env --env-file current.env \
  -f compose.yaml -f /home/ubuntu/monitoring/backend.compose.yaml config --quiet
docker compose --env-file secrets.env --env-file current.env \
  -f compose.yaml -f /home/ubuntu/monitoring/backend.compose.yaml \
  up -d --no-deps --wait --wait-timeout 300 backend
touch monitoring.enabled
SH
```

`current.env` must already refer to the new backend image. This recreates only the
backend and leaves the frontend image/container in place. The marker makes future
deployments include the overlay; create it only after successful activation.
The deploy script and overlay must remain installed on the app host. This is not
automatically deployed from the repository by the existing workflow.

On monitoring, test Spring with the same client identity that worked for Node
Exporter:

```bash
cd ~/monitoring
curl --fail --silent --show-error --connect-timeout 10 --max-time 20 \
  --cacert runtime/pki/prometheus/ca.pem \
  --cert runtime/pki/prometheus/cert.pem \
  --key runtime/pki/prometheus/key.pem \
  https://172.31.45.83:8443/actuator/prometheus -o /tmp/spring-metrics.txt
head -n 5 /tmp/spring-metrics.txt
```

Without the client certificate/key, TLS must fail. With the trusted identity,
`/actuator/metrics` and `/api/status` on port 8443 must be denied. Check the normal
site and HTTP health probes too. Only once the listener is ready, copy the updated
tracked monitoring configuration and restart Prometheus to load job `backend`.
Before activation this job will correctly report down.

Grafana Explore checks:

```promql
up{job="backend"}
jvm_memory_used_bytes{job="backend"}
sum(rate(http_server_requests_seconds_count{job="backend"}[5m]))
```

Expect `up=1`. Request-rate queries need a few minutes of samples and real
application traffic. The histogram is enabled through the overlay; Actuator
request URI series are excluded from ingestion. The backend connector is
disabled by default, including normal local development. Renewing the shared
application identity requires recreating both Node Exporter and backend to pick
up replaced certificate bind mounts. Restart backend to reload its Java trust
store if the CA changes; never silently replace that CA during leaf renewal.

## Step 7: private Loki log storage

This step prepares storage only. It does not install Alloy or send application
logs, and does not yet register Loki in Grafana. The synthetic verification log
is deliberately unrelated to application data.

`loki.yml` runs single-process Loki 3.7.10, with a TSDB v13 index, filesystem
storage in persistent `loki_data`, replication factor one, and a local ring.
The Compactor applies 72-hour retention asynchronously, with a two-hour deletion
delay. Retention is not an exact deletion deadline or a disk-size quota. Monitor
disk space; this installation has no replicated or off-host log storage.

The `logs` Compose profile makes installation explicit. Loki has a 512 MiB hard
memory limit, 384 MiB Go soft target, one concurrent query, no result cache, and
a 128 MB WAL replay budget. Initial ingestion is capped at 0.05 MB/s with a 1 MB
burst, 1,000 streams maximum, and 64 KB/s per-stream limit with a 256 KB burst.
Excess traffic can receive HTTP 429; these are initial small-server budgets,
not a guarantee against exhaustion or log loss. Measure before increasing them.

Native HTTP mTLS requires a CA-signed client certificate with TLS 1.2 or newer.
`auth_enabled: false` selects single tenancy; it does **not** bypass the TLS
client-certificate requirement. There is no tenant or per-route role separation:
clients trusted by this CA can use Loki's HTTP API. In particular, existing
Prometheus client credentials would also satisfy that trust. Protect all CA-signed
client keys accordingly. gRPC binds only to container loopback and is not published.

Only `172.31.41.48:3100` is published. Both the standard and internal Docker
networks are used: publishing a port from an internal-only bridge did not expose
the listener on this Docker installation. The monitoring security group allows
3100 only from the application security group; no public Loki access is needed.

After copying the updated tracked files to monitoring (see [SYNC.md](../SYNC.md)),
run **on monitoring EC2**, with the existing CA already present:

```bash
cd ~/monitoring
test -s runtime/pki/ca/cert.pem && test -s runtime/pki/ca/key.pem
python3 init-mtls.py --logs --app-ip 172.31.45.83 --monitoring-ip 172.31.41.48
sudo docker compose --profile logs config --quiet
sudo docker compose --profile logs run --rm --no-deps loki \
  -config.file=/etc/loki/loki.yml -verify-config=true
sudo docker compose --profile logs up -d --no-deps loki
python3 verify-loki.py
```

`--logs` adds a Loki server identity, an Alloy client identity and a separate
Grafana log-client identity under the
existing CA. It preserves the CA, application server identity and Prometheus
identity; no working credentials are renewed unless `--renew` is explicitly used.
The Loki certificate contains monitoring's private IP and Docker DNS name `loki`.
The Alloy and Grafana certificates are for client authentication. Leaves expire after 90 days.
The signing key stays on monitoring. The Alloy bundle is prepared there but
will be securely transferred to the application host in the collection step.

`verify-loki.py` waits for readiness, verifies unauthenticated requests fail,
verifies the client rejects an untrusted server, then writes and queries a
unique synthetic log. It requires only Python's standard library and prints
no credentials or application logs. Each run adds a small test log subject to
the same retention as other streams. It tests from monitoring; the application
to monitoring network path will be verified when Alloy is installed.

For subsequent Loki configuration changes, sync `loki.yml`, validate with the
`run ... -verify-config=true` command, then recreate only Loki. Keep using
`--profile logs` when you intend to manage the full stack with Loki included.
Its restart policy still applies across Docker/host restarts.

For certificate renewal with logging enabled, use `init-mtls.py --logs --renew`
with the correct IP arguments. This renews **all five** selected leaves, so update
every dependent service: recreate Prometheus, application Node Exporter, Spring,
and Loki, transfer/reload the Alloy identity, then regenerate the Grafana log
provisioning and recreate Grafana as described below. Automatic
renewal remains pending. Do not expose generated keys or copy the CA signing key
to the application host.

References: [native mTLS](https://grafana.com/docs/loki/latest/operations/authentication/),
[retention](https://grafana.com/docs/loki/latest/operations/storage/retention/),
and [filesystem storage limits](https://grafana.com/docs/loki/latest/operations/storage/filesystem/).

## Step 8: collect application logs and query them in Grafana

The application-side `alloy` service runs Alloy 1.22.0 in the existing collectors
project, alongside Node Exporter. Metrics continue to use direct Prometheus
scraping. Alloy is configured for **logs only**, using `alloy.alloy`:

- Discover Docker containers in Compose project `theeverthread-prod`, then keep
  services `backend` and `frontend`. Follow replacement containers after releases.
- Collect stdout/stderr through the Docker log API. Nginx's access/error files
  already point to stdout/stderr, so no Nginx configuration change is needed.
- Group timestamped Spring entries and stack traces, up to 128 lines and two
  seconds of waiting. Container entries older than one hour are dropped, limiting
  initial replay and delayed backlog on this small installation.
- Read persistent host journal files from `/var/log/journal`, starting with up
  to one hour of recent entries. Units become a `unit` label; process IDs and
  request identifiers do not become indexed labels.
- Send batches up to 128 KiB, every two seconds, to
  `https://172.31.41.48:3100/loki/api/v1/push` using the Alloy client certificate.
  Normal server CA/IP verification remains enabled. HTTP 429 and transient
  failures are retried with one-to-30-second backoff, at most 20 retries.

Alloy has a 192 MiB hard limit and 128 MiB Go soft target. Persistent
`alloy_data` holds source positions under `/var/lib/theeverthread-alloy`.
It is created without copying image-owned directory permissions. This avoids
requiring extra Linux capabilities for runtime writes. There is no persistent
outbound WAL: saved read positions do not make queued batches durable. Long
outages, crashes, exhausted retries, Docker log rotation, and the one-hour age
window can lose entries. This is a small best-effort operational log setup.

The container runs as root to read the host journal and Docker socket, drops all
capabilities and uses `no-new-privileges`. The read-only socket mount does **not**
limit Docker API operations: treat Alloy as a trusted host-level component.
No Alloy port is published; its diagnostic listener binds to container loopback.
No additional security-group rule is needed beyond application egress and
monitoring ingress for private TCP 3100.

### Prepare and transfer identities

First sync tracked files using [SYNC.md](../SYNC.md), including `alloy.alloy` to
application and the complete Grafana provisioning directory to monitoring.
On **monitoring EC2**:

```bash
cd ~/monitoring
python3 init-mtls.py --logs --app-ip 172.31.45.83 --monitoring-ip 172.31.41.48
bash init.sh
python3 init-loki-grafana.py
```

Rerunning without `--renew` preserves existing keys. Securely transfer only
`runtime/pki/alloy/{ca,cert,key}.pem` to the matching path on application EC2.
Here is a complete transfer block to run **on your Mac**, independently of your
current directory. Its temporary local directory is deleted when the script exits:

```bash
bash <<'LOCAL'
set -euo pipefail
alloy_transfer_dir=$(mktemp -d)
trap 'rm -rf "$alloy_transfer_dir"' EXIT
scp -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -i /Users/samehadel/.ssh/monitoring_ec2_keypair.pem -r \
  ubuntu@3.124.6.143:monitoring/runtime/pki/alloy "$alloy_transfer_dir/"
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem \
  ubuntu@3.69.199.35 \
  'umask 077; mkdir -p ~/monitoring/runtime/pki/alloy; chmod 700 ~/monitoring/runtime ~/monitoring/runtime/pki ~/monitoring/runtime/pki/alloy'
scp -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem \
  "$alloy_transfer_dir/alloy/ca.pem" "$alloy_transfer_dir/alloy/cert.pem" \
  "$alloy_transfer_dir/alloy/key.pem" \
  ubuntu@3.69.199.35:monitoring/runtime/pki/alloy/
LOCAL
```

Do not transfer the CA signing key, Loki server key or Grafana client key.
SSH host keys must already be verified in your local known-hosts file. For
rotation, temporarily make existing target PEM files owner-writable (`chmod 600`)
before transferring replacements, then restore `chmod 444` below and recreate
Alloy. File replacement must not be attempted by deleting the CA or its trust.

### Start collection on application EC2

```bash
cd ~/monitoring
chmod 444 runtime/pki/alloy/*.pem
openssl verify -CAfile runtime/pki/alloy/ca.pem -purpose sslclient runtime/pki/alloy/cert.pem
sudo docker compose -f app-node.compose.yaml --profile logs config --quiet
sudo docker run --rm --network none --memory 192m \
  -v "$PWD/alloy.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.22.0 validate /etc/alloy/config.alloy
sudo docker compose -f app-node.compose.yaml --profile logs up -d --no-deps alloy
sudo docker compose -f app-node.compose.yaml logs --tail 30 alloy
```

The application and Node Exporter are not recreated. On an older attempted
installation whose Alloy state volume copied image-owned permissions, normalize
that **specific volume root** once before starting the updated service:

```bash
sudo docker run --rm --network none --user 0:0 --cap-drop ALL --cap-add CHOWN \
  --entrypoint /bin/chown -v theeverthread-app-collectors_alloy_data:/data \
  grafana/alloy:v1.22.0 0:0 /data
```

Fresh installs using `nocopy: true` do not need this migration. Do not recursively
change permissions on application data or host journal directories.

### Load Grafana's log data source on monitoring EC2

```bash
cd ~/monitoring
bash init.sh
python3 init-loki-grafana.py
sudo docker compose --profile logs config --quiet
sudo docker compose --profile logs up -d --no-deps --force-recreate \
  --wait --wait-timeout 180 grafana
python3 verify-logs.py
```

The versioned template is `grafana/loki-datasource.json`. The helper combines it
with `runtime/pki/grafana-loki` into ignored `runtime/grafana-loki.yaml`, protected
by a 0700 parent and mounted read-only as a single file. Its empty tracked target
under `grafana/provisioning/datasources/loki.yaml` is required because the parent
provisioning directory is also a read-only bind mount. The generated file contains
the client private key; never copy it into tracked provisioning or Git.

Grafana connects internally to `https://loki:3100`, verifies its DNS SAN and uses
its own CA-signed client identity. Prometheus remains the default data source.
`verify-logs.py` authenticates to loopback Grafana as admin using the existing
bootstrap password file, checks Loki's data-source health, and queries one recent
entry from each source without printing log contents. If the Grafana password
has changed, provide its protected file through `GRAFANA_PASSWORD_FILE`; do not
put the password itself in commands or Git.

A quiet source may need activity before verification. Request the local frontend
or write a harmless `logger -t monitoring-smoke 'Log collection verification'`
entry on application EC2, wait a few seconds, then rerun the check.

In **Grafana Explore**, choose **Loki** and try:

```logql
{host="application",service_name="backend"}
{host="application",service_name="frontend"}
{host="application",job="host-journal"}
{host="application",service_name="backend"} |~ "(?i)error|exception"
```

Logs retain their original message contents, including access-log URLs and
addresses; no general redaction is configured. They remain behind the existing
private Grafana access and Loki mTLS with 72-hour asynchronous retention.

After changing Alloy configuration, validate it and recreate only Alloy; keep
the state volume. On certificate renewal, transfer and reload Alloy's new bundle,
regenerate Grafana's secret provisioning, and recreate Grafana in addition to the
other telemetry services. Future app releases are discovered by container labels,
so log collection does not require changing `/opt/theeverthread/deploy.sh`.

References: [Docker log collection](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.docker/),
[journal collection](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.journal/),
and [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).
