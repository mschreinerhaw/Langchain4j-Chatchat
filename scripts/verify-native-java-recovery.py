"""Live candidate-only Java restart test; Cline supplies all analysis code.

The operator supplies an existing candidate Compose file. This script never
restarts the Native node, Silo, MCP, News or the main application entry point.
"""
import argparse
import hashlib
import base64
import copy
import hmac
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

p = argparse.ArgumentParser()
for name in ('origin', 'model', 'agent-id', 'api-project'):
    p.add_argument('--' + name, required=True)
for name in ('auth-file', 'input-file', 'node-root', 'api-compose', 'output'):
    p.add_argument('--' + name, type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False)
token = None


def save(name, value):
    (a.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))


def call(path, body=None, content_type='application/json'):
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    headers = {'Content-Type': content_type}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    with urllib.request.urlopen(urllib.request.Request(a.origin + path, data=data, headers=headers), timeout=30) as response:
        value = json.load(response)
    if value.get('code', 200) != 200:
        raise RuntimeError(value.get('message', 'Java rejected request'))
    return value.get('data', value)


def login():
    return call('/api/v1/enterprise/auth/login', json.loads(a.auth_file.read_text()))['token']


def registry(task):
    rows = [json.loads(f.read_text()) for f in (a.node_root / 'state/workers').glob('*/registry.json')]
    rows = [r for r in rows if r['contract']['taskId'] == task]
    if len(rows) != 1:
        raise RuntimeError('Expected exactly one Native worker for this task')
    return rows[0]


