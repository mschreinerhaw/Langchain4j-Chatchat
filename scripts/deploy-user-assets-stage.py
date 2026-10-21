"""Deploy the pinned private SILO and an already verified API package to the existing isolated stage.

Run on the authorized host: python3 deploy-user-assets-stage.py app.jar SHA256 schema.sql [offline-image-id] [--knowledge]
Credentials are generated once into root-only stage files and never printed. Backup is taken before mutation.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path('/opt/runtime-os-docker-stage')
IMAGE='docker.io/pgsty/silo:RELEASE.2026-10-18T00-00-00Z'
DIGEST='sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7'
PROJECT='runtime-os-app-stage'

def run(*args,input=None):
    result=subprocess.run(args,input=input,text=True,capture_output=True)
    if result.returncode:
        # Subprocess output may include mc user identities/configuration. Keep failures private.
        (evidence/'last-command-error-private.txt').write_text(result.stderr+'\n'+result.stdout)
        raise RuntimeError('Deployment command failed: '+args[0]+' (details in root-only evidence)')
    return result.stdout.strip()

def compose(*args):
    return run('docker','compose','--project-name',PROJECT,'--env-file',str(ROOT/'config/deployment.env'),'-f',str(ROOT/'compose.yaml'),*args)

def wait(url,seconds=180):
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            with client.open(url,timeout=3) as response:
                if response.status==200:return
        except urllib.error.HTTPError as error:
            if error.code in (401,403):return
        except OSError:pass
        time.sleep(2)
    raise RuntimeError('Service readiness timeout')

def main():
    global evidence
    os.umask(0o077)
    jar=Path(sys.argv[1]);expected=sys.argv[2];schema=Path(sys.argv[3])
    evidence=ROOT/'evidence/user-assets-silo-20261021';evidence.mkdir(mode=0o700,exist_ok=True)
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=expected:raise RuntimeError('Package hash mismatch')
    current=json.loads(run('docker','inspect',PROJECT+'-api-1'))[0]
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    monitor=urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',headers={
        'Authorization':'Bearer '+(ROOT/'config/coding-node-monitor.token').read_text().strip()})
    with client.open(monitor,timeout=10) as response:
        if json.load(response)['sessions']['active']!=0:raise RuntimeError('Active Cline sessions prevent API replacement')
    if run('docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',
        "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")!='0':
        raise RuntimeError('Active parent tasks prevent API replacement')
    if run('docker','ps','--filter','name=chatchat-py-','--format','{{.ID}}'):raise RuntimeError('Active Python operations prevent replacement')
    config=json.loads((ROOT/'compose.yaml').read_text())
    backup=evidence/('backup-'+str(int(time.time())));backup.mkdir(mode=0o700)
    for path in (ROOT/'compose.yaml',ROOT/'config/deployment.env'):shutil.copy2(path,backup/path.name)
    run('docker','cp',PROJECT+'-api-1:/srv/app/app.jar',str(backup/'app.jar'))
    # Existing tables are retained on rollback. Schema is additive and versioned with this change.
    (backup/'schema.sql').write_bytes(schema.read_bytes())
    run('docker','exec','-i','-u','postgres','postgres','psql','-v','ON_ERROR_STOP=1','-d','runtime_os_acceptance_api',input=schema.read_text())
    if subprocess.run(['docker','image','inspect',IMAGE],capture_output=True).returncode:run('docker','pull',IMAGE)
    inspected=json.loads(run('docker','image','inspect',IMAGE))[0]
    registry_verified=any(item.endswith('@'+DIGEST) for item in inspected.get('RepoDigests',[]))
    offline_id=next((arg for arg in sys.argv[4:] if not arg.startswith('--')),None)
    if not registry_verified and (not offline_id or inspected['Id']!=offline_id):raise RuntimeError('Pinned SILO content differs')
    silo=ROOT/'silo';silo.mkdir(mode=0o700,exist_ok=True);(silo/'data').mkdir(mode=0o700,exist_ok=True)
    secret=silo/'secrets';secret.mkdir(mode=0o700,exist_ok=True)
    for name,value in [('root-user','runtime-silo-admin'),('root-password',secrets.token_urlsafe(36)),('app-user','runtime-assets-api'),('app-password',secrets.token_urlsafe(36))]:
        path=secret/name
        if not path.exists():path.write_text(value+'\n')
        path.chmod(0o600)
    policy={'Version':'2012-10-17','Statement':[
        {'Effect':'Allow','Action':['s3:GetBucketLocation','s3:ListBucket','s3:ListBucketVersions'], 'Resource':['arn:aws:s3:::runtime-assets']},
        {'Effect':'Allow','Action':['s3:GetObject','s3:GetObjectVersion','s3:PutObject','s3:DeleteObject','s3:DeleteObjectVersion','s3:AbortMultipartUpload','s3:ListMultipartUploadParts'], 'Resource':['arn:aws:s3:::runtime-assets/tenants/*']}]}
    (silo/'app-policy.json').write_text(json.dumps(policy))
    config['services']['silo']={'image':IMAGE+'@'+DIGEST if registry_verified else IMAGE,'container_name':'silo','restart':'unless-stopped',
        'command':['server','/data','--console-address',':9001'],
        'ports':['127.0.0.1:9000:9000','127.0.0.1:9001:9001'],
        'environment':{'MINIO_ROOT_USER_FILE':'/run/secrets/root-user','MINIO_ROOT_PASSWORD_FILE':'/run/secrets/root-password'},
        'volumes':[str(silo/'data')+':/data',str(secret)+':/run/secrets:ro',str(silo/'app-policy.json')+':/bootstrap/app-policy.json:ro'],
        'networks':['control'],'security_opt':['no-new-privileges:true'],'mem_limit':'1g','cpus':2}
    (ROOT/'compose.yaml').write_text(json.dumps(config,indent=2))
    compose('up','-d','--no-deps','silo');wait('http://127.0.0.1:9000/minio/health/ready')
    bootstrap='''set -eu
export MC_HOST_store="http://$(cat /run/secrets/root-user):$(cat /run/secrets/root-password)@127.0.0.1:9000"
mc mb --ignore-existing store/runtime-assets
mc version enable store/runtime-assets
mc anonymous set none store/runtime-assets
mc admin user add store "$(cat /run/secrets/app-user)" "$(cat /run/secrets/app-password)"
mc admin policy create store runtime-assets-api /bootstrap/app-policy.json
mc admin policy attach store runtime-assets-api --user "$(cat /run/secrets/app-user)"
'''
    run('docker','exec','-i','silo','sh',input=bootstrap)
    context=evidence/'api-image';context.mkdir(exist_ok=True);shutil.copy2(jar,context/'app.jar')
    tag='runtime-os-api:user-assets-'+expected[:12]
    (context/'Dockerfile').write_text('FROM '+current['Config']['Image']+'\nCOPY app.jar /srv/app/app.jar\n')
    run('docker','build','--network=none','-t',tag,str(context))
    service=config['services']['api'];service['image']=tag
    env=service.setdefault('environment',{})
    if not isinstance(env,dict):raise RuntimeError('Structured API environment required')
    env.update({'CHATCHAT_ASSETS_ENABLED':'true','CHATCHAT_ASSETS_ENDPOINT':'http://silo:9000','CHATCHAT_ASSETS_BUCKET':'runtime-assets',
        'CHATCHAT_ASSETS_ACCESS_KEY_FILE':'/run/secrets/asset-access-key','CHATCHAT_ASSETS_SECRET_KEY_FILE':'/run/secrets/asset-secret-key'})
    if '--knowledge' in sys.argv[4:]:env['CHATCHAT_ASSETS_KNOWLEDGE_ENABLED']='true'
    mounts=service.setdefault('volumes',[])
    for source,target in [('app-user','asset-access-key'),('app-password','asset-secret-key')]:
        mount=str(secret/source)+':/run/secrets/'+target+':ro'
        if mount not in mounts:mounts.append(mount)
    # User namespaces may run API as a nonroot UID. Match its exact runtime user without broadening host secret modes.
    user=run('docker','exec',PROJECT+'-api-1','id','-u')
    group=run('docker','exec',PROJECT+'-api-1','id','-g')
    for name in ('app-user','app-password'):os.chown(secret/name,int(user),int(group))
    (ROOT/'compose.yaml').write_text(json.dumps(config,indent=2))
    deployment=ROOT/'config/deployment.env'
    deployment.write_text('\n'.join(line for line in deployment.read_text().splitlines() if not line.startswith('API_IMAGE='))+'\nAPI_IMAGE='+tag+'\n')
    try:
        compose('up','-d','--no-deps','--force-recreate','api');wait('http://127.0.0.1:18180/api/v1/data-science/user-assets/space')
        compose('up','-d','--no-deps','--force-recreate','node-proxy')
        run('bash',str(ROOT/'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        run('docker','exec',PROJECT+'-gateway-1','nginx','-t');run('docker','exec',PROJECT+'-gateway-1','nginx','-s','reload')
    except Exception:
        for name,target in [('compose.yaml',ROOT/'compose.yaml'),('deployment.env',deployment)]:shutil.copy2(backup/name,target)
        compose('up','-d','--no-deps','--force-recreate','api');wait('http://127.0.0.1:18180/api/v1/enterprise/auth/me')
        compose('up','-d','--no-deps','--force-recreate','node-proxy')
        run('bash',str(ROOT/'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        run('docker','exec',PROJECT+'-gateway-1','nginx','-s','reload')
        raise
    report={'status':'DEPLOYED','image':tag,'jarSha256':expected,'siloImage':IMAGE,'siloDigest':DIGEST,
        'siloVerifiedImageId':inspected['Id'],'offlineImageImport':not registry_verified,
        'backup':str(backup),'bucket':'runtime-assets','ports':'127.0.0.1:9000/9001','schemaSha256':hashlib.sha256(schema.read_bytes()).hexdigest()}
    (evidence/'deployment.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)

if __name__=='__main__':main()
