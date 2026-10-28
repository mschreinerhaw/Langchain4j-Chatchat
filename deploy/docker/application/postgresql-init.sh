#!/usr/bin/env bash
# Official postgres image runs this only for an empty PGDATA. No upgrades or business seeds.
(
set -euo pipefail
RUNTIME_API_PASSWORD=$(cat /run/secrets/postgresql_api_password) || exit 1
RUNTIME_MCP_PASSWORD=$(cat /run/secrets/postgresql_mcp_password) || exit 1
RUNTIME_NEWS_PASSWORD=$(cat /run/secrets/postgresql_news_password) || exit 1
if [[ -z "$RUNTIME_API_PASSWORD" || -z "$RUNTIME_MCP_PASSWORD" || -z "$RUNTIME_NEWS_PASSWORD" ]]; then
  echo 'PostgreSQL application secrets must not be empty' >&2
  exit 1
fi
export RUNTIME_API_PASSWORD RUNTIME_MCP_PASSWORD RUNTIME_NEWS_PASSWORD
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv api_password RUNTIME_API_PASSWORD
\getenv mcp_password RUNTIME_MCP_PASSWORD
\getenv news_password RUNTIME_NEWS_PASSWORD
CREATE ROLE chatchat_api LOGIN PASSWORD :'api_password';
CREATE ROLE chatchat_mcp LOGIN PASSWORD :'mcp_password';
CREATE ROLE chatchat_news LOGIN PASSWORD :'news_password';
CREATE DATABASE live_runtime_api OWNER chatchat_api;
CREATE DATABASE live_runtime_mcp OWNER chatchat_mcp;
CREATE DATABASE chatchat_news OWNER chatchat_news;
REVOKE CONNECT ON DATABASE live_runtime_api FROM PUBLIC;
REVOKE CONNECT ON DATABASE live_runtime_mcp FROM PUBLIC;
REVOKE CONNECT ON DATABASE chatchat_news FROM PUBLIC;
SQL
unset RUNTIME_API_PASSWORD RUNTIME_MCP_PASSWORD RUNTIME_NEWS_PASSWORD
psql -v ON_ERROR_STOP=1 --username chatchat_api --dbname live_runtime_api -f /opt/runtime-schema/chatchat-api.sql
psql -v ON_ERROR_STOP=1 --username chatchat_api --dbname live_runtime_api -f /opt/runtime-schema/chatchat-api-post-schema.sql
psql -v ON_ERROR_STOP=1 --username chatchat_mcp --dbname live_runtime_mcp -f /opt/runtime-schema/chatchat-mcp-server.sql
psql -v ON_ERROR_STOP=1 --username chatchat_news --dbname chatchat_news -f /opt/runtime-schema/chatchat-runtime-news.sql
touch "$PGDATA/.runtime-schema-initialized"
)
