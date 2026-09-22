create table if not exists resource_grant (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id varchar(128) not null,
    principal_type varchar(16) not null,
    principal_id varchar(64) not null,
    effect varchar(8) not null,
    enabled boolean not null default true,
    expires_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_resource_grant_scope (tenant_id, resource_type, resource_id)
);
