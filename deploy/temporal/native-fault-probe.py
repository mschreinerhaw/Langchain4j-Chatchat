"""Narrow operator probe for owned candidate containers, never production nodes.

Actions require an explicitly named candidate task and Compose ownership labels.
Signed old grants are retained only in a mode-0600 private file on the host.
"""
import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import re
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('action', choices=['inspect', 'crash', 'restore', 'replay'])
p.add_argument('--root', required=True)
p.add_argument('--task', required=True)
a = p.parse_args()
root = pathlib.Path(a.root).resolve()
if not str(root).startswith('/opt/runtime-os/native-temporal/') or not re.fullmatch('temporal-native-[0-9a-f]{64}', a.task):
    raise SystemExit('An isolated candidate root and exact Temporal-owned task required')

def run(argv, **kw):
    return subprocess.run(argv, check=True, capture_output=True, text=True, **kw).stdout

def read(path):
    return json.loads(pathlib.Path(path).read_text())

def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]

def owned(name, service):
    value = inspect(name)
    labels = value['Config']['Labels']
    assert labels['com.docker.compose.project'] == 'runtime-native-temporal-candidate'
    assert labels['com.docker.compose.service'] in ([service] if isinstance(service, str) else service)
    return value

records = []
for i in [1, 2]:
    g = read(root / 'private' / f'gateway{i}.json')
    for path in pathlib.Path(g['stateRoot']).glob('workers/*/registry.json'):
        r = read(path)
        if r['contract']['taskId'] == a.task:
            records.append((i, g, r))
if a.action == 'inspect':
    rows = []
    for i, g, r in records:
        marker = pathlib.Path(g['workerRoot']) / r['workerId'] / 'workspace/fault_ready.txt'
        python_running = False
        if marker.is_file() and r.get('containerId') and r['workerState'] == 'RUNNING':
            e = read(root / 'private' / f'executor{i}.json')
            cmd = ['docker', '-H', 'unix://'+e['dockerSocket']]
            worker = json.loads(run(cmd + ['inspect', r['containerId']]))[0]
            assert worker['Config']['Labels']['cline.executor'] == e['instanceId']
            if worker['State']['Running']:
                python_running = 'python' in run(cmd + ['top', r['containerId'], '-eo', 'pid,comm']).lower()
        rows.append(dict(nodeId=f'cline-worker-{i}', sessionId=r['contract']['sessionId'],
            containerId=r.get('containerId'), workerState=r['workerState'],
            markerPresent=marker.is_file(), pythonRunning=python_running,
            ready=marker.is_file() and python_running))
    print(json.dumps(rows))
    raise SystemExit()

snapshot = root / 'private' / ('fault-old-' + a.task + '.json')
if a.action == 'crash':
    active = [(i, g, r) for i, g, r in records if r['workerState'] == 'RUNNING']
    assert len(active) == 1, 'Exactly one real allocation required before the fault'
    i, g, r = active[0]
    marker = pathlib.Path(g['workerRoot']) / r['workerId'] / 'workspace/fault_ready.txt'
    assert marker.is_file(), 'The real Python readiness marker must exist'
    e = read(root / 'private' / f'executor{i}.json')
    cmd = ['docker', '-H', 'unix://' + e['dockerSocket']]
    worker = json.loads(run(cmd + ['inspect', r['containerId']]))[0]
    assert worker['State']['Running']
    assert worker['Config']['Labels']['cline.executor'] == e['instanceId']
    top = run(cmd + ['top', r['containerId'], '-eo', 'pid,comm'])
    assert 'python' in top.lower(), 'A real Python process must be running'
    connection = read(root / 'private/connection.json')
    body = base64.urlsafe_b64encode(json.dumps(r['contract'], separators=(',', ':'), ensure_ascii=False).encode()).rstrip(b'=').decode()
    signature = base64.urlsafe_b64encode(hmac.new(connection['issuerKey'].encode(), body.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
    value = dict(node=i, taskId=a.task, sessionId=r['contract']['sessionId'],
                 containerId=r['containerId'], grant=body+'.'+signature, crashedAt=time.time())
    with snapshot.open('x') as f:
        json.dump(value, f)
    snapshot.chmod(0o600)
    for kind in ['worker', 'node']:
        name = 'runtime-native-temporal-' + kind + str(i)
        owned(name, kind+str(i))
        run(['docker', 'update', '--restart=no', name])
        run(['docker', 'kill', name])
    print(json.dumps({k:v for k,v in value.items() if k!='grant'}))
elif a.action == 'restore':
    old = read(snapshot)
    for kind in ['node', 'worker']:
        name = 'runtime-native-temporal-' + kind + str(old['node'])
        owned(name, kind+str(old['node']))
        run(['docker', 'update', '--restart=unless-stopped', name])
        run(['docker', 'start', name])
    print(json.dumps(dict(restoredNode=old['node'], taskId=a.task)))
elif a.action == 'replay':
    old = read(snapshot)
    api = owned('runtime-native-temporal-api', ['api', 'temporal-control-api'])
    e = read(root / 'private' / f"executor{old['node']}.json")
    worker = json.loads(run(['docker', '-H', 'unix://'+e['dockerSocket'], 'inspect', old['containerId']]))[0]
    assert not worker['State']['Running'], 'Old process must actually stop before accepting recovery'
    port = 18451+old['node']*10
    requests = [dict(url=f"http://127.0.0.1:{port}/v1/sessions/{old['sessionId']}/"+route,
                     body=body) for route, body in [('messages', {'text':'Late old execution must not resume'}),
                                                   ('delivery', {'deliveryVersion':'late-old-version'})]]
    requests.append(dict(url='http://127.0.0.1:8080/internal/runtime/native-coding/model', body={}))
    code = '''import sys,json,urllib.request,urllib.error
x=json.load(sys.stdin);out=[]
for item in x['requests']:
 req=urllib.request.Request(item['url'],data=json.dumps(item['body']).encode(),headers={'Authorization':'Bearer '+x['grant'],'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=10) as r:status=r.status
 except urllib.error.HTTPError as e:status=e.code
 out.append({'route':item['url'].split('/')[-1],'status':status})
print(json.dumps(out))
'''
    values = json.loads(run(['nsenter', '-t', str(api['State']['Pid']), '-n', 'python3', '-c', code],
                           input=json.dumps(dict(grant=old['grant'], requests=requests))))
    assert all(v['status'] in [401, 403, 409] for v in values), values
    print(json.dumps(dict(taskId=a.task, oldContainerStopped=True, rejected=values)))
