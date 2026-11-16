"""Restricted Docker API executor. No shell, compose input, or host paths accepted."""
import os,json,hmac,http.client,socket,re,time,hashlib,threading
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
TOKEN=os.environ['EXECUTOR_TOKEN']
if os.getenv('OPS_NATIVE')=='1':
 from native_storage import validate
 validate()
from pathlib import Path
from urllib.parse import urlencode
from manager import Manager,redact
STATE=Path(os.getenv('OPS_STATE','/state')); STATE.mkdir(exist_ok=True)
LOCK=threading.Lock()
def release(spec):
 # Restricted single-container manifest. Images must already be local; no pulls.
 allowed={'application','image','environment','ports','healthPath','healthPort'}
 if set(spec)-allowed:raise ValueError('unsupported manifest keys')
 name=spec.get('application','')
 if not re.fullmatch('[a-z][a-z0-9-]{0,40}',name):raise ValueError('invalid application')
 image=spec.get('image','')
 if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}',image):raise ValueError('invalid local image reference')
 docker('GET','/images/'+image+'/json')
 ports=spec.get('ports',{})
 if not isinstance(ports,dict) or len(ports)>10:raise ValueError('ports must be map containerPort -> hostPort')
 bindings={}
 for k,v in ports.items():
  if not str(k).isdigit() or not str(v).isdigit() or not 1024<=int(v)<=65535 or not 1<=int(k)<=65535:raise ValueError('invalid port')
  bindings[str(k)+'/tcp']=[{'HostIp':'0.0.0.0','HostPort':str(v)}]
 environment=spec.get('environment',{})
 if not isinstance(environment,dict) or any(not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',k) or not isinstance(v,str) for k,v in environment.items()):raise ValueError('invalid environment')
 if not isinstance(spec.get('healthPort'),int) or not 1<=spec['healthPort']<=65535 or not re.fullmatch('/[A-Za-z0-9/_?=.-]*',spec.get('healthPath','')):raise ValueError('HTTP healthPort and healthPath required')
 current='ops-app-'+name; previous=None
 try:previous=docker('GET','/containers/'+current+'/json')
 except ValueError:pass
 if previous and previous['Config'].get('Labels',{}).get('deployment-ops.managed')!='true':raise ValueError('existing container is not owned by deployment-ops')
 ident=str(time.time_ns()); journal={'id':ident,'application':name,'image':image,'status':'pending','previous':previous['Id'] if previous else None,'createdAt':time.time()}
 file=STATE/(ident+'.json')
 def save():file.write_text(json.dumps(journal))
 save(); new=None
 try:
  if previous:
   docker('POST','/containers/'+previous['Id']+'/stop?t=20'); docker('POST','/containers/'+previous['Id']+'/rename?name='+current+'-rollback-'+ident)
  config={'Image':image,'Env':[k+'='+v for k,v in environment.items()],'Labels':{'deployment-ops.managed':'true','deployment-ops.application':name},'ExposedPorts':{k:{} for k in bindings},'HostConfig':{'NetworkMode':os.getenv('OPS_NETWORK','deployment-ops_edge'),'PortBindings':bindings,'RestartPolicy':{'Name':'unless-stopped'},'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true']}}
  new=docker('POST','/containers/create?name='+current,json.dumps(config).encode())['Id'];journal['container']=new;save();docker('POST','/containers/'+new+'/start')
  healthy=False
  for attempt in range(30):
   info=docker('GET','/containers/'+new+'/json')
   if info['State']['Running']:
    ip=next(iter(info['NetworkSettings']['Networks'].values()))['IPAddress']
    try:
     c=http.client.HTTPConnection(ip,spec['healthPort'],timeout=2);c.request('GET',spec['healthPath']);r=c.getresponse();r.read();healthy=200<=r.status<400;c.close()
    except (OSError,http.client.HTTPException):pass
   if healthy:break
   time.sleep(2)
  if not healthy:raise ValueError('health check failed')
  journal['status']='healthy';save();return journal
 except Exception as e:
  journal['status']='failed';journal['error']=str(e)
  if new:
   try:docker('POST','/containers/'+new+'/stop?t=5');docker('POST','/containers/'+new+'/rename?name='+current+'-failed-'+ident)
   except ValueError:pass
  if previous:
   try:docker('POST','/containers/'+previous['Id']+'/rename?name='+current);docker('POST','/containers/'+previous['Id']+'/start');journal['status']='rolled-back'
   except ValueError as recovery:journal['recoveryError']=str(recovery)
  save();raise ValueError(json.dumps(journal))

