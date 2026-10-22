"""Deploy the permission-workspace UX release using existing stage backups, policies and image tooling."""
import argparse,importlib.util,json,os,shutil
from pathlib import Path
spec=importlib.util.spec_from_file_location('stage_deployment',Path(__file__).with_name('deploy-unified-management.py'))
stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--release-dir',type=Path,required=True);parser.add_argument('--stage-root',type=Path,default=Path('/opt/runtime-os-docker-stage'));args=parser.parse_args()
 os.umask(0o077);root=args.stage_root.resolve();release=args.release_dir.resolve();manifest=json.loads((release/'release-manifest.json').read_text());version=manifest['version']
 if not version.replace('.','').replace('-','').isalnum():raise ValueError('Invalid release version')
 evidence=root/'evidence'/('permission-workspace-'+version);evidence.mkdir(parents=True,exist_ok=True,mode=0o700);stage.root,stage.evidence=root,evidence
 config=json.loads((root/'compose.yaml').read_text());databases={service:config['services'][service]['environment']['CHATCHAT_'+service.upper()+'_POSTGRESQL_URL'].rsplit('/',1)[1].split('?',1)[0] for service in ['api','mcp']}
 if stage.digest(release/'api.jar')!=manifest['artifacts']['api']['sha256']:raise RuntimeError('Artifact hash mismatch')
 stage.build_application_image('api',release/'api.jar','runtime-os-api:'+version,evidence/'api-image')
 def require_idle():
  if stage.sql(databases['api'],"select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")!='0' or stage.run('docker','ps','--filter','name=chatchat-py-','--format','{{.ID}}'):raise RuntimeError('Active analysis tasks prevent replacement')
 def fingerprints():
  values=stage.policy_fingerprints(databases['api'],databases['mcp'])
  values[databases['api']+'.sys_role_permission']=stage.sql(databases['api'],"select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from sys_role_permission t")
  return values
 require_idle();before=fingerprints();destination=stage.backup(config,databases);require_idle()
 report=dict(version=version,sourceCommit=manifest['sourceCommit'],backup=str(destination),policyBefore=before,mcpImage=config['services']['mcp']['image']);migrated=False
 print(json.dumps({'stage':'BACKED_UP','backup':str(destination)}),flush=True)
 try:
  stage.compose('stop','api')
  stage.sql(databases['api'],(release/'V20261023_01__permission_workspace_ux.sql').read_text());migrated=True
  config['services']['api']['image']='runtime-os-api:'+version;(root/'compose.yaml').write_text(json.dumps(config,indent=2))
  env=root/'config/deployment.env';env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith('API_IMAGE='))+'\nAPI_IMAGE=runtime-os-api:'+version+'\n')
  stage.compose('up','-d','--no-deps','--force-recreate','api');stage.ready(18180)
  if stage.sql(databases['api'],"select count(*) from runtime_product_retirement_archive where record_type='MARKER' and record_id='permission-workspace-ux-v1'")!='1':raise RuntimeError('Navigation migration incomplete')
  if stage.sql(databases['api'],"select count(*) from sys_menu where menu_code in ('systemSkillRoles','permissionTools')")!='0':raise RuntimeError('Obsolete menus remain')
  if stage.sql(databases['api'],"select manifest_json::jsonb->>'layoutType' from workspace_registry where id='permissions'")!='management':raise RuntimeError('Management layout missing')
  if stage.sql(databases['api'],"select count(*) from sys_menu_permission mp join sys_menu m on m.id=mp.menu_id join sys_permission p on p.id=mp.permission_id where m.menu_code='workspaces' and p.permission_code='system:workspaces:manage'")!='1':raise RuntimeError('Workspace settings binding missing')
  after=fingerprints()
  if before!=after:raise RuntimeError('Business authorization or fixed-template bindings changed')
  actual=stage.run('docker','exec','runtime-os-app-stage-api-1','sha256sum','/srv/app/app.jar').split()[0]
  if actual!=manifest['artifacts']['api']['sha256']:raise RuntimeError('Running artifact differs from release')
  stage.compose('up','-d','--no-deps','--force-recreate','node-proxy')
  worker=root/'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
  if worker.exists():stage.run('bash',str(worker))
  stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-t');stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload')
  report.update(status='DEPLOYED',policyAfter=after,policyUnchanged=True,artifactHashesMatch=True,artifacts=manifest['artifacts'])
 except Exception:
  stage.compose('stop','api')
  if migrated:stage.sql(databases['api'],(release/'R20261023_01__permission_workspace_ux.sql').read_text())
  for name,target in [('compose.yaml',root/'compose.yaml'),('deployment.env',root/'config/deployment.env')]:shutil.copy2(destination/name,target)
  stage.compose('up','-d','--no-deps','--force-recreate','api');stage.ready(18180);stage.compose('up','-d','--no-deps','--force-recreate','node-proxy');stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload')
  report['status']='ROLLED_BACK';(evidence/'deployment.json').write_text(json.dumps(report,indent=2));raise
 (evidence/'deployment.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
if __name__=='__main__':main()
