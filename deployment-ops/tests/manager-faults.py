"""Faults against the isolated QA manager, never a business daemon/service."""
import json
from pathlib import Path
import runpy
import subprocess
import time
from urllib import error

ROOT=Path(__file__).resolve().parents[1]
m=runpy.run_path(str(ROOT/'tests/manager-live.py'))
api,operation,check,inspect=m['api'],m['operation'],m['check'],m['inspect']
compose=ROOT/'qa-compose.json'
original=compose.read_text()
command=['docker','compose','-f',str(compose)]
api('/login',{'username':'admin','password':m['env']['ADMIN_PASSWORD']})
def policy_wait(expected):
    for _ in range(60):
        try:
            if api('/nodes/1/manager/policy')['readOnly']==expected:return
        except Exception:pass
        time.sleep(.5)
    raise AssertionError('Node did not return expected policy')
def configure(read_only):
    config=json.loads(original);config['services']['executor']['environment'].update(OPS_READ_ONLY=str(int(read_only)),OPS_ALLOWED_REGISTRIES='127.0.0.1:1')
    compose.write_text(json.dumps(config,indent=2))
    subprocess.run(command+['up','-d','--no-deps','--no-build','--pull','never','executor'],check=True,cwd=ROOT)
    policy_wait(read_only)
def result(spec):
    import uuid
    plan=api('/nodes/1/manager/plans',spec)
    data={'planId':plan['id'],'confirmation':plan['target'],'idempotencyKey':uuid.uuid4().hex}
    job=api('/nodes/1/manager/execute',data)
    for _ in range(120):
        value=api('/nodes/1/manager/jobs/'+job['id'])
        if value['status'] not in ('PENDING','RUNNING'):return value
        time.sleep(.5)
    raise AssertionError('Operation did not terminate')
try:
    image=inspect('deployment-manager-fixture:p0-qa-20261116')['Id']
    created,_=operation({'action':'container.create','name':'ops-qa-failed-start-'+str(int(time.time())),'image':image,'command':['/does-not-exist-qa'],'restart':'no'})
    ident=created['result']['Id']
    failed=result({'action':'container.start','id':ident})
    check('container start failure is persisted as FAILED',failed['status']=='FAILED' and not inspect(ident)['State']['Running'])
    configure(True)
    plan=api('/nodes/1/manager/plans',{'action':'container.start','id':ident})
    try:api('/nodes/1/manager/execute',{'planId':plan['id'],'confirmation':plan['target'],'idempotencyKey':'read-only-fault-approval'})
    except error.HTTPError as denied:denied.read();denied.close();check('actual read-only node rejects approved write',denied.code==400 and not inspect(ident)['State']['Running'])
    else:raise AssertionError('Read-only write was accepted')
    subprocess.run(command+['stop','executor'],check=True,cwd=ROOT)
    snapshot=api('/inventory')
    check('agent outage is reported as unknown/offline instead of cached healthy',all('data' not in node and node.get('error') for node in snapshot))
    subprocess.run(command+['start','executor'],check=True,cwd=ROOT);policy_wait(True)
    check('agent reconnect restores real inventory',bool(api('/inventory')[0].get('data')))
    configure(False)
    failed=result({'action':'image.pull','reference':'127.0.0.1:1/qa-no-registry:never'})
    check('actual unavailable-registry pull persists FAILED',failed['status']=='FAILED')
    operation({'action':'container.delete','id':ident})
    (ROOT/'qa-faults.json').write_text(json.dumps({'passed':m['checks'],'productionDaemonRestarted':False},indent=2))
finally:
    compose.write_text(original)
    subprocess.run(command+['up','-d','--no-deps','--no-build','--pull','never','executor'],check=True,cwd=ROOT)
