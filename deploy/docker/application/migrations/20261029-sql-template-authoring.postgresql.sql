-- Additive only; existing templates retain their original semantics (NULL publication_group_id).
BEGIN;
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS publication_group_id varchar(64);
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS publication_version integer;
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS author_tenant_id varchar(64);
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS author_id varchar(64);
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS publication_state varchar(24);
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS publication_lock bigint DEFAULT 0;
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS authoring_source_json text;
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS sql_test_evidence_json text;
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS released_capability_id varchar(64);
ALTER TABLE mcp_sql_template ADD COLUMN IF NOT EXISTS draft_request_digest varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sql_template_publication_revision ON mcp_sql_template(publication_group_id,publication_version);
COMMIT;
