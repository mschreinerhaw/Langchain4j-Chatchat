SET @resource_grant_effective_at_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'resource_grant' AND column_name = 'effective_at'),
    'SELECT 1',
    'ALTER TABLE resource_grant ADD COLUMN effective_at DATETIME(6) NULL'
);
PREPARE resource_grant_effective_at_stmt FROM @resource_grant_effective_at_ddl;
EXECUTE resource_grant_effective_at_stmt;
DEALLOCATE PREPARE resource_grant_effective_at_stmt;
