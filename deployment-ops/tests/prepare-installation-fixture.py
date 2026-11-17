import os,json,subprocess,hashlib,zipfile,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1];work=root/'tests/installation-fixture';work.mkdir(exist_ok=True);os.chmod(work,0o700)
assets=work/'assets';assets.mkdir(exist_ok=True)
def run(args):
 result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 if result.returncode:raise RuntimeError(result.stdout)
 return result.stdout.strip()
(work/'Dockerfile').write_text('FROM python:3.11-slim\nRUN mkdir /data && chown 10001:10001 /data\nUSER 10001:10001\n')
print(run(['docker','build','--pull=false','--network=none','-t','deployment-ops-installation-test:local',str(work)]),flush=True)
service="""import http.server,json,os,signal,sys,socket
from pathlib import Path
signal.signal(signal.SIGTERM,lambda *args:sys.exit(0))
p=Path('/data/value')
if not p.exists():p.write_text('persistent-data')
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  with socket.create_connection(('database',5432),timeout=2):pass
  body=json.dumps({'version':os.environ['RELEASE_VERSION'],'value':p.read_text()}).encode()
  self.send_response(503 if os.environ.get('BROKEN')=='yes' else 200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
http.server.HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
"""
(assets/'service.py').write_text(service)
(assets/'init.sql').write_text("CREATE TABLE IF NOT EXISTS ops_acceptance(id integer PRIMARY KEY,value text NOT NULL); INSERT INTO ops_acceptance VALUES(1,'persistent-data') ON CONFLICT(id) DO NOTHING;\n")
images={}
for key,reference in [('database','postgres:18'),('application','deployment-ops-installation-test:local')]:
 info=json.loads(run(['docker','image','inspect',reference]))[0];archive=work/(key+'.tar')
 if not archive.exists():run(['docker','save','-o',str(archive),info['Id']])
 images[key]={'id':info['Id'],'archive':'images/'+key+'.tar','size':info['Size']}
keys=root/'backups/package-test-keys';keys.mkdir(exist_ok=True);keys.chmod(0o700)
if not (keys/'private.der').exists():
 run(['docker','run','--rm','--network','none','--user','0:0','--mount','type=bind,source='+str(keys)+',target=/keys','deployment-ops-web:1.0.0','java','-cp','classes:lib/*','PackageSigner','keygen','/keys/private.der','/keys/public.json','acceptance-signer'])
shutil.copy2(keys/'public.json',root/'trust/acceptance-signer.json')
for version,broken in [('1.0',False),('1.1',False),('2.0',True)]:
 m={'schemaVersion':1,'name':'installation-acceptance','description':'Acceptance fixture: isolated PostgreSQL, SQL init, persistent application and verification job. Not a Runtime OS production release.','version':version,'platform':'linux/amd64','dataCompatibility':'backward-compatible','upgradeFrom':['1.0','1.1'],'requirements':{'memoryBytes':700*1024**2,'diskBytes':64*1024**2},'images':images,'parameters':{'databasePassword':{'type':'string','label':'Database password','secret':True,'generate':True,'required':True,'pattern':'[A-Za-z0-9_-]{16,128}'},'httpPort':{'type':'integer','label':'Application port','default':19231,'min':1024,'max':65535},'serverAddress':{'type':'string','label':'Private server address','default':'192.168.195.221','pattern':'[A-Za-z0-9.-]+'}},'volumes':{'postgres-data':{},'application-data':{}},'components':[
 {'id':'database','image':'database','user':'999:999','environment':{'POSTGRES_USER':'ops','POSTGRES_DB':'ops','POSTGRES_PASSWORD':'${databasePassword}'},'mounts':[{'volume':'postgres-data','target':'/var/lib/postgresql'}],'health':{'type':'docker','test':['CMD','pg_isready','-U','ops','-d','ops'],'timeoutSeconds':90},'memoryBytes':384*1024**2},
 {'id':'schema','kind':'job','image':'database','user':'999:999','dependsOn':['database'],'environment':{'PGPASSWORD':'${databasePassword}'},'command':['psql','--single-transaction','-v','ON_ERROR_STOP=1','-h','database','-U','ops','-d','ops','-f','/opt/ops-config/init.sql'],'files':[{'source':'assets/init.sql','target':'/opt/ops-config/init.sql'}],'retrySafe':True,'memoryBytes':64*1024**2},
 {'id':'application','image':'application','dependsOn':['schema'],'environment':{'RELEASE_VERSION':version,'BROKEN':'yes' if broken else 'no'},'command':['python','/opt/ops-config/service.py'],'files':[{'source':'assets/service.py','target':'/opt/ops-config/service.py'}],'mounts':[{'volume':'application-data','target':'/data'}],'ports':[{'container':8080,'host':'${httpPort}','bind':'127.0.0.1'}],'health':{'type':'http','port':8080,'path':'/health','timeoutSeconds':10},'memoryBytes':64*1024**2},
 {'id':'verify','kind':'job','image':'database','user':'999:999','dependsOn':['application'],'environment':{'PGPASSWORD':'${databasePassword}'},'command':['psql','-v','ON_ERROR_STOP=1','-h','database','-U','ops','-d','ops','-c',"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM ops_acceptance WHERE id=1 AND value='persistent-data') THEN RAISE EXCEPTION 'persistent data missing'; END IF; END $$;"],'retrySafe':True,'runOn':'always','dataChange':False,'memoryBytes':64*1024**2}],'links':[{'name':'Acceptance application','url':'http://${serverAddress}:${httpPort}/health'}]}
 folder=work/('release-'+version);folder.mkdir(exist_ok=True);(folder/'manifest.json').write_text(json.dumps(m,indent=2))
 payload={'manifest.json':folder/'manifest.json','assets/service.py':assets/'service.py','assets/init.sql':assets/'init.sql','images/database.tar':work/'database.tar','images/application.tar':work/'application.tar'}
 checksums={}
 for name,file in payload.items():
  with file.open('rb') as stream:checksums[name]=hashlib.file_digest(stream,'sha256').hexdigest()
 (folder/'checksums.json').write_text(json.dumps(checksums,sort_keys=True,separators=(',',':')))
 run(['docker','run','--rm','--network','none','--user','0:0','--mount','type=bind,source='+str(keys)+',target=/keys,readonly','--mount','type=bind,source='+str(folder)+',target=/release','deployment-ops-web:1.0.0','java','-cp','classes:lib/*','PackageSigner','sign','/keys/private.der','/release/checksums.json','/release/signature.json','acceptance-signer'])
 payload.update({'checksums.json':folder/'checksums.json','signature.json':folder/'signature.json'})
 archive=work/('acceptance-'+version+'.ops.zip')
 with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as z:
  for name,file in payload.items():z.write(file,name)
 with archive.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
 archive.with_suffix('.zip.sha256').write_text(digest+'\n');print(version,archive.stat().st_size,digest,flush=True)
print('Fixture packages built from existing local images. No production configuration or data copied.',flush=True)
