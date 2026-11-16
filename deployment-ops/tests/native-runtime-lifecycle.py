"""Real signed upgrade/rollback acceptance for the isolated native candidate only.

Initial full media upload is covered by native-runtime-browser.cjs. For subsequent
large media, root seeds a hardlink into the API-created staging file, then invokes
the same authenticated signature/image verification and deployment endpoints.
"""
import argparse,hashlib,http.cookiejar,json,os,subprocess,time,urllib.request,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--media',type=Path,required=True);p.add_argument('--version',required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--environment',default='runtime-native-v1');p.add_argument('--expect',choices=['succeeded','rolled_back'],default='succeeded');p.add_argument('--http-port',type=int,default=19402);p.add_argument('--mcp-port',type=int,default=19403);a=p.parse_args()
state=Path('/mnt/disk01/deployment-ops-v1-candidate/state');base='http://127.0.0.1:19240/api'
if os.geteuid()!=0 or not a.media.resolve().is_relative_to('/mnt/disk01/ops-publisher-20261116'):raise SystemExit('Restricted to root and isolated QA publisher media')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,data=None):
    with op.open(urllib.request.Request(base+path,data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json','X-Ops-Request':'1'}),timeout=180) as r:return json.load(r)
def workloads():
    ids=subprocess.check_output(['docker','ps','-aq'],text=True).split();items=json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []
    return sorted((c['Id'],c['State']['StartedAt'],c['State']['Running']) for c in items if c['State']['Running'] and (c['Config'].get('Labels') or {}).get('com.docker.compose.project')!='ops-env-'+a.environment and (c['Config'].get('Labels') or {}).get('deployment-ops.environment')!=a.environment)
result={'version':a.version,'expected':a.expect,'before':workloads()};call('/login',{'username':'admin','password':os.environ['OPS_TEST_PASSWORD']})
with a.media.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
catalog=call('/nodes/1/packages')
if not any(item['digest']==digest for item in catalog):
    upload=call('/nodes/1/packages/uploads',{'name':a.media.name,'size':a.media.stat().st_size,'sha256':digest})
    staged=state/'packages/uploads'/upload['id'];temporary=staged.with_name(staged.name+'.seed-'+uuid.uuid4().hex);os.link(a.media,temporary);os.replace(temporary,staged)
    call('/nodes/1/packages/uploads/'+upload['id']+'/commit',{})
    for _ in range(600):
        status=call('/nodes/1/packages/uploads/'+upload['id'])
        if status['status']=='verified':break
        if status['status']=='failed':raise RuntimeError(status)
        time.sleep(2)
    else:raise RuntimeError('Offline verification timeout')
    print('Real signature and image verification passed',flush=True)
review=call('/nodes/1/installations/preflight',{'package':digest,'environment':a.environment,'parameters':{'httpPort':a.http_port,'mcpPort':a.mcp_port}})
assert review['ok'],review
result['preflight']=review;job=call('/nodes/1/installations',{'package':digest,'environment':a.environment,'reviewToken':review['reviewToken']});result['id']=job['id'];print('Task '+job['id'],flush=True)
for index in range(1200):
    job=call('/nodes/1/installations/'+job['id'])
    if index%15==0:print(job['status']+' '+job.get('message',''),flush=True)
    if job['status'] not in ['queued','running','starting','rolling_back']:break
    time.sleep(2)
result['task']=job;result['after']=workloads();result['businessUnchanged']=result['before']==result['after'];result['passed']=job['status']==a.expect and result['businessUnchanged'];a.report.write_text(json.dumps(result,indent=2));assert result['passed'],job
print(json.dumps({'passed':True,'task':job['id'],'status':job['status'],'businessUnchanged':True}))
