"""Build/deploy the execution feedback patch on the existing acceptance installation.

Uses release-product.py for clean images/readiness and existing Compose/configuration.
No credentials are accepted as arguments or written into images.
"""
import argparse,importlib.util,json,os,shutil,subprocess,time
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['build','deploy','verify','rollback'])
parser.add_argument('--release-dir',required=True,type=Path)
parser.add_argument('--revision',required=True)
scope_args=parser.add_mutually_exclusive_group()
scope_args.add_argument('--api-only',action='store_true',help='Patch API/UI while retaining the verified MCP and Node containers')
scope_args.add_argument('--node-only',action='store_true',help='Build a complete Linux worker from its lockfile; replace only Node and its proxy')
parser.add_argument('--image-manifest',type=Path,help='Use checksum-pinned images already loaded from the offline package')
args=parser.parse_args();os.umask(0o077)
r=args.release_dir.resolve();stage=Path('/opt/runtime-os-docker-stage');project='runtime-os-app-stage'
if not r.is_relative_to(Path('/opt/chatchat-releases')):raise ValueError('Patch must stay under release root')
spec=importlib.util.spec_from_file_location('product_release',r/'scripts/release-product.py')
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
d.release=r;d.stage=stage;d.project=project;d.version='1.2.0-20261022'
d.base='runtime-os-java-base:temurin17-jammy-20261022';d.manifest={'sourceCommit':args.revision}
(r/'evidence').mkdir(exist_ok=True)
tag=r.name
images={s:'runtime-os-'+s+':'+tag for s in (('coding-agent',) if args.node_only else ('api',) if args.api_only else ('api','mcp','coding-agent'))}
if args.image_manifest:
    manifest_path=args.image_manifest.resolve()
    if not manifest_path.is_relative_to(r):raise ValueError('Image manifest must be inside the release directory')
    pinned=json.loads(manifest_path.read_text())['images']
    if set(pinned)!=set(images):raise ValueError('Image manifest service set does not match deployment scope')
    images={service:value['tag'] for service,value in pinned.items()}
    for service,image in images.items():
        if d.inspect(image)['Id']!=pinned[service]['id']:raise ValueError('Loaded image identity mismatch: '+service)
    if args.action=='build':raise ValueError('Offline pinned images must be loaded, not rebuilt')

def verify():
    d.wait_ready()
    deadline=time.monotonic()+60
    while True:
        state=d.inspect(d.container('coding-agent'))['State']
        if state.get('Health',{}).get('Status')=='healthy':break
        if time.monotonic()>=deadline:raise RuntimeError('Node health timeout')
        time.sleep(2)

if args.action=='verify':verify();print('VERIFIED');raise SystemExit(0)
if args.action=='build':
    if args.node_only:
        context=r/'build/node'
        output=d.run('docker','build','--build-arg','NODE_RUNTIME_IMAGE=runtime-os-node-base:node24-bookworm-20261022',
            '--build-arg','BUILD_REVISION='+args.revision,'-t',images['coding-agent'],str(context))
        (r/'evidence/build-node.log').write_text(output)
        info=d.inspect(images['coding-agent'])
        if info['Size']>1200*1024*1024:raise RuntimeError('Node image size budget exceeded')
        packages=json.loads(d.run('docker','run','--rm','--network','none','--entrypoint','node',images['coding-agent'],'dist/sdk-packages.js'))
        d.record('build',{'images':{'coding-agent':{'id':info['Id'],'bytes':info['Size']}},'revision':args.revision,'packages':packages})
        print('BUILT');raise SystemExit(0)
    for service in (('api',) if args.api_only else ('api','mcp')):d.build_one(service,r/'jars'/f'{service}.jar',images[service])
    if args.api_only:
        d.record('build',{'images':{'api':{'id':d.inspect(images['api'])['Id'],'bytes':d.inspect(images['api'])['Size']}},'revision':args.revision})
        print('BUILT');raise SystemExit(0)
    context=r/'build/node';context.mkdir(parents=True,exist_ok=True)
    d.run('docker','cp',d.container('coding-agent')+':/srv/coding-agent/node_modules',str(context/'node_modules'))
    shutil.copy2(r/'deploy/docker/application/Dockerfile.coding-agent',context/'Dockerfile')
    (context/'.dockerignore').write_text('*\n!Dockerfile\n!node_modules/\n!node_modules/**\n!dist/\n!dist/**\n!package.json\n!src/\n!src/healthcheck.js\n')
    base='runtime-os-node-base:node24-bookworm-20261022'
    if '/srv/coding-agent' in d.run('docker','history','--no-trunc','--format','{{.CreatedBy}}',base):raise RuntimeError('Node base contains an application')
    output=d.run('docker','build','--build-arg','BUILD_REVISION='+args.revision,'-t',images['coding-agent'],str(context))
    (r/'evidence/build-node.log').write_text(output)
    info=d.inspect(images['coding-agent'])
    if info['Size']>1200*1024*1024:raise RuntimeError('Node image size budget exceeded')
    d.run('docker','run','--rm','--network','none','--entrypoint','node',images['coding-agent'],'--version')
    d.record('build',{'images':{s:{'id':d.inspect(image)['Id'],'bytes':d.inspect(image)['Size']} for s,image in images.items()},'revision':args.revision})
    print('BUILT');raise SystemExit(0)

