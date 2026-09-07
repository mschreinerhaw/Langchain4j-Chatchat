#!/usr/bin/env bash
set -Eeuo pipefail

# ChatChat MySQL backup/migration utility.
#
# Commands:
#   inventory [output.tsv]       Show the live table count and estimated data size.
#   backup [output-directory]    Create a checksummed, compressed logical backup.
#   restore <backup-directory>   Restore it to another MySQL instance.
#
# Connection variables (password values are never placed on the command line):
#   SRC_MYSQL_HOST, SRC_MYSQL_PORT, SRC_MYSQL_USER
#   SRC_MYSQL_PASSWORD or SRC_MYSQL_PASSWORD_FILE
#   DST_MYSQL_HOST, DST_MYSQL_PORT, DST_MYSQL_USER
#   DST_MYSQL_PASSWORD or DST_MYSQL_PASSWORD_FILE
#
# Optional:
#   CHATCHAT_MYSQL_DATABASES="live_runtime_api live_runtime_mcp chatchat_news"
#   CONFIRM_RESTORE=YES                 Required for restore.
#   ALLOW_NONEMPTY_TARGET=true          Required if a target schema already has tables.

readonly DEFAULT_DATABASES="live_runtime_api live_runtime_mcp chatchat_news"
readonly DATABASES_TEXT="${CHATCHAT_MYSQL_DATABASES:-$DEFAULT_DATABASES}"
read -r -a DATABASES <<<"$DATABASES_TEXT"

temp_files=()
GENERATED_DEFAULTS_FILE=""
cleanup() {
  local file
  for file in "${temp_files[@]:-}"; do
    [[ -n "$file" && -f "$file" ]] && rm -f -- "$file"
  done
}
trap cleanup EXIT

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

