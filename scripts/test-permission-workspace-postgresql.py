"""Exercise permission navigation migration/rollback in a disposable synthetic PostgreSQL database."""
import argparse,json,subprocess,uuid
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--migration',type=Path,required=True);parser.add_argument('--rollback',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();database='permission_ux_validation_'+uuid.uuid4().hex[:12]
subprocess.run(['docker','exec','-u','postgres','postgres','createdb',database],check=True)
checks={}
def sql(query,succeeds=True):
 result=subprocess.run(['docker','exec','-i','-u','postgres','postgres','psql','-At','-v','ON_ERROR_STOP=1','-d',database],input=query,text=True,capture_output=True)
 if (result.returncode==0)!=succeeds:raise RuntimeError(result.stderr)
 return result.stdout.strip()
try:
 sql('''
 create table sys_menu(id varchar primary key,parent_id varchar references sys_menu(id),menu_code varchar,menu_name varchar,menu_type varchar,route_path varchar,icon varchar,sort_order int,status varchar,created_at timestamp default CURRENT_TIMESTAMP,updated_at timestamp default CURRENT_TIMESTAMP);
 create table sys_permission(id varchar primary key,permission_code varchar);
 create table sys_menu_permission(id varchar primary key,menu_id varchar references sys_menu(id),permission_id varchar references sys_permission(id),created_at timestamp default CURRENT_TIMESTAMP,updated_at timestamp default CURRENT_TIMESTAMP);
 create table workspace_registry(id varchar primary key,manifest_json text,version bigint,updated_at timestamp default CURRENT_TIMESTAMP);
 create table runtime_product_retirement_archive(batch_id varchar,record_type varchar,record_id varchar,saved_payload text,replacement_payload text,created_at timestamp default CURRENT_TIMESTAMP,primary key(batch_id,record_type,record_id));
 create table resource_grant(id varchar primary key,payload text);insert into resource_grant values('original','PRECISE_SCOPE_AND_SERVICE_BINDING');
 create table mcp_tool_permission(id varchar primary key,payload text);insert into mcp_tool_permission values('original','ALLOW_ONLY_ORIGINAL_DATASOURCE');
 create table sys_role_permission(id varchar primary key,payload text);insert into sys_role_permission values('original','ORIGINAL_ROLE_BINDING');
 insert into sys_permission values('access','workspace:permissions:access'),('settings','system:workspaces:manage'),('zhice','workspace:zhice:access');
 insert into sys_menu(id,menu_code,menu_name,status) values('platform','platform','platform','enabled'),('system','system','system','enabled');
 ''')
 for name in ['systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins','systemSkillRoles','permissionTools','workspaces']:
  sql("insert into sys_menu(id,parent_id,menu_code,menu_name,status) values('"+name+"','system','"+name+"','"+name+"','enabled')")
 sql("insert into sys_menu_permission(id,menu_id,permission_id) values('legacy','systemSkillRoles','access'),('ws-selection-access','workspaces','zhice')")
 permission_manifest=json.dumps({'entryPage':'systemSkillRoles','configVersion':1,'layoutType':'assistant','name':'permissions','custom':'preserve','pages':[{'id':x,'componentKey':x} for x in ['systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins','systemSkillRoles','permissionTools']]})
 zhice_manifest=json.dumps({'entryPage':'chat','configVersion':7,'pages':[{'id':x,'componentKey':x} for x in ['chat','systemRoles','systemResources']]})
 for name,manifest in [('permissions',permission_manifest),('zhice',zhice_manifest)]:sql("insert into workspace_registry(id,manifest_json,version) values('"+name+"','"+manifest+"',7)")
 original={table:sql("select md5(jsonb_agg(to_jsonb(t) order by id)::text) from "+table+' t') for table in ['sys_menu','sys_menu_permission','resource_grant','mcp_tool_permission','sys_role_permission']}
 sql("insert into sys_menu(id,parent_id,menu_code) values('custom','systemSkillRoles','custom')")
 sql(args.migration.read_text(),False)
 checks['customChildrenBlockMigration']=sql('select count(*) from runtime_product_retirement_archive')=='0'
 sql("delete from sys_menu where id='custom'")
 sql(args.migration.read_text());sql(args.migration.read_text())
 checks['fiveFlatNavigationPages']=sql("select count(*) from sys_menu where parent_id='permission-workspace-root'")=='5'
 checks['obsoleteMenusRemoved']=sql("select count(*) from sys_menu where menu_code in ('systemSkillRoles','permissionTools')")=='0'
 checks['settingsBoundToExistingManagementPermission']=sql("select count(*) from sys_menu_permission where menu_id='workspaces' and permission_id='settings'")=='1'
 checks['settingsNotExposedThroughGenericWorkspaceAccess']=sql("select count(*) from sys_menu_permission where id='ws-selection-access'")=='0'
 checks['currentWorkspaceManifestPreserved']=sql("select manifest_json::jsonb->>'custom' from workspace_registry where id='permissions'")=='preserve'
 checks['duplicateMainWorkspacePagesRemoved']=sql("select count(*) from workspace_registry,jsonb_array_elements(manifest_json::jsonb->'pages') p where id='zhice' and p->>'id' like 'system%'")=='0'
 checks['businessGrantsUnchanged']=all(sql("select md5(jsonb_agg(to_jsonb(t) order by id)::text) from "+table+' t')==original[table] for table in ['resource_grant','mcp_tool_permission','sys_role_permission'])
 migrated=sql("select manifest_json from workspace_registry where id='permissions'")
 sql("update workspace_registry set manifest_json='{}' where id='permissions'");sql(args.rollback.read_text(),False)
 checks['laterWorkspaceChangesBlockRollback']=sql("select count(*) from sys_menu where menu_code='permissionsRoot'")=='1'
 sql("update workspace_registry set manifest_json='"+migrated.replace("'","''")+"' where id='permissions'")
 sql("update sys_menu set menu_name='customized' where menu_code='systemRoles'");sql(args.rollback.read_text(),False)
 checks['laterMenuChangesBlockRollback']=sql("select count(*) from sys_menu where menu_code='permissionsRoot'")=='1'
 sql("update sys_menu set menu_name='角色权限' where menu_code='systemRoles'")
 sql(args.rollback.read_text());sql(args.rollback.read_text())
 checks['originalMenusAndBindingsExactlyRestored']=all(sql("select md5(jsonb_agg(to_jsonb(t) order by id)::text) from "+table+' t')==original[table] for table in ['sys_menu','sys_menu_permission'])
 checks['originalManifestExactlyRestored']=sql("select manifest_json from workspace_registry where id='permissions'")==permission_manifest
 checks['archiveHistoryRetained']=int(sql('select count(*) from runtime_product_retirement_archive'))>0
 report={'status':'PASSED' if all(checks.values()) else 'FAILED','checks':checks,'productionDataUsed':False}
 args.output.write_text(json.dumps(report,indent=2));print(json.dumps(report));assert all(checks.values()),checks
finally:
 subprocess.run(['docker','exec','-u','postgres','postgres','dropdb','--force',database],check=True)
