#!/bin/bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 -h postgresql -U postgres -d postgres <<'SQL'
\getenv api_password API_DB_PASSWORD
\getenv mcp_password MCP_DB_PASSWORD
\getenv news_password NEWS_DB_PASSWORD
SELECT 'CREATE ROLE chatchat_api LOGIN' WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='chatchat_api')\gexec
SELECT 'CREATE ROLE chatchat_mcp LOGIN' WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='chatchat_mcp')\gexec
SELECT 'CREATE ROLE chatchat_news LOGIN' WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='chatchat_news')\gexec
ALTER ROLE chatchat_api PASSWORD :'api_password';
ALTER ROLE chatchat_mcp PASSWORD :'mcp_password';
ALTER ROLE chatchat_news PASSWORD :'news_password';
SELECT 'CREATE DATABASE live_runtime_api OWNER chatchat_api' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='live_runtime_api')\gexec
SELECT 'CREATE DATABASE live_runtime_mcp OWNER chatchat_mcp' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='live_runtime_mcp')\gexec
SELECT 'CREATE DATABASE chatchat_news OWNER chatchat_news' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='chatchat_news')\gexec
REVOKE CONNECT ON DATABASE live_runtime_api FROM PUBLIC;
REVOKE CONNECT ON DATABASE live_runtime_mcp FROM PUBLIC;
REVOKE CONNECT ON DATABASE chatchat_news FROM PUBLIC;
SQL
for item in 'api:live_runtime_api:chatchat-api.sql' 'mcp:live_runtime_mcp:chatchat-mcp-server.sql' 'news:chatchat_news:chatchat-runtime-news.sql'; do
 IFS=: read -r role database filename <<< "$item"
 marker=$(psql -At -h postgresql -U postgres -d "$database" -c "SELECT to_regclass('public.ops_installation_schema') IS NOT NULL")
 if [ "$marker" = t ]; then continue; fi
 export PGOPTIONS="-c role=chatchat_$role"
 args=(); if [ "$role" = api ]; then args=(-f /opt/ops-config/chatchat-api-post-schema.sql -f /opt/ops-config/runtime-semantic-policy.sql); fi
 if [ "$role" = mcp ]; then args=(-f /opt/ops-config/chatchat-mcp-extra-schema.sql -f /opt/runtime-config/mcp-initial-user.sql); fi
 psql --single-transaction -v ON_ERROR_STOP=1 -h postgresql -U postgres -d "$database" -f "/opt/ops-config/$filename" "${args[@]}" -c "CREATE TABLE ops_installation_schema(initialized_at timestamptz NOT NULL DEFAULT now()); INSERT INTO ops_installation_schema DEFAULT VALUES;"
 unset PGOPTIONS
done
printf 'Application schemas initialized transactionally.\n'
