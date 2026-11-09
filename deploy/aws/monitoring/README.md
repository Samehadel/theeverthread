# Dedicated monitoring EC2

The monitoring EC2 runs Grafana, Prometheus, Loki, an HTTPS ingestion gateway,
Blackbox Exporter, and its own Node Exporter. The application EC2 runs only Alloy
and Node Exporter alongside the app. Alloy scrapes the backend locally and pushes
metrics and application/server logs to the monitoring EC2 over authenticated
HTTPS. The app server exposes no new inbound ports.

The provisioned **theeverthread · Operations** dashboard covers public HTTPS
uptime, PostgreSQL readiness, HTTP traffic/5xx errors, p95 latency, JVM memory,
database connections, both servers' health, backend restarts, application logs,
Nginx logs, and the application host's system journal. No notification channels,
alert rules, or Alertmanager are installed. Browser JavaScript exceptions, full
tracing, Neon provider internals, and the monitoring host's journal are outside
this setup; its container logs remain available with the management script.

## Network and host prerequisites

Both hosts need rootful Docker Compose, Python 3, OpenSSL, and Bash on Ubuntu.
Place them in the same VPC, or provide equivalent private routing. Use the
monitoring EC2's primary **private IPv4 address** in the commands below. The
initializer rejects public, wildcard, loopback, and link-local bind addresses.
This configuration does not create EC2 instances, security groups, or DNS.

| Connection | Destination | Restriction |
| --- | --- | --- |
| App Alloy → monitoring gateway | Monitoring private IP, TCP 9443 | Monitoring security-group ingress from the **app EC2 security group only** |
| Monitoring → public app | HTTPS 443 | Required for website/readiness probes, DNS, and TLS checks |
| Operator → Grafana | Monitoring loopback 3000 | SSH or SSM port-forwarding session; no public port 3000 rule |
| Operator → host administration | Existing SSH/SSM access | Keep existing authorized access controls |

If egress is restricted, allow the app host to reach monitoring TCP 9443.
Prometheus, Loki, exporter ports, and Alloy's UI have no published host ports.
Only the TLS gateway binds the monitoring **private IP** on 9443. It accepts
metric/log writes with a dedicated ingestion token; it does not expose query or
administration APIs. Grafana retains its own separate credentials. The backend
metrics token is generated on the app host and never transferred to monitoring.

## 1. Prepare the monitoring EC2

Copy the tracked `monitoring/` files into `/opt/theeverthread/monitoring/` on this
host; exclude all generated `runtime/` state. The monitoring role does not need
application images, `current.env`, database credentials, or the base app Compose
file. Use a root-owned mode-0700 installation directory. From a root shell:

```bash
cd /opt/theeverthread
MONITORING_PRIVATE_IP=10.20.0.10 PUBLIC_MONITOR_URL=https://theeverthread.com \
  bash monitoring/manage.sh monitoring init
bash monitoring/manage.sh monitoring up
```

Replace the sample private IP with the new monitoring EC2's actual private IP.
`PUBLIC_MONITOR_URL` must be the actual public HTTPS origin without a path.
Initialization generates a private CA, an IP-specific server certificate, a
random ingestion token, and a separate Grafana password. Runtime directories are
0700. Mounted files are container-readable inside those protected directories;
the CA private key stays 0600 and is **never mounted into a container**.

## 2. Install trust on the app EC2

Transfer **only** these two files from the monitoring EC2 using an authenticated
administrative channel:

- `monitoring/runtime/monitoring/ca.crt`
- `monitoring/runtime/monitoring/ingestion_token`

Install them as `ca.crt` and `ingestion_token` in the app host's
`/opt/theeverthread/monitoring/runtime/app/` directory, with root ownership and
mode 0700 on `runtime/` and `runtime/app/`. Verify the CA fingerprint through your
trusted administrative session. Never transfer `ca.key`, `tls.key`, the Grafana
password, or the entire monitoring runtime directory.

Deploy backend/frontend images containing this change first; the old backend
denies metrics even with the new token. Copy the tracked `monitoring/` files,
updated `deploy.sh`, and base `compose.yaml` to the app EC2, preserving its
existing application secrets and image digest files. Then, from its root shell:

```bash
cd /opt/theeverthread
MONITORING_PRIVATE_IP=10.20.0.10 bash monitoring/manage.sh app init
bash monitoring/manage.sh app up
bash monitoring/manage.sh app check
```

Use the **monitoring** EC2's private IP here too. App initialization requires the
transferred trust files, generates a local-only backend metrics token, and records
the HTTPS destination. It never creates a replacement ingestion token or CA.
Alloy verifies the server's certificate against the transferred CA; certificate
verification is not disabled. For a non-default Docker data root, set
`DOCKER_LOG_ROOT` to its `containers` directory in the app's `secrets.env`.
Rootless Docker requires different log mounts and is not supported by this runbook.

App activation shares the application's deployment lock and briefly recreates
backend/frontend containers to mount the metrics token, enable HTTP histograms,
and attach Docker log labels. After success it creates
`runtime/app/enabled`. Updated `deploy.sh` then includes **only the collector
overlay** on future app releases. The monitoring host remains independent of app
releases and application health. CI does not copy server configuration files.

