#!/usr/bin/env python3
"""Operate the existing installation without changing its data locations."""
import argparse
import datetime
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import tempfile
import re
import zipfile

ROOT = Path(__file__).resolve().parent
STAGE = Path('/opt/runtime-os-docker-stage')
SOURCE = Path('/opt/runtime-os/releases/latest-main-20261115/source')
NATIVE = Path('/opt/runtime-os/native-temporal/rc-20261112/compose.private.json')
NATIVE_PROJECT = 'runtime-native-temporal-candidate'

# A role is a container. A public name can represent several roles of one image.
ROLES = {
    'postgresql': ('postgresql18', [], ROOT/'infrastructure.compose.json', 'postgresql', None),
    'opensearch': ('opensearch-node', [], None, None, None),
    'redis': ('redis', [], None, None, None),
    'silo': ('silo', [], None, None, None),
    'news': ('runtime-os-app-stage-news-1', ['postgresql'], STAGE/'compose.yaml', 'news', None),
    'mcp': ('runtime-os-app-stage-mcp-1', ['postgresql','opensearch','silo','news'], STAGE/'compose.yaml', 'mcp', None),
    'frontend-hub': ('frontend-resource-hub-frontend-resource-hub-1', [], Path('/opt/frontend-resource-hub/1.0.0/compose.yaml'), 'frontend-resource-hub', 'frontend-resource-hub'),
    'frontend-worker': ('frontend-resource-hub-frontend-preview-worker-1', ['frontend-hub'], Path('/opt/frontend-resource-hub/1.0.0/compose.yaml'), 'frontend-preview-worker', 'frontend-resource-hub'),
    'control-api': ('runtime-native-temporal-api', ['postgresql','redis','opensearch','silo','mcp'], NATIVE, 'temporal-control-api', NATIVE_PROJECT),
    'temporal': ('runtime-native-temporal-server', ['control-api'], NATIVE, 'temporal', NATIVE_PROJECT),
    'executor1': ('runtime-native-temporal-executor1', ['control-api'], NATIVE, 'executor1', NATIVE_PROJECT),
    'executor2': ('runtime-native-temporal-executor2', ['control-api'], NATIVE, 'executor2', NATIVE_PROJECT),
    'recovery1': ('runtime-native-temporal-recovery1', ['executor1'], NATIVE, 'recovery1', NATIVE_PROJECT),
    'recovery2': ('runtime-native-temporal-recovery2', ['executor2'], NATIVE, 'recovery2', NATIVE_PROJECT),
    'node1': ('runtime-native-temporal-node1', ['recovery1'], NATIVE, 'node1', NATIVE_PROJECT),
    'node2': ('runtime-native-temporal-node2', ['recovery2'], NATIVE, 'node2', NATIVE_PROJECT),
    'worker1': ('runtime-native-temporal-worker1', ['temporal','node1'], NATIVE, 'worker1', NATIVE_PROJECT),
    'worker2': ('runtime-native-temporal-worker2', ['temporal','node2'], NATIVE, 'worker2', NATIVE_PROJECT),
    'studio': ('runtime-coding-studio-gateway', ['control-api','node1','node2','frontend-worker'], NATIVE, 'studio-gateway', NATIVE_PROJECT),
    'nginx': ('runtime-os-app-stage-gateway-1', ['worker1','worker2','studio'], STAGE/'compose.gateway-ui.json', 'gateway', 'runtime-os-stage-maintenance'),
}
GROUPS = {
    'opensearch':['opensearch'], 'silo':['silo'], 'postgresql':['postgresql'], 'redis':['redis'],
    'news':['news'], 'mcp':['mcp'], 'api':['control-api','worker1','worker2'],
    'temporal':['temporal'], 'native-node':['executor1','executor2','recovery1','recovery2','node1','node2'],
    'studio':['studio'], 'frontend-resource-hub':['frontend-hub','frontend-worker'], 'nginx':['nginx'],
    'all':list(ROLES),
}

def order(targets):
    result=[]
    def visit(role):
        if role in result:return
        for dep in ROLES[role][1]:visit(dep)
        result.append(role)
    for role in targets:visit(role)
    return result

def dependents(targets):
    found=set(targets)
    while True:
        extra={r for r,v in ROLES.items() if any(d in found for d in v[1])}
        if extra <= found:return found
        found |= extra

