-- Additive schema; execute through the deployment migration, never application startup DDL.
CREATE TABLE IF NOT EXISTS runtime_user_workspace (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    quota_bytes BIGINT NOT NULL,
    used_bytes BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (tenant_id, user_id)
);
CREATE TABLE IF NOT EXISTS runtime_workspace_file (
    id VARCHAR(64) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL REFERENCES runtime_user_workspace(id),
    kind VARCHAR(24) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_version VARCHAR(256),
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    run_id VARCHAR(256) NOT NULL,
    source_ref VARCHAR(1024) NOT NULL,
    created_at BIGINT NOT NULL,
    deleted_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_runtime_workspace_file_owner ON runtime_workspace_file(workspace_id, state, created_at);
CREATE INDEX IF NOT EXISTS idx_runtime_workspace_file_task ON runtime_workspace_file(workspace_id, task_id, run_id);

-- Migrations may run as postgres while the API uses the existing restricted Runtime role.
-- Follow the current evidence table owner, without granting privileges to unrelated roles.
DO $$
DECLARE runtime_owner TEXT;
BEGIN
    SELECT tableowner INTO STRICT runtime_owner FROM pg_tables
    WHERE schemaname=current_schema() AND tablename='runtime_evidence_entry';
    EXECUTE format('ALTER TABLE runtime_user_workspace OWNER TO %I', runtime_owner);
    EXECUTE format('ALTER TABLE runtime_workspace_file OWNER TO %I', runtime_owner);
END $$;
