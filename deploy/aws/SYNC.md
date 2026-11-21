# Sync versioned configuration to EC2

Run these commands on **your Mac**, from the repository root (the directory
containing `deploy/`). Copy each complete block. Do not paste a terminal prompt.
Use the checkout/branch containing the configuration you intend to install.

These commands copy configuration only. They do not build images, deploy the
application, restart containers, or enable the Spring monitoring listener.
The pipeline builds images and invokes the deployment script installed on EC2;
it does not sync these files for you.

## 1. Check your local directory

```bash
pwd
git branch --show-current
git status --short
ls deploy/aws/deploy.sh deploy/aws/monitoring/backend.compose.yaml
```

Both files must exist before continuing. Check local edits: rsync copies your
working files, including uncommitted changes.

## 2. Sync application EC2 files

This copies only the collector configuration and Spring overlay needed on the
application host. The deployment script is staged in your home directory, then
installed with root ownership under `/opt/theeverthread`. Installation uses the
same lock as deployments; if another deployment is running, wait and retry the
installation block.

```bash
ssh -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem \
  ubuntu@3.69.199.35 \
  'umask 077; mkdir -p ~/monitoring ~/deployment-sync; chmod 700 ~/monitoring ~/deployment-sync'

rsync -av \
  -e 'ssh -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem' \
  deploy/aws/monitoring/app-node.compose.yaml \
  deploy/aws/monitoring/node-web.yml \
  deploy/aws/monitoring/backend.compose.yaml \
  ubuntu@3.69.199.35:~/monitoring/

rsync -av \
  -e 'ssh -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem' \
  deploy/aws/deploy.sh \
  ubuntu@3.69.199.35:~/deployment-sync/deploy.sh
```

Install and verify the script (still run this block on your Mac):

```bash
ssh -i /Users/samehadel/.ssh/theeverthread-prod-i-0bfc9d538cefca896.pem \
  ubuntu@3.69.199.35 'sudo bash -s' <<'REMOTE'
set -euo pipefail
exec 9>/opt/theeverthread/deploy.lock
flock -n 9
bash -n /home/ubuntu/deployment-sync/deploy.sh
if [ -f /opt/theeverthread/deploy.sh ]; then
  cp -p /opt/theeverthread/deploy.sh /opt/theeverthread/deploy.sh.before-sync
fi
install -o root -g root -m 700 /home/ubuntu/deployment-sync/deploy.sh /opt/theeverthread/deploy.sh
cmp /home/ubuntu/deployment-sync/deploy.sh /opt/theeverthread/deploy.sh
echo 'Deployment script installed and verified.'
if [ -f /opt/theeverthread/monitoring.enabled ]; then
  echo 'Monitoring overlay is enabled for future deployments.'
else
  echo 'Monitoring overlay remains disabled.'
fi
REMOTE
```

The backup retains the script from the preceding sync only. This deliberately
leaves the live application `compose.yaml`, Nginx configuration, environment
files, certificates, image references, and marker file untouched. In particular,
the live HTTPS configuration differs from the repository's older base setup.

## 3. Sync monitoring EC2 files

This copies the versioned monitoring directory recursively, including Grafana
provisioning. Host-local `runtime/` and `.env` are excluded. There is no `--delete`,
so files existing only on the server are retained. Copying files does not apply
them to running services; Docker Compose recreation or a service reload is a
separate step.

```bash
ssh -i /Users/samehadel/.ssh/monitoring_ec2_keypair.pem \
  ubuntu@3.124.6.143 \
  'umask 077; mkdir -p ~/monitoring; chmod 700 ~/monitoring'

rsync -av --exclude 'runtime/' --exclude '.env' \
  -e 'ssh -i /Users/samehadel/.ssh/monitoring_ec2_keypair.pem' \
  deploy/aws/monitoring/ \
  ubuntu@3.124.6.143:~/monitoring/
```

Validate the copied monitoring Compose configuration without restarting it:

```bash
ssh -i /Users/samehadel/.ssh/monitoring_ec2_keypair.pem \
  ubuntu@3.124.6.143 \
  'cd ~/monitoring && sudo docker compose config --quiet'
```

## 4. Apply changes separately

For Spring, first deploy an image containing `MonitoringConfiguration`, then
follow [Step 6 of the monitoring guide](monitoring/README.md#step-6-spring-metrics-on-private-port-8443)
to activate the overlay and create `monitoring.enabled` after successful startup.
Do not create the marker as part of a routine file sync.

For monitoring services and the application Node Exporter, follow their matching
steps in [the monitoring guide](monitoring/README.md). That guide documents the
required recreate/reload commands and verification. A newly copied Spring scrape
configuration will report the backend as DOWN until the listener is activated.

These are the current EC2 public IPs and your local key paths. Update them here
if an instance's address or your key location changes. No private key contents,
passwords, or generated certificates belong in Git.
