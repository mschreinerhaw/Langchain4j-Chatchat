"""Real file isolation harness. Does not implement analysis or modify Cline/model payloads.

Run on the authorized host after preparing the pinned image/config; all raw evidence
stays under ROOT. One initial instruction, one new session, no follow-up rescue.
"""
import datetime,hashlib,http.client,http.server,json,os,secrets,shutil,socket,subprocess,sys,threading,time,urllib.request,urllib.parse,uuid
from pathlib import Path

ROOT=Path('/opt/cline-parquet-isolation-20261110')
PROMPT='请分析工作目录中的 test.parquet 文件。你可以自主读取文件、探索字段、编写 Python 程序并运行。请分析数据规模、字段类型、缺失值、重复值、数值分布和潜在异常，最终生成 analysis.py 和 report.md。报告必须基于真实计算结果，不允许编造数据。'
MODEL_REQUEST_LIMIT=12
TIMEOUT=900
TOOL_LIMIT=100
PROXY_PORT=int(os.environ.get('PARQUET_PROXY_PORT','18359'))
TOOLS=['read_files','search_codebase','editor','apply_patch','skills','run_commands','submit_and_exit']
settings=json.loads((ROOT/'private/settings.json').read_text())
lock=threading.Lock();counts={};active={};usage={}

def write(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2))
def command(args,**kw):return subprocess.run(args,capture_output=True,text=True,**kw)
def docker(args):
 p=command(['docker',*args],timeout=90)
 if p.returncode:raise RuntimeError(p.stderr[:1800])
 return p.stdout.strip()
def call(path,data=None):
 req=urllib.request.Request('http://127.0.0.1:18358'+path,data=json.dumps(data).encode() if data is not None else None,headers={'Authorization':'Bearer '+settings['apiToken'],'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':str(uuid.uuid4())})
 with urllib.request.urlopen(req,timeout=70) as r:return json.load(r)

