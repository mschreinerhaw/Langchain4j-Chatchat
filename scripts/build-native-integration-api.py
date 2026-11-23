"""Compile the uploaded branch source into an isolated Java API image on Docker.
Never uploads application binaries or updates a running service by itself.
"""
import argparse,json,shutil,subprocess
from pathlib import Path
from source_release_verification import extract_verified_source,sha256,verify_image_jar
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--root',type=Path,required=True);p.add_argument('--tag',required=True);a=p.parse_args()
if not a.tag.startswith('runtime-os-api:integration-'):raise ValueError('Explicit integration image tag required')
r=a.root.resolve();r.mkdir(parents=True,exist_ok=False);s=r/'source';s.mkdir();e=r/'evidence';e.mkdir()
m=extract_verified_source(a.archive,s)
t=s/'tools';t.mkdir(exist_ok=True);shutil.copytree('/root/apache-maven-3.9.16',t/'maven');shutil.copy2('/usr/bin/docker',t/'docker')
f=t/'maven/bin/mvn';f.write_text('\n'.join(line for line in f.read_text().splitlines() if not line.startswith('export JAVA_HOME='))+'\n');(t/'maven/conf/settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>')
iid_file=e/'api.iid'
args=['docker','build','--network=host','--progress=plain','--iidfile',str(iid_file),'-f',str(s/'deploy/docker/source-stack/Dockerfile'),'--target','api','-t',a.tag]
bases={'NODE_BUILD_IMAGE':'node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9','JAVA_BUILD_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717','JAVA_RUNTIME_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717'}
for k,v in bases.items():args+=['--build-arg',k+'='+v]
with (e/'build.log').open('wb') as log:code=subprocess.run(args+[str(s)],stdout=log,stderr=subprocess.STDOUT).returncode
result={'exitCode':code,'baseCommit':m['baseCommit'],'branch':m.get('branch'),'workingTreeIncluded':m['workingTreeIncluded'],
        'workingTreeDirty':m.get('workingTreeDirty'),'sourceArchiveSha256':sha256(a.archive),
        'sourceManifestSha256':sha256(s/'source-manifest.json'),'tag':a.tag,'bases':bases}
if not code:
    result['imageId']=json.loads(subprocess.check_output(['docker','image','inspect',iid_file.read_text().strip()]))[0]['Id']
    try:
        result.update(verify_image_jar(result['imageId'],s,'api',e))
    except (ValueError,OSError,subprocess.CalledProcessError) as error:
        result.update(exitCode=1,verification='FAILED')
        (e/'build-result.json').write_text(json.dumps(result,indent=2))
        raise RuntimeError('Image JAR verification failed for api') from error
    result['verification']='PASSED'
(e/'build-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
raise SystemExit(code)
