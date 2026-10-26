#!/usr/bin/env bash
# Preserve existing security configuration. Bootstrap only the uninitialized index.
set -euo pipefail
cd /usr/share/opensearch
umask 077
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
password=$(tr -d '\r\n' </run/secrets/opensearch_admin_password)
[[ "$password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo 'Use a 32-128 character URL-safe OpenSearch secret' >&2; exit 1; }
printf 'user = "admin:%s"\n' "$password" >"$work/curl.conf"
status=$(curl --silent --output "$work/status.txt" --write-out '%{http_code}' \
  --config "$work/curl.conf" --cacert config/certs/root-ca.pem https://opensearch:9200)
if [[ "$status" == 503 ]]; then
  grep -qi 'OpenSearch Security not initialized' "$work/status.txt" || {
    echo 'OpenSearch is unavailable; refusing to overwrite existing security configuration' >&2; exit 1;
  }
  hash=$(plugins/opensearch-security/tools/hash.sh -p "$password" | grep '^[$]2' | tail -n 1)
  test -n "$hash"
  cp -R config/opensearch-security "$work/security"
  # Do not import the image's other demo users/passwords.
  printf '_meta:\n  type: internalusers\n  config_version: 2\nadmin:\n  hash: "%s"\n  reserved: true\n  backend_roles: [admin]\n' \
    "$hash" >"$work/security/internal_users.yml"
  plugins/opensearch-security/tools/securityadmin.sh -h opensearch -p 9200 \
    -cd "$work/security" -cacert config/certs/root-ca.pem \
    -cert config/certs/admin.pem -key config/certs/admin-key.pem -icl
elif [[ "$status" != 200 ]]; then
  echo 'OpenSearch authentication failed; existing security configuration was preserved' >&2
  exit 1
fi
unset password hash
curl --silent --show-error --fail --retry 10 --retry-delay 1 --retry-max-time 30 \
  --config "$work/curl.conf" --cacert config/certs/root-ca.pem \
  'https://opensearch:9200/_cluster/health?wait_for_status=yellow&timeout=30s' >"$work/health.json"
grep -Eq '"timed_out"[[:space:]]*:[[:space:]]*false' "$work/health.json"
echo 'OpenSearch authenticated health check passed'
