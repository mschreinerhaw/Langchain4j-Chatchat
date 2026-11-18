"""Publish a signed-package recipe for the existing Native/Temporal Runtime OS installer.

The installer and all product images must already be locally source-built and
verified. This extracts public build metadata only; no running configuration,
credentials, license or application database is read.
"""
import argparse,hashlib,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--installer-image',required=True);p.add_argument('--version',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--upgrade-from',action='append',default=[]);a=p.parse_args()
if a.output.exists():raise SystemExit('Use a new immutable recipe directory')
command='''import json,hashlib,pathlib,ast,runpy,tempfile,collections
root=pathlib.Path('/opt/ops-config')
source=pathlib.Path('/opt/product/installer.py').read_text()
assert 'nativeCoreVerified' in source and 'pythonRuntimeVerified' in source and 'runtime-native' in source, 'Native/Temporal installer protocol missing'
images=json.loads(pathlib.Path('/opt/product/images.json').read_text())
# Call only the pure renderer, bypassing __init__/install. No Docker socket,
# business mount, customer configuration or production credentials are exposed.
namespace=runpy.run_path('/opt/product/installer.py',run_name='protocol_export')
cls=namespace['Installation'];app=object.__new__(cls)
temporary=pathlib.Path(tempfile.mkdtemp(prefix='contract-'));(temporary/'data').mkdir()
cls.render.__globals__['ROOT']=temporary
class SecretPlaceholders(dict):
 def __missing__(self,key):self[key]='__INSTALLER_GENERATED_'+key+'__';return self[key]
app.secret=SecretPlaceholders();app.state={'project':'runtime-contract','httpPort':19400,'mcpPort':19401,'hostMac':'02:00:00:00:00:01','secrets':app.secret,'adminPassword':'__USER_INPUT_osAdminPassword__','siloUser':'__INSTALLER_GENERATED_siloUser__'}
app.images=images;app.image='__SELF_INSTALLER__';app.project='runtime-contract';app.host=pathlib.Path('/mnt/disk01/ops/r/contract');app.compose=temporary/'compose.json';app.render()
tree=ast.parse(source);install=next(m for c in tree.body if isinstance(c,ast.ClassDef) and c.name=='Installation' for m in c.body if isinstance(m,ast.FunctionDef) and m.name=='install')
stages=[]
for statement in install.body:
 if isinstance(statement,ast.Expr) and isinstance(statement.value,ast.Call):
  call=statement.value
  if isinstance(call.func,ast.Attribute) and isinstance(call.func.value,ast.Name) and call.func.value.id=='self' and call.func.attr=='up':
   assert all(isinstance(v,ast.Constant) and isinstance(v.value,str) for v in call.args),'Dynamic native stages require reviewed exporter support'
   stages.append([v.value for v in call.args])
assert stages and set(sum(stages,[]))==set(app.services),'Native renderer/stage inventory mismatch'
public={name:{k:value[k] for k in ['image','entrypoint','command','healthcheck','network_mode'] if k in value} for name,value in app.services.items()}
print(json.dumps({'images':images,'schemaHashes':{p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*.sql')},'installerSourceSha256':hashlib.sha256(source.encode()).hexdigest(),'startup':public,'stages':stages}))
'''
metadata=json.loads(subprocess.check_output(['docker','run','--rm','--network','none','--label','deployment-ops.release-inspection=true','--entrypoint','python3',a.installer_image,'-c',command],text=True))
mapping=metadata['images'];required={'api','mcp','news','node','gateway','postgres','redis','search','silo','temporal','daemon','frontend'}
if not required<=set(mapping) or not metadata['schemaHashes']:raise SystemExit('Incomplete native Runtime OS image/schema inventory')
image_configs={}
for role,item in mapping.items():
    actual=json.loads(subprocess.check_output(['docker','image','inspect',item['reference']]))[0]
    if actual['Id']!=item['imageId']:raise SystemExit('Local image differs from verified installer map: '+role)
    image_configs[role]=actual['Config']
