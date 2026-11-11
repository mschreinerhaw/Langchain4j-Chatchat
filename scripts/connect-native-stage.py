"""Connect the authorized existing stage API to a verified Native S3 node.

Only API is recreated; gateway configuration is reloaded. Existing model,
database, storage, MCP, News and data volumes are retained. Private rollback
files contain environment configuration and must never be committed.
"""
import argparse,datetime,json,os,pathlib,shutil,subprocess,urllib.request,time
p=argparse.ArgumentParser();p.add_argument('--stage',type=pathlib.Path,required=True);p.add_argument('--image',required=True);p.add_argument('--connection',type=pathlib.Path,required=True);p.add_argument('--acceptance',type=pathlib.Path,required=True);p.add_argument('--cancel-acceptance',type=pathlib.Path,required=True);a=p.parse_args()
os.umask(0o077);stage=a.stage.resolve();compose=stage/'compose.yaml';gateway=stage/'config/public-gateway.conf'
for report in [a.acceptance,a.cancel_acceptance]:
    if json.loads(report.read_text()).get('passed') is not True:raise RuntimeError('Real HTTP acceptance is required: '+str(report))
config=json.loads(a.connection.read_text());assert config['endpoint'].startswith('http://host.docker.internal:')
c=json.loads(compose.read_text());project=c['name'];assert project=='runtime-os-stage-maintenance'
old=json.loads(subprocess.check_output(['docker','inspect',c['services']['api']['container_name']]))[0]
assert old['Config']['Labels']['com.docker.compose.project']==project
image=json.loads(subprocess.check_output(['docker','image','inspect',a.image]))[0]['Id']
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');backup=stage/'rollback'/('native-s3-'+stamp);backup.mkdir(parents=True,exist_ok=False)
shutil.copy2(compose,backup/'compose.yaml');shutil.copy2(gateway,backup/'public-gateway.conf')
(backup/'api-inspect.private.json').write_text(json.dumps(old))
subprocess.run(['docker','tag',old['Image'],'runtime-os-api:rollback-native-s3-'+stamp],check=True)
connection=stage/'config/secrets/native-coding-connection.json';connection_existed=connection.exists()
if connection_existed:shutil.copy2(connection,backup/'native-coding-connection.private.json')
shutil.copyfile(a.connection,connection);connection.chmod(0o600)
api=c['services']['api'];api['image']=image;env=api.setdefault('environment',{})
env.update(CHATCHAT_RUNTIME_CODING_UNIFIED_ENABLED='true',CHATCHAT_RUNTIME_CODING_UNIFIED_CONFIG_FILE='/run/secrets/native-coding-connection.json')
c['services']['gateway']['healthcheck']['test']=['CMD-SHELL',"curl --silent --show-error --max-time 2 --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8080/api/v1/enterprise/auth/me | grep -qx 401"]
mount={'type':'bind','source':str(connection),'target':'/run/secrets/native-coding-connection.json','read_only':True,'bind':{'create_host_path':False}}
api['volumes']=[v for v in api['volumes'] if not isinstance(v,dict) or v.get('target')!=mount['target']]+[mount]
text=gateway.read_text();text=text.replace('set $monitor_endpoint api:8792;','set $monitor_endpoint api:8080;')
start=text.index('    # Native monitor API routes') if '    # Native monitor API routes' in text else text.index('    # Node management');end=text.index('    location = / {',start)
text=text[:start]+'    # Node management APIs are not exposed through the business gateway.\n    location ~ ^/api/(login|logout|config|sessions|dispatches|inputs|assets)(/|$) { return 404; }\n'+text[end:]
text=text.replace('proxy_pass http://$monitor_endpoint;','proxy_pass http://$api_endpoint;')
# Compatibility URLs lead to the Java monitor, never the removed Node page.
start=text.index('    location = /v1/monitor {');end=text.index('    # Node management',start)
text=text[:start]+'    location = /v1/monitor { return 302 /monitor/; }\n    location ^~ /v1/monitor/ { return 302 /monitor/; }\n'+text[end:]
commands=['docker','compose','--env-file',str(stage/'config/deployment.env'),'-p',project,'-f',str(compose)]
rollback="#!/bin/sh\nset -eu\ncp "+str(backup/'compose.yaml')+' '+str(compose)+'\ncp '+str(backup/'public-gateway.conf')+' '+str(gateway)+'\n'
if connection_existed:rollback+='cp '+str(backup/'native-coding-connection.private.json')+' '+str(connection)+'\n'
rollback+=' '.join(commands)+' up -d --no-deps api\ndocker exec runtime-os-app-stage-gateway-1 nginx -t\ndocker exec runtime-os-app-stage-gateway-1 nginx -s reload\n'
(backup/'rollback.sh').write_text(rollback);(backup/'rollback.sh').chmod(0o700)
try:
    compose.write_text(json.dumps(c,indent=2));gateway.write_text(text)
    subprocess.run(commands+['config','--quiet'],check=True)
    subprocess.run(['docker','exec','runtime-os-app-stage-gateway-1','nginx','-t'],check=True)
    subprocess.run(commands+['up','-d','--no-deps','api'],check=True)
    for _ in range(120):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18180/monitor/',timeout=2) as r:
                if r.status==200:break
        except OSError:pass
        time.sleep(1)
    else:raise RuntimeError('New API readiness failed')
    subprocess.run(commands+['up','-d','--no-deps','gateway'],check=True)
    subprocess.run(['docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload'],check=True)
except Exception:
    subprocess.run([str(backup/'rollback.sh')],check=True);raise
record={'apiImageId':image,'previousApiImageId':old['Image'],'rollback':str(backup/'rollback.sh'),'businessPort':18100,'monitorPath':'/monitor/','nodeEndpoint':config['endpoint'],'databaseAndModelsUnchanged':True,'formalRelease':False}
(backup/'activation.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))
