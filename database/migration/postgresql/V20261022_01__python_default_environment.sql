-- API database only. Defaults are scoped to the authenticated tenant and user.
CREATE TABLE IF NOT EXISTS ds_python_environment_preference (
    id varchar(64) PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    owner_id varchar(64) NOT NULL,
    asset_id varchar(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_python_environment_preference_owner UNIQUE (tenant_id, owner_id)
);
