-- Legacy values have no explicit-choice marker and inherit the current platform default.
SET @workspace_preference_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'workspace_user_preference'
            AND column_name = 'default_workspace_explicit'),
    'SELECT 1',
    'ALTER TABLE workspace_user_preference ADD COLUMN default_workspace_explicit BOOLEAN');
PREPARE workspace_preference_stmt FROM @workspace_preference_ddl;
EXECUTE workspace_preference_stmt;
DEALLOCATE PREPARE workspace_preference_stmt;
