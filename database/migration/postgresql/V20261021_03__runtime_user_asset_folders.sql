-- Logical organization only; no existing objects, scripts, tasks or storage paths are changed.
CREATE TABLE IF NOT EXISTS runtime_user_asset_folder (
    id VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL REFERENCES runtime_user_workspace(id),
    parent_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(workspace_id,parent_id,name)
);
CREATE TABLE IF NOT EXISTS runtime_user_asset_location (
    workspace_id VARCHAR(64) NOT NULL REFERENCES runtime_user_workspace(id),
    source VARCHAR(24) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    folder_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(workspace_id,source,source_id)
);
DO $$
DECLARE app_owner TEXT;
BEGIN
    SELECT tableowner INTO app_owner FROM pg_tables
    WHERE schemaname=current_schema() AND tablename='runtime_workspace_file';
    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE runtime_user_asset_folder OWNER TO %I',app_owner);
        EXECUTE format('ALTER TABLE runtime_user_asset_location OWNER TO %I',app_owner);
    END IF;
END $$;
