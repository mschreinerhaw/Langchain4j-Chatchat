#!/usr/bin/env python3
import pathlib,subprocess,json,shutil
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');old=pathlib.Path('/opt/cline-server-p3-stage1-20261108')
op=json.loads((R/'private/config.json').read_text());c=json.loads((old/'control/config.json').read_text())
(R/'control/state').mkdir(parents=True,exist_ok=True)
if not (R/'templates').exists():shutil.copytree(old/'templates',R/'templates')
c.update(port=18330,host='0.0.0.0',stateRoot='/control/state',hostStateRoot=str(R/'control/state'),workerImage=subprocess.check_output(['docker','image','inspect','cline-agent-server:p3-stage2','--format','{{.Id}}'],text=True).strip(),operations={'url':'http://cline-p32-authority:18502','secret':op['gatewaySecret']})
for t in c['tenants'].values():
 t['tools']=['read_files','search_codebase','editor','apply_patch','run_commands','use_mcp_tool','tenant_data__long_mcp','tenant_data__python_execute']
 t['mcp']={'url':'http://cline-p32-remote:18402/mcp','token':op['mcpToken'],'tools':['long_mcp','python_execute']}
p=R/'control/config.json';p.write_text(json.dumps(c));p.chmod(0o600)
names=subprocess.check_output(['docker','ps','-a','--format','{{.Names}}'],text=True).splitlines()
if 'cline-p32-control' not in names:
 subprocess.run(['docker','run','-d','--name','cline-p32-control','--user','0:0','--label','cline.stage=P3.2','--no-healthcheck','-p','127.0.0.1:18330:18330','--mount',f'type=bind,src={R}/control,dst=/control','--mount',f'type=bind,src={R}/templates,dst=/templates,readonly','--mount','type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock','-e','CLINE_GOVERNANCE_CONFIG=/control/config.json','--entrypoint','node',c['workerImage'],'/poc/service/src/governance-server.mjs'],check=True)
 subprocess.run(['docker','network','connect','cline-p32-control','cline-p32-control'],check=True)
print('Stage2 gateway on loopback 18330; worker image pinned; original gateway unchanged.')
