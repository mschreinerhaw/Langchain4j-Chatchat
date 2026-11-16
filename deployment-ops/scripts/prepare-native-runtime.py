"""Publish a signed-package recipe for the existing Native/Temporal Runtime OS installer.

The installer and all product images must already be locally source-built and
verified. This extracts public build metadata only; no running configuration,
credentials, license or application database is read.
"""
import argparse,hashlib,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--installer-image',required=True);p.add_argument('--version',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--upgrade-from',action='append',default=[]);a=p.parse_args()
if a.output.exists():raise SystemExit('Use a new immutable recipe directory')
command='''import json,hashlib,pathlib
root=pathlib.Path('/opt/ops-config')
source=pathlib.Path('/opt/product/installer.py').read_text()
assert 'nativeCoreVerified' in source and 'pythonRuntimeVerified' in source and 'runtime-native' in source, 'Native/Temporal installer protocol missing'
print(json.dumps({'images':json.loads(pathlib.Path('/opt/product/images.json').read_text()),'schemaHashes':{p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*.sql')},'installerSourceSha256':hashlib.sha256(source.encode()).hexdigest()}))
'''
metadata=json.loads(subprocess.check_output(['docker','run','--rm','--network','none','--label','deployment-ops.release-inspection=true','--entrypoint','python3',a.installer_image,'-c',command],text=True))
mapping=metadata['images'];required={'api','mcp','news','node','gateway','postgres','redis','search','silo','temporal','daemon','frontend'}
if not required<=set(mapping) or not metadata['schemaHashes']:raise SystemExit('Incomplete native Runtime OS image/schema inventory')
for role,item in mapping.items():
    actual=json.loads(subprocess.check_output(['docker','image','inspect',item['reference']]))[0]
    if actual['Id']!=item['imageId']:raise SystemExit('Local image differs from verified installer map: '+role)
a.output.mkdir(parents=True);assets=a.output/'assets';assets.mkdir()
(assets/'runtime-images.json').write_text(json.dumps(mapping,indent=2))
(assets/'runtime-source.json').write_text(json.dumps({'installerImage':a.installer_image,'installerSourceSha256':metadata['installerSourceSha256'],'schemaHashes':metadata['schemaHashes']},indent=2))
services=['postgresql','temporal-db','temporal','redis','opensearch','silo','news','mcp','api','executor-daemon','executor1','executor2','studio-executor','recovery1','recovery2','node1','node2','studio','worker1','worker2','frontend-resource-hub','frontend-worker','gateway','manager']
components=[]
for service in services:
    role='postgres' if service in ('postgresql','temporal-db') else 'search' if service=='opensearch' else 'api' if service.startswith('worker') else 'frontend' if service.startswith('frontend-') else 'installer' if service=='manager' else 'node' if service.startswith(('executor1','executor2','studio','recovery','node')) else 'daemon' if service=='executor-daemon' else service
    components.append({'id':service,'image':role,'health':{'type':'docker'}})
manifest={'schemaVersion':1,'name':'runtime-os','version':a.version,'platform':'linux/amd64','description':'Native/Temporal Runtime OS: persistent workflow engine, API/MCP/News, restricted Core/Python executors, recovery nodes and application frontend. Uses the reviewed source-built product installer. No SDK substitute, online pull, customer data or production secrets.',
 'requirements':{'memoryBytes':8*1024**3,'diskBytes':12*1024**3},'upgradeFrom':a.upgrade_from,'dataCompatibility':'backward-compatible' if a.upgrade_from else 'initial-only',
 'parameters':{
  'serverAddress':{'label':'服务器访问地址','type':'string','default':'127.0.0.1','pattern':'[A-Za-z0-9.-]+'},
  'httpPort':{'label':'Runtime OS 入口端口','type':'integer','default':19400,'min':1024,'max':65535},
  'mcpPort':{'label':'MCP 入口端口','type':'integer','default':19401,'min':1024,'max':65535},
  'directoryName':{'label':'部署目录名（可选）','type':'string','default':'','pattern':'([a-z0-9][a-z0-9-]{0,15})?','description':'目录固定在 /mnt/disk01/ops/r 下；留空生成独立目录，已有环境不允许变更数据根目录。'},
  'osAdminPassword':{'label':'Runtime OS 管理员初始密码','type':'string','secret':True,'required':True,'pattern':'[A-Za-z0-9_!@#%+.,:-]{12,128}','description':'首次部署设置 12–128 位密码，OS 和 MCP 共用初始管理员凭据。升级时留空保留原凭据。'}},
 'images':dict({role:{'source':item['reference']} for role,item in mapping.items()},installer={'source':a.installer_image}),
 'volumes':{},'components':components,
 'runtimeInstaller':{'protocol':'runtime-os-native/v1','image':'installer','imageMap':'assets/runtime-images.json','services':services,'schemaHashes':metadata['schemaHashes']},
 'links':[{'name':'Runtime OS','url':'http://${serverAddress}:${httpPort}/'},{'name':'MCP 管理入口','url':'http://${serverAddress}:${mcpPort}/'}]}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'manifest':str(a.output/'manifest.json'),'images':len(manifest['images']),'services':len(services),'protocol':'runtime-os-native/v1'}))
