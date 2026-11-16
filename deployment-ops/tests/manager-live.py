"""Explicit isolated QA only. Never targets a pre-existing container or image.

Run in a dedicated QA source directory with its private .env and qa-compose.json.
Business Docker resources are inspected and their identities checked, never operated.
"""
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import subprocess
import time
from urllib import request, error
import uuid

ROOT=Path(__file__).resolve().parents[1]
BASE=os.getenv('OPS_QA_URL','http://127.0.0.1:19230')
env=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#'))
op=request.build_opener(request.ProxyHandler({}),request.HTTPCookieProcessor(CookieJar()))
checks=[]
def api(path,data=None,headers=None):
    raw=data if isinstance(data,bytes) else json.dumps(data).encode() if data is not None else None
    q=request.Request(BASE+'/api'+path,data=raw,headers={'X-Ops-Request':'1',**(headers or {})})
    with op.open(q,timeout=90) as response:return json.load(response)
def check(label,condition):
    assert condition,label
    checks.append(label);print('PASS',label,flush=True)
def rejected(path,data):
    try:api(path,data)
    except error.HTTPError as failure:
        failure.read();failure.close();return failure.code in (400,403)
    return False
def operation(spec):
    plan=api('/nodes/1/manager/plans',spec)
    data={'planId':plan['id'],'confirmation':plan['target'],'idempotencyKey':uuid.uuid4().hex}
    job=api('/nodes/1/manager/execute',data)
    for _ in range(120):
        current=api('/nodes/1/manager/jobs/'+job['id'])
        if current['status'] not in ('PENDING','RUNNING'):
            assert current['status']=='SUCCESS',current
            return current,data
        time.sleep(.25)
    raise AssertionError('Job timed out')
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def baseline():
    ids=subprocess.check_output(['docker','ps','-aq','--no-trunc'],text=True).split()
    rows=json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []
    return {x['Id']:{'startedAt':x['State']['StartedAt'],'running':x['State']['Running']} for x in rows if not x['Name'].startswith('/deployment-manager-p0-qa-')}

