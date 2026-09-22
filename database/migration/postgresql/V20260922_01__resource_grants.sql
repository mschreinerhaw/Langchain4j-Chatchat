create table if not exists resource_grant (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id varchar(128) not null,
    principal_type varchar(16) not null,
    principal_id varchar(64) not null,
    effect varchar(8) not null,
    enabled boolean not null default true,
    expires_at timestamp null,
    created_at timestamp not null,
    updated_at timestamp not null
);
create index if not exists idx_resource_grant_scope on resource_grant (tenant_id, resource_type, resource_id);
