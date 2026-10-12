create table if not exists sys_workspace_resource_binding (
    id varchar(64) primary key, tenant_id varchar(64) not null, resource_type varchar(32) not null,
    resource_id varchar(128) not null, origin_workspace_id varchar(64) not null, owner_id varchar(64) not null,
    created_at timestamp not null, updated_at timestamp not null,
    constraint uk_workspace_resource unique (tenant_id, resource_type, resource_id)
);
