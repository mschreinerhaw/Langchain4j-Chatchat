"""Build API/MCP/News/Native Coding images on the target Docker host from source.

No prebuilt application JAR is uploaded. The Maven distribution is a build tool;
all Java modules and both frontends are compiled inside the Docker build.
"""
from pathlib import Path
import argparse, json, shutil, subprocess
from source_release_verification import extract_verified_source, sha256, verify_image_jar

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--archive',type=Path,required=True)
p.add_argument('--maven-home',type=Path,default=Path('/root/apache-maven-3.9.16'))
p.add_argument('--coding-base-daemon',default='unix:///srv/runtime-os-coding-node-20261110/strict-daemon-3/run/docker.sock')
p.add_argument('--coding-base',default='sha256:cb6e8b7fbfbc26088dfdfcbdfd2b2f3f53b7099480dd60228f58dff81b946fb3')
p.add_argument('--python-base',default='sha256:5ed38dd693dd8d2a74094595e6a12607cb26218b02094d324e3f3ec8a5f7a19e')
p.add_argument('--tag',default='source-20261111')
p.add_argument('--services',nargs='+',choices=['coding-node','mcp','news','api'],default=['coding-node','mcp','news','api'])
a=p.parse_args();root=a.root.resolve();root.mkdir(parents=True,exist_ok=False);source=root/'source';source.mkdir();logs=root/'evidence';logs.mkdir();(root/'private').mkdir(mode=0o700)
manifest=extract_verified_source(a.archive,source)
tools=source/'tools';tools.mkdir(exist_ok=True);shutil.copytree(a.maven_home,tools/'maven');shutil.copy2('/usr/bin/docker',tools/'docker')
# Do not carry a host-specific JAVA_HOME injected into the Maven launcher.
launcher=tools/'maven/bin/mvn'
launcher.write_text('\n'.join(line for line in launcher.read_text().splitlines() if not line.startswith('export JAVA_HOME='))+'\n')
# A build distribution's local settings must never import deployment credentials.
(tools/'maven/conf/settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>')
old=['docker','--host',a.coding_base_daemon]
if 'coding-node' in a.services and subprocess.run(['docker','image','inspect',a.coding_base],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    with (logs/'coding-base-import.log').open('wb') as log:
        save=subprocess.Popen(old+['image','save',a.coding_base],stdout=subprocess.PIPE,stderr=log)
        result=subprocess.run(['docker','image','load'],stdin=save.stdout,stdout=log,stderr=subprocess.STDOUT)
        save.stdout.close()
        if save.wait() or result.returncode:raise RuntimeError('Pinned coding base import failed')
refs={'NODE_BUILD_IMAGE':'node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9',
      'JAVA_BUILD_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717',
      'JAVA_RUNTIME_IMAGE':'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717'}
if 'coding-node' in a.services:
    refs.update(CODING_BASE_IMAGE=a.coding_base,PYTHON_IMAGE=a.python_base)
resolved={}
for role,reference in refs.items():
    info=json.loads(subprocess.check_output(['docker','image','inspect',reference]))[0]
    tag='runtime-source-base:'+role.lower().replace('_','-')+'-'+info['Id'].split(':')[1][:16]
    subprocess.run(['docker','tag',info['Id'],tag],check=True)
    resolved[role]={'imageId':info['Id'],'repoDigests':info.get('RepoDigests',[]),'localBuildTag':tag}
args=[x for k,v in resolved.items() for x in ['--build-arg',k+'='+v['localBuildTag']]]
record={'baseCommit':manifest['baseCommit'],'branch':manifest.get('branch'),
        'workingTreeIncluded':manifest['workingTreeIncluded'],'workingTreeDirty':manifest.get('workingTreeDirty'),
        'sourceArchiveSha256':sha256(a.archive),'sourceManifestSha256':sha256(source/'source-manifest.json'),
        'tag':a.tag,'bases':resolved,'images':{}}
for service in dict.fromkeys(a.services):
    dockerfile=source/'deploy/docker/source-stack/Dockerfile'
    build_args=args
    if service=='coding-node':
        dockerfile=source/'cline-agent-server/docker/Dockerfile.release'
        release_refs={'RUNTIME_ARTIFACT_IMAGE':resolved['CODING_BASE_IMAGE']['localBuildTag'],
            'NODE_IMAGE':resolved['NODE_BUILD_IMAGE']['localBuildTag'],'PYTHON_IMAGE':resolved['PYTHON_IMAGE']['localBuildTag'],
            'GIT_COMMIT':manifest['baseCommit']}
        build_args=[x for k,v in release_refs.items() for x in ['--build-arg',k+'='+v]]
    image='runtime-os-'+service+':'+a.tag
    iid_file=logs/(service+'.iid')
    with (logs/('build-'+service+'.log')).open('wb') as log:
        result=subprocess.run(['docker','build','--network=host','--progress=plain','--iidfile',str(iid_file),'-f',str(dockerfile),'--target',service,'-t',image,*build_args,str(source)],stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError('Source build failed: '+service+'; retained log '+str(logs/('build-'+service+'.log')))
    info=json.loads(subprocess.check_output(['docker','image','inspect',iid_file.read_text().strip()]))[0]
    if service=='coding-node' and a.tag=='source-20261111':
        subprocess.run(['docker','tag',info['Id'],'runtime-os-coding-node:cline-4.1.26-python3.11'],check=True)
    record['images'][service]={k:info.get(k) for k in ['Id','RepoTags','RepoDigests','Architecture','Os','Size']}
    if service!='coding-node':
        try:
            record['images'][service].update(verify_image_jar(info['Id'],source,service,logs))
        except (ValueError,OSError,subprocess.CalledProcessError) as error:
            record['images'][service]['verification']='FAILED'
            (logs/'build-result.json').write_text(json.dumps(record,indent=2))
            raise RuntimeError(f'Image JAR verification failed for {service}') from error
        record['images'][service]['verification']='PASSED'
    (logs/'build-result.json').write_text(json.dumps(record,indent=2));print('built',service,info['Id'],flush=True)
