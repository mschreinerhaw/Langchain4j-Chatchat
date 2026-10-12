-- Apply to the API database before enabling Workspace. Additive and repeatable.
create table if not exists sys_workspace (
    id varchar(64) primary key, tenant_id varchar(64) not null, workspace_id varchar(64) not null,
    manifest_json text not null, access_permission_code varchar(128), enabled boolean not null,
    is_default boolean not null, sort_order integer not null, revision bigint not null,
    created_at timestamp not null, updated_at timestamp not null,
    constraint uk_workspace_tenant_code unique (tenant_id, workspace_id)
);
create table if not exists sys_workspace_user_preference (
    id varchar(64) primary key, tenant_id varchar(64) not null, user_id varchar(64) not null,
    default_workspace_id varchar(64), last_workspace_id varchar(64), revision bigint not null,
    created_at timestamp not null, updated_at timestamp not null,
    constraint uk_workspace_preference_user unique (tenant_id, user_id)
);
insert into sys_permission (id, permission_code, permission_name, permission_type, resource_path, http_method, sort_order, status, created_at, updated_at)
select 'ws-use-api-v1', 'account:workspaces:use', '当前账户工作空间', 'api', '/api/v1/workspaces/**', '*', 4, 'enabled', current_timestamp, current_timestamp
where not exists (select 1 from sys_permission where permission_code = 'account:workspaces:use');
insert into sys_permission (id, permission_code, permission_name, permission_type, resource_path, http_method, sort_order, status, created_at, updated_at)
select 'ws-manage-api-v1', 'system:workspaces:manage', '工作空间管理', 'api', '/api/v1/enterprise/workspaces/**', '*', 5, 'enabled', current_timestamp, current_timestamp
where not exists (select 1 from sys_permission where permission_code = 'system:workspaces:manage');
-- Only inherit the harmless account API from an existing account-menu grant.
-- Management additionally checks system:admin:operate in the service.
insert into sys_role_permission (id, tenant_id, role_id, permission_id, created_at, updated_at)
select md5(random()::text || clock_timestamp()::text), r.tenant_id, r.id, p.id, current_timestamp, current_timestamp
from sys_role r cross join sys_permission p
where p.permission_code in ('account:workspaces:use', 'system:workspaces:manage')
and exists (select 1 from sys_role_permission rp join sys_permission prior on prior.id = rp.permission_id
    where rp.role_id = r.id and rp.tenant_id = r.tenant_id and prior.permission_code =
        case when p.permission_code = 'account:workspaces:use' then 'account:menus:read' else 'system:admin:operate' end)
and not exists (select 1 from sys_role_permission rp where rp.role_id = r.id and rp.permission_id = p.id and rp.tenant_id = r.tenant_id);
-- Built-in manifests are seeded lazily under the existing tenant row lock.
-- This covers future tenants and preserves administrators' disabled/customized records.