def skills(workspace):
    return {str(f.relative_to(workspace)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in workspace.glob('.agents/skills/runtime-agent-*/**/*') if f.is_file()}


started = time.monotonic()
try:
    token = login()
    boundary = 'recovery-' + uuid.uuid4().hex
    data = a.input_file.read_bytes()
    upload = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + a.input_file.name
              + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + data + ('\r\n--' + boundary + '--\r\n').encode()
    source = call('/api/v1/data-science/user-assets', upload, 'multipart/form-data; boundary=' + boundary)
    save('uploaded-input.json', source)
    objective = (
        'This is an explicit candidate control-plane recovery test using SYNTHETIC Parquet data. '
        'Consult the available Agent dataset-quality methodology and its reference. Autonomously write a Python program '
        'that creates logs/api-restart-ready as JSON containing its actual os.getpid() under pid and a fresh UUID under nonce, '
        'prints READY_FOR_API_RESTART with flush enabled, then sleeps for 25 seconds in that same foreground process. '
        'After the sleep, read the original Parquet and compute rows, total and mean of amount. Generate a concise Markdown '
        'report and a RESULT JSON containing only rows, total and mean at full Python precision. Choose meaningful source '
        'names. Execute your own program in the foreground; do not bypass the sleep, use background execution, fabricate the '
        'marker, rerun the checkpoint program, or produce final deliverables before it finishes. No Internet queries are needed. Inspect the actual results '
        'and deliver SOURCE, REPORT and RESULT through the normal delivery selection manifest.'
    )
    schema = {'type': 'object', 'properties': {k: {'type': t} for k, t in
              {'rows': 'integer', 'total': 'number', 'mean': 'number'}.items()},
              'required': ['rows', 'total', 'mean'], 'additionalProperties': False}
    task = call('/api/v1/coding/tasks', {'requestId': 'native-java-recovery-' + uuid.uuid4().hex[:12],
                'fileIds': [source['id']], 'instruction': objective, 'modelName': a.model, 'agentId': a.agent_id,
                'artifactTypes': ['SOURCE', 'REPORT', 'RESULT'], 'resultSchema': schema})
    save('accepted-task.json', task)
    route = '/api/v1/coding/tasks/runs/' + task['runId'] + '/' + task['taskId']
    deadline = time.time() + 600
    while time.time() < deadline:
        try:
            before = registry(task['taskId'])
            workspace = a.node_root / 'workers' / before['workerId'] / 'workspace'
            marker = workspace / 'logs/api-restart-ready'
            if marker.exists():
                try:
                    checkpoint=json.loads(marker.read_text())
                    if isinstance(checkpoint.get('pid'),int) and checkpoint.get('nonce'):break
                except (ValueError,OSError):pass
        except RuntimeError:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Cline did not reach the actual running-process checkpoint')
    marker_before = json.loads(marker.read_text())
    pid = marker_before['pid']
    assert isinstance(pid, int) and 1 < pid < 10000000 and marker_before['nonce']
    frozen = skills(workspace)
    assert any(path.endswith('/references/quality.md') for path in frozen)
    executor = json.loads((a.node_root / 'private/executor.json').read_text())
    docker = ['docker', '-H', 'unix://' + executor['dockerSocket']]

    def running_python():
        probe = subprocess.run(docker + ['exec', '--user', '10001:10001', before['containerId'], 'readlink', '/proc/' + str(pid) + '/exe'],
                               text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return probe.returncode == 0 and 'python' in probe.stdout.lower()

    assert running_python(), 'Checkpoint must correspond to a real live Python process'
    proof = {'taskId': task['taskId'], 'runId': task['runId'], 'workerIdBefore': before['workerId'],
             'nativeSessionBefore': before['nativeSessionId'], 'containerBefore': before['containerId'],
             'markerBefore': marker_before, 'skillHashesBefore': frozen, 'pythonRunningBefore': True}
    # Explicit operator security probes, not model calls. A valid signature
    # still cannot move a capability into a different tenant/user/session.
    gateway=json.loads((a.node_root/'private/gateway.json').read_text())
    key=gateway.get('issuerKeys',{}).get(before['contract']['tenantId'],gateway.get('signingKey'))
    def signed(contract):
        raw=base64.urlsafe_b64encode(json.dumps(contract,separators=(',',':'),ensure_ascii=False).encode()).decode().rstrip('=')
        return raw+'.'+base64.urlsafe_b64encode(hmac.new(key.encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    probes=[]
    for variant in ['original','tenant','user','session','expired']:
        contract=copy.deepcopy(before['contract'])
        if variant=='tenant':contract['tenantId']=contract['deliveryBinding']['tenantId']='denied-'+uuid.uuid4().hex
        if variant=='user':contract['userId']='denied-'+uuid.uuid4().hex
        if variant=='session':contract['sessionId']='denied-'+uuid.uuid4().hex
        if variant=='expired':contract['expiresAt']=int(time.time()*1000)-1
        request=urllib.request.Request(a.origin+'/internal/runtime/native-coding/mcp',data=json.dumps({'jsonrpc':'2.0','id':'scope-'+variant,'method':'tools/list'}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+signed(contract)})
        try:
            with urllib.request.urlopen(request,timeout=10) as response:status=response.status;value=json.load(response)
        except urllib.error.HTTPError as error:
            status=error.code;value=json.loads(error.read())
        probes.append({'variant':variant,'httpStatus':status,'hasMcpResult':'result' in value})
    save('live-task-scope-probes.json',{'source':'EXPLICIT_OPERATOR_PROBES_NOT_MODEL_CALLS','results':probes})
    assert probes[0]['httpStatus']==200 and probes[0]['hasMcpResult']
    assert all(v['httpStatus']>=400 and not v['hasMcpResult'] for v in probes[1:])
    proof['crossScopeAndExpiredCapabilitiesRejected']=True
    save('before-restart.json', proof)
    with (a.output / 'api-restart.log').open('wb') as log:
        subprocess.run(['docker', 'compose', '-p', a.api_project, '-f', str(a.api_compose), 'restart', 'monitor-api'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    proof['pythonRunningWhileJavaRestarts'] = running_python()
    try:
        urllib.request.urlopen(a.origin + '/monitor/', timeout=1)
        proof['monitorOfflineObserved'] = False
    except (OSError, urllib.error.URLError):
        proof['monitorOfflineObserved'] = True
    save('during-restart.json', proof)
    for _ in range(120):
        try:
            token = login()
            break
        except (OSError, urllib.error.URLError, RuntimeError):
            time.sleep(1)
    else:
        raise RuntimeError('Candidate API did not recover')
    save('resume-response.json', call(route + '/resume', {}))
    while time.time() < deadline:
        state = call(route)
        save('state.json', state)
        if state['status'] not in ['CREATED', 'RUNNING']:
            break
        time.sleep(1)
    assert state['status'] == 'COMPLETED', state.get('summary')
    after = registry(task['taskId'])
    proof['checks'] = {'pythonContinuedWhileJavaRestarted': proof['pythonRunningWhileJavaRestarts'],
        'monitorWasUnavailable': proof['monitorOfflineObserved'],
        'sameWorker': before['workerId'] == after['workerId'],
        'sameNativeSession': before['nativeSessionId'] == after['nativeSessionId'],
        'sameContainer': before['containerId'] == after['containerId'],
        'noDuplicateProgramStart': marker_before == json.loads(marker.read_text()),
        'skillsRetainedExactly': frozen == skills(workspace), 'deliveryCompleted': True,'liveCrossScopeAndExpiredDenials':proof['crossScopeAndExpiredCapabilitiesRejected']}
    monitor = '/api/v1/coding-monitor/tasks/' + task['runId'] + '/' + task['taskId']
    detail = call(monitor)
    save('java-task-monitor.json', detail)
    files = call(monitor + '/artifacts')
    save('registered-silo-files.json', files)
    values = []
    for file in files['files']:
        if file['kind'] != 'ARTIFACT' or not file['fileName'].endswith('.json'):
            continue
        req = urllib.request.Request(a.origin + monitor + '/artifacts/' + file['id'] + '/content?download=true',
                                     headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(req, timeout=30) as response:
            content = response.read()
        assert hashlib.sha256(content).hexdigest() == file['sha256']
        value = json.loads(content)
        if isinstance(value, dict) and 'rows' in value:
            values.append(value)
    proof['checks']['independentStatistics'] = any(v['rows'] == 3 and abs(v['total'] - 47.5) < 1e-9
        and abs(v['mean'] - 47.5 / 3) < 1e-9 for v in values)
    proof['passed'] = all(proof['checks'].values())
    proof['elapsedSeconds'] = time.monotonic() - started
    save('verification.json', proof)
    print(json.dumps({'passed': proof['passed'], 'checks': proof['checks'], 'taskId': task['taskId']}), flush=True)
    assert proof['passed']
except Exception as error:
    save('failure.json', {'type': type(error).__name__, 'message': str(error), 'elapsedSeconds': time.monotonic() - started})
    raise
