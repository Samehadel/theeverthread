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
echo 'Grafana bootstrap credential ready; existing password preserved.'
