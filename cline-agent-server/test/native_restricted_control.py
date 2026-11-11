"""Restricted Executor transport for the live Native/Silo fixture.

No analysis program or Agent policy. The real Core is network-none; a task-only
Unix broker carries unchanged model bytes to the existing audit proxy. Factory
grants and S3/provider credentials never enter the task input tree.
"""
from pathlib import Path
import base64, hashlib, hmac, http.client, http.server, json, os, secrets
import socket, socketserver, subprocess, threading, time, uuid

class UnixConnection(http.client.HTTPConnection):
    def __init__(self,path,timeout=95):super().__init__('localhost',timeout=timeout);self.socket_path=str(path)
    def connect(self):
        self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(self.timeout)
        if len(os.fsencode(self.socket_path))<104:self.sock.connect(self.socket_path)
        else:
            directory=os.open(str(Path(self.socket_path).parent),os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:self.sock.connect('/proc/self/fd/'+str(directory)+'/'+Path(self.socket_path).name)
            finally:os.close(directory)

class ThreadingUnixServer(socketserver.ThreadingMixIn,socketserver.UnixStreamServer):
    daemon_threads=True

class RestrictedNative:
    def __init__(self,root,folder,config_file,plan,profile,model_options,image,case,proxy_port,tools):
        self.root,self.folder,self.plan,self.profile,self.case=Path(root),Path(folder),plan,profile,case
        self.cfg=json.loads(Path(config_file).read_text());self.proxy_port=proxy_port;self.tools=tools
        cfg=self.cfg
        if cfg.get('workerRuntime')!='cline-python311' or cfg.get('enableNativeSilo') is not True or cfg['workerImage']!=image:
            raise RuntimeError('An explicitly enabled, pinned Python Executor is required')
        if cfg['modelNames'].get('fixed')!=profile['model'] or cfg.get('modelOptions')!=model_options:
            raise RuntimeError('Frozen model/options differ from the declared Executor')
        self.worker=str(uuid.uuid4());self.container=None;self.closed=False;self.broker=None
        self.anchor=Path(cfg['workersRoot'])/self.worker
        self.docker=['docker','--host','unix://'+cfg['dockerSocket']]
        self.native_token=profile['apiToken'];self.broker_token=profile['proxyToken']

    def factory(self,path,method='GET',value=None):
        connection=UnixConnection(self.cfg['socketPath'],30)
        try:
            connection.request(method,path,None if value is None else json.dumps(value),{'Authorization':'Bearer '+self.cfg['apiToken'],'x-cline-task':self.grant,'Content-Type':'application/json'})
            response=connection.getresponse();raw=response.read(1024*1024+1)
            if len(raw)>1024*1024:raise RuntimeError('Unbounded Executor response')
            result=json.loads(raw)
            if response.status>=400:raise RuntimeError('Restricted Executor rejected: '+str(response.status)+' '+str(result.get('message')))
            return result
        finally:connection.close()

    def provision(self):
        binding={key:self.plan['binding'][key] for key in ['tenantId','taskId','runId','inputSnapshotVersion']}
        tenant=self.cfg['tenants'].get(binding['tenantId'])
        if not tenant or len(tenant['users'])!=1:raise RuntimeError('One explicit fixture authorization owner required')
        contract={'version':2,**{key:binding[key] for key in ['tenantId','taskId']},'userId':tenant['users'][0],
            'sessionId':self.worker,'workspaceRef':'project','modelProfile':'fixed','budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},
            'toolPermissions':self.tools,'dataPermissions':[],'networkPolicy':'broker-only','expiresAt':int((time.time()+1500)*1000),
            'runtimeProfile':'native-python-silo-v1','deliveryBinding':binding}
        raw=base64.urlsafe_b64encode(json.dumps(contract,separators=(',',':')).encode()).decode().rstrip('=')
        signature=base64.urlsafe_b64encode(hmac.new(self.cfg['signingKey'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
        self.grant=raw+'.'+signature
        created=self.factory('/internal/workers','POST',{'workerId':self.worker,'resourceProfile':'standard','apiToken':self.native_token,'brokerToken':self.broker_token,'editTool':'editor'})
        self.container=created['Id'];info=self.inspect()
        self._write('restricted-worker-ownership.json',{'containerId':self.container,'daemonId':self.cfg['expectedDaemonId'],
            'daemonSocket':self.cfg['dockerSocket'],'workerId':self.worker,'executorInstance':self.cfg['instanceId'],
            'imageId':info['Image'],'created':info['Created'],'labels':info['Config']['Labels'],
            'finalCleanupAuthorized':False,'applicationHttpSpiAcceptance':False})
        for name in ['workspace','state']:
            link=self.folder/name
            # These are the empty directories just created by this fixture.
            # Never replace an existing symlink or directory containing evidence.
            if link.is_symlink():raise RuntimeError('Existing case workspace pointer')
            link.rmdir();link.symlink_to(self.anchor/name,target_is_directory=True)
        outer=self
        class Broker(http.server.BaseHTTPRequestHandler):
            protocol_version='HTTP/1.1'
            def log_message(self,*args):pass
            def do_GET(self):self.forward()
            def do_POST(self):self.forward()
            def forward(self):
                if outer.closed or self.headers.get('Authorization')!='Bearer '+outer.broker_token or not self.path.startswith('/model/v1/'):
                    self.send_error(403);return
                size=int(self.headers.get('Content-Length','0'))
                if not 0<=size<=16*1024*1024:self.send_error(413);return
                body=self.rfile.read(size);connection=http.client.HTTPConnection('127.0.0.1',outer.proxy_port,timeout=180)
                try:
                    connection.request(self.command,'/'+outer.case+'/v1/'+self.path.removeprefix('/model/v1/'),body or None,
                        {'Authorization':'Bearer '+outer.broker_token,'Content-Type':'application/json','Accept':self.headers.get('Accept','*/*')})
                    response=connection.getresponse();self.send_response(response.status)
                    self.send_header('Content-Type',response.getheader('Content-Type','application/json'));self.send_header('Connection','close');self.end_headers();self.close_connection=True
                    while True:
                        block=response.read1(8192)
                        if not block:break
                        self.wfile.write(block);self.wfile.flush()
                except (BrokenPipeError,ConnectionResetError):pass
                finally:connection.close()
        bridge=self.anchor/'bridge';directory=os.open(bridge,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:self.broker=ThreadingUnixServer('/proc/self/fd/'+str(directory)+'/broker.sock',Broker)
        finally:os.close(directory)
        os.chown(bridge/'broker.sock',10001,10001);(bridge/'broker.sock').chmod(0o600)
        threading.Thread(target=self.broker.serve_forever,daemon=True).start()
        return self.container

    def start(self):
        response=self.factory('/internal/workers/'+self.worker+'/start','POST',{})
        if response.get('resources',{}).get('enforced') is not True:raise RuntimeError('Executor resource enforcement is not confirmed')
        self._write('restricted-worker-start.json',response)

    def api(self,path,value=None):
        connection=UnixConnection(self.anchor/'bridge/worker.sock')
        try:
            connection.request('GET' if value is None else 'POST',path,None if value is None else json.dumps(value),
                {'Authorization':'Bearer '+self.native_token,'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':str(uuid.uuid4())})
            response=connection.getresponse();raw=response.read(32*1024*1024+1)
            if len(raw)>32*1024*1024:raise RuntimeError('Native control evidence response exceeds resource bound')
            if response.status>=400:
                # Preserve the real HTTP error contract used by delivery feedback.
                import io,urllib.error
                raise urllib.error.HTTPError(path,response.status,response.reason,response.headers,io.BytesIO(raw))
            return json.loads(raw)
        finally:connection.close()

    def first_sse_frame(self,session):
        connection=UnixConnection(self.anchor/'bridge/worker.sock',10)
        try:
            connection.request('GET','/v1/sessions/'+session+'/events',headers={'Authorization':'Bearer '+self.native_token,'Accept':'text/event-stream'})
            response=connection.getresponse()
            if response.status!=200:raise RuntimeError('Native SSE unavailable')
            lines=[]
            for _ in range(5):
                line=response.readline(1024*1024).decode();lines.append(line)
                if not line.strip():break
            return ''.join(lines)
        finally:connection.close()

    def inspect(self):return json.loads(subprocess.check_output(self.docker+['inspect',self.container],timeout=20))[0]
    def stop(self):
        self.closed=True;receipt=self.factory('/internal/workers/'+self.worker+'/stop','POST',{})
        if receipt.get('running') is not False or self.inspect()['State']['Running']:raise RuntimeError('Strict Worker stop is not confirmed')
        self._write('restricted-worker-stop.json',receipt)
        return receipt
    def logs(self):
        result=subprocess.run(self.docker+['logs',self.container],capture_output=True,text=True,timeout=20);return result.stdout+result.stderr
    def close(self):
        if self.broker:self.broker.shutdown();self.broker.server_close()
    def _write(self,name,value):(self.folder/name).write_text(json.dumps(value,indent=2))
