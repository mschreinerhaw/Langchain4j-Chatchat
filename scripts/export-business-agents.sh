#!/usr/bin/env bash
set -Eeuo pipefail

# Export/import a reusable ChatChat business package containing only:
#   live_runtime_mcp.mcp_business_category
#   live_runtime_api.skill_config
#   live_runtime_api.skill_config_version
#   live_runtime_api.agent_release
#
# It intentionally excludes users, tenants, role bindings, API tokens, conversations,
# task history, audit logs and credentials. The target schema must already exist.
#
# Commands:
#   export [package-parent-directory]
#   import <package-directory>
#
# Selection:
#   AGENT_IDS=agent_a,agent_b     # omit to export every maintained Agent
#
# Connection:
#   SRC_MYSQL_HOST/PORT/USER and SRC_MYSQL_PASSWORD[_FILE]
#   DST_MYSQL_HOST/PORT/USER and DST_MYSQL_PASSWORD[_FILE]
#   API_DATABASE=live_runtime_api
#   MCP_DATABASE=live_runtime_mcp
#
# Import authorization:
#   CONFIRM_IMPORT=YES
#   ALLOW_PACKAGE_MERGE=true      # required when target package tables contain data

readonly API_DATABASE="${API_DATABASE:-live_runtime_api}"
readonly MCP_DATABASE="${MCP_DATABASE:-live_runtime_mcp}"
temp_files=()
GENERATED_DEFAULTS_FILE=""

cleanup() {
  local file
  for file in "${temp_files[@]:-}"; do
    [[ -n "$file" && -f "$file" ]] && rm -f -- "$file"
  done
}
trap cleanup EXIT

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"; }

validate_name() {
  [[ "$1" =~ ^[A-Za-z0-9_]+$ ]] || fail "unsafe database name: $1"
}

read_password() {
  local prefix="$1" password_var="${1}_MYSQL_PASSWORD" file_var="${1}_MYSQL_PASSWORD_FILE"
  local password="${!password_var:-}" password_file="${!file_var:-}"
  if [[ -n "$password_file" ]]; then
    [[ -r "$password_file" ]] || fail "$file_var is not readable: $password_file"
    password="$(tr -d '\r\n' <"$password_file")"
  fi
  [[ -n "$password" ]] || fail "set $password_var or $file_var"
  printf '%s' "$password"
}

option_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "$value"
}

make_defaults_file() {
  local prefix="$1" host_var="${1}_MYSQL_HOST" port_var="${1}_MYSQL_PORT" user_var="${1}_MYSQL_USER"
  local host="${!host_var:-127.0.0.1}" port="${!port_var:-3306}" user="${!user_var:-root}"
  local password defaults_file
  [[ "$port" =~ ^[0-9]{1,5}$ ]] || fail "$port_var must be a valid TCP port"
  password="$(read_password "$prefix")"
  defaults_file="$(mktemp "${TMPDIR:-/tmp}/chatchat-package-${prefix,,}.XXXXXX.cnf")"
  temp_files+=("$defaults_file")
  chmod 600 "$defaults_file"
  {
    printf '[client]\nprotocol=tcp\n'
    printf 'host="%s"\n' "$(option_escape "$host")"
    printf 'port=%s\n' "$port"
    printf 'user="%s"\n' "$(option_escape "$user")"
    printf 'password="%s"\n' "$(option_escape "$password")"
    printf 'default-character-set=utf8mb4\n'
  } >"$defaults_file"
  GENERATED_DEFAULTS_FILE="$defaults_file"
}

require_table() {
  local defaults_file="$1" database="$2" table="$3" count
  count="$(mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${database}' AND table_name='${table}'")"
  [[ "$count" == "1" ]] || fail "required table is missing: ${database}.${table}"
}

