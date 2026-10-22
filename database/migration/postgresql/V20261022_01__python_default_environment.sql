-- API database only. Defaults are scoped to the authenticated tenant and user.
CREATE TABLE IF NOT EXISTS ds_python_environment_preference (
    id varchar(64) PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    owner_id varchar(64) NOT NULL,
    asset_id varchar(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_python_environment_preference_owner UNIQUE (tenant_id, owner_id)
);

-- Operators may execute migrations as postgres while the application uses a
-- dedicated role. Match the existing workshop table's owner in that case.
DO $$
DECLARE workshop_owner text;
BEGIN
    SELECT pg_get_userbyid(relowner) INTO workshop_owner
      FROM pg_class
     WHERE oid = to_regclass('ds_python_asset');
    IF workshop_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ds_python_environment_preference OWNER TO %I', workshop_owner);
    END IF;
END $$;
