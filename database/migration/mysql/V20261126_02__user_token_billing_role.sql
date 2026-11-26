SET @user_token_billing_role_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'token_billing_role_id'),
    'SELECT 1',
    'ALTER TABLE sys_user ADD COLUMN token_billing_role_id VARCHAR(64) NULL'
);
PREPARE user_token_billing_role_stmt FROM @user_token_billing_role_ddl;
EXECUTE user_token_billing_role_stmt;
DEALLOCATE PREPARE user_token_billing_role_stmt;
