"""Two real native tasks plus adversarial tenant probes against an isolated P3.1 deployment.

MCP records are actual file-backed security fixtures. No simulated Agent or tool receipts.
Run on deployment host: python3 stage1-live.py ACCEPTANCE_ROOT
"""
import pathlib,json,time,uuid,urllib.request,urllib.error,hashlib,hmac,base64,threading,concurrent.futures,subprocess,traceback,sys
r=pathlib.Path(sys.argv[1]);cfg=json.loads((r/'control/config.json').read_text());out=r/'evidence/live';out.mkdir(parents=True,exist_ok=True);base='http://127.0.0.1:'+str(cfg['port']);trace=[];lock=threading.Lock()
def encode(b):return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
def sign(c):
 b=encode(json.dumps(c,separators=(',',':')).encode());return b+'.'+encode(hmac.new(cfg['signingKey'].encode(),b.encode(),hashlib.sha256).digest())
def api(token,path,value=None,expected=200,key=None):
 headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Accept':'application/json'}
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(base+path,None if value is None else json.dumps(value).encode(),headers)
 try:
  with urllib.request.urlopen(req,timeout=105) as res:code=res.status;data=json.load(res)
 except urllib.error.HTTPError as e:code=e.code;data=json.load(e)
 with lock:
  trace.append({'method':req.get_method(),'path':path,'request':value,'status':code,'response':data,'identityHash':hashlib.sha256(token.encode()).hexdigest()});(out/'http.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2))
 if code!=expected:raise RuntimeError(str((path,code,data)))
 return data
contracts={};tokens={};sessions={};summaries={}
for name in ['a','b']:
 c={'version':1,'tenantId':'tenant-'+name,'userId':'user-'+name,'taskId':'task-'+uuid.uuid4().hex,'sessionId':'session-'+uuid.uuid4().hex,'workspaceRef':'project','modelProfile':'qwen','budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},'toolPermissions':cfg['tenants']['tenant-'+name]['tools'],'dataPermissions':['dataset-'+name],'networkPolicy':'broker-only','expiresAt':int(time.time()*1000)+1800000}
 contracts[name]=c;tokens[name]=sign(c)
(out/'contracts.json').write_text(json.dumps(contracts,indent=2))
try:
 for name in ['a','b']:
  key=uuid.uuid4().hex;sessions[name]=api(tokens[name],'/v1/sessions',{},201,key);api(tokens[name],'/v1/sessions',{},200,key)
  api(tokens[name],'/v1/sessions',{'editTool':'apply_patch'},409,key)
 for _ in range(240):
  states={name:api(tokens[name],'/v1/sessions/'+contracts[name]['sessionId']+'/status') for name in ['a','b']}
  if all(d['workerState']=='READY' for d in states.values()):break
  if any(d['workerState'] in ['START_FAILED','RECOVERABLE'] for d in states.values()):raise RuntimeError('Worker startup failed: '+str(states))
  time.sleep(1)
 else:raise TimeoutError('Dual native startup deadline')
 checks={}
 for name,other in [('a','b'),('b','a')]:
  foreign='/v1/sessions/'+contracts[other]['sessionId']
  for suffix in ['', '/status','/messages','/events','/diff','/artifacts']:api(tokens[name],foreign+suffix,expected=404)
  for suffix in ['/messages','/cancel','/revoke']:api(tokens[name],foreign+suffix,{'text':'cross tenant'},404,uuid.uuid4().hex)
 checks['crossTenantSessionEventsDiffAndMutations']='PASS'
 mutated=contracts['a'].copy();mutated['tenantId']='tenant-b';forged=sign(mutated).split('.')[0]+'.'+tokens['a'].split('.')[1];api(forged,'/v1/sessions/'+contracts['a']['sessionId'],expected=401)
 checks['forgedIdentity']='PASS'
 extra={**contracts['a'],'sessionId':'extra-'+uuid.uuid4().hex,'taskId':'extra-'+uuid.uuid4().hex};api(sign(extra),'/v1/sessions',{},429,uuid.uuid4().hex)
 checks['boundedScheduler']='PASS'
 for name in ['a','b']:api(tokens[name],'/v1/sessions',{'workspaceRef':'/host/private'},400,uuid.uuid4().hex)
 checks['clientHostPathRejected']='PASS'
 def task(name):
  target=out/name;target.mkdir();c=contracts[name];token=tokens[name];s=c['sessionId']
  prompt='这是 Runtime coding-agent-node 真实项目的双租户并发验收。阅读 src/monitor-client.ts 确认导出的客户端工厂名称；使用已授权的 MCP read_dataset 工具，datasetId="dataset-'+name+'"，取得本租户真实测试记录。新增 tenant-proof.json，包含 factoryName、datasetId 和从 MCP 得到的 marker；新增 test/tenant-proof.test.js，用 node:test 验证 JSON 与工厂名称，使用原生编辑工具写文件，并执行 node --test test/tenant-proof.test.js。不要改现有业务文件、Agent 或构建工具。完成后报告真实回执。MCP 测试记录不是行情数据。'
  accepted=api(token,'/v1/sessions/'+s+'/messages',{'text':prompt},202,uuid.uuid4().hex);(target/'submission.json').write_text(json.dumps({'prompt':prompt,'accepted':accepted},ensure_ascii=False,indent=2))
  start=time.monotonic();approved=set()
  while time.monotonic()-start<900:
   state=api(token,'/v1/sessions/'+s+'/status');native=state.get('native') or {}
   for p in native.get('pendingPermissions',[]):
    if p['id'] in approved:continue
    api(token,'/v1/permissions/'+p['id']+'/respond',{'decision':'approve','reason':'Authorized isolated tenant coding and dataset acceptance'})
    approved.add(p['id'])
   if native.get('status') in ['turn_completed','failed','awaiting_input']:break
   time.sleep(1)
  else:raise TimeoutError(name+' model deadline')
  events=api(token,'/v1/sessions/'+s+'/events')['events'];(target/'native-events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
  assert native.get('status')=='turn_completed','Native real task did not complete'
  registry=json.loads((r/'control/workers'/sessions[name]['workerId']/'registry.json').read_text());workspace=r/'control/workers'/registry['workerId']/'workspace'
  cmd=['docker','exec','-w','/poc/project',registry['containerId'],'node','--test','test/tenant-proof.test.js'];p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(target/'independent-test.log').write_bytes(p.stdout);assert p.returncode==0
  result=json.loads((workspace/'tenant-proof.json').read_text());expected=json.loads((r/'mcp'/('dataset-'+name+'.json')).read_text());assert result['marker']==expected['marker'] and result['datasetId']==expected['datasetId'] and result['factoryName']=='createMonitorClient'
  other='b' if name=='a' else 'a';foreign=json.loads((r/'mcp'/('dataset-'+other+'.json')).read_text())['marker'];assert foreign not in json.dumps(result)
  tools=[e['payload'] for e in events if e['eventType']=='tool.completed'];names=[t.get('toolName') for t in tools];assert 'read_files' in names and 'editor' in names and 'run_commands' in names
  assert any('mcp' in str(t.get('toolName','')).lower() or 'read_dataset' in str(t.get('toolName','')).lower() for t in tools),'No native MCP receipt'
  files=[{'path':f,'sha256':hashlib.sha256((workspace/f).read_bytes()).hexdigest()} for f in ['tenant-proof.json','test/tenant-proof.test.js']]
  summary={'status':'PASS','sessionId':s,'nativeSessionId':native.get('nativeSessionId'),'workerId':registry['workerId'],'containerId':registry['containerId'],'durationSeconds':round(time.monotonic()-start,2),'nativeToolNames':names,'fileHashes':files,'independentTestExitCode':0,'authorizedMCPDataMatched':True,'foreignMCPDataAbsent':True}
  (target/'summary.json').write_text(json.dumps(summary,indent=2));summaries[name]=summary
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
  futures=[pool.submit(task,name) for name in ['a','b']]
  while not all(f.done() for f in futures):
   current={name:api(tokens[name],'/v1/sessions/'+contracts[name]['sessionId']+'/status') for name in ['a','b']}
   if all((d.get('native') or {}).get('status') in ['running','awaiting_permission'] for d in current.values()):checks['simultaneousNativeExecution']='PASS';(out/'simultaneous-status.json').write_text(json.dumps(current,indent=2))
   time.sleep(2)
  for f in futures:f.result()
 assert checks.get('simultaneousNativeExecution')=='PASS'
 (out/'coding-summary.json').write_text(json.dumps({'status':'PASS','checks':checks,'cases':summaries},indent=2));print('Both real native tenant tasks PASS',flush=True)
 # Leave own workers alive for adversarial OS/network/broker probes, not for new coding.
except BaseException:
 (out/'failure.json').write_text(json.dumps({'status':'FAIL','traceback':traceback.format_exc()},indent=2));raise