class Proxy(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*a):pass
 def do_GET(self):self.forward()
 def do_POST(self):self.forward()
 def forward(self):
  parts=self.path.split('/');case=parts[1] if len(parts)>1 else ''
  if case not in active or self.headers.get('Authorization')!='Bearer '+settings['proxyToken']:
   self.send_error(403);return
  body=self.rfile.read(int(self.headers.get('Content-Length','0')))
  is_model=self.command=='POST' and 'chat/completions' in self.path
  with lock:
   count=counts.get(case,0)
   if is_model:
    if count>=MODEL_REQUEST_LIMIT or time.monotonic()-active[case]>TIMEOUT:
     self.send_error(429,'Fixed isolation model request budget exhausted');return
    counts[case]=count=count+1
  folder=ROOT/'runs'/case/'model';folder.mkdir(parents=True,exist_ok=True)
  ident=f'{count:03d}' if is_model else 'discovery-'+secrets.token_hex(4)
  meta={'case':case,'index':count if is_model else None,'start':datetime.datetime.now(datetime.timezone.utc).isoformat(),'path':self.path,'requestSha256':hashlib.sha256(body).hexdigest(),'requestBytes':len(body),'forwardedUnchanged':True}
  if is_model:
   parsed=json.loads(body)
   meta.update({'requestedModel':parsed.get('model'),'toolNames':[t.get('function',{}).get('name') for t in parsed.get('tools',[])],'requestParameters':{k:v for k,v in parsed.items() if k not in ['messages','tools']}})
   if parsed.get('model')!=settings['model']:
    meta['error']='MODEL_MISMATCH';write(folder/(ident+'-receipt.json'),meta);self.send_error(400,'Fixed model mismatch');return
   (folder/(ident+'-request.json')).write_bytes(body)
  upstream=urllib.parse.urlparse(settings['baseUrl'])
  suffix='/'+('/'.join(parts[2:])).removeprefix('v1/')
  path=upstream.path.rstrip('/')+suffix
  connection=http.client.HTTPSConnection(upstream.hostname,upstream.port or 443,timeout=120) if upstream.scheme=='https' else http.client.HTTPConnection(upstream.hostname,upstream.port or 80,timeout=120)
  # Host DNS differs from Docker DNS; preserve URL, TLS SNI and Host header.
  if settings.get('providerIp'):
   connection._create_connection=lambda address,timeout=None,source_address=None:socket.create_connection((settings['providerIp'],address[1]),timeout,source_address)
  response_bytes=bytearray();start=time.monotonic()
  try:
   connection.request(self.command,path,body=body if body else None,headers={'Authorization':'Bearer '+settings['apiKey'],'Content-Type':'application/json','Accept':self.headers.get('Accept','*/*')})
   response=connection.getresponse();meta['httpStatus']=response.status
   self.send_response(response.status)
   self.send_header('Content-Type',response.getheader('Content-Type','application/json'));self.send_header('Connection','close');self.end_headers();self.close_connection=True
   while True:
    chunk=response.read1(8192)
    if not chunk:break
    response_bytes.extend(chunk)
    try:self.wfile.write(chunk);self.wfile.flush()
    except (BrokenPipeError,ConnectionResetError):meta['clientDisconnected']=True;break
   chunks=[]
   text=response_bytes.decode('utf-8',errors='replace')
   for line in text.splitlines():
    if line.startswith('data: '):
     try:chunks.append(json.loads(line[6:]))
     except json.JSONDecodeError:pass
   if not chunks:
    try:chunks=[json.loads(text)]
    except json.JSONDecodeError:pass
   meta['providerModels']=sorted({p['model'] for p in chunks if p.get('model')})
   known=[p['usage'] for p in chunks if p.get('usage')]
   meta['usage']=known[-1] if known else None;meta['usageKnown']=bool(known)
  except Exception as e:
   meta['error']=repr(e)
   try:self.send_error(502,'Upstream transport failure')
   except Exception:pass
  finally:
   connection.close();meta['durationMs']=round((time.monotonic()-start)*1000);meta['responseSha256']=hashlib.sha256(response_bytes).hexdigest();(folder/(ident+'-response.bin')).write_bytes(response_bytes);write(folder/(ident+'-receipt.json'),meta)

def snapshot(case):
 folder=ROOT/'runs'/case;workspace=folder/'workspace'
 for p in workspace.rglob('*'):
  if not p.is_file() or '.git' in p.parts or '.cline' in p.parts or p.suffix not in ['.py','.md','.json','.csv','.txt']:continue
  if p.stat().st_size>4_000_000:continue
  try:
   raw=p.read_bytes();h=hashlib.sha256(raw).hexdigest();dest=folder/'versions'/h/p.relative_to(workspace)
   if not dest.exists():dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
  except OSError:pass

