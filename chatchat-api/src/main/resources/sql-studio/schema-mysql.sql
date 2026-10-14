CREATE TABLE IF NOT EXISTS ds_sql_project (
    id VARCHAR(64) PRIMARY KEY, name VARCHAR(180) NOT NULL, dialect VARCHAR(32) NOT NULL,
    session_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS ds_sql_generation_scope (
    session_id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS ds_sql_metadata_context (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
    purpose VARCHAR(16) NOT NULL, target_dialect VARCHAR(32) NOT NULL,
    bindings_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS ds_sql_file (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    path VARCHAR(240) NOT NULL, path_key VARCHAR(240) NOT NULL, kind VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL, version INTEGER NOT NULL, original_base64 LONGTEXT, source_task_id VARCHAR(64),
    updated_at TIMESTAMP NOT NULL, CONSTRAINT uk_sql_project_path UNIQUE(project_id, path_key)
);
CREATE TABLE IF NOT EXISTS ds_sql_file_version (
    id VARCHAR(64) PRIMARY KEY, file_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL, content LONGTEXT NOT NULL, original_base64 LONGTEXT, source_task_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL, CONSTRAINT uk_sql_file_version UNIQUE(file_id, version)
);
CREATE TABLE IF NOT EXISTS ds_sql_generation (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    context_json LONGTEXT NOT NULL, base_versions_json LONGTEXT NOT NULL, task_id VARCHAR(64),
    applied BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ds_sql_folder (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    path VARCHAR(240) NOT NULL, path_key VARCHAR(240) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_sql_folder_path UNIQUE(project_id, path_key)
);
