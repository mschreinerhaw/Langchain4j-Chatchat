-- Additive migration. Existing local accounts, password hashes, login logs and business grants are preserved.
-- Built-in admin retains all console permissions. Other accounts start without console grants.
CREATE TABLE IF NOT EXISTS mcp_admin_menu_grant (
    id varchar(64) PRIMARY KEY,
    username varchar(128) NOT NULL REFERENCES mcp_admin_user(username),
    menu_key varchar(128) NOT NULL,
    granted_by varchar(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    UNIQUE(username, menu_key)
);
-- Rollback application image first. Archive this table with pg_dump before optionally dropping it.
-- No existing business authorization table is modified by this migration.
