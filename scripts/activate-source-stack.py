"""Activate source-built application images in the recovered Compose directory.

Existing database, model, credential and data configuration is retained. Native
business compatibility and generic S3 endpoints use separate restricted Executors.
"""
import argparse, copy, json, os, secrets, shlex, shutil, subprocess
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True)
a=p.parse_args();os.umask(0o077);stage=a.stage.resolve()
c=json.loads((stage/'compose.yaml').read_text());services=c['services']
for name in ['api','mcp','news']:
    image='runtime-os-'+name+':source-20261111'
    info=json.loads(subprocess.check_output(['docker','image','inspect',image]))[0]
    services[name]['image']=info['Id'];services[name].pop('profiles',None)
    services[name]['pull_policy']='never'
    services[name].pop('command',None)
    services[name]['entrypoint']=['java','-jar','/srv/app/app.jar']
services['gateway']['image']='nginx:latest'
services['gateway']['depends_on']={'api':{'condition':'service_started'},'mcp':{'condition':'service_started'}}
services['node-proxy']['profiles']=['legacy-sdk-disabled']
services['coding-agent']['profiles']=['legacy-sdk-disabled']
env=services['api'].get('environment',{})
if isinstance(env,list):env=dict(x.split('=',1) for x in env)
env.update(CHATCHAT_CODING_CLINE_BACKEND='native',
    CHATCHAT_CODING_CLINE_ALLOWED_PEER_ADDRESSES='127.0.0.1,::1,0:0:0:0:0:0:0:1',
    CHATCHAT_CODING_CLINE_ALLOWED_PEER_HOSTNAMES='',
    CHATCHAT_CODING_CLINE_NODE_URL='http://127.0.0.1:8791',
    CHATCHAT_CODING_INPUT_WORKSPACE_FAST_PATH_ENABLED='false')
services['api']['environment']=env
services['api']['depends_on']={'silo':{'condition':'service_started'},'redis':{'condition':'service_started'}}
root=stage/'native-business';private=root/'private';private.mkdir(parents=True,exist_ok=True)
base=json.loads((stage/'config/native-node/executor.json').read_text())
base['workerImage']=json.loads(subprocess.check_output(['docker','image','inspect','runtime-os-coding-node:source-20261111']))[0]['Id']
daemon=['docker','--host','unix://'+base['dockerSocket']]
if subprocess.run(daemon+['image','inspect',base['workerImage']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    save=subprocess.Popen(['docker','image','save',base['workerImage']],stdout=subprocess.PIPE)
    loaded=subprocess.run(daemon+['image','load'],stdin=save.stdout);save.stdout.close()
    if save.wait() or loaded.returncode:raise RuntimeError('Business Worker import failed')
template=copy.deepcopy(base)
template.update(instanceId='native-business-'+secrets.token_hex(8),
    apiToken=secrets.token_hex(32),signingKey=secrets.token_hex(32),ownershipKey=secrets.token_hex(32),
    workersRoot=str(root/'workers'),hostWorkersRoot=str(root/'workers'),
    controlRoot=str(root/'executor-control'),socketPath=str(root/'executor.sock'),
    mcpServerName='runtime',operationContext=True,modelNames={'runtime':'runtime-selected'})
for tenant in template['tenants'].values():
    tenant['models']=['runtime'];tenant['tools']=['read_files','search_codebase','skills','editor','apply_patch','submit_and_exit','runtime__*'];tenant['mcp']=True
gateway={'secret':(stage/'config/secrets/coding-agent-shared-secret').read_text().strip(),
    'signingKey':template['signingKey'],'workerImage':template['workerImage'],'workerRuntime':'cline-python311',
    'stateRoot':str(root/'state'),'workersRoot':template['workersRoot'],
    'callbackUrl':'http://127.0.0.1:8080','port':8791,'maxWorkers':2,
    'executor':{'socketPath':template['socketPath'],'token':template['apiToken']}}
for name,value in [('executor',template),('gateway',gateway)]:
    file=private/(name+'.json')
    if file.exists():raise RuntimeError('Existing business authority must be reconciled, not regenerated')
    file.write_text(json.dumps(value));file.chmod(0o600)
ex=copy.deepcopy(services['coding-executor'])
ex.update(image=base['workerImage'],container_name='runtime-business-executor',environment={'CLINE_EXECUTOR_CONFIG':str(private/'executor.json')})
ex['volumes']=[str(root)+':'+str(root),template['dedicatedDaemonRoot']+':'+template['dedicatedDaemonRoot']+':ro']
for path in sorted({v for t in template['tenants'].values() for v in t['workspaces'].values()}):ex['volumes'].append(path+':'+path+':ro')
services['coding-business-executor']=ex
services['coding-business']={'image':base['workerImage'],'container_name':'runtime-coding-business','user':'0:0',
    'restart':'unless-stopped','network_mode':'service:api',
    'entrypoint':['node','/poc/service/src/business-server.mjs'],
    'environment':{'CLINE_BUSINESS_CONFIG':str(private/'gateway.json')},
    'volumes':[str(root)+':'+str(root)],
    'depends_on':{'api':{'condition':'service_started'},'coding-business-executor':{'condition':'service_started'}}}
# Reuse the existing Native monitor and bind its diagnostic operator to the
# restored account. Browser login still verifies the real Runtime credentials.
api_env={}
for line in (stage/'config/api.env').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        key,value=line.split('=',1);parts=shlex.split(value);api_env[key]=parts[0] if parts else ''
database=api_env['CHATCHAT_API_POSTGRESQL_URL'].rsplit('/',1)[-1].split('?',1)[0]
query="select json_build_object('userId',id,'tenantId',tenant_id) from sys_user where username='admin'"
operator=json.loads(subprocess.check_output(['docker','exec','postgresql18','psql','-U','postgres','-d',database,'-tAc',query],text=True).strip())
monitor={'host':'127.0.0.1','port':8792,'runtimeUrl':'http://127.0.0.1:8080',
    'operator':operator,'businessState':str(root/'state'),'models':[]}
(private/'monitor.json').write_text(json.dumps(monitor))
services['coding-monitor']={'image':base['workerImage'],'container_name':'runtime-native-monitor',
    'user':'0:0','restart':'unless-stopped','network_mode':'service:api',
    'entrypoint':['node','/poc/service/monitor/server.mjs'],
    'environment':{'CLINE_MONITOR_CONFIG':str(private/'monitor.json')},
    'volumes':[str(root)+':'+str(root)+':ro'],'depends_on':{'api':{'condition':'service_started'}}}
gateway_file=stage/'config/public-gateway.conf';gateway_text=gateway_file.read_text()
marker='# Native monitor API routes'
if marker not in gateway_text:
    shutil.copy2(gateway_file,stage/'maintenance/public-gateway-before-source.conf')
    location='    '+marker+'\n    location ~ ^/api/(login|logout|config|sessions|dispatches|inputs|assets)(/|$) {\n      set $monitor_endpoint api:8792;\n      proxy_pass http://$monitor_endpoint;\n    }\n'
    gateway_text=gateway_text.replace('    location = / {',location+'    location = / {',1)
    gateway_file.write_text(gateway_text)
# Closed H2 database backup before starting News; never replace restored data.
db=Path('/opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data/chatchat-news.mv.db')
backup=stage/'maintenance/news-before-source-20261111.mv.db'
if db.exists() and not backup.exists():shutil.copy2(db,backup)
(stage/'compose.yaml').write_text(json.dumps(c,indent=2))
print('Application Compose activation prepared; existing data/configuration preserved')
