-- Read only. Names are candidate hints, never sufficient evidence for deleting production data.
SELECT r.id,r.tenant_id,r.role_name,r.role_code,
 (SELECT count(*) FROM sys_user_role u WHERE u.role_id=r.id) user_references,
 (SELECT count(*) FROM sys_role_permission p WHERE p.role_id=r.id) permission_references,
 (SELECT count(*) FROM role_agent_binding b WHERE b.role_id=r.id) query_service_references,
 (SELECT count(*) FROM resource_grant g WHERE g.principal_type='ROLE' AND g.principal_id=r.id) resource_grants,
 (SELECT count(*) FROM mcp_tool_permission p WHERE p.target_type='ROLE' AND p.target_id=r.id) tool_grants
FROM sys_role r WHERE lower(r.role_code) LIKE 'unified_%' OR r.role_name LIKE 'Temporary unified acceptance%';
SELECT id,tenant_code,tenant_name FROM sys_tenant WHERE lower(tenant_code) LIKE 'unified_%' OR tenant_name LIKE 'Temporary unified acceptance%';
SELECT permission_code,count(*) FROM sys_permission GROUP BY permission_code HAVING count(*)>1;
SELECT menu_code,count(*) FROM sys_menu GROUP BY menu_code HAVING count(*)>1;
SELECT g.id,g.tenant_id,g.principal_type,g.principal_id,g.resource_type,g.agent_id,
 EXISTS(SELECT 1 FROM role_agent_binding b WHERE b.tenant_id=g.tenant_id AND b.role_id=g.principal_id AND b.agent_id=g.agent_id AND b.enabled=true) role_service_binding_exists
FROM resource_grant g WHERE g.agent_id IS NOT NULL;
SELECT g.id,g.principal_type,g.principal_id,g.resource_type FROM resource_grant g
WHERE (g.principal_type='ROLE' AND NOT EXISTS(SELECT 1 FROM sys_role r WHERE r.id=g.principal_id AND r.tenant_id=g.tenant_id))
 OR (g.principal_type='USER' AND NOT EXISTS(SELECT 1 FROM sys_user u WHERE u.id=g.principal_id AND u.tenant_id=g.tenant_id));