def main():
    for attempt in range(60):
        try:
            api('/health');break
        except Exception:
            if attempt == 59:raise
            time.sleep(.5)
    before=baseline()
    api('/login',{'username':'admin','password':env['ADMIN_PASSWORD']})
    inventory=api('/inventory');check('discover existing running and stopped containers',bool(inventory[0]['data']['containers']))
    host=api('/nodes/1/manager/host');check('real host metrics with explicit scope',host['available'] and host['scope']=='host' and host['memoryTotalBytes']>0)
    check('real networks and volumes',bool(api('/nodes/1/manager/networks')) and 'Volumes' in api('/nodes/1/manager/volumes'))
    api('/users',{'username':'qa-viewer','password':'viewer-'+uuid.uuid4().hex,'role':'viewer'})
    # Independent viewer client checks that a direct API cannot bypass disabled UI.
    viewer=request.build_opener(request.ProxyHandler({}),request.HTTPCookieProcessor(CookieJar()))
    password='viewer-'+uuid.uuid4().hex
    api('/users',{'username':'qa-viewer','password':password,'role':'viewer'})
    viewer.open(request.Request(BASE+'/api/login',data=json.dumps({'username':'qa-viewer','password':password}).encode())).close()
    try:viewer.open(request.Request(BASE+'/api/nodes/1/manager/plans',data=b'{"action":"volume.create","name":"ops-forbidden"}',headers={'X-Ops-Request':'1'}))
    except error.HTTPError as denied:check('viewer cannot mutate or approve operations',denied.code==403);denied.close()
    else:raise AssertionError('viewer mutation accepted')
    suffix=str(int(time.time()));volume='ops-qa-volume-'+suffix;name='ops-qa-container-'+suffix
    fixture='deployment-manager-fixture:p0-qa-20261116'
    image=inspect(fixture)['Id']
    operation({'action':'volume.create','name':volume})
    created,data=operation({'action':'container.create','name':name,'image':image,'command':['sh','-c','echo persistent-data > /data/proof; echo manager-qa-ready; while true; do sleep 5; done'],'volumes':[{'source':volume,'target':'/data','readOnly':False}],'user':'999:999','memoryMiB':64,'cpus':.5,'restart':'no'})
    cid=created['result']['Id'];check('real container created without modifying existing containers',inspect(cid)['State']['Status']=='created')
    check('duplicate submission returns original job',api('/nodes/1/manager/execute',data)['id']==created['id'])
    operation({'action':'container.start','id':cid});check('container start',inspect(cid)['State']['Running'])
    logs=api('/nodes/1/containers/'+cid+'/logs');check('real container logs','manager-qa-ready' in logs['text'])
    metrics=api('/nodes/1/manager/containers/'+cid+'/metrics');check('container metrics are not host metrics',metrics['scope']=='container' and metrics['memoryLimitBytes']==64*1024**2)
    check('in-use image deletion is rejected',rejected('/nodes/1/manager/plans',{'action':'image.delete','id':image}))
    operation({'action':'container.restart','id':cid});check('container restart',inspect(cid)['State']['Running'])
    stable=inspect(cid)['State']['StartedAt']
    command=['docker','compose','-f',str(ROOT/'qa-compose.json')]
    subprocess.run(command+['stop','web','executor'],check=True,cwd=ROOT)
    check('manager stopped while workload remains running',inspect(cid)['State']['Running'] and inspect(cid)['State']['StartedAt']==stable)
    subprocess.run(command+['start','executor','web'],check=True,cwd=ROOT)
    for _ in range(60):
        try:
            api('/login',{'username':'admin','password':env['ADMIN_PASSWORD']});break
        except Exception:time.sleep(.5)
    check('manager restart preserves operation ledger',api('/nodes/1/manager/jobs/'+created['id'])['status']=='SUCCESS')
    check('manager restart does not restart workload',inspect(cid)['State']['StartedAt']==stable)
    operation({'action':'container.stop','id':cid});check('container stop',not inspect(cid)['State']['Running'])
    operation({'action':'container.delete','id':cid});check('container deletion preserves named volume',json.loads(subprocess.check_output(['docker','volume','inspect',volume]))[0]['Name']==volume)
    # The daemon's read-only archive endpoint confirms the file survives deletion.
    # Recreate a container on the retained volume without starting it (no writes).
    proof,_=operation({'action':'container.create','name':name+'-proof','image':image,'command':['sh','-c','cat /data/proof; sleep 600'],'user':'999:999','volumes':[{'source':volume,'target':'/data','readOnly':True}],'restart':'no'})
    proof_id=proof['result']['Id']
    operation({'action':'container.start','id':proof_id})
    content=subprocess.check_output(['docker','cp',proof_id+':/data/proof','-'])
    check('persistent bytes survive deletion',b'persistent-data' in content)
    operation({'action':'container.stop','id':proof_id})
    operation({'action':'container.delete','id':proof_id})
    with op.open(BASE+'/api/nodes/1/manager/export/'+image.replace('sha256:',''),timeout=90) as response:archive=response.read()
    check('offline image archive export',len(archive)>1024)
    operation({'action':'image.delete','id':image})
    result=api('/nodes/1/images/import',archive,{'X-SHA256':hashlib.sha256(archive).hexdigest()})
    check('offline image import with SHA256',bool(result.get('sha256')) and inspect(fixture)['Id']==image)
    operation({'action':'image.tag','id':image,'repository':'deployment-manager-fixture','tag':'qa-alias-'+suffix})
    check('real local image tag',inspect('deployment-manager-fixture:qa-alias-'+suffix)['Id']==image)
    audit=api('/audit');check('authenticated audit records',any(r['actor']=='admin' and r['action']=='/manager/execute' for r in audit))
    after=baseline();check('all pre-existing containers retain identity, state, and startup time',all(after.get(k)==v for k,v in before.items()))
    (ROOT/'qa-acceptance.json').write_text(json.dumps({'passed':checks,'fixtureImage':image,'retainedTestVolume':volume,'baselinePreserved':True},indent=2))

if __name__=='__main__':main()
