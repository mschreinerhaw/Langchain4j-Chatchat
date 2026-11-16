import hashlib,hmac,json,os,secrets,time,urllib.request,http.cookiejar
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
deadline=time.monotonic()+180;stable=None;last='Waiting for application readiness'
while time.monotonic()<deadline:
 try:
  for name,base in [('OS','http://api:8080/api/v1/enterprise/auth'),('MCP','http://mcp:8090/api/v1/admin/auth')]:
   phase=name+' login'
   request=urllib.request.Request(base+'/login',data=json.dumps({'username':'admin','password':os.environ['OS_ADMIN_PASSWORD']}).encode(),headers={'Content-Type':'application/json'})
   with op.open(request,timeout=10) as response:
    result=json.load(response)
    if response.status!=200 or not result.get('data',{}).get('token'):raise RuntimeError('Administrator token missing')
    token=result['data']['token']
    if name=='OS':api_token=token
   phase=name+' authenticated identity'
   with op.open(urllib.request.Request(base+'/me',headers={'Authorization':'Bearer '+token}),timeout=10) as response:
    identity=json.load(response).get('data',{});assert identity.get('username')=='admin','Administrator identity differs'
  if stable is None:stable=time.monotonic()
  if time.monotonic()-stable>=15:
   print('OS and MCP administrator logins and identities remained healthy through the readiness window.');break
 except Exception as error:
  stable=None;last=phase+': '+type(error).__name__;print('Waiting for '+last,flush=True)
 time.sleep(3)
else:raise RuntimeError('Administrator readiness deadline exceeded: '+last)
# Never print login responses, tokens or credentials.
path='/internal/v1/license/workspace-entitlement';stamp=str(int(time.time()));nonce=secrets.token_hex(16)
signature=hmac.new(os.environ['INTERNAL_SECRET'].encode(),('GET\n'+path+'\n'+stamp+'\n'+nonce).encode(),hashlib.sha256).hexdigest()
headers={'X-Chatchat-User':'chatchat_mcp_internal','X-Chatchat-Timestamp':stamp,'X-Chatchat-Nonce':nonce,'X-Chatchat-Signature':signature}
with op.open(urllib.request.Request('http://mcp:8090'+path,headers=headers),timeout=15) as response:
 result=json.load(response)
 if result.get('code')!=200 or 'licenseValid' not in result.get('data',{}):raise RuntimeError('Signed internal MCP request failed')
 license_valid=bool(result['data']['licenseValid'])
 print('Signed internal MCP request passed; license status: '+str(result['data'].get('licenseStatus')))
request=urllib.request.Request('http://api:8080/api/v1/mcp/center/sync',data=b'',headers={'Authorization':'Bearer '+api_token},method='POST')
with op.open(request,timeout=90) as response:
 result=json.load(response)
 data=result.get('data',{});errors=data.get('errors',[])
 expected_unlicensed=['center-services: 403 Forbidden from GET http://mcp:8090/api/v1/mcp-services']
 if result.get('code')!=200 or data.get('importedCount',0)<1 or (errors and (license_valid or errors!=expected_unlicensed)):
  raise RuntimeError('API to MCP center authentication or standalone discovery failed')
print('API to MCP standalone authentication and tool discovery passed.')
if errors:print('Licensed MCP service catalog remains protected until a product license is imported.')