class UnixHTTP(http.client.HTTPConnection):
 def connect(self):
  self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); self.sock.settimeout(90); self.sock.connect(os.getenv('DOCKER_SOCKET','/var/run/docker.sock'))
def docker(method,path,body=None):
 c=UnixHTTP('localhost'); c.request(method,'/v1.45'+path,body,{'Content-Type':'application/json'}); r=c.getresponse(); data=r.read(); c.close()
 if r.status>=300: raise ValueError(data.decode(errors='replace')[:1000])
 try:return json.loads(data) if data else {}
 except ValueError:
  if '/logs?' in path and data[:1] in (b'\x00',b'\x01',b'\x02'):
   chunks=[];offset=0
   while offset+8<=len(data):
    length=int.from_bytes(data[offset+4:offset+8],'big');chunks.append(data[offset+8:offset+8+length]);offset+=8+length
   data=b''.join(chunks)
  return {'text':data.decode(errors='replace')}
def cid(v):
 if not re.fullmatch('[a-f0-9]{12,64}',v): raise ValueError('full container/image ID required')
 return v
def protected(v):
 info=docker('GET','/containers/'+cid(v)+'/json'); labels=info['Config'].get('Labels') or {}
 if labels.get('com.docker.compose.project')=='deployment-ops': raise ValueError('system containers require external recovery procedure')
 if labels.get('deployment-ops.installation')=='true':raise ValueError('Use the installation workflow for this container')
 return info
def raw_put(path,data):
 c=UnixHTTP('localhost');c.request('PUT','/v1.45'+path,data,{'Content-Type':'application/x-tar'});r=c.getresponse();body=r.read();c.close()
 if r.status>=300:raise ValueError('Docker config injection failed')
def raw_load(file):
 with open(file,'rb') as source:
  c=UnixHTTP('localhost');c.putrequest('POST','/v1.45/images/load?quiet=1');c.putheader('Content-Type','application/x-tar');c.putheader('Content-Length',str(file.stat().st_size));c.endheaders();c.sock.settimeout(1800)
  for block in iter(lambda:source.read(1024**2),b''):c.send(block)
  response=c.getresponse();data=response.read();c.close()
  if response.status>=300:raise ValueError('Docker image import failed')
  for line in data.splitlines():
   try:
    if json.loads(line).get('error'):raise ValueError('Docker rejected the image archive')
   except json.JSONDecodeError:pass
