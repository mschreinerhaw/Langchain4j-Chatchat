"""Negative scope and input-integrity checks against an existing candidate.
No model request or business validation is supplied by this controller fixture.
"""
import argparse,base64,copy,hashlib,hmac,json,time,urllib.request,urllib.error,uuid
from pathlib import Path
from urllib.parse import urlsplit
p=argparse.ArgumentParser();p.add_argument('--deployment',type=Path,required=True);p.add_argument('--source-case',required=True);p.add_argument('--case',required=True);a=p.parse_args()
cfg=json.loads((a.deployment/'private/gateway.json').read_text());source=json.loads((a.deployment/'acceptance'/a.source_case/'intent.json').read_text())['grant']
if not a.case.replace('-','').isalnum():raise ValueError('Unique case name required')
root=a.deployment/'acceptance'/a.case;root.mkdir(exist_ok=False);checks={};receipts=[]
def token(grant):
    raw=base64.urlsafe_b64encode(json.dumps(grant,separators=(',',':')).encode()).decode().rstrip('=')
    return raw+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
def request(grant,path,body=None,key=None):
    headers={'Authorization':'Bearer '+token(grant),'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    req=urllib.request.Request('http://127.0.0.1:'+str(cfg['port'])+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=100) as res:return res.status,json.load(res)
    except urllib.error.HTTPError as error:return error.code,json.load(error)
source=copy.deepcopy(source);source['sessionId']='scope-'+uuid.uuid4().hex;source['taskId']='scope-'+uuid.uuid4().hex;source['expiresAt']=int((time.time()+180)*1000)
source['deliveryBinding'].update(taskId=source['taskId'],runId='run-'+uuid.uuid4().hex,inputSnapshotVersion='scope-no-input-v1')
source['s3']['inputManifestSha256']=hashlib.sha256(b'[]').hexdigest()
status,created=request(source,'/v1/sessions',{'inputs':[]},'create-'+source['sessionId']);assert status==201,(status,created)
(root/'intent.json').write_text(json.dumps({'grant':source,'modelExpected':False},indent=2))
route='/v1/sessions/'+source['sessionId']+'/status'
status,_=request(source,route);checks['authorizedSessionReadable']=status==200
for field in ['tenantId','userId','taskId']:
    altered=copy.deepcopy(source);altered[field]='other-'+uuid.uuid4().hex
    if field in ['tenantId','taskId']:altered['deliveryBinding'][field]=altered[field]
    status,receipt=request(altered,route);checks[field+'MismatchDenied']=status in [401,403,404]
    receipts.append({'check':field,'httpStatus':status,'receipt':receipt})
altered=copy.deepcopy(source);altered['deliveryBinding']['runId']='other-'+uuid.uuid4().hex
status,receipt=request(altered,route);checks['runIdMismatchDenied']=status in [401,403,404] or status==409 and receipt.get('message')=='Task contract changed';receipts.append({'check':'runId','httpStatus':status,'receipt':receipt})
request(source,'/v1/sessions/'+source['sessionId']+'/cancel',{})
plan=json.loads((a.deployment/'private/parquet-plan.json').read_text());inputs=copy.deepcopy(plan['inputs']);inputs[0]['sha256']='0'*64
with urllib.request.urlopen(inputs[0]['url'],timeout=30) as response:
    observed=response.read();checks['sourceInputAvailable']=len(observed)==inputs[0]['size'] and hashlib.sha256(observed).hexdigest()==plan['inputs'][0]['sha256']
grant=copy.deepcopy(source);grant['sessionId']='integrity-'+uuid.uuid4().hex;grant['taskId']='integrity-'+uuid.uuid4().hex;grant['deliveryBinding']['taskId']=grant['taskId'];grant['deliveryBinding']['runId']='run-'+uuid.uuid4().hex;grant['expiresAt']=int((time.time()+180)*1000)
grant['s3']['inputPrefix']=plan['inputPrefix'];grant['s3']['outputPrefix']='/runtime-assets/tenants/'+grant['tenantId']+'/acceptance/'+grant['taskId']+'/'+grant['deliveryBinding']['runId']+'/output/'
inventory=[{**{k:i[k] for k in ['path','size','sha256','objectVersion']},'objectPath':urlsplit(i['url']).path} for i in sorted(inputs,key=lambda i:i['path'])]
grant['s3']['inputManifestSha256']=hashlib.sha256(json.dumps(inventory,separators=(',',':')).encode()).hexdigest()
(root/'invalid-input-intent.json').write_text(json.dumps({'grant':grant,'expectedFailure':'INPUT_SHA256_MISMATCH','modelExpected':False},indent=2))
status,reply=request(grant,'/v1/sessions',{'inputs':inputs},'create-'+grant['sessionId']);receipts.append({'check':'integrity-create','httpStatus':status,'receipt':reply})
state={};deadline=time.time()+60
while status<400 and time.time()<deadline:
    _,state=request(grant,'/v1/sessions/'+grant['sessionId']+'/status')
    if state.get('workerState') in ['START_FAILED','STOPPED','RECOVERABLE']:break
    time.sleep(.5)
checks['badInputRejectedBeforeCore']=status in [400,403,422] or state.get('workerState')=='START_FAILED' and not state.get('native')
checks['noModelForInvalidInput']=not list((a.deployment/'runs'/grant['sessionId']/'model').glob('*request.json'))
request(grant,'/v1/sessions/'+grant['sessionId']+'/cancel',{})
result={'checks':checks,'passed':all(checks.values()),'receipts':receipts,'invalidInputState':state,'businessAcceptance':False}
(root/'boundary-verification.json').write_text(json.dumps(result,indent=2));print(json.dumps({'checks':checks,'passed':result['passed']}))
if not result['passed']:raise SystemExit(1)
