"""Promote a verified native archive through the real API on the release QA instance."""
import argparse,hashlib,http.cookiejar,json,os,subprocess,time,urllib.request,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
state=Path('/mnt/disk01/deployment-ops-v1-release/state');base='http://127.0.0.1:19240/api'
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(path,data=None,headers=None):
    body=json.dumps(data).encode() if isinstance(data,dict) else data
    with op.open(urllib.request.Request(base+path,body,{'Content-Type':'application/json','X-Ops-Request':'1',**(headers or {})}),timeout=180) as response:return json.load(response)
def login():request('/login',{'username':'admin','password':os.environ['OPS_TEST_PASSWORD']})
def containers():
    ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
    return sorted(subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}} {{.State.Running}}',*ids],text=True).splitlines())
with zipfile.ZipFile(a.archive) as z:
    manifest=json.loads(z.read('release.json'))
    assert all(hashlib.sha256(z.read(name)).hexdigest()==digest for name,digest in manifest['files'].items())
login();assert request('/platform/health')['dataDirectory']==str(state/'data')
result={'before':containers(),'archiveSha256':hashlib.file_digest(a.archive.open('rb'),'sha256').hexdigest(),'version':manifest['version'],'sourceCommit':manifest['sourceCommit']}
upload=request('/platform/upgrades',a.archive.read_bytes(),{'X-SHA256':result['archiveSha256'],'Content-Type':'application/zip'})
job=request('/platform/maintenance',{'action':'upgrade','name':upload['name'],'confirmation':'UPGRADE:'+upload['name']})
for _ in range(180):
    file=state/'maintenance'/(job['id']+'.json')
    if file.exists():
        maintenance=json.loads(file.read_text())
        if maintenance['status'] in ('succeeded','failed'):break
    time.sleep(2)
else:raise RuntimeError('Timeout; inspect retained maintenance journal')
result['maintenance']=maintenance;assert maintenance['status']=='succeeded',maintenance
login();result['health']=request('/platform/health');assert result['health']['version']==manifest['version'] and result['health']['sourceCommit']==manifest['sourceCommit']
result['after']=containers();result['allBusinessIdsAndStartTimesUnchanged']=result['before']==result['after'];assert result['allBusinessIdsAndStartTimesUnchanged']
result['businessContainerCount']=len(result['before']);result['passed']=True;a.report.write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k not in ['before','after','maintenance']}))