from package_store import PackageStore,MAX_CHUNK
from installations import Installer
STORE=None
INSTALLER=None
MANAGER=Manager(STATE,docker,LOCK)
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self): self.handle_request()
 def do_POST(self): self.handle_request()
 def handle_request(self):
  try:
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN): return self.send(401,{'error':'executor authentication required'})
   path=self.path
   if path=='/platform/maintenance' and self.command=='POST':
    from platform_maintenance import submit
    size=int(self.headers.get('Content-Length','0'))
    if not 0<size<=65536:raise ValueError('Invalid maintenance request size')
    if INSTALLER and INSTALLER.busy:raise ValueError('Deployment running; retry platform maintenance later')
    return self.send(200,submit(json.loads(self.rfile.read(size)),self.headers.get('X-Ops-Actor','')))
   if self.command=='POST' and os.getenv('OPS_NATIVE')=='1' and (Path(os.environ['OPS_DATA_DIR']).parent/'maintenance/active.lock').exists():raise ValueError('Platform maintenance active; mutations temporarily blocked')
   if self.command=='GET':
    if re.fullmatch('/manager/export/[a-f0-9]{64}',path):
     ident='sha256:'+path.rsplit('/',1)[1];info=MANAGER.image(ident)
     tags=[tag for tag in info.get('RepoTags') or [] if tag!='<none>:<none>']
     export_path='/images/get?'+urlencode({'names':tags},doseq=True) if tags else '/images/'+ident+'/get'
     connection=UnixHTTP('localhost');connection.request('GET','/v1.45'+export_path);response=connection.getresponse()
     if response.status!=200:connection.close();raise ValueError('Image export failed')
     self.send_response(200);self.send_header('Content-Type','application/x-tar');self.send_header('Connection','close');self.end_headers();self.close_connection=True
     try:
      while True:
       block=response.read(65536)
       if not block:break
       self.wfile.write(block)
     finally:connection.close()
     return
    elif path.startswith('/manager/'):result=MANAGER.get(path)
    elif path=='/packages':result=STORE.catalog()
    elif re.fullmatch('/packages/uploads/[a-f0-9]{32}',path):result=STORE.upload_status(path.rsplit('/',1)[-1])
    elif path=='/installations':result=INSTALLER.list()
    elif re.fullmatch('/installations/[a-f0-9]{32}',path):result=INSTALLER.get(path.rsplit('/',1)[-1])
    elif path=='/releases':
     result=[json.loads(f.read_text()) for f in sorted(STATE.glob('*.json'),reverse=True)]
    elif path=='/registry':
     url=os.getenv('REGISTRY_URL','http://registry:5000')
     import urllib.request
     with urllib.request.urlopen(url+'/v2/_catalog?n=1000',timeout=10) as r:result=json.load(r)
     result['url']=url
     result['tags']={}
     from urllib.parse import quote
     for repo in result.get('repositories',[]):
      with urllib.request.urlopen(url+'/v2/'+quote(repo,safe='/')+'/tags/list',timeout=10) as r:result['tags'][repo]=json.load(r).get('tags',[])
    elif path=='/inventory':
     info=docker('GET','/info'); result={'containers':docker('GET','/containers/json?all=1'),'images':docker('GET','/images/json'),'node':{k:info.get(k) for k in ['Name','NCPU','MemTotal','ContainersRunning','DockerRootDir','ServerVersion','ID']},'collectedAt':time.time()}
     for item in result['containers']:item['managerMetrics']=MANAGER.latest(item['Id'])
    elif re.fullmatch('/containers/[a-f0-9]{12,64}/(logs|stats|inspect)',path):
     _,_,v,action=path.split('/'); suffix={'logs':'logs?stdout=1&stderr=1&timestamps=1&tail=200','stats':'stats?stream=false','inspect':'json'}[action]; result=docker('GET','/containers/'+v+'/'+suffix)
     if action=='inspect':
      result=redact(result)
      if self.headers.get('X-Ops-Role')!='admin':
       for field in ('Env','Cmd','Entrypoint','Healthcheck'):result.get('Config',{}).pop(field,None)
    else: return self.send(404,{'error':'unsupported route'})
   else:
    if INSTALLER and INSTALLER.busy and path.startswith('/manager/') and path!='/manager/plans':raise ValueError('A signed installation is running; retry after it finishes')
    if path in ('/manager/plans','/manager/execute') or re.fullmatch('/manager/jobs/[a-f0-9]{32}/cancel',path):
     size=int(self.headers.get('Content-Length','0'))
     if not 0<size<=65536:raise ValueError('Operation payload size invalid')
     data=json.loads(self.rfile.read(size));actor=self.headers.get('X-Ops-Actor','')
     if not actor:raise ValueError('Authenticated actor required')
     result=MANAGER.cancel(path.split('/')[3]) if path.endswith('/cancel') else MANAGER.plan(data,actor) if path.endswith('/plans') else MANAGER.submit(data,actor)
     return self.send(200,result)
    if MANAGER.read_only:raise ValueError('Node is read-only; deployment owner must explicitly enable writes')
    if INSTALLER and INSTALLER.busy and not (path.startswith('/packages/') or path.startswith('/installations')):raise ValueError('An installation is running on this node; retry the operation after it finishes')
    if path.startswith('/packages/') or path.startswith('/installations'):
     size=int(self.headers.get('Content-Length','0'));limit=MAX_CHUNK if re.fullmatch('/packages/uploads/[a-f0-9]{32}/[0-9]+',path) else 1024**2
     if not 0<=size<=limit:raise ValueError('Request size exceeds allowed limit')
     data=self.rfile.read(size)
     if len(data)!=size:raise ValueError('Incomplete request')
     if re.fullmatch('/packages/[a-f0-9]{64}/retire',path):result=STORE.retire(path.split('/')[2],self.headers.get('X-Ops-Actor','executor-admin'))
     elif path=='/packages/uploads':result=STORE.create_upload(json.loads(data))
     elif re.fullmatch('/packages/uploads/[a-f0-9]{32}/[0-9]+',path):
      parts=path.split('/');result=STORE.chunk(parts[3],int(parts[4]),data)
     elif re.fullmatch('/packages/uploads/[a-f0-9]{32}/commit',path):result=STORE.begin_commit(path.split('/')[3],LOCK)
     elif path=='/installations/preflight':result=INSTALLER.preflight(json.loads(data))
     elif path=='/installations':result=INSTALLER.submit(json.loads(data),self.headers.get('X-Ops-Actor','executor-admin'))
     elif re.fullmatch('/installations/[a-f0-9]{32}/(resume|cancel|rollback|stop|start)',path):
      _,_,ident,action=path.split('/');result=INSTALLER.control(ident,action,self.headers.get('X-Ops-Actor','executor-admin')) if action in ('stop','start') else INSTALLER.rollback(ident,self.headers.get('X-Ops-Actor','executor-admin')) if action=='rollback' else getattr(INSTALLER,action)(ident)
     else:return self.send(404,{'error':'Unsupported package/installation operation'})
    elif path=='/releases':
     size=int(self.headers.get('Content-Length','0'))
     if not 0<size<=65536:raise ValueError('manifest size invalid')
     spec=json.loads(self.rfile.read(size))
     with LOCK:result=release(spec)
    elif path=='/rollback':
     size=int(self.headers.get('Content-Length','0'))
     if not 0<size<1024:raise ValueError('invalid request')
     ident=str(json.loads(self.rfile.read(size)).get('release',''))
     if not re.fullmatch('[0-9]{10,30}',ident):raise ValueError('invalid release ID')
     with LOCK:
      file=STATE/(ident+'.json');record=json.loads(file.read_text());previous=record.get('previous')
      if not previous:raise ValueError('no retained previous version')
      current='ops-app-'+record['application'];old=protected(previous)
      if old['Config'].get('Labels',{}).get('deployment-ops.managed')!='true':raise ValueError('not a managed container')
      active=docker('GET','/containers/'+current+'/json')
      if active['Id']==previous:raise ValueError('already rolled back')
      docker('POST','/containers/'+active['Id']+'/stop?t=20')
      docker('POST','/containers/'+active['Id']+'/rename?name='+current+'-retained-'+str(time.time_ns()))
      try:
       docker('POST','/containers/'+previous+'/rename?name='+current);docker('POST','/containers/'+previous+'/start')
       record['status']='manually-rolled-back';file.write_text(json.dumps(record));result=record
      except Exception:
       docker('POST','/containers/'+previous+'/rename?name='+current+'-rollback-'+str(time.time_ns()))
       docker('POST','/containers/'+active['Id']+'/rename?name='+current);docker('POST','/containers/'+active['Id']+'/start');raise
    elif re.fullmatch('/containers/[a-f0-9]{12,64}/(start|stop|restart)',path):
     _,_,v,action=path.split('/')
     with LOCK:
      protected(v); result=docker('POST','/containers/'+v+'/'+action+'?t=20')
    elif path=='/images/import':
     size=int(self.headers.get('Content-Length','0'))
     if size<=0 or size>2*1024**3: raise ValueError('TAR size must be 1 byte to 2 GiB')
     expected=self.headers.get('X-SHA256','')
     if not re.fullmatch('[a-f0-9]{64}',expected): raise ValueError('SHA256 required')
     import tempfile
     with tempfile.TemporaryFile() as f:
      digest=hashlib.sha256(); left=size
      while left:
       block=self.rfile.read(min(left,1024*1024))
       if not block: raise ValueError('incomplete upload')
       f.write(block); digest.update(block); left-=len(block)
      if digest.hexdigest()!=expected: raise ValueError('SHA256 mismatch')
      # Docker load may overwrite tag bindings. Inspect the archive first and
      # reject collisions with existing image identities before calling load.
      import tarfile
      f.seek(0)
      with tarfile.open(fileobj=f,mode='r:*') as archive:
       member=archive.getmember('manifest.json')
       if member.size>1024**2:raise ValueError('Archive manifest is too large')
       manifest=json.load(archive.extractfile(member))
       tags={tag:image['Id'] for image in docker('GET','/images/json') for tag in image.get('RepoTags') or []}
       for image in manifest:
        config=archive.getmember(image['Config'])
        if config.size>16*1024**2:raise ValueError('Image configuration is too large')
        identity='sha256:'+hashlib.sha256(archive.extractfile(config).read()).hexdigest()
        if any(tag in tags and tags[tag]!=identity for tag in image.get('RepoTags') or []):raise ValueError('Archive would overwrite an existing image tag')
      f.seek(0); c=UnixHTTP('localhost'); c.putrequest('POST','/v1.45/images/load?quiet=1'); c.putheader('Content-Type','application/x-tar'); c.putheader('Content-Length',str(size)); c.endheaders()
      while True:
       b=f.read(1024*1024)
       if not b:break
       c.send(b)
      r=c.getresponse(); data=r.read(); c.close()
      if r.status>=300:raise ValueError(data.decode(errors='replace'))
      for line in data.splitlines():
       if json.loads(line).get('error'):raise ValueError('Docker rejected the image archive')
      result={'sha256':expected,'output':data.decode(errors='replace')}
    else:return self.send(404,{'error':'unsupported operation'})
   self.send(200,result)
  except Exception as e:self.send(400,{'error':str(e)})
 def send(self,status,value):
  b=json.dumps(value).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b)