def execute(cmd, dry=False, cwd=None):
    print('$ '+shlex.join([str(v) for v in cmd]),flush=True)
    if not dry:subprocess.run(cmd,check=True,cwd=cwd)

def container(role):
    p=subprocess.run(['docker','inspect',ROLES[role][0]],capture_output=True,text=True)
    if p.returncode:return None
    return json.loads(p.stdout)[0]

def compose(role):
    _,_,path,_,project=ROLES[role]
    cmd=['docker','compose']
    if project:cmd+=['-p',project]
    if path.parent==STAGE:cmd+=['--env-file',str(STAGE/'config/deployment.env')]
    return cmd+['-f',str(path)]

def ready(role, timeout=120):
    limit=time.monotonic()+timeout
    while time.monotonic()<limit:
        c=container(role)
        if not c or not c['State']['Running']:raise RuntimeError(ROLES[role][0]+' did not stay running; check logs')
        health=c['State'].get('Health',{}).get('Status')
        if health is None or health=='healthy':return
        if health=='unhealthy':raise RuntimeError(ROLES[role][0]+' is unhealthy; check logs')
        time.sleep(2)
    raise RuntimeError(ROLES[role][0]+' health check timed out')

def start_roles(targets,dry=False):
    roles=order(targets)
    print('Start in order: '+', '.join(ROLES[r][0] for r in roles),flush=True)
    for role in roles:
        c=container(role)
        if c and c['State']['Running']:
            print('RUNNING '+ROLES[role][0],flush=True)
            if not dry:ready(role)
            continue
        if c:execute(['docker','start',ROLES[role][0]],dry)
        elif ROLES[role][2]:execute(compose(role)+['up','-d','--no-deps','--no-build',ROLES[role][3]],dry)
        else:execute(['bash',str(ROOT/'docs'/role/'run.sh')],dry)
        if not dry:ready(role)

def stop_roles(targets,dry=False):
    affected=dependents(targets)
    active=[r for r in reversed(order(list(ROLES))) if r in affected and (container(r) or {}).get('State',{}).get('Running')]
    print('Stop dependents first: '+', '.join(ROLES[r][0] for r in active),flush=True)
    for r in active:execute(['docker','stop','-t','60',ROLES[r][0]],dry)
    return active

def image_exists(image):
    subprocess.run(['docker','image','inspect',image],check=True,stdout=subprocess.DEVNULL)

def require_idle(dry=False):
    if dry:
        print('CHECK: require no active business tasks before stopping services')
        return
    sql="select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','FAILED','CANCELLED','PARTIAL','REJECTED')"
    result=subprocess.check_output(['docker','exec','postgresql18','psql','-U','postgres','-d','runtime_os_acceptance_api','-Atc',sql],text=True).strip()
    if result!='0':raise RuntimeError('Business tasks are active or unknown; finish/cancel them before this operation')

