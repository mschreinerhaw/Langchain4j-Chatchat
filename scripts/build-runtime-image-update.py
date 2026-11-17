#!/usr/bin/env python3
"""Build source overlays on an explicitly pinned, verified Native Runtime release.

Only release sources and image metadata enter the build. No customer mounts,
credentials, Docker volumes or application databases are copied into images.
"""
import argparse, hashlib, json, pathlib, subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--base-installer',required=True)
p.add_argument('--source-commit',required=True)
p.add_argument('--version',required=True)
a=p.parse_args()
assert len(a.source_commit)==40 and all(x in '0123456789abcdef' for x in a.source_commit)
assert a.version.replace('.','').isalnum()
a.output.mkdir(parents=True,exist_ok=False)
def run(argv,**kwargs):return subprocess.run([str(x) for x in argv],check=True,**kwargs)
def inspect(ref):return json.loads(subprocess.check_output(['docker','image','inspect',ref]))[0]
def local_base(identity):
 ref='runtime-release-base:'+identity.removeprefix('sha256:')
 existing=subprocess.run(['docker','image','inspect',ref],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if existing.returncode:run(['docker','tag',identity,ref])
 assert inspect(ref)['Id']==identity,'Local build base changed'
 return ref
base=inspect(a.base_installer)['Id']
images=json.loads(subprocess.check_output(['docker','run','--rm','--network=none','--label','deployment-ops.release-inspection=true','--entrypoint','cat',base,'/opt/product/images.json']))
original=json.loads(json.dumps(images))
for role,entry in images.items():assert inspect(entry['reference'])['Id']==entry['imageId'],role
for role in list(images):
 ref='runtime-os-'+role+':'+a.version
 exists=subprocess.run(['docker','image','inspect',ref],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
 assert not exists or role not in ('node','studio') and inspect(ref)['Id']==images[role]['imageId'],'Immutable release image already differs: '+ref
 if role not in ('node','studio'):run(['docker','tag',images[role]['imageId'],ref])
 images[role]['reference']=ref
node=a.source/'cline-agent-server'
(a.output/'Dockerfile.node').write_text('FROM '+local_base(original['node']['imageId'])+'\nENV LANG=C.UTF-8 LC_ALL=C.UTF-8 PYTHONUTF8=1\nCOPY src/ /opt/runtime-os/coding-node/service/src/\nLABEL org.opencontainers.image.version="'+a.version+'" org.opencontainers.image.revision="'+a.source_commit+'" runtime-os.build="server-source-overlay"\n')
run(['docker','build','--pull=false','--network=none','-f',a.output/'Dockerfile.node','-t',images['node']['reference'],node])
run(['docker','tag',images['node']['reference'],images['studio']['reference']])
for role in ('node','studio'):images[role]['imageId']=inspect(images[role]['reference'])['Id']
(a.output/'images.json').write_text(json.dumps(images,indent=2)+'\n')
installer=a.source/'deploy/standalone/installer.py'
(a.output/'installer.py').write_bytes(installer.read_bytes().replace(b'\r\n',b'\n'))
(a.output/'Dockerfile.installer').write_text('FROM '+local_base(base)+'\nCOPY installer.py /opt/product/installer.py\nCOPY images.json /opt/product/images.json\nLABEL org.opencontainers.image.version="'+a.version+'" org.opencontainers.image.revision="'+a.source_commit+'" runtime-os.build="server-source-overlay"\n')
ref='runtime-os-installer:'+a.version
assert subprocess.run(['docker','image','inspect',ref],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
run(['docker','build','--pull=false','--network=none','-f',a.output/'Dockerfile.installer','-t',ref,a.output])
receipt={'version':a.version,'sourceCommit':a.source_commit,'sourceFileHashes':{str(f.relative_to(a.source)):hashlib.sha256(f.read_bytes()).hexdigest() for folder in [node/'src',a.source/'deploy/standalone'] for f in folder.rglob('*') if f.is_file()},'baseImages':original,'images':images,'installer':{'reference':ref,'imageId':inspect(ref)['Id']},'unchangedJavaImages':'Java/API/MCP/News source trees are identical to the verified baseline; binaries reused by immutable ID','customerDataIncluded':False,'tests':'NOT_RUN'}
(a.output/'image-update.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'built':True,'installer':receipt['installer'],'node':images['node']}))
