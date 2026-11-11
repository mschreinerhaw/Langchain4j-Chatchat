"""Build an isolated integration/RC image from a verified source archive.

This never creates a formal release tag, changes running containers, or pushes
to a registry. Promotion is a separate operation after business acceptance.
"""
import argparse, datetime, hashlib, json, subprocess, zipfile
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True)
p.add_argument('--root',type=Path,required=True);p.add_argument('--tag',required=True)
p.add_argument('--runtime-image',default='sha256:cb6e8b7fbfbc26088dfdfcbdfd2b2f3f53b7099480dd60228f58dff81b946fb3')
p.add_argument('--python-image',default='sha256:5ed38dd693dd8d2a74094595e6a12607cb26218b02094d324e3f3ec8a5f7a19e')
p.add_argument('--node-image',default='node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9')
a=p.parse_args()
if not a.tag.startswith('runtime-os-coding-node:') or not any(x in a.tag.split(':',1)[1] for x in ['integration-','-rc.']):
    raise ValueError('Build only an explicit integration/RC tag; formal tags require verified promotion')
r=a.root.resolve();r.mkdir(parents=True,exist_ok=False);source=r/'source';source.mkdir();evidence=r/'evidence';evidence.mkdir()
with zipfile.ZipFile(a.archive) as archive:
    for entry in archive.infolist():
        if not (source/entry.filename).resolve().is_relative_to(source):raise RuntimeError('Unsafe source archive path')
    archive.extractall(source)
manifest=json.loads((source/'source-manifest.json').read_text())
for item in manifest['files']:
    if hashlib.sha256((source/item['path']).read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('Source bytes differ')
base_refs={'RUNTIME_ARTIFACT_IMAGE':a.runtime_image,'NODE_IMAGE':a.node_image,'PYTHON_IMAGE':a.python_image}
record={'schemaVersion':'coding_node_build.v1','tag':a.tag,'baseCommit':manifest['baseCommit'],
    'sourceManifestSha256':hashlib.sha256((source/'source-manifest.json').read_bytes()).hexdigest(),
    'sourceArchiveSha256':hashlib.sha256(a.archive.read_bytes()).hexdigest(),
    'requirementsLockSha256':hashlib.sha256((source/'deploy/docker/python-runtime/requirements.lock').read_bytes()).hexdigest(),
    'officialClineSource':'a459f1d04dea030833b558be645011ab739c463b','bases':{},'formalRelease':False}
args=[]
for role,ref in base_refs.items():
    info=json.loads(subprocess.check_output(['docker','image','inspect',ref]))[0]
    if ref.startswith('sha256:') and info['Id']!=ref:raise RuntimeError('Base image identity differs')
    tag='runtime-release-base:'+info['Id'].split(':')[1]
    subprocess.run(['docker','tag',info['Id'],tag],check=True)
    record['bases'][role]={'imageId':info['Id'],'repoDigests':info.get('RepoDigests',[])}
    args+=['--build-arg',role+'='+tag]
args+=['--build-arg','GIT_COMMIT='+manifest['baseCommit'],'--build-arg','BUILD_TIME='+datetime.datetime.now(datetime.timezone.utc).isoformat()]
with (evidence/'build.log').open('wb') as log:
    result=subprocess.run(['docker','build','--network=none','--progress=plain','-f',str(source/'cline-agent-server/docker/Dockerfile.release'),
        '-t',a.tag,*args,str(source)],stdout=log,stderr=subprocess.STDOUT)
record['buildExitCode']=result.returncode
if result.returncode==0:
    info=json.loads(subprocess.check_output(['docker','image','inspect',a.tag]))[0]
    record.update(imageId=info['Id'],repoDigests=info.get('RepoDigests',[]),size=info['Size'])
(evidence/'build.json').write_text(json.dumps(record,indent=2));print(json.dumps(record,indent=2))
raise SystemExit(result.returncode)
