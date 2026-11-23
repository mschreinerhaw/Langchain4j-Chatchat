-- Apply before starting the MCP server with Hibernate schema validation.
-- Existing publication rows retain their Required behavior when this column is NULL.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE public.mcp_template_query_binding
    ADD COLUMN IF NOT EXISTS optional_template_keys_json text;
COMMIT;
