create index if not exists idx_resource_grant_principal
    on resource_grant (tenant_id, principal_type, principal_id, resource_type, resource_id);
