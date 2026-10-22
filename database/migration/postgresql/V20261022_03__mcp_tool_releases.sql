-- Apply to the MCP PostgreSQL database; central permission data remains in the API database.
BEGIN;
CREATE TABLE IF NOT EXISTS mcp_tool_release (
 id varchar(64) PRIMARY KEY, logical_id varchar(64) NOT NULL, tenant_id varchar(64) NOT NULL,
 release_number integer NOT NULL, state varchar(24) NOT NULL, kind varchar(64) NOT NULL,
 title varchar(256) NOT NULL, description text, definition_json text NOT NULL, input_schema_json text NOT NULL,
 output_schema_json text NOT NULL, test_evidence_json text, binding_id varchar(64), actor_id varchar(64) NOT NULL,
 created_at timestamptz NOT NULL, published_at timestamptz, version bigint,
 UNIQUE (logical_id,release_number)
);
CREATE INDEX IF NOT EXISTS ix_tool_release_tenant ON mcp_tool_release(tenant_id,created_at DESC);
CREATE INDEX IF NOT EXISTS ix_tool_release_state ON mcp_tool_release(state);
CREATE OR REPLACE FUNCTION protect_published_tool_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.published_at IS NOT NULL AND (TG_OP = 'DELETE' OR
   (NEW.logical_id,NEW.tenant_id,NEW.release_number,NEW.kind,NEW.title,NEW.description,NEW.definition_json,
    NEW.input_schema_json,NEW.output_schema_json,NEW.test_evidence_json,NEW.published_at,NEW.actor_id,NEW.created_at,NEW.binding_id)
   IS DISTINCT FROM
   (OLD.logical_id,OLD.tenant_id,OLD.release_number,OLD.kind,OLD.title,OLD.description,OLD.definition_json,
    OLD.input_schema_json,OLD.output_schema_json,OLD.test_evidence_json,OLD.published_at,OLD.actor_id,OLD.created_at,OLD.binding_id)) THEN
   RAISE EXCEPTION 'Published tool release definitions and evidence are immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS protect_published_tool_release ON mcp_tool_release;
CREATE TRIGGER protect_published_tool_release BEFORE UPDATE OR DELETE ON mcp_tool_release
FOR EACH ROW EXECUTE FUNCTION protect_published_tool_release();
COMMIT;
-- Deliberately retain mcp_authorization_role as a rollback archive. New runtime never reads/writes it.
