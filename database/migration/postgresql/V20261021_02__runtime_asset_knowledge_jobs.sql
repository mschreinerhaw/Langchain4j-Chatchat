-- Additive projection state; Silo+PostgreSQL remain the facts and authorization authority.
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
INSERT INTO runtime_asset_knowledge_job(file_id,state,next_attempt_at,updated_at)
SELECT id,CASE WHEN state='DELETED' THEN 'DELETE_PENDING' ELSE 'PENDING' END,0,0
FROM runtime_workspace_file WHERE state IN ('AVAILABLE','DELETED')
AND (kind='DOCUMENT' OR (kind='CONTEXT' AND source_ref='user-analysis-result'))
ON CONFLICT(file_id) DO NOTHING;
DO $$
DECLARE app_owner TEXT;
BEGIN
    SELECT tableowner INTO app_owner FROM pg_tables
    WHERE schemaname=current_schema() AND tablename='runtime_workspace_file';
    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE runtime_asset_knowledge_job OWNER TO %I',app_owner);
    END IF;
END $$;
