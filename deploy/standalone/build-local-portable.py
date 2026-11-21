"""Assemble the portable Linux/amd64 bundle using locally compiled application JARs."""
import argparse
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile

standalone = Path(__file__).resolve().parent
repo = standalone.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--work-dir', required=True, type=Path)
parser.add_argument('--plugins', required=True, type=Path,
                    help='Directory containing extracted OpenSearch 2.19.1 ICU, IK and Pinyin plugins')
args = parser.parse_args()
build = args.work_dir.resolve()
delivery = args.output.resolve()
plugins = args.plugins.resolve()
stage = build / 'release'
stage.mkdir(parents=True)
(delivery / 'offline-images').mkdir(parents=True, exist_ok=True)

def run(*args, capture=False):
    return subprocess.run([str(x) for x in args], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout

def write(path, value):
    path.write_text(value, encoding='utf-8', newline='\n')

def dockerfile(name, source):
    folder = stage / name
    folder.mkdir(exist_ok=True)
    write(folder / 'Dockerfile', source)
    return folder

def build_image(name, folder):
    tag = 'runtime-os-local-' + name + ':1.3.2'
    run('docker', 'build', '--pull=false', '--network=none', '--platform', 'linux/amd64', '-t', tag, folder)
    return tag

roles = dict(postgres='postgres:18', redis='redis:7.4-alpine', silo='pgsty/silo:latest', temporal='temporalio/auto-setup:1.29.7')
for role, module in [('api','chatchat-api'), ('mcp','chatchat-mcp-server'), ('news','chatchat-runtime-news')]:
    folder = dockerfile(role, f'FROM {module}:1.3.2\nCOPY --chown=10001:10001 app.jar /srv/app/app.jar\n')
    shutil.copyfile(repo / module / 'target' / f'{module}-1.0.0-SNAPSHOT.jar', folder / 'app.jar')
    roles[role] = build_image(role, folder)

native = dockerfile('native', '''FROM runtime-os-native-worker:1.3.2
COPY cline-src/ /opt/runtime-os/coding-node/service/src/
COPY cline-scripts/ /opt/runtime-os/coding-node/service/scripts/
COPY coding-src/ /opt/runtime-os/coding-node/coding-agent-node/src/
COPY skills/ /opt/runtime-os/coding-node/coding-agent-node/skills/
RUN node --check /opt/runtime-os/coding-node/service/src/governance-server.mjs && node --check /opt/runtime-os/coding-node/service/src/worker-executor.mjs
''')
for source,dst in [('cline-agent-server/src','cline-src'),('cline-agent-server/scripts','cline-scripts'),('coding-agent-node/src','coding-src'),('coding-agent-node/skills','skills')]:
    shutil.copytree(repo/source, native/dst, dirs_exist_ok=True)
node = build_image('native', native)
roles.update({role:node for role in ('node','recovery','executor','studio','task')})
frontend = dockerfile('frontend', '''FROM frontend-resource-hub:1.3.2
USER root
COPY --chown=10001:10001 source/ /app/
RUN node --check /app/server.mjs && node --check /app/worker.mjs && node /app/build.mjs
USER 10001:10001
''')
src = frontend / 'source'
src.mkdir(exist_ok=True)
for name in ('build.mjs','catalog.mjs','server.mjs','worker.mjs'):
    shutil.copyfile(repo/'frontend-resource-hub'/name, src/name)
for name in ('licenses','report-assets'):
    shutil.copytree(repo/'frontend-resource-hub'/name,src/name,dirs_exist_ok=True)
roles['frontend'] = build_image('frontend', frontend)

installer = stage / 'installer'
assets = installer / 'assets'
shutil.copytree(standalone/'assets', assets, dirs_exist_ok=True)
for sh in assets.glob('*.sh'):
    write(sh, sh.read_text(encoding='utf-8'))
prepare = assets/'prepare.sh'
script = prepare.read_text(encoding='utf-8')
marker = 'keytool -importcert -noprompt -alias runtime-ca'
if 'keytool -importkeystore' not in script:
    script = script.replace(marker, 'keytool -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit -destkeystore /shared/opensearch-truststore.p12 -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1\n '+marker)
write(prepare,script)
shutil.copytree(repo/'chatchat-api/src/main/resources/coding-monitor',assets/'coding-monitor',dirs_exist_ok=True)
shutil.copyfile(repo/'frontend-resource-hub/seccomp_profile.json',assets/'frontend-seccomp.json')
(assets/'runtime-assets').mkdir(exist_ok=True)
for migration in (repo/'database/migration/postgresql').glob('V20261021_*__runtime_*.sql'):
    shutil.copyfile(migration, assets/'runtime-assets'/migration.name)
gateway_source = (standalone/'prepare-build.py').read_text(encoding='utf-8')
gateway_conf = gateway_source.split("gateway.write_text('''", 1)[1].split("''')", 1)[0]
write(assets/'public-gateway.conf',gateway_conf)
search_conf = assets/'opensearch.yml'
write(search_conf,search_conf.read_text(encoding='utf-8').replace('bootstrap.memory_lock: true','bootstrap.memory_lock: false'))
gateway = stage/'gateway'
shutil.copytree(assets/'coding-monitor',gateway/'assets/coding-monitor',dirs_exist_ok=True)
shutil.copyfile(assets/'public-gateway.conf',gateway/'assets/public-gateway.conf')
write(gateway/'Dockerfile',(standalone/'Dockerfile.gateway').read_text(encoding='utf-8'))
run('docker','build','--pull=false','--network=none','--build-arg','NGINX_IMAGE=nginx:1.27.5-alpine','-t','runtime-os-local-gateway:1.3.2',gateway)
roles['gateway']='runtime-os-local-gateway:1.3.2'

daemon=dockerfile('daemon','''FROM docker:27.5.1-dind AS engine
FROM python:3.11-alpine
COPY --from=engine /usr/local/bin/ /usr/local/bin/
COPY supervise.py /usr/local/lib/runtime-os/supervise.py
RUN dockerd --version && containerd --version && runc --version
ENTRYPOINT ["python3","/usr/local/lib/runtime-os/supervise.py"]
''')
shutil.copyfile(repo/'deploy/docker/production/native-daemon/supervise.py',daemon/'supervise.py')
roles['daemon']=build_image('daemon',daemon)

roles['search']='runtime-os-local-search:1.3.2'
search=stage/'search'
if not search.exists():
    run(sys.executable,standalone/'schema-tools/scripts/prepare-search-image.py','--plugins',plugins,'--output',search,'--image',roles['search'],'--build')
else:
    build_image('search', search)

for role in ('api','mcp','news'):
    schema = assets/f'{"chatchat-mcp-server" if role=="mcp" else "chatchat-runtime-news" if role=="news" else "chatchat-api"}.sql'
    if not schema.exists():
        run(sys.executable,standalone/'schema-tools/scripts/export-image-schema.py','--role',role,'--image',roles[role],'--output',schema)

write(installer/'installer.py',(standalone/'installer.py').read_text(encoding='utf-8'))
write(installer/'Dockerfile','''FROM docker:27.5.1-cli AS cli
FROM eclipse-temurin:17-jdk-jammy AS jdk
FROM runtime-os-local-api:1.3.2
USER root
COPY --from=jdk /opt/java/openjdk/ /opt/java/openjdk/
COPY --from=cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
COPY assets/ /opt/ops-config/
COPY installer.py images.json /opt/product/
RUN command -v openssl && javac -version && python3 --version && docker compose version
ENTRYPOINT ["python3","/opt/product/installer.py"]
CMD ["install"]
''')

def save(name, refs):
    archive=stage/name
    run('docker','image','save','-o',archive,*dict.fromkeys(refs))
    configs={}
    with tarfile.open(archive) as tar:
        for item in json.load(tar.extractfile('manifest.json')):
            digest='sha256:'+hashlib.sha256(tar.extractfile(item['Config']).read()).hexdigest()
            for tag in item['RepoTags']:
                configs[tag]=digest
    return archive,configs

payload,configs=save('runtime-os-local-images.tar',roles.values())
write(installer/'images.json',json.dumps({role:{'reference':tag,'imageId':configs[tag]} for role,tag in roles.items()},indent=2))
installer_ref=build_image('installer',installer)
installer_archive,installer_configs=save('runtime-os-local-installer.tar',[installer_ref])
configs.update(installer_configs)
refs=list(dict.fromkeys([*roles.values(),installer_ref]))
lines=[]
for ref in refs:
    info=json.loads(run('docker','image','inspect',ref,capture=True))[0]
    assert info['Os']=='linux' and info['Architecture']=='amd64',ref
    lines.append(ref+'\t'+' '.join(dict.fromkeys([info['Id'],configs[ref]])))
run('docker','run','--rm','--pull=never','--network','none','--entrypoint','python3',installer_ref,'-m','py_compile','/opt/product/installer.py','/opt/ops-config/verify.py')
offline=delivery/'offline-images'
checksums=[]
for archive in (payload,installer_archive):
    with archive.open('rb') as f:
        digest=hashlib.file_digest(f,'sha256').hexdigest()
    shutil.move(archive, offline/archive.name)
    checksums.append(digest+'  '+archive.name)
write(offline/'images.tsv','\n'.join(lines)+'\n')
write(offline/'SHA256SUMS','\n'.join(checksums)+'\n')
config=delivery/'install-config'
compose=config/'docker-config/cli-plugins'
compose.mkdir(parents=True, exist_ok=True)
shutil.copy2(standalone/'portable-package/install.sh',config/'install.sh')
shutil.copy2(standalone/'portable-package/README.md',config/'README.md')
shutil.copy2(standalone/'portable-package/OPERATIONS.md',config/'OPERATIONS.md')
(config/'install.sh').chmod(0o755)
container=run('docker','create','--pull=never','docker:27.5.1-cli',capture=True).strip()
try:
    run('docker','cp',container+':/usr/local/libexec/docker/cli-plugins/docker-compose',compose/'docker-compose')
finally:
    run('docker','rm',container)
(compose/'docker-compose').chmod(0o755)
envfile=config/'manual-install.env'
if not envfile.exists():
    shutil.copy2(standalone/'manual-install.env',envfile)
old=envfile.read_text(encoding='utf-8')
write(envfile,re.sub(r'(?m)^INSTALLER_IMAGE=.*\n?','',old).rstrip()+'\nINSTALLER_IMAGE='+installer_ref+'\n')
print('LOCAL OFFLINE PACKAGE READY', flush=True)