def rollback(backup):
    shutil.copy2(backup/'compose.yaml',stage/'compose.yaml');shutil.copy2(backup/'deployment.env',stage/'config/deployment.env')
    d.compose('up','-d','--no-build','--no-deps','--force-recreate',*images.keys())
    d.compose('up','-d','--no-build','--no-deps','--force-recreate','node-proxy');verify()
    d.run('bash',str(stage/'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
    d.run('docker','exec',d.container('gateway'),'nginx','-s','reload')

if args.action=='rollback':
    rollback(Path(json.loads((r/'evidence/deployment.json').read_text())['backup']));print('ROLLED_BACK');raise SystemExit(0)
if (r/'evidence/deployment.json').exists():raise RuntimeError('Patch already deployed')
active=d.run('docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',
    "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
if active!='0':raise RuntimeError('Active tasks prevent deployment')
before={name:d.inspect(name) for name in d.run('docker','ps','--format','{{.Names}}').splitlines()}
if args.node_only:
    worker=before[d.container('coding-agent')]
    labels=worker['Config']['Labels']
    if labels.get('com.docker.compose.project')!=project or labels.get('com.docker.compose.service')!='coding-agent':
        raise RuntimeError('Unexpected worker deployment scope')
    probe="""const fs=require('node:fs');const e=process.env;const s=e.RUNTIME_CODING_SHARED_SECRET_FILE?fs.readFileSync(e.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim():e.RUNTIME_CODING_SHARED_SECRET;fetch('http://127.0.0.1:'+(e.RUNTIME_CODING_PORT||3100)+'/readyz',{headers:{authorization:'Bearer '+s},signal:AbortSignal.timeout(5000)}).then(async r=>{if(!r.ok)throw Error('Not ready');console.log((await r.json()).activeSessions);}).catch(()=>process.exit(1));"""
    if d.run('docker','exec',d.container('coding-agent'),'node','-e',probe)!='0':raise RuntimeError('Active Node sessions prevent replacement')
backup=r/'private-backup'/str(int(time.time()));backup.mkdir(parents=True);backup.chmod(0o700)
for source in (stage/'compose.yaml',stage/'config/deployment.env'):shutil.copy2(source,backup/source.name)
for database in (() if args.node_only else ('runtime_os_acceptance_api',) if args.api_only else ('runtime_os_acceptance_api','runtime_os_acceptance_mcp')):
    with (backup/(database+'.dump')).open('wb') as output:
        subprocess.run(['docker','exec','-u','postgres','postgres','pg_dump','-Fc',database],stdout=output,check=True)
config=json.loads((stage/'compose.yaml').read_text());lines=(stage/'config/deployment.env').read_text().splitlines()
for service,image in images.items():
    config['services'][service]['image']=image
    keys=('NODE_IMAGE','CODING_AGENT_IMAGE') if service=='coding-agent' else (service.upper().replace('-','_')+'_IMAGE',)
    lines=[line for line in lines if not any(line.startswith(key+'=') for key in keys)]+[key+'='+image for key in keys]
try:
    if args.node_only:
        worker=before[d.container('coding-agent')]
        directory=next((entry.split('=',1)[1] for entry in worker['Config']['Env'] if entry.startswith('RUNTIME_CODING_MONITOR_DIRECTORY=')),None)
        mount=next((m for m in worker['Mounts'] if m['Destination']==directory),None)
        if not mount:raise RuntimeError('Persistent worker history mount required before replacement')
        d.compose('stop','-t','60','coding-agent')
        shutil.copytree(mount['Source'],backup/'monitor-data')
    else:
        d.compose('stop','-t','60','api')
        mount=next(m for m in before[d.container('api')]['Mounts'] if m['Destination']=='/srv/app/data')
        for name in ('chat-rocksdb','ui-artifacts'):
            source=Path(mount['Source'])/name
            if source.is_dir():shutil.copytree(source,backup/name)
    (stage/'compose.yaml').write_text(json.dumps(config,indent=2)+'\n');(stage/'config/deployment.env').write_text('\n'.join(lines)+'\n')
    d.compose('config','-q');d.compose('up','-d','--no-build','--no-deps','--force-recreate',*images.keys())
    d.compose('up','-d','--no-build','--no-deps','--force-recreate','node-proxy');verify()
    d.run('bash',str(stage/'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
    d.run('docker','exec',d.container('gateway'),'nginx','-t');d.run('docker','exec',d.container('gateway'),'nginx','-s','reload')
    allowed={d.container(s) for s in (*images.keys(),'node-proxy')}
    for name,old in before.items():
        if name not in allowed and d.inspect(name)['Id']!=old['Id']:raise RuntimeError('Unrelated container changed: '+name)
    for service in images:
        old=before[d.container(service)];new=d.inspect(d.container(service))
        strip=lambda env:sorted(v for v in env if not v.startswith('RUNTIME_CODING_BUILD='))
        if strip(old['Config']['Env'])!=strip(new['Config']['Env']) or sorted(old['Mounts'],key=lambda m:m['Destination'])!=sorted(new['Mounts'],key=lambda m:m['Destination']):
            raise RuntimeError('Unexpected environment or mount changes: '+service)
    d.record('deployment',{'status':'DEPLOYED','revision':args.revision,'backup':str(backup),
        'unrelatedContainersUnchanged':True,'configurationAndMountsPreserved':True,
        'images':{s:{'id':d.inspect(image)['Id'],'bytes':d.inspect(image)['Size']} for s,image in images.items()}})
except Exception:
    rollback(backup);raise
print('DEPLOYED')
