#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p runtime
chmod 700 runtime
if [[ ! -e runtime/grafana_password ]]; then
    openssl rand -hex 32 > runtime/grafana_password
fi
[[ -s runtime/grafana_password ]] || { echo 'Grafana password file is empty.' >&2; exit 1; }
# Compose mounts the single file for Grafana's non-root UID. The parent directory
# remains private to its owner on the host; never make runtime/ world-readable.
chmod 444 runtime/grafana_password
# Keep metrics-only installs usable before log certificates are prepared.
# The log provisioning helper replaces this placeholder when logging is enabled.
if [[ ! -e runtime/grafana-loki.yaml ]]; then
    printf 'apiVersion: 1\ndatasources: []\n' > runtime/grafana-loki.yaml
fi
chmod 444 runtime/grafana-loki.yaml
echo 'Grafana bootstrap credential ready; existing password preserved.'
