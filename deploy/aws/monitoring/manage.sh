#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
base_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
role=${1:-}
action=${2:-}
[[ "$role" == app || "$role" == monitoring ]] || {
  echo 'Usage: monitoring/manage.sh {app|monitoring} {init|up|check|ps|logs|restart|stop}' >&2
  exit 2
}
runtime_dir="$base_dir/monitoring/runtime/$role"

compose() {
  if [[ "$role" == app ]]; then
    docker compose --env-file "$base_dir/secrets.env" --env-file "$base_dir/current.env" \
      --env-file "$runtime_dir/host.env" \
      -f "$base_dir/compose.yaml" -f "$base_dir/monitoring/app.compose.yaml" "$@"
  else
    docker compose --env-file "$runtime_dir/host.env" \
      -f "$base_dir/monitoring/compose.yaml" "$@"
  fi
}

lock_deployment() {
  local lock_file="$runtime_dir/manage.lock"
  if [[ "$role" == app ]]; then lock_file="$base_dir/deploy.lock"; fi
  exec 9>"$lock_file"
  flock -n 9 || { echo 'Another deployment is running' >&2; exit 1; }
}

case "$action" in
  init)
    shift 2
    python3 "$base_dir/monitoring/init.py" "$role" "$@"
    ;;
  up)
    [[ -f "$runtime_dir/host.env" ]] || {
      echo "Run monitoring/manage.sh $role init first." >&2
      exit 1
    }
    lock_deployment
    compose config --quiet
    compose up -d --wait --wait-timeout 300
    if [[ "$role" == app ]]; then touch "$runtime_dir/enabled"; fi
    echo "$role monitoring started. Run monitoring/manage.sh monitoring check on the monitoring EC2 after one minute."
    ;;
  check)
    if [[ "$role" == app ]]; then
      compose exec -T alloy /bin/alloy validate /etc/alloy/config.alloy
      echo 'Collector configuration is valid. Check remote delivery from the monitoring EC2.'
      exit 0
    fi
    compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
    compose exec -T ingress nginx -t
    openssl x509 -in "$runtime_dir/tls.crt" -noout -checkend 2592000
    compose exec -T prometheus wget -qO- http://localhost:9090/api/v1/targets |
      python3 -c '
import json, sys
targets = json.load(sys.stdin)["data"]["activeTargets"]
for target in targets:
    print(target["labels"]["job"], target["labels"]["instance"], target["health"])
sys.exit(1 if any(target["health"] != "up" for target in targets) or len(targets) < 6 else 0)
'
    query_url=$(python3 -c '
from urllib.parse import urlencode
selector = "up{host=\"application\",job=~\"backend|server|alloy\"}"
query = "(" + selector + " == 1) and (timestamp(" + selector + ") > time() - 120)"
print("http://localhost:9090/api/v1/query?" + urlencode({"query": query}))
')
    compose exec -T prometheus wget -qO- "$query_url" |
      python3 -c '
import json, sys
results = json.load(sys.stdin)["data"]["result"]
jobs = {sample["metric"]["job"] for sample in results}
print("Fresh, healthy application collectors:", ", ".join(sorted(jobs)))
sys.exit(0 if jobs == {"backend", "server", "alloy"} else 1)
'
    curl --fail --silent --show-error http://127.0.0.1:3000/api/health
    ;;
  ps) compose ps ;;
  logs)
    if [[ "$role" == app ]]; then compose logs --tail 100 alloy node-exporter
    else compose logs --tail 100; fi
    ;;
  restart|stop)
    lock_deployment
    if [[ "$role" == app ]]; then compose "$action" alloy node-exporter
    else compose "$action"; fi
    ;;
  *)
    echo 'Usage: monitoring/manage.sh {app|monitoring} {init|up|check|ps|logs|restart|stop}' >&2
    exit 2
    ;;
esac
