"""Actual native platform replacement and failed-candidate recovery on fresh QA instance."""
import hashlib,http.cookiejar,json,os,subprocess,time,urllib.error,urllib.request,zipfile
from pathlib import Path
root=Path('/mnt/disk01/deployment-ops-v1-validation');state=root/'fresh-rc1/state';base='http://127.0.0.1:19250/api';archive=root/'deployment-ops-1.0.0-rc.2.zip'
if not (state/'manager.env').is_file():raise SystemExit('Install the isolated fresh-rc1 instance first')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(path,data=None,headers=None):
    body=json.dumps(data).encode() if isinstance(data,dict) else data
    with op.open(urllib.request.Request(base+path,body,{'X-Ops-Request':'1','Content-Type':'application/json',**(headers or {})}),timeout=120) as r:return json.load(r)
def login():return request('/login',{'username':'admin','password':os.environ['OPS_TEST_PASSWORD']})
def containers():
    ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
    return subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}} {{.State.Running}}',*ids],text=True).splitlines() if ids else []
def execute(media):
    login();content=media.read_bytes();upload=request('/platform/upgrades',content,{'X-SHA256':hashlib.sha256(content).hexdigest(),'Content-Type':'application/zip'})
    job=request('/platform/maintenance',{'action':'upgrade','name':upload['name'],'confirmation':'UPGRADE:'+upload['name']});print('Maintenance '+job['id'],flush=True)
    for _ in range(180):
        file=state/'maintenance'/(job['id']+'.json')
        if file.exists():
            result=json.loads(file.read_text())
            if result['status'] in ('failed','succeeded'):return result
        time.sleep(2)
    raise RuntimeError('Inspect retained maintenance journal; timeout')
results={'before':containers()};successful=execute(archive);results['upgrade']=successful;assert successful['status']=='succeeded',successful
login();assert request('/health')['status']=='UP';results['afterUpgrade']=containers();assert results['before']==results['afterUpgrade']
previousConfig=(state/'manager.env').read_bytes();broken=root/'deployment-ops-intentional-failure-qa.zip'
with zipfile.ZipFile(archive) as source,zipfile.ZipFile(broken,'w',zipfile.ZIP_DEFLATED) as output:
    metadata=json.loads(source.read('release.json'));payload=b'INTENTIONAL QA INVALID JAR: recovery acceptance only'
    metadata['version']='1.0.0-rc.failure-qa';metadata['files']['lib/deployment-ops.jar']=hashlib.sha256(payload).hexdigest()
    for item in source.infolist():
        if item.filename=='release.json':output.writestr(item,json.dumps(metadata).encode())
        elif item.filename=='lib/deployment-ops.jar':output.writestr(item,payload)
        else:output.writestr(item,source.read(item))
failed=execute(broken);results['failedCandidate']=failed;assert failed['status']=='failed' and failed.get('recovery',{}).get('status')=='succeeded',failed
login();assert request('/health')['status']=='UP';assert previousConfig==(state/'manager.env').read_bytes();results['configurationRecovered']=True
results['afterRecovery']=containers();results['businessUnchanged']=results['before']==results['afterRecovery'];results['passed']=results['businessUnchanged'];(root/'platform-upgrade-acceptance.json').write_text(json.dumps(results,indent=2));assert results['passed'];print(json.dumps({'passed':True,'nativeUpgrade':True,'failedCandidateRecovered':True,'allBusinessContainerIdsAndStartTimesUnchanged':True}))
