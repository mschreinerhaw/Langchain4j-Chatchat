#!/usr/bin/env python3
"""Build and export a Docker-only product on the Linux build server."""
import argparse,json,pathlib,subprocess,shutil,hashlib

p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,required=True);p.add_argument('--version',default='20261115');p.add_argument('--export',action='store_true')
p.add_argument('--source',type=pathlib.Path,default=pathlib.Path('/opt/runtime-os/releases/latest-main-20261115/source'))
p.add_argument('--api-image',default='runtime-api:latest-main-20261115');p.add_argument('--mcp-image',default='runtime-os-mcp:latest-main-20261115');p.add_argument('--news-image',default='runtime-os-news:latest-main-20261115');p.add_argument('--node-image',default='runtime-node:latest-main-20261115')
p.add_argument('--plugins',type=pathlib.Path,default=pathlib.Path('/data/opensearch/plugins'));p.add_argument('--drivers',type=pathlib.Path,default=pathlib.Path('/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers'))
p.add_argument('--frontend-source',type=pathlib.Path,default=pathlib.Path('/opt/frontend-resource-hub/1.0.0'))
p.add_argument('--schema-jdk-image',default='eclipse-temurin:17-jdk-jammy')
a=p.parse_args();r=a.root.resolve();source=a.source.resolve();r.mkdir(parents=True,exist_ok=True)
here=pathlib.Path(__file__).resolve().parent
if r!=here:
 shutil.copytree(here/'assets',r/'assets',dirs_exist_ok=True);shutil.copytree(here/'schema-tools',r/'schema-tools',dirs_exist_ok=True)
 for file in ['installer.py','Dockerfile.installer','Dockerfile.daemon','Dockerfile.gateway','daemon-entrypoint.sh','.dockerignore']:shutil.copy2(here/file,r/file)
shutil.copytree(source/'chatchat-api/src/main/resources/coding-monitor',r/'assets/coding-monitor',dirs_exist_ok=True)
runtime_assets=r/'assets/runtime-assets'
runtime_assets.mkdir(exist_ok=True)
for migration in ['V20261021_01__runtime_user_assets.sql','V20261021_02__runtime_asset_knowledge_jobs.sql','V20261021_03__runtime_user_asset_folders.sql']:
 shutil.copy2(source/'database/migration/postgresql'/migration,runtime_assets/migration)
