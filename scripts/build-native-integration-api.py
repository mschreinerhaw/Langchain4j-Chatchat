"""Compile the uploaded branch source into an isolated Java API image on Docker.
Never uploads application binaries or updates a running service by itself.
"""
import argparse,hashlib,json,shutil,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--root',type=Path,required=True);p.add_argument('--tag',required=True);a=p.parse_args()
if not a.tag.startswith('runtime-os-api:integration-'):raise ValueError('Explicit integration image tag required')
r=a.root.resolve();r.mkdir(parents=True,exist_ok=False);s=r/'source';s.mkdir();e=r/'evidence';e.mkdir()
with zipfile.ZipFile(a.archive) as z:
 for f in z.infolist():
  if not (s/f.filename).resolve().is_relative_to(s):raise ValueError('Archive traversal')
 z.extractall(s)
m=json.loads((s/'source-manifest.json').read_text())
for f in m['files']:
 if hashlib.sha256((s/f['path']).read_bytes()).hexdigest()!=f['sha256']:raise ValueError('Source fingerprint mismatch')
t=s/'tools';t.mkdir(exist_ok=True);shutil.copytree('/root/apache-maven-3.9.16',t/'maven');shutil.copy2('/usr/bin/docker',t/'docker')
f=t/'maven/bin/mvn';f.write_text('\n'.join(line for line in f.read_text().splitlines() if not line.startswith('export JAVA_HOME='))+'\n');(t/'maven/conf/settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>')
args=['docker','build','--network=host','--progress=plain','-f',str(s/'deploy/docker/source-stack/Dockerfile'),'--target','api','-t',a.tag]
bases={'NODE_BUILD_IMAGE':'node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9','JAVA_BUILD_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717','JAVA_RUNTIME_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717'}
for k,v in bases.items():args+=['--build-arg',k+'='+v]
with (e/'build.log').open('wb') as log:code=subprocess.run(args+[str(s)],stdout=log,stderr=subprocess.STDOUT).returncode
result={'exitCode':code,'baseCommit':m['baseCommit'],'workingTreeIncluded':True,'sourceArchiveSha256':hashlib.sha256(a.archive.read_bytes()).hexdigest(),'sourceManifestSha256':hashlib.sha256((s/'source-manifest.json').read_bytes()).hexdigest(),'tag':a.tag,'bases':bases}
if not code:result['imageId']=json.loads(subprocess.check_output(['docker','image','inspect',a.tag]))[0]['Id']
(e/'build-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
raise SystemExit(code)