## 3. Verify delivery and open Grafana

After at least one minute, run on the **monitoring EC2**:

```bash
cd /opt/theeverthread
bash monitoring/manage.sh monitoring check
```

This validates configurations, checks certificate expiry, checks local scrape
and HTTPS probe targets, and requires fresh successful backend, app-server, and
Alloy metrics received through remote write. Remote-write sources do not appear
in Prometheus's local scrape-target list, so the separate received-metric check
is necessary. App-side `check` validates the collector configuration; it does not
prove delivery. Inspect the dashboard log panels too, including a recent API
request and a host service event. Alloy's own health alone cannot prove log delivery.

Forward Grafana from the **monitoring** host through an authorized SSH connection
(or an SSM port-forwarding session):

```bash
ssh -N -L 3000:127.0.0.1:3000 ubuntu@YOUR_MONITORING_SERVER
```

Open <http://127.0.0.1:3000>. Username: `admin`. Read the generated password on
that host with `sudo cat /opt/theeverthread/monitoring/runtime/monitoring/grafana_password`.
Grafana uses this password only when creating its database; subsequent password
changes belong in Grafana. Anonymous access and signup are disabled.

Use **Explore → Loki** for log searches:

```logql
{host="application",job="containers",project="theeverthread-prod",service="backend"} |= "ERROR"
{host="application",job="containers",service="frontend"} | json | status >= 500
{host="application",job="host-journal",unit="ssh.service"}
```

Backend stack traces are grouped across lines. Nginx access logs omit query
strings, IPs, headers, cookies, and bodies. Other logs can contain sensitive
operational details; restrict Grafana users. Alloy reads application Docker JSON
logs and persistent/volatile journals through read-only mounts, uses root to read
host-owned files with capabilities dropped, and has no Docker API socket. Only
backend/frontend container streams are collected, avoiding telemetry feedback
loops. If the Compose project name changes, update the dashboard's `project`
selectors. Previously rotated logs cannot be recovered; initial journal history
is limited by the collector's default age window.

## Operation, retention, and recovery

Prometheus retains at most 15 days or 2 GB of historical blocks; WAL/head add
overhead. Loki applies seven-day asynchronous retention without a hard size quota.
Named volumes on monitoring retain telemetry; Alloy's app-host volume retains
reading positions and the metrics write-ahead log. Pending log batches use memory
and retry delivery for a finite period; long ingestion outages or collector
restarts can lose unsent logs. The local Docker log rotation remains the fallback
source. Buffers do not replace backups. Back up monitoring volumes and protected runtime credentials separately.
The monitoring host has roughly 1.6 GiB of container memory limits, and app-side
collectors add roughly 480 MiB. Size the hosts and EBS storage for actual traffic.
The monitoring-host CPU/memory and filesystem panels help track its own capacity.

The new probe location can detect a complete **app EC2 outage** while Grafana and
historical data remain available. The backend status panel treats app telemetry
older than 120 seconds as down. API p95 needs real API requests and is undefined
without traffic; an empty 5xx panel can mean no error series exists. A monitoring
EC2 failure still makes this single monitoring stack unavailable, and shared VPC
or regional failures may affect both hosts. No notifications are sent.

Commands take the role explicitly:

```bash
bash monitoring/manage.sh app ps
bash monitoring/manage.sh app logs
bash monitoring/manage.sh monitoring logs
bash monitoring/manage.sh monitoring restart
```

After changing probe origins, rerun monitoring `init` with the desired
`PUBLIC_MONITOR_URL` and restart that role. App destination changes require
rerunning app `init` and using app `up` so Compose recreates Alloy with its new
environment. Initialization preserves credentials and the CA. For a certificate
renewal or monitoring private-IP change, on the monitoring host run:

```bash
MONITORING_PRIVATE_IP=10.20.0.10 \
  bash monitoring/manage.sh monitoring init --renew-certificate
bash monitoring/manage.sh monitoring restart
```

Renewal keeps the CA and ingestion token, so existing client trust remains valid.
If the IP changes, also run monitoring `up` to recreate the gateway's private-IP
port binding, then update the app destination. The server certificate lasts
397 days; monitoring `check` fails when fewer than 30 days remain. Keep the CA
private key only on monitoring and in its protected backup.

`manage.sh ROLE stop` preserves volumes. Stopping app collectors leaves their
enablement marker in place, so the next application deployment starts them again.
To disable persistently, stop app collectors, remove only `runtime/app/enabled`,
and use the next base application deployment to remove its token mount and
histogram setting. Monitoring can be stopped separately. Do not use `down -v` to
suspend either role. If an older single-host setup was installed, stop its old
monitoring services and preserve its volumes before switching roles; moving
historical telemetry to the new host requires a separate data migration.

Local checks:

```bash
python3 -B -m unittest discover -s deploy/aws/monitoring -p 'test_*.py'
bash -n deploy/aws/deploy.sh deploy/aws/monitoring/manage.sh
```

Architecture baseline: `851bb25cf3fd9c09724a3af74dc419479bbbc229`
(`theeverthread-architecture/main`), reverified 2026-11-09. The separate proposed
monitoring decision records this two-host design; unrelated uncommitted documents
were not treated as accepted decisions.
