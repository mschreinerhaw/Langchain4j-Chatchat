"""Validate retirement and rollback on an isolated PostgreSQL database, without business rows."""
import argparse
import json
from pathlib import Path
import subprocess
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--migration', type=Path, required=True)
parser.add_argument('--rollback', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
database = 'retired_agent_validation_' + uuid.uuid4().hex[:12]
subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'createdb', database], check=True)
checks = {}

def sql(query, succeeds=True):
    result = subprocess.run(['docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '-At',
        '-v', 'ON_ERROR_STOP=1', '-d', database], input=query, text=True, capture_output=True)
    if (result.returncode == 0) != succeeds:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

try:
    sql("""
      create table workspace_registry(id varchar primary key,manifest_json text,version bigint,updated_at timestamp);
      create table sys_permission(id varchar primary key,permission_code varchar,permission_name varchar,resource_path varchar);
      create table sys_menu(id varchar primary key,parent_id varchar references sys_menu(id),menu_code varchar);
      create table sys_role_permission(id varchar primary key,permission_id varchar references sys_permission(id));
      create table sys_menu_permission(id varchar primary key,permission_id varchar references sys_permission(id),menu_id varchar references sys_menu(id));
      create table agent_compute_definition(id varchar primary key,definition_json text);
      create table agent_provider_health(agent_id varchar primary key references agent_compute_definition(id));
      create table agent_a2a_task_link(id varchar primary key,agent_id varchar references agent_compute_definition(id));
      insert into agent_compute_definition values('synthetic','{"credentialRef":"env:SYNTHETIC_REFERENCE"}');
      insert into agent_provider_health values('synthetic');
      insert into agent_a2a_task_link values('task','synthetic');
      insert into workspace_registry values('custom','{"entryPage":"domainAnalysis","pages":[{"id":"chat","componentKey":"chat"},{"id":"domainAnalysis","componentKey":"domainAnalysis"}],"custom":"preserve"}',3,CURRENT_TIMESTAMP);
      insert into sys_permission values('old','workspace:analysis:api','old','/api/v1/agent/analysis/**');
      insert into sys_menu values('old',null,'domainAnalysis');
      insert into sys_role_permission values('old','old');
      insert into sys_menu_permission values('old','old','old');
      create table business_reference(agent_id varchar references agent_compute_definition(id));
    """)
    sql(args.migration.read_text(), succeeds=False)
    checks['liveForeignKeyBlocksArchive'] = sql("select to_regclass('public.agent_compute_definition') is not null") == 't'
    sql('drop table business_reference; create view business_view as select id from agent_compute_definition;')
    sql(args.migration.read_text(), succeeds=False)
    checks['liveViewBlocksArchive'] = sql("select to_regclass('public.agent_compute_definition') is not null") == 't'
    sql('drop view business_view;')
    original = sql("select manifest_json from workspace_registry where id='custom'")
    sql(args.migration.read_text())
    sql(args.migration.read_text())
    checks['tablesArchivedWithoutDeletingRows'] = sql('select count(*) from runtime_os_archive.agent_compute_definition') == '1'
    checks['publicRegistryRemoved'] = sql("select to_regclass('public.agent_compute_definition') is null") == 't'
    checks['archiveSchemaNotPublic'] = sql("select not exists(select 1 from pg_namespace n cross join lateral aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a where n.nspname='runtime_os_archive' and a.grantee=0 and a.privilege_type='USAGE')") == 't'
    for table in ['sys_permission', 'sys_menu', 'sys_role_permission', 'sys_menu_permission', 'workspace_registry']:
        sql("insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) select 'fixture','" + table + "',id,row_to_json(t)::text from " + table + ' t')
    replacement = '{"entryPage":"chat","pages":[{"id":"chat","componentKey":"chat"}],"custom":"preserve"}'
    sql("update runtime_product_retirement_archive set replacement_payload='" + replacement + "' where record_type='workspace_registry';"
        "update workspace_registry set manifest_json='" + replacement + "';"
        "delete from sys_menu_permission;delete from sys_role_permission;delete from sys_menu;delete from sys_permission;"
        "insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) values('fixture','MARKER','professional-agents-v1','{}');")
    sql("update workspace_registry set manifest_json='{}'")
    sql(args.rollback.read_text(), succeeds=False)
    checks['customizationConflictBlocksRollback'] = sql('select count(*) from sys_permission') == '0'
    sql("update workspace_registry set manifest_json='" + replacement + "'")
    sql(args.rollback.read_text())
    checks['workspaceExactlyRestored'] = sql("select manifest_json from workspace_registry where id='custom'") == original
    checks['permissionAndBindingsRestored'] = sql('select count(*) from sys_menu_permission') == '1' and sql('select count(*) from sys_role_permission') == '1'
    checks['physicalTablesRestored'] = sql('select count(*) from public.agent_a2a_task_link') == '1'
    checks['archiveHistoryRetained'] = sql("select count(*) from runtime_product_retirement_archive where record_type<>'MARKER'") == '5'
    report = {'status': 'PASSED' if all(checks.values()) else 'FAILED', 'checks': checks, 'productionDataUsed': False}
    args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report))
    assert report['status'] == 'PASSED'
finally:
    subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'dropdb', database], check=True)
