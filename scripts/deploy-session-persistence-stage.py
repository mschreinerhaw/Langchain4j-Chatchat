"""Deploy only the API/history fix to the authorized isolated test project.

ARCHIVE SHA256 TAG; requires prepared private body recovery files beside the evidence.
Never rewrites PostgreSQL ownership, clears RocksDB locks, or replaces MCP/News/Node.
"""
import hashlib
import json
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT=Path('/opt/runtime-os-docker-stage')
PROJECT='runtime-os-app-stage'
EVIDENCE=ROOT/'evidence/session-recovery-20261019'

def run(*args):
    result=subprocess.run(args,capture_output=True,text=True)
    if result.returncode:raise RuntimeError('Deployment command failed: '+args[0])
    return result.stdout

def compose(*args):
    return run('docker','compose','--project-name',PROJECT,'--env-file',str(ROOT/'config/deployment.env'),'-f',str(ROOT/'compose.yaml'),*args)

def inspect():
    return {c['Name'].lstrip('/'):c for c in json.loads(run('docker','inspect',*run('docker','ps','-q').split()))}

def main():
    archive,expected,tag=sys.argv[1:]
    os.umask(0o077)
    if not re.fullmatch('[a-zA-Z0-9_.-]+',tag) or not re.fullmatch('[0-9a-f]{64}',expected):raise ValueError('Invalid release')
    archive=Path(archive).resolve()
    if not archive.is_relative_to((ROOT/'images').resolve()):raise ValueError('Archive outside stage images')
    with archive.open('rb') as f:
        if hashlib.file_digest(f,'sha256').hexdigest()!=expected:raise ValueError('Archive checksum mismatch')
    if run('docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',
           "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')").strip()!='0':
        raise RuntimeError('Active tasks prevent API replacement')
    before=inspect();api=before[PROJECT+'-api-1']
    if api['Config']['Labels'].get('com.docker.compose.project')!=PROJECT:raise RuntimeError('Unexpected API project')
    data=next(m for m in api['Mounts'] if m['Destination']=='/srv/app/data')
    if data['Type']!='bind' or not data['RW'] or not (Path(data['Source'])/'chat-rocksdb/CURRENT').is_file():
        raise RuntimeError('Expected existing persistent history store is unavailable')
    cfg_path=ROOT/'compose.yaml';env_path=ROOT/'config/deployment.env'
    backup=EVIDENCE/('deployment-before-'+str(int(time.time())))
    backup.mkdir(mode=0o700,parents=True)
    shutil.copy2(cfg_path,backup/'compose.json');shutil.copy2(env_path,backup/'deployment.env')
    cfg=json.loads(cfg_path.read_text())
    service=cfg['services']['api'];env=service.get('environment',{})
    if isinstance(env,list):env=dict(v.split('=',1) for v in env)
    env.update(CHATCHAT_CHAT_DETAIL_STORE_EXTERNAL_TEXT_ENABLED='true',
               CHATCHAT_CHAT_DETAIL_STORE_EXTERNAL_TEXT_MIGRATE_LEGACY_ON_READ='false',
               CHATCHAT_CHAT_DETAIL_STORE_PATH='/srv/app/data/chat-rocksdb',
               CHATCHAT_UI_ARTIFACT_STORAGE_PATH='/srv/app/data/ui-artifacts')
    service.update(environment=env,stop_grace_period='60s',image='runtime-os-api:'+tag)
    run('docker','load','-i',str(archive))
    # Stop the single writer before a consistent physical backup and atomic additive repair.
    compose('stop','-t','60','api')
    try:
        shutil.copytree(Path(data['Source'])/'chat-rocksdb',backup/'chat-rocksdb')
        repair=run('/opt/jdk-17.0.5/bin/java','--class-path',str(EVIDENCE/'*'),str(EVIDENCE/'ChatHistoryBodyRecovery.java'),
                   'apply',str(Path(data['Source'])/'chat-rocksdb'),str(EVIDENCE/'candidates.jsonl'),str(backup/'repaired.jsonl'))
        cfg_path.write_text(json.dumps(cfg,indent=2)+'\n');cfg_path.chmod(0o600)
        lines=[v for v in env_path.read_text().splitlines() if not v.startswith('API_IMAGE=')]
        env_path.write_text('\n'.join(lines+['API_IMAGE=runtime-os-api:'+tag])+'\n');env_path.chmod(0o600)
        compose('config','-q');compose('up','-d','--no-build','--no-deps','--force-recreate','api')
        client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline=time.monotonic()+180
        while True:
            try:
                with client.open('http://127.0.0.1:18180/api/v1/enterprise/auth/me',timeout=3) as response:
                    ready=response.status==200
            except urllib.error.HTTPError as ex:ready=ex.code in (401,403)
            except (OSError,urllib.error.URLError):ready=False
            if ready:break
            if time.monotonic()>deadline:raise RuntimeError('API readiness failed')
            time.sleep(2)
        compose('up','-d','--no-build','--no-deps','--force-recreate','node-proxy','gateway')
    except Exception:
        shutil.copy2(backup/'compose.json',cfg_path);shutil.copy2(backup/'deployment.env',env_path)
        compose('up','-d','--no-build','--no-deps','--force-recreate','api','node-proxy','gateway')
        raise
    after=inspect();changed={PROJECT+'-'+n+'-1' for n in ('api','node-proxy','gateway')}
    if any(name not in changed and after.get(name,{}).get('Id')!=value['Id'] for name,value in before.items()):
        raise RuntimeError('An unrelated container changed')
    mounted=next(m for m in after[PROJECT+'-api-1']['Mounts'] if m['Destination']=='/srv/app/data')
    if mounted['Source']!=data['Source']:raise RuntimeError('Persistent data mount changed')
    report=dict(tag=tag,archiveSha256=expected,apiImage=after[PROJECT+'-api-1']['Image'],
                backupDirectory=str(backup),bodyRepair=json.loads(repair),persistentDataSource=mounted['Source'],
                externalBodyReadsEnabled=True,unrelatedContainersUnchanged=True,productionSwitched=False)
    (EVIDENCE/'deployment.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))

if __name__=='__main__':main()
