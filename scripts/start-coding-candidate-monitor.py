"""Add an authenticated read-only Java monitor to the existing candidate project.
Requires a source-built API image and a separately restored candidate database.
Does not change the application Compose project, production database, or traffic.
"""
import argparse,copy,hashlib,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--stage',type=Path,required=True);p.add_argument('--image',required=True);p.add_argument('--monitor-script',type=Path);p.add_argument('--unified-config',type=Path);a=p.parse_args();os.umask(0o077)
r=a.root.resolve();assert (r/'evidence/deployment.json').is_file()
image=json.loads(subprocess.check_output(['docker','image','inspect',a.image]))[0]['Id']
original=json.loads(subprocess.check_output(['docker','inspect','runtime-os-app-stage-api-1']))[0]
assert original['Config']['Labels']['com.docker.compose.project']=='runtime-os-stage-maintenance'
assert original['Config']['Labels']['com.docker.compose.service']=='api'
env=dict(x.split('=',1) for x in original['Config']['Env'])
for k,v in env.items():env[k]=v.replace('runtime_os_acceptance_api','runtime_os_coding_candidate_20261111')
env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://192.168.195.221:5432/runtime_os_coding_candidate_20261111',CHATCHAT_RUNTIME_CODING_DIAGNOSTICS_ENABLED='true',CHATCHAT_RUNTIME_CODING_DIAGNOSTICS_ROOT=str(r),CHATCHAT_CODING_CLINE_NODE_URL='http://127.0.0.1:1')
for d in ['api-data','api-logs']:(r/d).mkdir(exist_ok=True)
config=r/'private/application.yml';config.write_text((a.stage/'config/api/application.yml').read_text().replace('runtime_os_acceptance_api','runtime_os_coding_candidate_20261111'))
volumes=[str(r)+':'+str(r)+':ro',str(r/'api-data')+':/srv/app/data',str(r/'api-logs')+':/srv/app/logs',str(config)+':/srv/app/config/application.yml:ro']
for mount in original['Mounts']:
 if mount['Destination'] in ['/run/secrets/asset-access-key','/run/secrets/asset-secret-key','/run/secrets/coding_agent_shared_secret','/srv/app/config/internal-credential.key','/srv/app/lib/drivers']:
  volumes.append(mount['Source']+':'+mount['Destination']+':ro')
if a.unified_config:
 connection=a.unified_config.resolve();assert connection.is_file() and connection.is_relative_to(r)
 env.update(CHATCHAT_RUNTIME_CODING_UNIFIED_ENABLED='true',CHATCHAT_RUNTIME_CODING_UNIFIED_CONFIG_FILE='/run/secrets/native-coding-connection.json')
 volumes.append(str(connection)+':/run/secrets/native-coding-connection.json:ro')
c=json.loads((r/'compose.json').read_text());c['networks']={name:{'external':True,'name':name} for name in original['NetworkSettings']['Networks']}
c['services']['monitor-api']={'image':image,'pull_policy':'never','container_name':'runtime-coding-candidate-api','restart':'unless-stopped','entrypoint':['java','-jar','/srv/app/app.jar'],'environment':env,'volumes':volumes,'ports':['127.0.0.1:18352:8080'],'networks':list(c['networks']),'logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'5'}}}
c['services']['monitor-api']['extra_hosts']=original['HostConfig'].get('ExtraHosts') or []
nginx=r/'private/monitor-nginx.conf'
nginx.write_text("""events { worker_connections 128; }
http { server { listen 18351; server_name _; client_max_body_size 64k;
location = / { return 302 /monitor/?candidate=true; }
location /monitor { proxy_pass http://127.0.0.1:18352; }
location = /api/v1/enterprise/auth/login { proxy_pass http://127.0.0.1:18352; }
location = /api/v1/enterprise/auth/me { limit_except GET { deny all; } proxy_pass http://127.0.0.1:18352; }
location /api/v1/coding-node-diagnostics/ { limit_except GET { deny all; } proxy_pass http://127.0.0.1:18352; }
location / { return 404; }
} }
""")
if a.unified_config:
 nginx.write_text(nginx.read_text().replace('client_max_body_size 64k;', 'client_max_body_size 65m; proxy_read_timeout 1300s; proxy_buffering off;').replace('location / { return 404; }', '''location /api/v1/coding/tasks { proxy_pass http://127.0.0.1:18352; }
location /api/v1/coding-monitor/ { limit_except GET { deny all; } proxy_pass http://127.0.0.1:18352; }
location /api/v1/data-science/user-assets { proxy_pass http://127.0.0.1:18352; }
location /api/v1/interactions/user-assets { proxy_pass http://127.0.0.1:18352; }
location / { return 404; }'''))
nginx_image=json.loads(subprocess.check_output(['docker','image','inspect','nginx:latest']))[0]['Id']
c['services']['monitor-web']={'image':nginx_image,'pull_policy':'never','container_name':'runtime-coding-candidate-monitor','restart':'unless-stopped','network_mode':'host','volumes':[str(nginx)+':/etc/nginx/nginx.conf:ro'],'depends_on':['monitor-api']}
if a.monitor_script:
 script=a.monitor_script.resolve();assert script.is_relative_to(r) and script.is_file()
 c['services']['monitor-web']['volumes'].append(str(script)+':/etc/nginx/monitor-app.js:ro')
 nginx.write_text(nginx.read_text().replace('location /monitor {', 'location = /monitor/app.js { default_type text/javascript; alias /etc/nginx/monitor-app.js; }\nlocation /monitor {'))
 (r/'evidence/monitor-frontend.json').write_text(json.dumps({'path':str(script),'sha256':hashlib.sha256(script.read_bytes()).hexdigest()}))
(r/'compose.json').write_text(json.dumps(c,indent=2))
subprocess.run(['docker','compose','-p','runtime-coding-candidate','-f',str(r/'compose.json'),'up','-d','--no-deps','monitor-api','monitor-web'],check=True)
(r/'evidence/monitor-deployment.json').write_text(json.dumps({'apiImageId':image,'url':'http://192.168.195.221:18351/monitor/?candidate=true','readOnly':True,'database':'runtime_os_coding_candidate_20261111','diagnosticOnly':not bool(a.unified_config),'unifiedTasksEnabled':bool(a.unified_config),'productionChanged':False},indent=2))
print('Candidate Java monitor starting at port 18351; production unchanged')
