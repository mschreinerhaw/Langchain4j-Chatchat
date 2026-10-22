"""Deploy MCP local console accounts and menu authorization, preserving Runtime business grants."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

spec=importlib.util.spec_from_file_location('stage',Path(__file__).with_name('deploy-unified-management.py'))
stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir',type=Path,required=True)
    parser.add_argument('--stage-root',type=Path,default=Path('/opt/runtime-os-docker-stage'))
    args=parser.parse_args();os.umask(0o077)
    root=args.stage_root.resolve();release=args.release_dir.resolve()
    manifest=json.loads((release/'release-manifest.json').read_text());version=manifest['version']
    if not version.replace('.','').replace('-','').isalnum():raise ValueError('Invalid version')
    evidence=root/'evidence'/('mcp-local-admin-'+version);evidence.mkdir(mode=0o700,parents=True,exist_ok=True)
    stage.root,stage.evidence=root,evidence
    config=json.loads((root/'compose.yaml').read_text())
    databases={name:config['services'][name]['environment']['CHATCHAT_'+name.upper()+'_POSTGRESQL_URL'].rsplit('/',1)[1].split('?',1)[0] for name in ['api','mcp']}
    if stage.digest(release/'mcp.jar')!=manifest['artifacts']['mcp']['sha256']:raise RuntimeError('Artifact hash mismatch')
    if shutil.disk_usage(root).free<1000*1024*1024:raise RuntimeError('Insufficient free space')
    context=evidence/'mcp-image';stage.build_application_image('mcp',release/'mcp.jar','runtime-os-mcp:'+version,context)
    if stage.digest(context/'app.jar')==manifest['artifacts']['mcp']['sha256']:(context/'app.jar').unlink()
    def idle():
        if stage.sql(databases['api'],"select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")!='0':raise RuntimeError('Analysis task is active')
    before=stage.policy_fingerprints(databases['api'],databases['mcp'])
    accountHash=stage.sql(databases['mcp'],"select md5(coalesce(jsonb_agg(to_jsonb(t) order by username)::text,'[]')) from mcp_admin_user t")
    idle();backup=evidence/('backup-'+str(int(time.time())));backup.mkdir(mode=0o700)
    for path in [root/'compose.yaml',root/'config/deployment.env']:shutil.copy2(path,backup/path.name)
    previous={'image':config['services']['mcp']['image'],'imageId':stage.run('docker','image','inspect','--format','{{.Id}}',config['services']['mcp']['image']),
              'sha256':stage.run('docker','exec','runtime-os-app-stage-mcp-1','sha256sum','/srv/app/app.jar').split()[0]}
    (backup/'previous-mcp-artifact.json').write_text(json.dumps(previous,indent=2))
    with (backup/'mcp.dump').open('wb') as output:subprocess.run(['docker','exec','-u','postgres','postgres','pg_dump','-Fc','-d',databases['mcp']],stdout=output,check=True)
    report={'version':version,'sourceCommit':manifest['sourceCommit'],'backup':str(backup),'previousMcp':previous,'apiImage':config['services']['api']['image'],'policyBefore':before,'artifacts':manifest['artifacts']}
    try:
        idle();stage.compose('stop','mcp')
        # Run additive DDL as the existing account-table owner, never leave a postgres-owned table
        # inaccessible to the application's least-privilege database role.
        owner=stage.sql(databases['mcp'],"select pg_get_userbyid(relowner) from pg_class where oid='mcp_admin_user'::regclass")
        quotedOwner='"'+owner.replace('"','""')+'"'
        stage.sql(databases['mcp'],'SET ROLE '+quotedOwner+';\n'+(release/'mcp-admin-menu-grants.sql').read_text()+'\nALTER TABLE mcp_admin_menu_grant OWNER TO '+quotedOwner+';\nRESET ROLE;')
        config['services']['mcp']['image']='runtime-os-mcp:'+version
        (root/'compose.yaml').write_text(json.dumps(config,indent=2))
        env=root/'config/deployment.env';env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith('MCP_IMAGE='))+'\nMCP_IMAGE=runtime-os-mcp:'+version+'\n')
        stage.compose('up','-d','--no-deps','--force-recreate','mcp');stage.ready(18190)
        after=stage.policy_fingerprints(databases['api'],databases['mcp'])
        if before!=after:raise RuntimeError('Runtime business permissions changed')
        if accountHash!=stage.sql(databases['mcp'],"select md5(coalesce(jsonb_agg(to_jsonb(t) order by username)::text,'[]')) from mcp_admin_user t"):raise RuntimeError('Existing local accounts changed')
        actual=stage.run('docker','exec','runtime-os-app-stage-mcp-1','sha256sum','/srv/app/app.jar').split()[0]
        if actual!=manifest['artifacts']['mcp']['sha256']:raise RuntimeError('Running artifact mismatch')
        stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-t');stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload')
        report.update(status='DEPLOYED',policyAfter=after,policyUnchanged=True,existingAccountsUnchanged=True)
    except Exception:
        for name,target in [('compose.yaml',root/'compose.yaml'),('deployment.env',root/'config/deployment.env')]:shutil.copy2(backup/name,target)
        stage.compose('up','-d','--no-deps','--force-recreate','mcp');stage.ready(18190)
        stage.run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload')
        report['status']='ROLLED_BACK';(evidence/'deployment.json').write_text(json.dumps(report,indent=2));raise
    (evidence/'deployment.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)

if __name__=='__main__':main()