def recover_pending():
 # Legacy manual recovery helper. Never invoke automatically at process startup.
 for file in STATE.glob('*.json'):
  record=json.loads(file.read_text())
  if record.get('status')!='pending':continue
  try:
   new=record.get('container')
   if new:
    docker('POST','/containers/'+new+'/stop?t=5');docker('POST','/containers/'+new+'/rename?name=ops-app-'+record['application']+'-interrupted-'+record['id'])
   previous=record.get('previous')
   if previous:
    docker('POST','/containers/'+previous+'/rename?name=ops-app-'+record['application']);docker('POST','/containers/'+previous+'/start')
   record['status']='interrupted-restored' if previous else 'interrupted'
  except Exception as e:record['status']='recovery-required';record['error']=str(e)
  file.write_text(json.dumps(record))

def mark_pending_unknown():
 # Control-plane recovery must not stop, start, rename or recreate workloads.
 for file in STATE.glob('*.json'):
  record=json.loads(file.read_text())
  if record.get('status')=='pending':
   record['status']='unknown';record['error']='Agent restarted; inspect actual containers before explicit recovery'
   file.write_text(json.dumps(record))
if __name__=='__main__':
 if os.getenv('OPS_NATIVE')=='1':
  from native_storage import validate
  validate()
 mark_pending_unknown()
 STORE=PackageStore(os.getenv('OPS_PACKAGES','/packages'),os.getenv('OPS_TRUST','/trust'),docker,raw_load)
 INSTALLER=Installer(STATE/'installations',STORE,docker,raw_put,LOCK)
 threading.Thread(target=MANAGER.collect,daemon=True).start()
 threading.Thread(target=MANAGER.collect_containers,daemon=True).start()
 server=ThreadingHTTPServer((os.getenv('OPS_EXECUTOR_BIND','0.0.0.0'),int(os.getenv('OPS_EXECUTOR_PORT','8091'))),Handler)
 if os.getenv('TLS_CERT'):
  import ssl
  context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2
  context.load_cert_chain(os.environ['TLS_CERT'],os.environ['TLS_KEY']);server.socket=context.wrap_socket(server.socket,server_side=True)
 server.serve_forever()