shutil.copy2(a.frontend_source/'seccomp_profile.json',r/'assets/frontend-seccomp.json')
def run(cmd,**kwargs):return subprocess.run([str(x) for x in cmd],check=True,**kwargs)
def inspect(ref):return json.loads(subprocess.check_output(['docker','image','inspect',ref]))[0]
tag=lambda role:'runtime-os-'+role+':'+a.version+'-docker-only'
refs={'postgres':'postgres:18.6','redis':'redis:7.4-alpine','silo':'pgsty/silo:latest','temporal':'temporalio/auto-setup:1.29.7','frontend':'runtime-frontend-resource-hub:1.0.0'}
for ref in ['docker:27.5.1-dind','temporalio/auto-setup:1.29.7']:
 if subprocess.run(['docker','image','inspect',ref],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:run(['docker','pull',ref])
tools=r/'tools';tools.mkdir(exist_ok=True);shutil.copy2(shutil.which('docker'),tools/'docker')
compose=next((path for path in [pathlib.Path('/usr/libexec/docker/cli-plugins/docker-compose'),pathlib.Path('/usr/lib/docker/cli-plugins/docker-compose'),pathlib.Path('/usr/local/lib/docker/cli-plugins/docker-compose'),pathlib.Path.home()/'.docker/cli-plugins/docker-compose'] if path.is_file()),None)
if not compose:raise RuntimeError('Install Docker Compose on the build machine so it can be embedded in the installer image')
shutil.copy2(compose,tools/'docker-compose')
for role,image,file in [('api',a.api_image,'chatchat-api.sql'),('mcp',a.mcp_image,'chatchat-mcp-server.sql'),('news',a.news_image,'chatchat-runtime-news.sql')]:
 target=r/'assets'/file
 if not (target.with_suffix('.sql.source.json')).exists():
  target.unlink(missing_ok=True)
  if subprocess.run(['docker','image','inspect',a.schema_jdk_image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:run(['docker','pull',a.schema_jdk_image])
  run(['python3',r/'schema-tools/scripts/export-image-schema.py','--image',image,'--role',role,'--output',target,'--jdk-image',a.schema_jdk_image])
# Build an offline search image from vendor binaries only, never search data.
search=r/'search-build'
if not search.exists():run(['python3',r/'schema-tools/scripts/prepare-search-image.py','--plugins',a.plugins,'--output',search,'--image',tag('search'),'--build'])
refs['search']=tag('search')
# Ship driver artifacts inside the Java images; no deployment-host driver mount.
drivers=r/'drivers';drivers.mkdir(exist_ok=True)
base=a.drivers
driver_hashes={}
for f in base.rglob('*'):
 if f.is_file() and (f.suffix=='.jar' or '.so' in f.name):
  dest=drivers/f.relative_to(base);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,dest);driver_hashes[str(f.relative_to(base))]=hashlib.sha256(f.read_bytes()).hexdigest()
(r/'driver-artifacts.json').write_text(json.dumps(driver_hashes,indent=2))
for role,base_image in [('api',a.api_image),('mcp',a.mcp_image)]:
 dockerfile=r/('Dockerfile.'+role)
 dockerfile.write_text('FROM '+base_image+'\nCOPY drivers/ /srv/app/lib/drivers/\n')
 run(['docker','build','--pull=false','--network=none','-f',dockerfile,'-t',tag(role),r]);refs[role]=tag(role)
node=r/'node-src';shutil.copytree(source/'cline-agent-server/src',node,dirs_exist_ok=True)
(r/'Dockerfile.node').write_text('FROM '+a.node_image+'\nCOPY node-src/ /opt/runtime-os/coding-node/service/src/\n')
run(['docker','build','--pull=false','--network=none','-f',r/'Dockerfile.node','-t',tag('node'),r]);refs['node']=tag('node');refs['studio']=tag('node')
refs['news']=a.news_image
run(['docker','build','--pull=false','--network=none','-f',r/'Dockerfile.gateway','-t',tag('gateway'),r]);refs['gateway']=tag('gateway')
run(['docker','build','--pull=false','--network=none','-f',r/'Dockerfile.daemon','-t',tag('daemon'),r]);refs['daemon']=tag('daemon')
# Preserve the public JVM CA bundle when adding our installation-specific CA.
prepare=r/'assets/prepare.sh';text=prepare.read_text()
needle='keytool -importcert -noprompt -alias runtime-ca'
if 'keytool -importkeystore' not in text:
 text=text.replace(needle,'keytool -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit -destkeystore /shared/opensearch-truststore.p12 -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1\n '+needle)
prepare.write_text(text)
osconf=r/'assets/opensearch.yml';osconf.write_text(osconf.read_text().replace('bootstrap.memory_lock: true','bootstrap.memory_lock: false'))
gateway=r/'assets/public-gateway.conf'
gateway.write_text('''worker_processes 1;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;
events { worker_connections 1024; }
http {
 include /etc/nginx/mime.types;
 resolver 127.0.0.11 valid=5s ipv6=off;
 client_body_temp_path /tmp/body;
 proxy_temp_path /tmp/proxy;
 fastcgi_temp_path /tmp/fastcgi;
 uwsgi_temp_path /tmp/uwsgi;
 scgi_temp_path /tmp/scgi;
 client_max_body_size 64m;
 proxy_http_version 1.1;
 proxy_buffering off;
 proxy_request_buffering off;
 proxy_read_timeout 1800s;
 proxy_set_header Host $http_host;
 proxy_set_header Connection "";
 server {
  listen 8080;
  set $api api:8080;
  location ^~ /internal/ { return 404; }
  location ^~ /actuator/ { return 404; }
  location = /monitor { absolute_redirect off; return 302 /monitor/; }
  location = /monitor/ { rewrite ^ /monitor/index.html last; }
  location = /monitor/index.html { alias /srv/coding-monitor/index.html; default_type text/html; }
  location = /monitor/app.js { alias /srv/coding-monitor/app.js; }
  location = /monitor/style.css { alias /srv/coding-monitor/style.css; }
  location / { proxy_pass http://$api; }
 }
 server {
  listen 8090;
  set $mcp mcp:8090;
  location ^~ /internal/ { return 404; }
  location ^~ /actuator/ { return 404; }
  location / { proxy_pass http://$mcp; }
 }
}
''')
# Gateway content was prepared above; rebuild to include the final product config.
run(['docker','build','--pull=false','--network=none','-f',r/'Dockerfile.gateway','-t',tag('gateway'),r])
manifest={}
origins=dict(refs)
for role in ['postgres','redis','silo','temporal','frontend','news']:
 run(['docker','image','tag',refs[role],tag(role)]);refs[role]=tag(role)
for role,ref in refs.items():
 info=inspect(ref);manifest[role]={'reference':ref,'imageId':info['Id'],'sourceReference':origins[role],'repoDigests':info.get('RepoDigests',[])}
(r/'images.json').write_text(json.dumps(manifest,indent=2))
run(['docker','build','--pull=false','--network=none','--build-arg','API_IMAGE='+a.api_image,'-f',r/'Dockerfile.installer','-t',tag('installer'),r])
installer=inspect(tag('installer'));manifest['installer']={'reference':tag('installer'),'imageId':installer['Id']}
(r/'release-images.json').write_text(json.dumps(manifest,indent=2))
if a.export:
 import gzip,os
 from concurrent.futures import ThreadPoolExecutor
 from collections import deque
 archive=r/('runtime-os-'+a.version+'-docker-only.tar.gz')
 temporary=archive.with_suffix('.partial')
 save=subprocess.Popen(['docker','image','save',*dict.fromkeys(v['reference'] for v in manifest.values())],stdout=subprocess.PIPE)
 # Concatenated gzip members are standard gzip. Bounded parallel compression
 # avoids retaining an entire multi-gigabyte image archive in memory.
 workers=min(4,max(1,(os.cpu_count() or 2)//2))
 pending=deque()
 with temporary.open('wb') as target,ThreadPoolExecutor(max_workers=workers) as pool:
  for chunk in iter(lambda:save.stdout.read(8*1024*1024),b''):
   pending.append(pool.submit(gzip.compress,chunk,compresslevel=1,mtime=0))
   if len(pending)>=workers*2:target.write(pending.popleft().result())
  while pending:target.write(pending.popleft().result())
 save.stdout.close()
 if save.wait():raise RuntimeError('Image export failed; partial archive retained')
 temporary.replace(archive)
 hash=hashlib.sha256()
 with archive.open('rb') as file:
  for chunk in iter(lambda:file.read(16*1024*1024),b''):hash.update(chunk)
 digest=hash.hexdigest()
 (r/'SHA256SUMS').write_text(digest+'  '+archive.name+'\n')
print('DOCKER_ONLY_PRODUCT_BUILT',tag('installer'),flush=True)
