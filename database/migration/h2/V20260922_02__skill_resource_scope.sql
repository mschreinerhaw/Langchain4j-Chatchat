create table if not exists skill_resource_scope (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    skill_id varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id varchar(128) not null,
    enabled boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null
);
create index if not exists idx_skill_resource_scope on skill_resource_scope (tenant_id, skill_id, resource_type);
