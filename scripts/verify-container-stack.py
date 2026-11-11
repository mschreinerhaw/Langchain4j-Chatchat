"""Authenticated HTTP smoke checks; never persist passwords, JWTs or cookies."""
import argparse, http.cookiejar, json, re, subprocess, urllib.error, urllib.request
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--credentials-file',type=Path,required=True)
p.add_argument('--output',type=Path,required=True);p.add_argument('--base',default='http://127.0.0.1:18100')
p.add_argument('--mcp-base',default='http://127.0.0.1:18101');a=p.parse_args()
credentials=json.loads(a.credentials_file.read_text());checks=[]
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def http(path,body=None,token=None,base=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request((base or a.base)+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    try:
        with opener.open(req,timeout=30) as res:return res.status,res.headers,res.read()
    except urllib.error.HTTPError as error:return error.code,error.headers,error.read()
def check(name,passed,**details):checks.append({'name':name,'passed':bool(passed),**details})
try:
    status,_,_=http('/api/v1/enterprise/auth/me');check('apiRejectsUnauthenticated',status==401,httpStatus=status)
    status,_,raw=http('/api/v1/enterprise/auth/login',credentials);login=json.loads(raw)
    token=login.get('data',{}).get('token');check('adminLogin',status==200 and bool(token),httpStatus=status)
    if not token:raise RuntimeError('Real application login failed')
    status,_,raw=http('/api/v1/enterprise/auth/me',token=token);me=json.loads(raw)
    check('authenticatedIdentity',status==200 and bool(me.get('data',{}).get('id')),httpStatus=status)
    status,_,raw=http('/');check('apiFrontend',status==200 and b'<html' in raw.lower(),httpStatus=status)
    assets=re.findall(rb'(?:src|href)="([^" ]+\.js)"',raw)
    if assets:
        path=assets[0].decode();status,headers,body=http(path if path.startswith('/') else '/'+path)
        check('compiledFrontendAsset',status==200 and 'javascript' in headers.get('Content-Type','') and not body.lstrip().startswith(b'<!'),httpStatus=status)
    else:check('compiledFrontendAsset',False)
    status,_,raw=http('/admin/',base=a.mcp_base);check('mcpFrontend',status==200 and b'<html' in raw.lower(),httpStatus=status)
    status,_,raw=http('/api/v1/admin/auth/login',credentials,base=a.mcp_base)
    mcp_token=json.loads(raw).get('data',{}).get('token');check('mcpAdminLogin',status==200 and bool(mcp_token),httpStatus=status)
    status,_,raw=http('/api/v1/news/collections?limit=1',token=mcp_token,base=a.mcp_base)
    payload=json.loads(raw);check('mcpAuthenticatedNewsBridge',status==200 and payload.get('code')==200,httpStatus=status)
    status,_,raw=http('/api/v1/data-science/user-assets?limit=1&personalOnly=true',token=token)
    check('authorizedAssetInventory',status==200 and json.loads(raw).get('code')==200,httpStatus=status)
    status,_,raw=http('/monitor/');check('nativeMonitorPage',status==200 and b'<html' in raw.lower(),httpStatus=status)
    status,_,raw=http('/api/login',credentials);check('nativeMonitorLogin',status==200 and json.loads(raw).get('authenticated') is True,httpStatus=status)
    status,_,raw=http('/api/sessions');check('nativeMonitorSessions',status==200 and isinstance(json.loads(raw).get('items'),list),httpStatus=status)
    status,_,_=http('/minio/health/live',base='http://127.0.0.1:9000');check('siloHealth',status==200,httpStatus=status)
    code="const c=JSON.parse(require('fs').readFileSync(process.env.CLINE_BUSINESS_CONFIG));fetch('http://127.0.0.1:8791/healthz',{headers:{authorization:'Bearer '+c.secret}}).then(async r=>{if(!r.ok)process.exit(1);console.log(await r.text());});"
    native=json.loads(subprocess.check_output(['docker','exec','runtime-coding-business','node','-e',code],text=True))
    check('nativeBusinessAdapter',native.get('engine')=='cline-standalone' and native.get('legacySdkWorker') is False,version=native.get('version'))
except Exception as error:
    checks.append({'name':'verificationCompleted','passed':False,'errorType':type(error).__name__})
result={'schemaVersion':'source_container_stack_smoke.v1','checks':checks,'passed':all(c['passed'] for c in checks)}
a.output.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
raise SystemExit(0 if result['passed'] else 1)
