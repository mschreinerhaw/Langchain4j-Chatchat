#!/usr/bin/env python3
"""Isolated test topology. Requires stage2-prepare.py and the stage-1 private config.
No shared container, database, model, or MCP configuration is changed.
"""
import pathlib,subprocess,json,secrets,re,time,os
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');S=R/'source'
def run(*a,**kw):
 r=subprocess.run(a,text=True,**kw)
 if r.returncode:raise RuntimeError('Deployment command failed: '+a[0]+' (arguments withheld)')
 return r
def out(*a):return subprocess.check_output(a,text=True).strip()
names=out('docker','ps','-a','--format','{{.Names}}').splitlines()
def create(name,*args):
 if name not in names:run('docker','run','-d','--name',name,*args)
def secret():return secrets.token_hex(32)
p=R/'private/config.json'
if p.exists():c=json.loads(p.read_text())
else:
 c={k:secret() for k in ('dbPassword','cryptoKey','internalSecret','gatewaySecret','mcpSecret','dispatchSecret','remoteControlSecret','mcpToken')}
 c.update(port=18502,jdbcUrl='jdbc:postgresql://cline-p32-db:5432/stage2',dbUser='stage2',pythonEnvironment='native-stage2',pythonWorkspace=str(R/'python/workspace'),pythonData=str(R/'python/data'),mcpUrl='http://cline-p32-remote:18402/mcp',remoteControlUrl='http://cline-p32-remote:18402/control',authorityUrl='http://cline-p32-authority:18502',stateRoot=str(R/'remote-state'))
 p.write_text(json.dumps(c));p.chmod(0o600)
(R/'pg').mkdir(exist_ok=True)
if 'cline-p32-control' not in out('docker','network','ls','--format','{{.Name}}').splitlines():run('docker','network','create','--internal','cline-p32-control')
create('cline-p32-db','--network','cline-p32-control','--label','cline.stage=P3.2','--mount',f'type=bind,src={R}/pg,dst=/var/lib/postgresql','-e','POSTGRES_USER=stage2','-e','POSTGRES_DB=stage2','-e','POSTGRES_PASSWORD='+c['dbPassword'],'postgres:18')
for n in range(60):
 if subprocess.run(['docker','exec','cline-p32-db','pg_isready','-U','stage2'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
 time.sleep(1)
marker=R/'private/schema-applied'
if not marker.exists():
 ddl=(S/'database/init/postgresql/chatchat-mcp-server.sql').read_text();ddl=re.search(r'create table mcp_python_environment\s*\(.*?\);',ddl,re.S).group(0)
 for f in ('V20261009_05__runtime_evidence_revision_gate.sql','V20261015_01__mcp_python_managed_operation.sql','V20261019_03__mcp_python_unadmitted_cancellation.sql'):ddl+='\n'+(S/'database/migration/postgresql'/f).read_text()
 ddl+="\nINSERT INTO mcp_python_environment (id,name,description,docker_image,python_version,cpu_limit,memory_limit,disk_limit,tmpfs_limit,runtime_user,network_policy,network_name,requirements_json,timeout_seconds,network_enabled,version_number,status,created_at,updated_at) VALUES ('native-stage2','Stage2 isolated Python','real cancellation acceptance','chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022','3.11','1','256m','128m','64m','10001:10001','NONE',NULL,'[]',120,false,1,'PUBLISHED',now(),now());"
 run('docker','exec','-i','cline-p32-db','psql','-v','ON_ERROR_STOP=1','-U','stage2','-d','stage2',input=ddl);marker.touch()
cp=':'.join(str(R/n) for n in ('classes','api-classes','mcp-classes','lib/*'))
java_image=out('docker','inspect','runtime-native-acceptance-mcp','--format','{{.Image}}')
java_mounts=[]
for n in ('classes','api-classes','mcp-classes','lib'):java_mounts+=['--mount',f'type=bind,src={R}/{n},dst={R}/{n},readonly']
for n in ('python/workspace','python/data'):java_mounts+=['--mount',f'type=bind,src={R}/{n},dst={R}/{n}']
create('cline-p32-authority','--network','cline-p32-control','--label','cline.stage=P3.2',*java_mounts,'--mount',f'type=bind,src={p},dst=/run/cline-operations.json,readonly','--mount','type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock','--health-cmd','wget -q -O /dev/null http://127.0.0.1:18502/health/live','--health-interval','5s','--health-start-period','15s','-e','CLINE_OPERATION_CONFIG=/run/cline-operations.json','--entrypoint','java',java_image,'-cp',cp,'com.chatchat.mcpserver.python.NativeStage2Application')
remote={k:c[k] for k in ('dispatchSecret','mcpSecret','mcpToken','remoteControlSecret','authorityUrl','stateRoot')};remote['port']=18402;rp=R/'private/remote.json';rp.write_text(json.dumps(remote));rp.chmod(0o600)
state=pathlib.Path(remote['stateRoot']);state.mkdir(exist_ok=True)
create('cline-p32-remote','--network','cline-p32-control','--label','cline.stage=P3.2','--mount',f'type=bind,src={S}/cline-agent-server/src/stage2-remote.mjs,dst=/app/remote.mjs,readonly','--mount',f'type=bind,src={rp},dst=/run/cline-remote.json,readonly','--mount',f'type=bind,src={state},dst={state}','--health-cmd','node -e "fetch(\'http://127.0.0.1:18402/health/live\').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"','--health-interval','5s','-e','CLINE_REMOTE_CONFIG=/run/cline-remote.json','--user','0:0','--entrypoint','node','cline-agent-server:p3-stage1','/app/remote.mjs')
print('Isolated stage2 services created. No shared service modified.')