def build(service,image,dry=False):
    if service not in ['api','mcp','news','native-node','studio','frontend-resource-hub']:
        raise RuntimeError('This is an upstream image. Use its docker pull command; no local build is required.')
    if not image:raise RuntimeError('Specify a new image tag with --image, for example runtime-api:release-20261115-r2')
    if subprocess.run(['docker','image','inspect',image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise RuntimeError('Image tag already exists. Choose a new release tag so the previous image remains available.')
    args=['docker','build','--network=host','--progress=plain','-t',image]
    node='runtime-source-base:node-build-image-280af32071729473'
    java='runtime-source-base:java-build-image-fee8cb4328cdfa32'
    if service=='api':
        args+=['-f','deploy/docker/source-stack/Dockerfile.java-skills','--target','api','--build-arg','NODE_BUILD_IMAGE='+node,'--build-arg','JAVA_BUILD_IMAGE='+java,'--build-arg','API_RUNTIME_IMAGE=runtime-api:studio-lease-20261114']
    elif service in ['mcp','news']:
        args+=['-f','deploy/docker/source-stack/Dockerfile.extra','--target',service,'--build-arg','NODE_BUILD_IMAGE='+node,'--build-arg','JAVA_BUILD_IMAGE='+java,'--build-arg','JAVA_RUNTIME_IMAGE='+java]
    elif service=='native-node':args+=['-f','cline-agent-server/docker/Dockerfile.temporal','--build-arg','CODING_NODE_IMAGE=runtime-node:exploration-model-fix-20261113']
    elif service=='studio':args+=['-f','Dockerfile.studio-update']
    else:args+=['-f','Dockerfile']
    cwd=Path('/opt/frontend-resource-hub/1.0.0') if service=='frontend-resource-hub' else SOURCE
    if not cwd.is_dir():raise RuntimeError('Source directory does not exist: '+str(cwd))
    print('BUILD SOURCE: '+str(cwd),flush=True)
    execute(args+['.'],dry,cwd)
    if not dry:image_exists(image)
    print('Built only. Deploy separately to change the running service.',flush=True)

def prepare_frontend(image,release):
    ui=release/'public-ui';ui.mkdir()
    candidate=subprocess.check_output(['docker','create',image],text=True).strip()
    try:
        with tempfile.TemporaryDirectory(prefix='jar-',dir=release) as temporary:
            jar=Path(temporary)/'app.jar'
            subprocess.run(['docker','cp',candidate+':/srv/app/app.jar',str(jar)],check=True)
            with zipfile.ZipFile(jar) as z:
                prefix='BOOT-INF/classes/static/'
                for entry in z.infolist():
                    if entry.is_dir() or not entry.filename.startswith(prefix):continue
                    target=ui/entry.filename[len(prefix):]
                    if not target.resolve().is_relative_to(ui.resolve()):raise RuntimeError('Invalid frontend archive path')
                    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(entry))
            index=ui/'index.html'
            if not index.is_file():raise RuntimeError('Built API image is missing index.html')
            assets=re.findall(r'''(?:src|href)=["'](/assets/[^"']+)["']''',index.read_text())
            if not assets or any(not (ui/url.lstrip('/')).is_file() for url in assets):
                raise RuntimeError('Built API frontend is incomplete')
    finally:subprocess.run(['docker','rm',candidate],check=True,stdout=subprocess.DEVNULL)
    return ui

def deploy(service,image,dry=False):
    if service not in ['api','mcp','news','native-node','studio','frontend-resource-hub']:
        raise RuntimeError('Deploy supports source-built business images. Infrastructure upgrades use the corresponding image guide.')
    if not image:raise RuntimeError('Specify the already built local image with --image')
    image_exists(image)
    targets=GROUPS[service]
    path=ROLES[targets[0]][2]
    yaml_hub=service=='frontend-resource-hub'
    original=path.read_text()
    data=None if yaml_hub else json.loads(original)
    if yaml_hub:
        refs=re.findall(r'^\s*image:\s*(\S+)\s*$',original,re.MULTILINE)
        if len(refs)!=2:raise RuntimeError('Expected two explicit image fields in the frontend Compose file')
        old=dict(zip([ROLES[r][3] for r in targets],refs))
    else:old={ROLES[r][3]:data['services'][ROLES[r][3]]['image'] for r in targets}
    print('DEPLOY '+service+' -> '+image+'; previous images: '+json.dumps(old),flush=True)
    require_idle(dry)
    if dry:
        print('BACKUP: existing Compose -> private/releases/<release>/compose.json')
        print('UPDATE: image fields only in '+str(path))
        if service=='api':print('FRONTEND: extract and validate matching UI from the new image; update the gateway static mount')
        print('RECREATE: '+', '.join(ROLES[r][0] for r in order(list(dependents(targets))) if r in dependents(targets)))
        return
    # Persist the old config and exact affected containers before any stop.
    release=ROOT/'private/releases'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    release.mkdir(parents=True,mode=0o700)
    (release/'compose.json').write_text(path.read_text());(release/'compose.json').chmod(0o600)
    gateway_path=STAGE/'compose.gateway-ui.json'
    gateway_original=gateway_path.read_text() if service=='api' else None
    gateway_conf=STAGE/'config/public-gateway.conf'
    conf_original=gateway_conf.read_text() if service=='api' else None
    conf_updated=None
    frontend=None
    if service=='api':
        (release/'gateway-compose.json').write_text(gateway_original);(release/'gateway-compose.json').chmod(0o600)
        frontend=prepare_frontend(image,release)
        # Gateway UID 101 must be able to traverse the otherwise private release root.
        # The parent private directory is 0700; mount the UI directly, never its parent.
        frontend.chmod(0o755)
        (release/'gateway.conf').write_text(conf_original)
        info=json.loads(subprocess.check_output(['docker','image','inspect',image]))[0]
        def version(match):
            value=json.loads(match.group(2))
            for key in ['sourceCommit','frontendSourceCommit','monitorSourceCommit']:value.pop(key,None)
            value.update({'release':image,'api':image,'apiImageId':info['Id'],'frontendImage':image})
            return match.group(1)+json.dumps(value,separators=(',',':'))+match.group(3)
        conf_updated,count=re.subn(r"(location = /version\.json[^\n]*?return 200 ')([^']*)(')",version,conf_original)
        if count!=1:raise RuntimeError('Expected one gateway version.json location')
    affected=dependents(targets)
    active={r for r in affected if (container(r) or {}).get('State',{}).get('Running')}
    record={'service':service,'image':image,'previousImages':old,'config':str(path),'restoreRoles':sorted(active),'status':'PREPARED'}
    (release/'release.json').write_text(json.dumps(record,indent=2));(release/'release.json').chmod(0o600)
    try:
        stop_roles(targets)
        if yaml_hub:path.write_text(re.sub(r'^(\s*image:)\s*\S+\s*$',lambda m:m.group(1)+' '+image,original,flags=re.MULTILINE))
        else:
            for r in targets:data['services'][ROLES[r][3]]['image']=image
            path.write_text(json.dumps(data,indent=2))
        path.chmod(0o600)
        if service=='api':
            gateway=json.loads(gateway_original)
            mounts=gateway['services']['gateway']['volumes']
            found=False
            for mount in mounts:
                if isinstance(mount,dict) and mount.get('target')=='/srv/task-lifecycle-ui':
                    mount['source']=str(frontend);found=True
            if not found:raise RuntimeError('Gateway frontend mount was not found')
            gateway_path.write_text(json.dumps(gateway,indent=2));gateway_path.chmod(0o600)
            gateway_conf.write_text(conf_updated)
        # Remove stopped namespace children before their parent is recreated.
        # Volumes are retained: no -v and no volume/image pruning.
        for r in reversed(order(list(affected))):
            if r in affected and container(r):execute(['docker','rm',ROLES[r][0]])
        start_roles(list(active|set(targets)))
        record['status']='DEPLOYED'
    except Exception:
        path.write_text((release/'compose.json').read_text());path.chmod(0o600)
        if gateway_original is not None:gateway_path.write_text(gateway_original);gateway_path.chmod(0o600)
        if conf_original is not None:gateway_conf.write_text(conf_original)
        record['status']='FAILED_CONFIG_RESTORED'
        try:
            stop_roles(targets)
            for r in reversed(order(list(affected))):
                if r in affected and container(r):execute(['docker','rm',ROLES[r][0]])
            start_roles(list(active))
            record['status']='FAILED_ROLLED_BACK'
        except Exception as rollback_error:
            record['rollbackError']=str(rollback_error)
        print('Deployment failed; recovery status '+record['status']+'. Record: '+str(release),file=sys.stderr)
        raise
    finally:(release/'release.json').write_text(json.dumps(record,indent=2))
    print('Release record: '+str(release))

def main():
    p=argparse.ArgumentParser(description='Start, stop, inspect, build and deploy the installed system.')
    p.add_argument('action',choices=['start','stop','restart','status','logs','build','deploy'])
    p.add_argument('service',choices=list(GROUPS),nargs='?',default='all')
    p.add_argument('--image');p.add_argument('--dry-run',action='store_true')
    a=p.parse_args();targets=GROUPS[a.service]
    if a.action=='start':start_roles(targets,a.dry_run)
    elif a.action in ['stop','restart']:
        require_idle(a.dry_run)
        active=stop_roles(targets,a.dry_run)
        if a.action=='restart':start_roles(list(set(active)|set(targets)),a.dry_run)
    elif a.action=='status':
        for role in order(targets):
            c=container(role);state=c['State'] if c else {}
            image=c['Config']['Image'] if c else '-'
            print(f"{ROLES[role][0]:48} {state.get('Status','MISSING'):12} {state.get('Health',{}).get('Status',''):9} {image}")
    elif a.action=='logs':
        for role in targets:execute(['docker','logs','--tail','100',ROLES[role][0]],a.dry_run)
    elif a.action=='build':build(a.service,a.image,a.dry_run)
    elif a.action=='deploy':deploy(a.service,a.image,a.dry_run)

if __name__=='__main__':
    try:main()
    except (RuntimeError,subprocess.CalledProcessError) as error:
        print('ERROR: '+str(error),file=sys.stderr);sys.exit(1)
