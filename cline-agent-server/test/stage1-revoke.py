"""Revoke an actually running native shell; do not claim MCP/Sandbox remote cancellation."""
import pathlib,json,time,sys,subprocess,urllib.request,urllib.error,hashlib,hmac,base64,uuid
r=pathlib.Path(sys.argv[1]);cfg=json.loads((r/'control/config.json').read_text());out=r/'evidence/live';cs=json.loads((out/'contracts.json').read_text());base='http://127.0.0.1:'+str(cfg['port']);trace=[]
def sign(c):
 b=base64.urlsafe_b64encode(json.dumps(c,separators=(',',':')).encode()).rstrip(b'=');return b.decode()+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=').decode()
def api(name,path,value=None,expected=200):
 req=urllib.request.Request(base+path,None if value is None else json.dumps(value).encode(),{'Authorization':'Bearer '+sign(cs[name]),'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':uuid.uuid4().hex})
 try:
  with urllib.request.urlopen(req,timeout=30) as res:code=res.status;data=json.load(res)
 except urllib.error.HTTPError as res:code=res.code;data=json.load(res)
 trace.append({'principal':name,'path':path,'status':code,'request':value,'response':data});(out/'revocation-http.json').write_text(json.dumps(trace,indent=2));assert code==expected,(path,code,data);return data
records={}
for name in ['a','b']:records[name]=next(json.loads(p.read_text()) for p in (r/'control/workers').glob('*/registry.json') if json.loads(p.read_text())['contract']['sessionId']==cs[name]['sessionId'])
s=cs['a']['sessionId'];api('a','/v1/sessions/'+s+'/messages',{'text':'为撤权测试执行并等待这条真实长命令，不要写其他文件：node -e "setTimeout(()=>require(\'fs\').writeFileSync(\'revoked-late.txt\',\'late\'),20000);setInterval(()=>{},1000)"'},202)
for _ in range(180):
 state=api('a','/v1/sessions/'+s+'/status');pending=(state.get('native') or {}).get('pendingPermissions',[]);permission=next((p for p in pending if p['toolName']=='run_commands'),None)
 if permission:break
 time.sleep(1)
else:raise TimeoutError('No real native shell permission')
assert 'revoked-late.txt' in json.dumps(permission['input']);api('a','/v1/permissions/'+permission['id']+'/respond',{'decision':'approve'})
for _ in range(30):
 ev=api('a','/v1/sessions/'+s+'/events')['events']
 if any(e['eventType']=='tool.started' and e['payload'].get('toolCallId')==permission['toolCallId'] for e in ev):break
 time.sleep(0.25)
else:raise TimeoutError('Native approved shell did not start')
time.sleep(1);before=json.loads(subprocess.check_output(['docker','inspect',records['a']['containerId']]))[0];assert before['State']['Running']
api('a','/v1/sessions/'+s+'/revoke',{},202)
for _ in range(60):
 stopped=api('a','/v1/sessions/'+s+'/status')
 if stopped['workerState']=='STOPPED':break
 time.sleep(0.5)
else:raise TimeoutError('Revoked container stop unconfirmed')
api('a','/v1/sessions/'+s+'/messages',{'text':'must not run'},403);api('a','/v1/sessions/'+s+'/events',expected=403);api('a','/v1/permissions/'+permission['id']+'/respond',{'decision':'approve'},403)
other=api('b','/v1/sessions/'+cs['b']['sessionId']+'/status');assert other['native']['status']=='turn_completed'
time.sleep(25)
path=r/'control/workers'/records['a']['workerId'];assert not (path/'workspace/revoked-late.txt').exists()
native_events=next((path/'state').glob('*/events.jsonl'));(out/'revocation-native-events.json').write_text(native_events.read_text())
inspect=json.loads(subprocess.check_output(['docker','inspect',records['a']['containerId']]))[0];assert not inspect['State']['Running']
report={'status':'PASS','nativeShellToolCallId':permission['toolCallId'],'localContainerStopProof':stopped['stopProof'],'postRevokeMessagesEventsAndApproval':403,'otherTenantUnaffected':True,'delayedMarkerAbsentAfter25SecondObservation':True,'workerState':stopped['workerState'],'taskState':stopped['taskState'],'remoteStopConfirmed':False,'scope':'Local native worker revocation only; MCP/Python long-operation stop proofs remain NOT_RUN'}
(out/'revocation-summary.json').write_text(json.dumps(report,indent=2));api('b','/v1/sessions/'+cs['b']['sessionId']+'/cancel',{},202)
print(json.dumps(report))
