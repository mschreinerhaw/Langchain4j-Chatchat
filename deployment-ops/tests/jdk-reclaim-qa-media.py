"""Retire only the two completed QA fixtures; retain signed persistent payloads and all business data."""
import hashlib,http.cookiejar,json,os,stat,subprocess,time,urllib.request
from pathlib import Path

state=Path('/mnt/disk01/deployment-ops-v1-validation/jdk-jar-state')
publisher=Path('/mnt/disk01/ops-publisher-20261116')
fixtures={
 'runtime-os-gateway-good-qa.ops.zip':'3f65d56e77606b9fbd542d1f6d6533c51dc720395a700f7a262dc701a9c9ade3',
 'runtime-os-gateway-bad-qa.ops.zip':'ac720683218db8d28ab13d14477a8013b423cb3c2dc1436d18af0b8ee08c086c',
}
client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def api(path,data=None):
 request=urllib.request.Request('http://127.0.0.1:19270/api'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json','X-Ops-Request':'1'})
 with client.open(request,timeout=60) as response:return json.load(response)
def running():
 ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
 return sorted(subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}}',*ids],text=True).splitlines()) if ids else []
api('/login',{'username':'admin','password':os.environ['OPS_QA_PASSWORD']})
assert api('/platform/health')['dataDirectory']==str(state/'data')
jobs={j['id']:j for j in api('/nodes/1/installations')};protected=set()
for job in jobs.values():
 if job.get('active'):
  current=job
  while current:
   protected.add(current['package']);current=jobs.get(current.get('previousRun'))
before=running();records=[]
for name,digest in fixtures.items():
 assert digest not in protected,'An active release or its rollback ancestry still uses QA media'
 media=publisher/name;assert media.resolve()==media and stat.S_ISREG(media.lstat().st_mode)
 with media.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest
 uploads=[]
 for path in (state/'standalone/jdk/packages/uploads').glob('*.json'):
  record=json.loads(path.read_text())
  if record.get('sha256')==digest:uploads.append(record['id'])
 assert uploads,'No verified QA upload receipt'
 for ident in uploads:
  api('/nodes/1/packages/uploads/'+ident+'/commit',{})
  for _ in range(1800):
   status=api('/nodes/1/packages/uploads/'+ident)
   if status['status']=='failed':raise RuntimeError(status)
   if status['status']=='verified' and status.get('stagingReclaimed'):break
   time.sleep(1)
  else:raise RuntimeError('QA cache reverification timed out')
  assert not (state/'standalone/jdk/packages/uploads'/ident).exists()
 api('/nodes/1/packages/'+digest+'/retire',{'confirmation':digest,'reason':'Completed isolated QA upgrade/failure fixture; signed persistent payloads retained'})
 folder=state/'standalone/jdk/packages/packages'/digest
 assert (folder/'receipt.json').is_file() and (folder/'retired.json').is_file() and (folder/'images').is_dir()
 assert media.stat().st_nlink==1,'Unknown media aliases remain; refuse deletion'
 size=media.stat().st_size;media.unlink()
 records.append({'name':name,'sha256':digest,'reclaimedBytes':size,'verifiedUploads':uploads,'signedPersistentPayloadsRetained':True})
assert running()==before,'A running business container changed'
result={'passed':True,'scope':'two completed QA publisher ZIP files and their verified staging links only','records':records,'businessIdsAndStartedAtUnchanged':True,'businessDataAndVolumesRemoved':False}
Path('/mnt/disk01/deployment-ops-v1-validation/jdk-only-current/qa-media-reclamation.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
