"""Authenticated acceptance on the authorized stage; private requests/tokens stay in root-only evidence.

python3 verify-user-assets-stage.py foundation|analysis|finish
"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

os.umask(0o077)
STAGE=Path('/opt/runtime-os-docker-stage')
ROOT=STAGE/'evidence/user-assets-silo-20261021';ROOT.mkdir(mode=0o700,exist_ok=True)
BASE='http://127.0.0.1:18180/api/v1'
ASSETS='/data-science/user-assets'
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
label=sys.argv[3] if len(sys.argv)>3 else 'primary'
if not re.fullmatch('[a-zA-Z0-9_-]{1,40}',label):raise ValueError('Invalid acceptance label')
private=ROOT/('acceptance-private.json' if label=='primary' else 'acceptance-'+label+'-private.json')
if not private.exists() and label!='primary':
    initial=json.loads((ROOT/'acceptance-private.json').read_text());initial.pop('analysisRequest',None);initial.pop('taskId',None)
    private.write_text(json.dumps(initial))

def request(path,body=None,token=None,method=None,content_type='application/json',raw=False,timeout=30):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(BASE+path,data=data,method=method,headers={'Content-Type':content_type,
        'X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(req,timeout=timeout) as response:
            result=response.read()
            if raw:return response.status,result
            result=json.loads(result)
            if result.get('code',0) not in (0,200):raise RuntimeError('Application request failed: '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        if raw:return error.code,error.read()
        (ROOT/'http-error-private.txt').write_bytes(error.read())
        raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None

def save():private.write_text(json.dumps(state,indent=2))

def upload(token,name,data):
    boundary='asset-'+uuid.uuid4().hex
    body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: text/csv\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
    return request(ASSETS,body,token,content_type='multipart/form-data; boundary='+boundary)

admin=request('/enterprise/auth/login',json.loads((STAGE/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token=admin['token'];state=json.loads(private.read_text()) if private.exists() else {}
(ROOT/'browser-auth-private.json').write_text(json.dumps(admin))
phase=sys.argv[1]

if phase=='foundation':
    # A dedicated temporary role gets only the existing data-science and self-account permissions.
    if 'roleId' not in state:
        permissions=request('/enterprise/permissions',token=token)
        ids=[p['id'] for p in permissions if p['permissionCode'] in ('capability:data-science','account:self:read')]
        if len(ids)!=2:raise RuntimeError('Expected existing permission policies not found')
        role=request('/enterprise/roles',{'tenantId':admin['user']['tenantId'],'roleCode':'ASSET_ACCEPTANCE_'+uuid.uuid4().hex[:12],
            'roleName':'Temporary asset isolation acceptance','roleType':'business','status':'enabled'},token)
        state['roleId']=role['id'];save()
        request('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[]},token,'PUT')
    if 'otherUser' not in state:
        name='assetcheck_'+uuid.uuid4().hex[:12];password=secrets.token_urlsafe(24)
        user=request('/enterprise/users',{'user':{'tenantId':admin['user']['tenantId'],'username':name,'displayName':'Temporary asset isolation acceptance',
            'passwordHash':password,'status':'enabled'},'roleIds':[state['roleId']]},token)
        state['otherUser']={'id':user['id'],'username':name,'password':password};save()
    other=request('/enterprise/auth/login',{'username':state['otherUser']['username'],'password':state['otherUser']['password']})['token']
    data=b'category,amount,currency\nalpha,12.50,CNY\nbeta,30.25,CNY\n'
    file=upload(token,'silo-acceptance.csv',data);state['fileId']=file['id'];state['sha256']=hashlib.sha256(data).hexdigest();save()
    again=upload(token,'silo-acceptance.csv',data)
    if again['id']!=file['id']:raise RuntimeError('Retry created a second version of unchanged bytes')
    foreign=upload(other,'silo-acceptance.csv',data);state['otherFileId']=foreign['id'];save()
    checks={
        'ownerRead':request(ASSETS+'/'+file['id']+'/content',token=token,raw=True)==(200,data),
        'crossOwnerReadDenied':request(ASSETS+'/'+file['id']+'/content',token=other,raw=True)[0]==404,
        'crossOwnerInfoDenied':request(ASSETS+'/'+file['id'],token=other,raw=True)[0]==404,
        'forgedOwnerDenied':request(ASSETS+'/'+file['id']+'/content?userId='+admin['user']['id'],token=other,raw=True)[0]==404,
        'crossOwnerDeleteDenied':request(ASSETS+'/'+file['id'],token=other,method='DELETE',raw=True)[0]==404,
        'anonymousDownloadDenied':request(ASSETS+'/'+file['id']+'/content',raw=True)[0]==401,
        'differentOwnerDifferentId':file['id']!=foreign['id'],
        'foreignNotListed':all(item['id']!=file['id'] for item in request(ASSETS,token=other))}
    request(ASSETS+'/'+foreign['id'],token=other,method='DELETE')
    replacement=upload(other,'silo-acceptance.csv',data);state['otherFileId']=replacement['id'];save()
    checks['reuploadAfterDelete']=replacement['id']!=foreign['id'] and request(ASSETS+'/'+replacement['id']+'/content',token=other,raw=True)==(200,data)
    subprocess.run(['docker','restart','silo'],check=True,capture_output=True)
    for _ in range(30):
        try:
            with client.open('http://127.0.0.1:9000/minio/health/ready',timeout=2) as response:
                if response.status==200:break
        except OSError:time.sleep(1)
    checks['containerRestartRead']=request(ASSETS+'/'+file['id']+'/content',token=token,raw=True)==(200,data)
    try:
        with client.open('http://127.0.0.1:9000/runtime-assets',timeout=3):checks['privateBucket']=False
    except urllib.error.HTTPError as error:checks['privateBucket']=error.code==403

elif phase=='analysis':
    if 'analysisRequest' not in state:
        state['analysisRequest']={'requestId':'asset-accept-'+uuid.uuid4().hex,'fileIds':[state['fileId']],'contextIds':[],
            'modelName':sys.argv[2] if len(sys.argv)>2 else 'qwen3.8-max','instruction':'Read the complete original CSV, verify its hash and calculate total amount using exact Decimal arithmetic and the number of records. Currency is declared by the currency column; report per currency and do not combine currencies. Show the declared amount field, formula and both source records as references. Do not change the input data.'};save()
    print(json.dumps({'phase':'ANALYSIS_SUBMIT','requestId':state['analysisRequest']['requestId']}),flush=True)
    result=request(ASSETS+'/analyses',state['analysisRequest'],token,timeout=900)
    (ROOT/('analysis-result-'+label+'-private.json')).write_text(json.dumps(result,indent=2))
    state['taskId']=result.get('taskId');save()
    if not result.get('resultAvailable'):raise RuntimeError('Live Cline analysis not accepted; inspect private receipt')
    markdown=result.get('results',[{}])[0].get('reportMarkdown','')
    if not re.search(r'42[.]75',markdown):raise RuntimeError('Computed report does not contain the independently expected total')
    files=request(ASSETS+'?taskId='+result['taskId']+'&limit=100',token=token)
    kinds=sorted({item['kind'] for item in files})
    if not {'CODE','DATASET','LOG','ARTIFACT','CONTEXT'}.issubset(set(kinds)):raise RuntimeError('Required task assets were not archived')
    for file in files:
        if file['state']!='AVAILABLE':raise RuntimeError('Task contains an unfinished asset publication')
        status,content=request(ASSETS+'/'+file['id']+'/content',token=token,raw=True)
        if status!=200 or hashlib.sha256(content).hexdigest()!=file['sha256']:raise RuntimeError('Archived asset hash differs')
    report={'status':'PASSED','taskId':result['taskId'],'requestId':state['analysisRequest']['requestId'],
        'resultAvailable':True,'expectedTotal':'42.75','currency':'CNY','originalSha256':state['sha256'],
        'taskAssetCount':len(files),'assetKinds':kinds,'allArchivedHashesVerified':True,'evidenceRefs':result.get('evidenceRefs',[])}
    (ROOT/('analysis-acceptance-'+label+'.json')).write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)

elif phase=='finish':
    if 'otherUser' in state and not state.get('otherUserDeleted'):
        other=request('/enterprise/auth/login',{'username':state['otherUser']['username'],'password':state['otherUser']['password']})['token']
        request(ASSETS+'/'+state['otherFileId'],token=other,method='DELETE')
        request('/enterprise/users/'+state['otherUser']['id'],token=token,method='DELETE');state['otherUserDeleted']=True;save()
        request('/enterprise/roles/'+state['roleId'],token=token,method='DELETE')
    probe=r'''import net from 'node:net';import {access} from 'node:fs/promises';
const host=process.argv[1];let allowed=await new Promise(resolve=>{const s=net.createConnection({host,port:9000});s.setTimeout(1500,()=>{s.destroy();resolve(false)});s.on('connect',()=>{s.destroy();resolve(true)});s.on('error',()=>resolve(false));});
let credentials=false;try{await access('/run/secrets/asset-secret-key');credentials=true;}catch{}
console.log(JSON.stringify({siloReachable:allowed,hasAssetCredentials:credentials}));'''
    info=json.loads(subprocess.check_output(['docker','inspect','silo']))[0]
    ip=next(iter(info['NetworkSettings']['Networks'].values()))['IPAddress']
    boundary=json.loads(subprocess.check_output(['docker','exec','runtime-os-app-stage-coding-agent-1','node','--input-type=module','-e',probe,ip],text=True))
    if boundary['siloReachable'] or boundary['hasAssetCredentials']:raise RuntimeError('Worker asset boundary failed')
    admin_probe=subprocess.run(['docker','exec','-i','silo','sh'],input='export MC_HOST_app="http://$(cat /run/secrets/app-user):$(cat /run/secrets/app-password)@127.0.0.1:9000"\nmc admin info app\n',text=True,capture_output=True)
    denied=admin_probe.returncode!=0 and 'access denied' in (admin_probe.stdout+admin_probe.stderr).lower()
    if not denied:raise RuntimeError('API storage account administrator boundary not confirmed')
    report={'status':'PASSED','temporaryUserDeleted':True,'temporaryRoleDeleted':True,'workerBoundary':boundary,'apiStorageAdminDenied':denied}
    (ROOT/'finish-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)

else:raise ValueError('Unknown acceptance phase')

if phase=='foundation':
    # Anonymous S3 must return 403; no presigned bearer URLs are issued by the application.
    report={'status':'PASSED' if all(checks.values()) else 'FAILED','checks':checks,'fileId':state['fileId'],'sha256':state['sha256']}
    (ROOT/'foundation-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
    if not all(checks.values()):raise RuntimeError('Foundation acceptance failed')