def run_case(case):
 folder=ROOT/'runs'/case
 if folder.exists():raise RuntimeError('Refusing replay: '+case)
 workspace=folder/'workspace';workspace.mkdir(parents=True);os.chmod(workspace,0o777)
 shutil.copyfile(ROOT/'data/test.parquet',workspace/'test.parquet');os.chmod(workspace/'test.parquet',0o444)
 native=case.startswith('native-');name='cline-parquet-'+case
 state=folder/'state';state.mkdir();os.chmod(state,0o777)
 write(folder/'intent.json',{'case':case,'prompt':PROMPT,'model':settings['model'],'modelRequestLimit':MODEL_REQUEST_LIMIT,'timeoutSeconds':TIMEOUT,'toolLimit':TOOL_LIMIT,'inputSha256':hashlib.sha256((workspace/'test.parquet').read_bytes()).hexdigest(),'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()})
 if native:
  cfg={'sourceRoot':'/not-mounted/upstream','distDir':'/poc/runtime','stateDir':'/state','apiToken':settings['apiToken'],'apiKey':settings['proxyToken'],'model':settings['model'],'baseUrl':f'http://cline-parquet-proxy:{PROXY_PORT}/{case}/v1','workspaces':{'parquet':'/workspace'},'toolPermissions':TOOLS,'host':'0.0.0.0','port':18358}
  write(folder/'native-config.json',cfg);os.chmod(folder/'native-config.json',0o600)
  extra=['-p','127.0.0.1:18358:18358','-e','CLINE_SERVER_CONFIG=/run/native-config.json','-v',str(folder/'native-config.json')+':/run/native-config.json:ro']
  args=['node','/service/src/server.mjs']
 else:
  # Official auth writes the official settings format, before any inference.
  extra=[];args=['sleep','infinity']
 docker(['run','-d','--name',name,'--network','cline-parquet-isolation','--add-host','cline-parquet-proxy:host-gateway','--dns','114.114.114.114','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','3g','--cpus','2','--pids-limit','256','--user','0:0','-e','HOME=/state/home','-e','DO_NOT_TRACK=1','-v',str(workspace)+':/workspace','-v',str(workspace/'test.parquet')+':/workspace/test.parquet:ro','-v',str(state)+':/state','-v',str(ROOT/'source')+':/service:ro','-v',str(ROOT/'official-cli/package')+':/official-cli:ro',*extra,settings['image'],*args])
 start=time.monotonic();active[case]=start;terminal=None;native_id=None;proc=None;streams=[]
 try:
  if native:
   for _ in range(100):
    try:
     if call('/health/ready').get('ready'):break
    except (urllib.error.URLError,ConnectionError):time.sleep(.3)
   native_id=call('/v1/sessions',{'workspaceRef':'parquet'})['sessionId'];write(folder/'session-created.json',{'sessionId':native_id})
   if not native_id:raise RuntimeError('No Native HTTP readiness')
   while time.monotonic()-start<TIMEOUT:
    status=call(f'/v1/sessions/{native_id}')
    if status['status']=='idle':break
    if status['status']=='failed':raise RuntimeError('Worker startup failed')
    time.sleep(.5)
   write(folder/'submission.json',call(f'/v1/sessions/{native_id}/messages',{'text':PROMPT}))
  else:
   auth=command(['docker','exec',name,'/official-cli/bin/cline','auth','--provider','openai','--apikey',settings['proxyToken'],'--modelid',settings['model'],'--baseurl',f'http://cline-parquet-proxy:{PROXY_PORT}/{case}/v1','--config','/state/home/.cline'],timeout=60)
   (folder/'auth.log').write_text((auth.stdout+auth.stderr).replace(settings['proxyToken'],'[REDACTED]'))
   if auth.returncode:raise RuntimeError('Official CLI auth preparation failed')
   write(state/'home/.cline/data/settings/global-settings.json',{'telemetryOptOut':True,'autoUpdateEnabled':False,'disabledTools':['spawn','spawn_agent','team','teams','schedule','cron','fetch_web_content'],'tools':{'web_search':{'enabled':False}}})
   for stream in ['stdout','stderr']:streams.append((folder/(stream+'.jsonl')).open('w'))
   proc=subprocess.Popen(['docker','exec','-w','/workspace',name,'/official-cli/bin/cline','--json','--auto-approve','true','--provider','openai','--model',settings['model'],'--config','/state/home/.cline','--data-dir','/state/home/.cline/data','--timeout',str(TIMEOUT),PROMPT],stdout=streams[0],stderr=streams[1])
  while time.monotonic()-start<TIMEOUT:
   snapshot(case)
   if native:
    status=call(f'/v1/sessions/{native_id}');write(folder/'latest-status.json',status)
    for permission in status.get('pendingPermissions',[]):
     decision='approve' if permission['toolName'] in TOOLS else 'deny'
     call('/v1/permissions/'+permission['id']+'/respond',{'decision':decision,'reason':'Fixed file isolation policy'})
    events=call(f'/v1/sessions/{native_id}/events');write(folder/'events.json',events)
    fatal=[e for e in events['events'] if e['eventType']=='model.error' and e.get('payload',{}).get('recoverable') is False]
    if fatal:
     terminal='MODEL_ERROR_OBSERVED';write(folder/'failure-observation.json',{'terminal':terminal,'serverStatus':status['status'],'errors':fatal,'cleanupOnly':True});break
    if sum(e['eventType']=='tool.started' for e in events['events'])>TOOL_LIMIT:terminal='TOOL_LIMIT';break
    if status['status'] in ['turn_completed','failed','awaiting_input','recoverable']:
     terminal=status['status'];break
   elif proc.poll() is not None:
    terminal='CLI_EXIT_'+str(proc.returncode);break
   else:
    try:
     rows=[json.loads(l) for l in (folder/'stdout.jsonl').read_text().splitlines() if l.strip()]
     if sum((r.get('event') or {}).get('type')=='content_start' and (r.get('event') or {}).get('contentType')=='tool' for r in rows)>TOOL_LIMIT:terminal='TOOL_LIMIT';break
    except json.JSONDecodeError:pass
   time.sleep(.5)
  if terminal is None:terminal='DEADLINE_EXCEEDED'
 except Exception as e:
  terminal='HARNESS_OR_ENVIRONMENT_FAILURE';write(folder/'failure.json',{'error':repr(e)})
 finally:
  snapshot(case)
  if native and native_id:
   try:
    write(folder/'final-status.json',call(f'/v1/sessions/{native_id}'));write(folder/'events.json',call(f'/v1/sessions/{native_id}/events'));write(folder/'stop.json',call(f'/v1/sessions/{native_id}/cancel',{}))
   except Exception as e:write(folder/'stop-error.json',{'error':repr(e)})
  log=command(['docker','logs',name],timeout=15);(folder/'container.log').write_text((log.stdout+log.stderr).replace(settings['proxyToken'],'[REDACTED]').replace(settings['apiKey'],'[REDACTED]'))
  docker(['stop','-t','3',name])
  if proc:
   try:proc.wait(timeout=10)
   except subprocess.TimeoutExpired:proc.terminate()
  for f in streams:f.close()
  active.pop(case,None)
  write(folder/'summary.json',{'case':case,'terminal':terminal,'durationSeconds':round(time.monotonic()-start,3),'nativeSessionId':native_id,'modelRequests':counts.get(case,0),'inputUnchanged':hashlib.sha256((workspace/'test.parquet').read_bytes()).hexdigest()==hashlib.sha256((ROOT/'data/test.parquet').read_bytes()).hexdigest(),'artifacts':{p.name:{'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in workspace.iterdir() if p.is_file()},'independentAcceptance':'PENDING'})
  print(json.dumps(json.loads((folder/'summary.json').read_text()),ensure_ascii=False),flush=True)

if __name__=='__main__':
 if not (ROOT/'evidence/frozen-plan.json').exists():
  write(ROOT/'evidence/frozen-plan.json',{'sourceCommit':'c167da87ddfc9dbdab813efd8444d640421ff438','prompt':PROMPT,'model':settings['model'],'modelRequestLimit':MODEL_REQUEST_LIMIT,'timeoutSeconds':TIMEOUT,'toolLimit':TOOL_LIMIT,'nativeApprovalAllowlist':TOOLS,'sameContainerImage':settings['image'],'roundOrder':['native-'+str(i) for i in range(1,6)]+['official-'+str(i) for i in range(1,6)],'noFollowUpInstructions':True,'noRuntimeTemplates':True,'sourceUnmodified':True})
 server=http.server.ThreadingHTTPServer(('0.0.0.0',PROXY_PORT),Proxy);threading.Thread(target=server.serve_forever,daemon=True).start()
 cases=sys.argv[1:] or [f'{variant}-{i}' for variant in ['native','official'] for i in range(1,6)]
 for case in cases:run_case(case)
 server.shutdown()
