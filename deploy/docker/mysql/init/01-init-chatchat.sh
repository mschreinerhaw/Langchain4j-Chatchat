#!/bin/bash
set -Eeuo pipefail

read_secret() {
  local name="$1" value
  value="$(tr -d '\r\n' <"/run/secrets/${name}")"
  if [[ ! "$value" =~ ^[A-Za-z0-9._~!@#%+=-]{32,128}$ ]]; then
    echo "${name} must be 32-128 URL-safe characters" >&2
    exit 1
  fi
  printf '%s' "$value"
}

api_password="$(read_secret mysql_api_password)"
mcp_password="$(read_secret mysql_mcp_password)"
news_password="$(read_secret mysql_news_password)"
redis_node="${CHATCHAT_REDIS_NODE:-redis:6379}"
if [[ ! "$redis_node" =~ ^[A-Za-z0-9._-]+:[0-9]{1,5}$ ]]; then
  echo "CHATCHAT_REDIS_NODE must use host:port syntax" >&2
  exit 1
fi

mysql_root=(mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}")

"${mysql_root[@]}" <<SQL
CREATE DATABASE IF NOT EXISTS live_runtime_api CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS live_runtime_mcp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS chatchat_news CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'chatchat_api'@'%' IDENTIFIED BY '${api_password}';
CREATE USER IF NOT EXISTS 'chatchat_mcp'@'%' IDENTIFIED BY '${mcp_password}';
CREATE USER IF NOT EXISTS 'chatchat_news'@'%' IDENTIFIED BY '${news_password}';
ALTER USER 'chatchat_api'@'%' IDENTIFIED BY '${api_password}';
ALTER USER 'chatchat_mcp'@'%' IDENTIFIED BY '${mcp_password}';
ALTER USER 'chatchat_news'@'%' IDENTIFIED BY '${news_password}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON live_runtime_api.* TO 'chatchat_api'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON live_runtime_mcp.* TO 'chatchat_mcp'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON chatchat_news.* TO 'chatchat_news'@'%';
FLUSH PRIVILEGES;
SQL

"${mysql_root[@]}" live_runtime_api </opt/chatchat-schema/chatchat-api.sql
"${mysql_root[@]}" live_runtime_mcp </opt/chatchat-schema/chatchat-mcp-server.sql
"${mysql_root[@]}" chatchat_news </opt/chatchat-schema/chatchat-runtime-news.sql

"${mysql_root[@]}" live_runtime_mcp <<SQL
INSERT INTO mcp_redis_cache_config
  (id, enabled, mode, nodes_json, master_name, database_index, username, password,
   sentinel_username, sentinel_password, ssl_enabled, timeout_millis, max_redirects, updated_at)
VALUES
  ('default', b'1', 'STANDALONE_AUTH', '["${redis_node}"]', '', 0, 'chatchat', 'file:/run/secrets/redis_password',
   '', '', b'0', 3000, 5, UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  enabled=VALUES(enabled), mode=VALUES(mode), nodes_json=VALUES(nodes_json),
  database_index=VALUES(database_index), username=VALUES(username), password=VALUES(password),
  ssl_enabled=VALUES(ssl_enabled), timeout_millis=VALUES(timeout_millis),
  max_redirects=VALUES(max_redirects), updated_at=VALUES(updated_at);
SQL

unset api_password mcp_password news_password redis_node MYSQL_ROOT_PASSWORD
