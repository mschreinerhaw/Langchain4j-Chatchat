"""Native SSE compatibility through scoped governance ingress; no model inference."""
import pathlib,json,time,sys,uuid,urllib.request,urllib.error,base64,hmac,hashlib,http.client
r=pathlib.Path(sys.argv[1]);cfg=json.loads((r/'control/config.json').read_text());old=json.loads((r/'evidence/live/contracts.json').read_text())
c={**old['a'],'sessionId':'sse-'+uuid.uuid4().hex,'taskId':'sse-'+uuid.uuid4().hex,'expiresAt':int(time.time()*1000)+600000};base='http://127.0.0.1:'+str(cfg['port'])
def token(value):
 b=base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=');return b.decode()+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=').decode()
def api(value,path,body=None,expected=200):
 req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer '+token(value),'Content-Type':'application/json','Idempotency-Key':uuid.uuid4().hex})
 try:
  with urllib.request.urlopen(req,timeout=30) as res:code=res.status;data=json.load(res)
 except urllib.error.HTTPError as e:code=e.code;data=json.load(e)
 assert code==expected,(code,data);return data
created=api(c,'/v1/sessions',{},201)
for _ in range(180):
 state=api(c,'/v1/sessions/'+c['sessionId']+'/status')
 if (state.get('native') or {}).get('status')=='idle':break
 time.sleep(0.5)
else:raise TimeoutError('SSE worker ready')
def one(value,after,expected=200):
 req=urllib.request.Request(base+'/v1/sessions/'+c['sessionId']+'/events',headers={'Authorization':'Bearer '+token(value),'Accept':'text/event-stream','Last-Event-ID':str(after)})
 try:
  with urllib.request.urlopen(req,timeout=10) as res:
   assert res.status==expected
   for line in res:
    if line.startswith(b'data: '):return json.loads(line[6:])
 except urllib.error.HTTPError as e:assert e.code==expected;return {'status':e.code}
 raise RuntimeError('Missing SSE native record')
first=one(c,0);second=one(c,first['eventId']);assert int(second['eventId'])>int(first['eventId'])
assert api(c,'/v1/sessions/'+c['sessionId']+'/status')['native']['status']=='idle'
foreign=one(old['b'],0,404)
extra={**c,'taskId':'quota-'+uuid.uuid4().hex,'sessionId':'quota-'+uuid.uuid4().hex};api(extra,'/v1/sessions',{},429)
api({**c,'expiresAt':1},'/v1/sessions/'+c['sessionId'],expected=401)
api({**c,'dataPermissions':['dataset-b']},'/v1/sessions',{},403)
# A request is authenticated, but its body finishes only AFTER revocation.
payload=json.dumps({'text':'This must never reach native after revocation'}).encode()
slow=http.client.HTTPConnection('127.0.0.1',cfg['port'],timeout=30);slow.putrequest('POST','/v1/sessions/'+c['sessionId']+'/messages');slow.putheader('Authorization','Bearer '+token(c));slow.putheader('Content-Type','application/json');slow.putheader('Idempotency-Key',uuid.uuid4().hex);slow.putheader('Content-Length',str(len(payload)));slow.endheaders();slow.send(payload[:-1]);time.sleep(0.25)
api(c,'/v1/sessions/'+c['sessionId']+'/revoke',{},202)
slow.send(payload[-1:]);response=slow.getresponse();assert response.status==403,response.read();response.read();slow.close()
for _ in range(60):
 stopped=api(c,'/v1/sessions/'+c['sessionId']+'/status')
 if stopped['workerState']=='STOPPED':break
 time.sleep(0.5)
else:raise TimeoutError('SSE worker cleanup')
assert not any(e.get('type')=='model.forwarded' and e.get('workerId')==created['workerId'] for e in [json.loads(line) for line in (r/'control/audit.jsonl').read_text().splitlines()])
report={'status':'PASS','sessionId':c['sessionId'],'firstEventId':first['eventId'],'replayedEventId':second['eventId'],'disconnectDidNotCancelNativeWorker':True,'foreignSSEStatus':foreign['status'],'perTenantQuotaWithOneGlobalSlotFree':429,'expiredIdentity':401,'foreignDatasetGrant':403,'authenticatedBeforeRevokeBodyFinishedAfterRevoke':403,'nativeInferenceCalls':0,'localStopProof':stopped['stopProof']}
(r/'evidence/live/scoped-sse-and-quota.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
