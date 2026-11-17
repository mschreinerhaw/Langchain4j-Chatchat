import json,os,time,urllib.request,http.cookiejar
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