build_agent_filter() {
  local raw="${AGENT_IDS:-}" id result="" separator=""
  if [[ -z "$raw" ]]; then
    AGENT_WHERE="1=1"
    return
  fi
  IFS=',' read -r -a requested <<<"$raw"
  ((${#requested[@]} > 0)) || fail "AGENT_IDS is empty"
  for id in "${requested[@]}"; do
    id="${id//[[:space:]]/}"
    [[ "$id" =~ ^[a-z0-9_-]{2,64}$ ]] || fail "invalid Agent id: $id"
    result+="${separator}'${id}'"
    separator=","
  done
  AGENT_WHERE="id IN (${result})"
}

dump_table() {
  local defaults_file="$1" database="$2" table="$3" where="$4"
  mysqldump --defaults-extra-file="$defaults_file" --single-transaction --quick \
    --no-create-info --skip-triggers --skip-lock-tables --compact --hex-blob \
    --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces --replace \
    "$database" "$table" --where="$where"
}

export_dependencies() {
  local defaults_file="$1" output="$2" where="$3"
  mysql --defaults-extra-file="$defaults_file" --batch --raw "$API_DATABASE" \
    -e "SELECT id AS agent_id, label, market_status, COALESCE(model_name,'') AS model_name,
               COALESCE(bound_mcp_service_ids_json,'[]') AS bound_mcp_service_ids,
               COALESCE(bound_mcp_tool_names_json,'[]') AS bound_mcp_tool_names,
               COALESCE(bound_document_ids_json,'[]') AS bound_document_ids,
               COALESCE(bound_document_tags_json,'[]') AS bound_document_tags,
               COALESCE(default_data_asset_json,'') AS default_data_asset
          FROM skill_config WHERE ${where} ORDER BY id" >"$output"
}

command_export() {
  local parent="${1:-./business-packages}" defaults_file timestamp package_dir category_dump agent_dump selected_count
  make_defaults_file SRC
  defaults_file="$GENERATED_DEFAULTS_FILE"
  require_table "$defaults_file" "$MCP_DATABASE" mcp_business_category
  require_table "$defaults_file" "$API_DATABASE" skill_config
  require_table "$defaults_file" "$API_DATABASE" skill_config_version
  require_table "$defaults_file" "$API_DATABASE" agent_release
  build_agent_filter
  selected_count="$(mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names "$API_DATABASE" \
    -e "SELECT COUNT(*) FROM skill_config WHERE ${AGENT_WHERE}")"
  [[ "$selected_count" != "0" ]] || fail "no Agent matched the selection"

  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  package_dir="${parent%/}/chatchat-business-agents-${timestamp}"
  [[ ! -e "$package_dir" ]] || fail "package directory already exists: $package_dir"
  mkdir -p "$package_dir"
  category_dump="$package_dir/mcp-business-categories.sql.gz"
  agent_dump="$package_dir/api-agents.sql.gz"

  dump_table "$defaults_file" "$MCP_DATABASE" mcp_business_category "1=1" | gzip -9 >"$category_dump"
  {
    dump_table "$defaults_file" "$API_DATABASE" skill_config "$AGENT_WHERE"
    dump_table "$defaults_file" "$API_DATABASE" skill_config_version "skill_id IN (SELECT id FROM skill_config WHERE ${AGENT_WHERE})"
    dump_table "$defaults_file" "$API_DATABASE" agent_release "agent_id IN (SELECT id FROM skill_config WHERE ${AGENT_WHERE})"
  } | gzip -9 >"$agent_dump"
  gzip -t "$category_dump"
  gzip -t "$agent_dump"
  export_dependencies "$defaults_file" "$package_dir/agent-dependencies.tsv" "$AGENT_WHERE"
  mysql --defaults-extra-file="$defaults_file" --batch --raw "$MCP_DATABASE" \
    -e "SELECT id,code,name,domain,enabled,sort_order FROM mcp_business_category ORDER BY sort_order,name" \
    >"$package_dir/business-categories.tsv"
  mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names "$API_DATABASE" \
    -e "SELECT id FROM skill_config WHERE ${AGENT_WHERE} ORDER BY id" >"$package_dir/agent-ids.txt"
  {
    printf 'contract=chatchat_business_agent_package_v1\n'
    printf 'created_utc=%s\n' "$timestamp"
    printf 'api_database=%s\n' "$API_DATABASE"
    printf 'mcp_database=%s\n' "$MCP_DATABASE"
    printf 'agent_count=%s\n' "$selected_count"
    printf 'category_count=%s\n' "$(mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names "$MCP_DATABASE" -e 'SELECT COUNT(*) FROM mcp_business_category')"
  } >"$package_dir/manifest.txt"
  (cd "$package_dir" && sha256sum *.gz *.tsv agent-ids.txt manifest.txt >SHA256SUMS)
  printf 'Business package completed: %s\n' "$package_dir"
  printf 'Review Agent dependencies before importing: %s\n' "$package_dir/agent-dependencies.tsv"
}

target_row_count() {
  local defaults_file="$1"
  mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names \
    -e "SELECT
          (SELECT COUNT(*) FROM ${MCP_DATABASE}.mcp_business_category) +
          (SELECT COUNT(*) FROM ${API_DATABASE}.skill_config) +
          (SELECT COUNT(*) FROM ${API_DATABASE}.skill_config_version) +
          (SELECT COUNT(*) FROM ${API_DATABASE}.agent_release)"
}

command_import() {
  local package_dir="${1:-}" defaults_file existing package_api package_mcp
  [[ -n "$package_dir" && -d "$package_dir" ]] || fail "import requires an existing package directory"
  [[ "${CONFIRM_IMPORT:-}" == "YES" ]] || fail "set CONFIRM_IMPORT=YES to authorize import"
  [[ -f "$package_dir/manifest.txt" && -f "$package_dir/SHA256SUMS" ]] || fail "package is incomplete"
  grep -q '^contract=chatchat_business_agent_package_v1$' "$package_dir/manifest.txt" || fail "unsupported package contract"
  package_api="$(sed -n 's/^api_database=//p' "$package_dir/manifest.txt")"
  package_mcp="$(sed -n 's/^mcp_database=//p' "$package_dir/manifest.txt")"
  [[ "$package_api" == "$API_DATABASE" && "$package_mcp" == "$MCP_DATABASE" ]] || \
    fail "package databases (${package_api}, ${package_mcp}) do not match target (${API_DATABASE}, ${MCP_DATABASE})"
  (cd "$package_dir" && sha256sum -c SHA256SUMS)

  make_defaults_file DST
  defaults_file="$GENERATED_DEFAULTS_FILE"
  require_table "$defaults_file" "$MCP_DATABASE" mcp_business_category
  require_table "$defaults_file" "$API_DATABASE" skill_config
  require_table "$defaults_file" "$API_DATABASE" skill_config_version
  require_table "$defaults_file" "$API_DATABASE" agent_release
  existing="$(target_row_count "$defaults_file")"
  if [[ "$existing" != "0" && "${ALLOW_PACKAGE_MERGE:-false}" != "true" ]]; then
    fail "target package tables contain $existing rows; use a fresh schema or explicitly set ALLOW_PACKAGE_MERGE=true"
  fi

  gunzip -c "$package_dir/mcp-business-categories.sql.gz" | mysql --defaults-extra-file="$defaults_file" "$MCP_DATABASE"
  gunzip -c "$package_dir/api-agents.sql.gz" | mysql --defaults-extra-file="$defaults_file" "$API_DATABASE"
  printf 'Import completed. Restart MCP and API so categories, Agent releases and tool bindings are refreshed.\n'
  printf 'Agent rows now present: '
  mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names "$API_DATABASE" \
    -e 'SELECT COUNT(*) FROM skill_config'
}

main() {
  require_command mysql
  require_command mysqldump
  require_command gzip
  require_command gunzip
  require_command sha256sum
  require_command mktemp
  validate_name "$API_DATABASE"
  validate_name "$MCP_DATABASE"
  local command="${1:-}"
  shift || true
  case "$command" in
    export) command_export "$@" ;;
    import) command_import "$@" ;;
    *) fail "usage: $0 {export [package-parent-directory]|import <package-directory>}" ;;
  esac
}

main "$@"