validate_databases() {
  ((${#DATABASES[@]} > 0)) || fail "no databases configured"
  local database
  for database in "${DATABASES[@]}"; do
    [[ "$database" =~ ^[A-Za-z0-9_]+$ ]] || fail "unsafe database name: $database"
  done
}

read_password() {
  local prefix="$1" password_var="${1}_MYSQL_PASSWORD" file_var="${1}_MYSQL_PASSWORD_FILE"
  local password="${!password_var:-}" password_file="${!file_var:-}"
  if [[ -n "$password_file" ]]; then
    [[ -r "$password_file" ]] || fail "$file_var is not readable: $password_file"
    password="$(tr -d '\r\n' <"$password_file")"
  fi
  [[ -n "$password" ]] || fail "set $password_var or $file_var"
  [[ "$password" != *$'\n'* && "$password" != *$'\r'* ]] || fail "$password_var must be one line"
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
  [[ "$port" =~ ^[0-9]{1,5}$ ]] || fail "$port_var must be a valid TCP port"
  local password defaults_file
  password="$(read_password "$prefix")"
  defaults_file="$(mktemp "${TMPDIR:-/tmp}/chatchat-mysql-${prefix,,}.XXXXXX.cnf")"
  temp_files+=("$defaults_file")
  chmod 600 "$defaults_file"
  {
    printf '[client]\n'
    printf 'protocol=tcp\n'
    printf 'host="%s"\n' "$(option_escape "$host")"
    printf 'port=%s\n' "$port"
    printf 'user="%s"\n' "$(option_escape "$user")"
    printf 'password="%s"\n' "$(option_escape "$password")"
    printf 'default-character-set=utf8mb4\n'
  } >"$defaults_file"
  GENERATED_DEFAULTS_FILE="$defaults_file"
}

sql_database_list() {
  local result="" database
  for database in "${DATABASES[@]}"; do
    [[ -n "$result" ]] && result+=","
    result+="'${database}'"
  done
  printf '%s' "$result"
}

assert_source_databases() {
  local defaults_file="$1" database found
  for database in "${DATABASES[@]}"; do
    found="$(mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names \
      -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${database}'")"
    [[ "$found" == "1" ]] || fail "source database does not exist: $database"
  done
}

write_inventory() {
  local defaults_file="$1" output="$2"
  mysql --defaults-extra-file="$defaults_file" --batch --raw \
    -e "SELECT table_schema AS database_name,
               table_name,
               COALESCE(table_rows,0) AS estimated_rows,
               COALESCE(data_length,0) AS data_bytes,
               COALESCE(index_length,0) AS index_bytes,
               COALESCE(data_length,0)+COALESCE(index_length,0) AS total_bytes
          FROM information_schema.tables
         WHERE table_type='BASE TABLE'
           AND table_schema IN ($(sql_database_list))
         ORDER BY table_schema, table_name" >"$output"
}

print_summary() {
  local defaults_file="$1"
  mysql --defaults-extra-file="$defaults_file" --table \
    -e "SELECT table_schema AS database_name,
               COUNT(*) AS tables,
               SUM(COALESCE(table_rows,0)) AS estimated_rows,
               SUM(COALESCE(data_length,0)+COALESCE(index_length,0)) AS total_bytes,
               ROUND(SUM(COALESCE(data_length,0)+COALESCE(index_length,0))/1024/1024,2) AS total_mib
          FROM information_schema.tables
         WHERE table_type='BASE TABLE'
           AND table_schema IN ($(sql_database_list))
         GROUP BY table_schema
         ORDER BY table_schema"
}

command_inventory() {
  local output="${1:-}" defaults_file
  make_defaults_file SRC
  defaults_file="$GENERATED_DEFAULTS_FILE"
  assert_source_databases "$defaults_file"
  print_summary "$defaults_file"
  if [[ -n "$output" ]]; then
    [[ ! -e "$output" ]] || fail "refusing to overwrite inventory: $output"
    write_inventory "$defaults_file" "$output"
    printf 'Inventory written to %s\n' "$output"
  fi
}

command_backup() {
  local output_parent="${1:-./backups}" defaults_file timestamp backup_dir dump_file inventory_file
  make_defaults_file SRC
  defaults_file="$GENERATED_DEFAULTS_FILE"
  assert_source_databases "$defaults_file"
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_dir="${output_parent%/}/chatchat-mysql-${timestamp}"
  [[ ! -e "$backup_dir" ]] || fail "backup directory already exists: $backup_dir"
  mkdir -p "$backup_dir"
  dump_file="$backup_dir/chatchat-mysql.sql.gz"
  inventory_file="$backup_dir/inventory-before.tsv"

  printf 'Backing up: %s\n' "${DATABASES[*]}"
  printf 'For a strict cutover snapshot, stop API, MCP and News writers before this step.\n'
  write_inventory "$defaults_file" "$inventory_file"
  mysqldump --defaults-extra-file="$defaults_file" \
    --single-transaction --quick --routines --events --triggers --hex-blob \
    --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces \
    --databases "${DATABASES[@]}" | gzip -9 >"$dump_file"
  gzip -t "$dump_file"
  (cd "$backup_dir" && sha256sum chatchat-mysql.sql.gz >SHA256SUMS)
  {
    printf 'created_utc=%s\n' "$timestamp"
    printf 'databases=%s\n' "${DATABASES[*]}"
    printf 'source_host=%s\n' "${SRC_MYSQL_HOST:-127.0.0.1}"
    printf 'source_port=%s\n' "${SRC_MYSQL_PORT:-3306}"
    printf 'dump_bytes=%s\n' "$(wc -c <"$dump_file" | tr -d ' ')"
    printf 'mysql_client=%s\n' "$(mysql --version)"
    printf 'mysqldump_client=%s\n' "$(mysqldump --version)"
  } >"$backup_dir/manifest.txt"
  print_summary "$defaults_file"
  printf 'Backup completed: %s\n' "$backup_dir"
}

command_restore() {
  local backup_dir="${1:-}" defaults_file dump_file nonempty post_inventory
  [[ -n "$backup_dir" ]] || fail "restore requires a backup directory"
  [[ -d "$backup_dir" ]] || fail "backup directory does not exist: $backup_dir"
  dump_file="$backup_dir/chatchat-mysql.sql.gz"
  [[ -f "$dump_file" && -f "$backup_dir/SHA256SUMS" ]] || fail "backup is incomplete: $backup_dir"
  [[ "${CONFIRM_RESTORE:-}" == "YES" ]] || fail "set CONFIRM_RESTORE=YES to authorize restore"
  (cd "$backup_dir" && sha256sum -c SHA256SUMS)
  gzip -t "$dump_file"

  make_defaults_file DST
  defaults_file="$GENERATED_DEFAULTS_FILE"
  nonempty="$(mysql --defaults-extra-file="$defaults_file" --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema IN ($(sql_database_list))")"
  if [[ "$nonempty" != "0" && "${ALLOW_NONEMPTY_TARGET:-false}" != "true" ]]; then
    fail "target contains $nonempty application tables; set ALLOW_NONEMPTY_TARGET=true only when replacement is intended"
  fi

  printf 'Restoring into %s:%s: %s\n' "${DST_MYSQL_HOST:-127.0.0.1}" "${DST_MYSQL_PORT:-3306}" "${DATABASES[*]}"
  gunzip -c "$dump_file" | mysql --defaults-extra-file="$defaults_file"
  post_inventory="$backup_dir/inventory-after-restore.tsv"
  write_inventory "$defaults_file" "$post_inventory"
  print_summary "$defaults_file"
  printf 'Restore completed. Post-restore inventory: %s\n' "$post_inventory"
}

main() {
  require_command mysql
  require_command mysqldump
  require_command gzip
  require_command sha256sum
  require_command mktemp
  validate_databases
  local command="${1:-}"
  shift || true
  case "$command" in
    inventory) command_inventory "$@" ;;
    backup) command_backup "$@" ;;
    restore) command_restore "$@" ;;
    *) fail "usage: $0 {inventory [output.tsv]|backup [output-directory]|restore <backup-directory>}" ;;
  esac
}

main "$@"
