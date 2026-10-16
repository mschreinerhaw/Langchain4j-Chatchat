-- CANDIDATES ONLY; NOT EXECUTED. Publish as version-appropriate, authorized MCP templates first.
-- Required binding: server-side asset ID, environment, schema allowlist, read-only role,
-- sample timestamp (UTC), execution timeout, row cap. Never let Coding Agent execute SQL.
-- STATUS/VARIABLES require server status visibility; InnoDB transaction/lock views may
-- require PROCESS or narrowly granted performance_schema access. Validate per MySQL version.

-- Candidate A: version and InnoDB status counters. Sample at least twice with actual interval.
SELECT UTC_TIMESTAMP(6) AS sampled_at_utc, VERSION() AS server_version;
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Innodb_buffer_pool_pages_total', 'Innodb_buffer_pool_pages_data',
  'Innodb_buffer_pool_pages_free', 'Innodb_buffer_pool_pages_dirty',
  'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads',
  'Innodb_data_reads', 'Innodb_data_writes', 'Innodb_data_fsyncs',
  'Innodb_os_log_written', 'Innodb_log_waits', 'Innodb_buffer_pool_wait_free',
  'Threads_connected', 'Threads_running', 'Innodb_row_lock_current_waits',
  'Innodb_row_lock_waits', 'Innodb_row_lock_time');
SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_page_size', 'innodb_buffer_pool_size');

-- Candidate B: table and index sizes. Bind :schema_name only from authorized asset metadata.
-- Supply the value through the template parameter binder; SQL below is illustrative syntax.
SELECT UTC_TIMESTAMP(6) AS sampled_at_utc, TABLE_SCHEMA, TABLE_NAME,
       DATA_LENGTH AS data_bytes, INDEX_LENGTH AS index_bytes,
       DATA_FREE AS free_bytes, TABLE_ROWS AS estimated_rows
FROM information_schema.TABLES
WHERE ENGINE = 'InnoDB' AND TABLE_SCHEMA = :schema_name
ORDER BY DATA_LENGTH + INDEX_LENGTH DESC
LIMIT :row_limit;

-- Candidate C: transaction overview; only if role and version permit.
SELECT UTC_TIMESTAMP(6) AS sampled_at_utc, trx_id, trx_state,
       trx_started, trx_wait_started, trx_rows_locked, trx_rows_modified
FROM information_schema.INNODB_TRX
ORDER BY trx_started
LIMIT :row_limit;

-- Version-specific lock wait and redo/checkpoint templates must be reviewed against
-- discovered server version and granted performance_schema/system status views.
-- Do not use SHOW ENGINE INNODB STATUS without explicit review of disclosure scope.
