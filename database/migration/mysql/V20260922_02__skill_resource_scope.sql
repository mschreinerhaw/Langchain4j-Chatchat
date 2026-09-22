create table if not exists skill_resource_scope (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    skill_id varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id varchar(128) not null,
    enabled boolean not null default true,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_skill_resource_scope (tenant_id, skill_id, resource_type)
);
