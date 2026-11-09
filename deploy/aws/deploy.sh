#!/bin/bash
set -euo pipefail
umask 077
role=${1:-}
action=${2:-apply}
if [[ "$role" != app && "$role" != analysis ]]; then echo 'Choose app or analysis' >&2; exit 2; fi
if [[ "$action" != apply && "$action" != rollback && "$action" != dry-run ]]; then exit 2; fi
release=$(cd -- "$(dirname -- "$0")" && pwd)
root=${KKOKKKOK_DEPLOY_ROOT:-/opt/kkokkkok}
if [[ "$action" == dry-run ]]; then
  echo "Prepared $role deployment: fetch role secrets, pull immutable images, verify health, preserve previous release. No AWS/Docker commands executed."
  exit 0
fi
if [[ "${KKOKKKOK_ENABLE_AWS_DEPLOY:-}" != 1 ]]; then echo 'Actual deployment is disabled' >&2; exit 2; fi
mkdir -p "$root/$role"
exec 9>"$root/deploy.lock"
flock --wait 10 9
current="$root/$role/current"
previous="$root/$role/previous"
compose() {
  local path=$1; shift
  docker compose --env-file "$path/release.env" -f "$path/$role.compose.yaml" "$@"
}
health() {
  local path=$1
  if [[ "$role" == app ]]; then
    local domain
    domain=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["domain"])' "$path/config.json")
    curl -fsS --retry 12 --retry-all-errors --retry-delay 5 --max-time 10 \
      --resolve "$domain:443:127.0.0.1" "https://$domain/actuator/health" >/dev/null 2>&1
  else
    curl -fsS --retry 6 --retry-all-errors --retry-delay 5 --max-time 5 http://127.0.0.1:3000/api/health >/dev/null 2>&1
  fi
}
activate() {
  compose "$1" up -d --wait --wait-timeout 180 >/dev/null 2>&1 && health "$1"
}
old=$(readlink "$current" || true)
if [[ "$action" == rollback ]]; then
  target=$(readlink "$previous" || true)
  if [[ -z "$target" ]]; then
    if [[ -n "$old" ]]; then compose "$old" down >/dev/null 2>&1; rm -f "$current"; fi
    echo 'No previous release; stopped the first deployment without deleting volumes.'
  elif activate "$target"; then ln -sfn "$target" "$current"; echo 'Previous release restored.'
  else echo 'Rollback failed; operator intervention required.' >&2; exit 1; fi
  exit 0
fi
python3 "$release/refresh_runtime.py" "$role"
# ECR login credentials are short-lived and kept outside the release bundle.
registry=$(python3 -c 'import os;print(os.environ["REGISTRY"])')
region=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["region"])' "$release/config.json")
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null 2>&1
compose "$release" pull >/dev/null 2>&1
if activate "$release"; then
  if [[ -n "$old" ]]; then ln -sfn "$old" "$previous"; else rm -f "$previous"; fi
  ln -sfn "$release" "$current"
  echo 'Deployment healthy.'
else
  if [[ -n "$old" ]] && activate "$old"; then echo 'New deployment failed; previous release restored.' >&2
  else compose "$release" down >/dev/null 2>&1 || true; echo 'New deployment failed; no healthy previous release. Volumes retained.' >&2; fi
  exit 1
fi