image_configs['installer']=json.loads(subprocess.check_output(['docker','image','inspect',a.installer_image]))[0]['Config']
a.output.mkdir(parents=True);assets=a.output/'assets';assets.mkdir()
(assets/'runtime-images.json').write_text(json.dumps(mapping,indent=2))
(assets/'runtime-source.json').write_text(json.dumps({'installerImage':a.installer_image,'installerSourceSha256':metadata['installerSourceSha256'],'schemaHashes':metadata['schemaHashes']},indent=2))
services=['postgresql','temporal-db','temporal','redis','opensearch','silo','news','mcp','api','executor-daemon','executor1','executor2','studio-executor','recovery1','recovery2','node1','node2','studio','worker1','worker2','frontend-resource-hub','frontend-worker','gateway','manager']
components=[]
dependencies={};previous=[]
for group in metadata['stages']:
    for service in group:dependencies[service]=list(previous)
    previous=group
for service in services:
    role='postgres' if service in ('postgresql','temporal-db') else 'search' if service=='opensearch' else 'api' if service.startswith('worker') else 'frontend' if service.startswith('frontend-') else 'installer' if service=='manager' else 'node' if service.startswith(('executor1','executor2','studio','recovery','node')) else 'daemon' if service=='executor-daemon' else service
    actual=metadata['startup'][service];config=image_configs[role]
    entrypoint=actual.get('entrypoint',config.get('Entrypoint') or [])
    command=actual.get('command',[] if 'entrypoint' in actual else config.get('Cmd') or [])
    if not isinstance(entrypoint,list) or not isinstance(command,list):raise SystemExit('Explicit argv arrays required: '+service)
    def literal_argv(values):
        if any('__INSTALLER_GENERATED_' in value or '__USER_INPUT_' in value for value in values):raise SystemExit('Generated values in argv need an explicit protocol binding: '+service)
        return [value.replace('${','$${') for value in values]
    component={'id':service,'image':role,'dependsOn':dependencies[service],'entrypoint':literal_argv(entrypoint),'command':literal_argv(command),'health':{'type':'docker','source':'docker' if actual.get('healthcheck') else 'native-installer'}}
    network=actual.get('network_mode','')
    if network.startswith('service:'):
        component['networkFrom']=network.split(':',1)[1]
        if component['networkFrom'] not in component['dependsOn']:component['dependsOn'].append(component['networkFrom'])
    components.append(component)
manifest={'schemaVersion':1,'name':'runtime-os','version':a.version,'platform':'linux/amd64','description':'Native/Temporal Runtime OS: persistent workflow engine, API/MCP/News, restricted Core/Python executors, recovery nodes and application frontend. Uses the reviewed source-built product installer. No SDK substitute, online pull, customer data or production secrets.',
 'requirements':{'memoryBytes':8*1024**3,'diskBytes':12*1024**3},'upgradeFrom':a.upgrade_from,'dataCompatibility':'backward-compatible' if a.upgrade_from else 'initial-only',
 'parameters':{
  'serverAddress':{'label':'服务器访问地址','type':'string','default':'127.0.0.1','pattern':'[A-Za-z0-9.-]+'},
  'httpPort':{'label':'Runtime OS 入口端口','type':'integer','default':19400,'min':1024,'max':65535},
  'mcpPort':{'label':'MCP 入口端口','type':'integer','default':19401,'min':1024,'max':65535},
  'directoryName':{'label':'部署目录名（可选）','type':'string','default':'','pattern':'([a-z0-9][a-z0-9-]{0,15})?','description':'目录位于管理员选择的应用数据根目录下；留空生成独立目录，已有环境不允许变更数据根目录。'},
  'osAdminPassword':{'label':'Runtime OS 管理员初始密码','type':'string','secret':True,'required':True,'pattern':'[A-Za-z0-9_!@#%+.,:-]{12,128}','description':'首次部署设置 12–128 位密码，OS 和 MCP 共用初始管理员凭据。升级时留空保留原凭据。'}},
 'images':dict({role:{'source':item['reference']} for role,item in mapping.items()},installer={'source':a.installer_image}),
 'volumes':{},'components':components,
 'runtimeInstaller':{'protocol':'runtime-os-native/v1','contractVersion':1,'image':'installer','imageMap':'assets/runtime-images.json','services':services,'schemaHashes':metadata['schemaHashes']},
 'links':[{'name':'Runtime OS','url':'http://${serverAddress}:${httpPort}/'},{'name':'MCP 管理入口','url':'http://${serverAddress}:${mcpPort}/'}]}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'manifest':str(a.output/'manifest.json'),'images':len(manifest['images']),'services':len(services),'protocol':'runtime-os-native/v1'}))
