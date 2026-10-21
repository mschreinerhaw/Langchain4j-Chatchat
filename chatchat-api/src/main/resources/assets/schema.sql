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
CREATE TABLE IF NOT EXISTS runtime_asset_knowledge_job (
    file_id VARCHAR(64) PRIMARY KEY REFERENCES runtime_workspace_file(id),
    state VARCHAR(24) NOT NULL,
    indexed_sha256 VARCHAR(64),
    attempts INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    next_attempt_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runtime_asset_knowledge_pending ON runtime_asset_knowledge_job(state,next_attempt_at);
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
