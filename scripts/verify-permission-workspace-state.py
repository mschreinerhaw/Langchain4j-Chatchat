"""Read-only reconciliation after permission workspace deployment and synthetic fixture cleanup."""
import argparse
import importlib.util
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--deployment', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('deploy-unified-management.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)
stage.evidence = args.output.parent
api_db, mcp_db = 'runtime_os_acceptance_api', 'runtime_os_acceptance_mcp'
deployment = json.loads(args.deployment.read_text())
checks, counts = {}, {}

for table in ['mcp_tool_permission', 'resource_grant', 'sys_user_role', 'sys_role_permission']:
    value = stage.sql(api_db, "select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from " + table + ' t')
    checks[table + 'RestoredAfterAcceptance'] = value == deployment['policyBefore'][api_db + '.' + table]

checks['originalMcpTemplateBindingsUnchanged'] = stage.sql(mcp_db,
    "select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from mcp_template_query_binding t where domain_code not like 'unified_acceptance_%' and domain_code not like 'browser_acceptance_%'") == 'c644dcbe35a9623798a74c6aa629b1c3'
queries = {
    'obsoleteMenus': "select count(*) from sys_menu where menu_code in ('systemSkillRoles','permissionTools')",
    'temporaryRoles': "select count(*) from sys_role where role_code like 'UX_%' or role_code like 'UNIFIED_%' or role_name like 'Temporary unified acceptance%'",
    'temporaryUsers': "select count(*) from sys_user where username like 'ux_permission_%' or username like 'ux_browser_%' or username like 'unified_acceptance_%'",
    'temporaryTenants': "select count(*) from sys_tenant where lower(tenant_code) like 'unified_%' or tenant_name like 'Temporary unified acceptance%'",
    'temporaryOrganizations': "select count(*) from sys_org where org_code like 'UX_ORG_%'",
    'duplicatePermissionCodes': "select count(*) from (select permission_code from sys_permission group by permission_code having count(*)>1) t",
    'duplicateMenuCodes': "select count(*) from (select menu_code from sys_menu group by menu_code having count(*)>1) t",
    'unboundScopedRoleGrants': "select count(*) from resource_grant g where g.agent_id is not null and g.principal_type='ROLE' and not exists(select 1 from role_agent_binding b where b.tenant_id=g.tenant_id and b.role_id=g.principal_id and b.agent_id=g.agent_id and b.enabled=true)",
}
for name, query in queries.items():
    counts[name] = int(stage.sql(api_db, query))
    checks[name + 'Absent'] = counts[name] == 0
checks['fiveFlatPermissionPages'] = stage.sql(api_db, "select count(*) from sys_menu where parent_id='permission-workspace-root'") == '5'
checks['settingsBoundToExistingManagementPermission'] = stage.sql(api_db,
    "select count(*) from sys_menu_permission mp join sys_menu m on m.id=mp.menu_id join sys_permission p on p.id=mp.permission_id where m.menu_code='workspaces' and p.permission_code='system:workspaces:manage'") == '1'
checks['apiRunningArtifactMatches'] = stage.run('docker', 'exec', 'runtime-os-app-stage-api-1', 'sha256sum', '/srv/app/app.jar').split()[0] == deployment['artifacts']['api']['sha256']
checks['mcpRunningArtifactUnchanged'] = stage.run('docker', 'exec', 'runtime-os-app-stage-mcp-1', 'sha256sum', '/srv/app/app.jar').split()[0] == deployment['artifacts']['mcp']['sha256']
report = {'status': 'PASSED' if all(checks.values()) else 'FAILED', 'checks': checks, 'counts': counts, 'readOnly': True}
args.output.write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
assert all(checks.values()), 'Post-deployment reconciliation failed'
