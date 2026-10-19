"""Verify real monitor history survives a restart, only when no parent tasks are active."""
import hashlib
import json
import pathlib
import subprocess
import time
import urllib.request

ROOT = pathlib.Path('/opt/runtime-os-docker-stage')
WORKER = 'runtime-os-app-stage-coding-agent-1'
CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TOKEN = (ROOT / 'config/coding-node-monitor.token').read_text().strip()


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)


def get(path):
    req = urllib.request.Request('http://127.0.0.1:18100/v1/monitor' + path, headers={'Authorization': 'Bearer ' + TOKEN})
    with CLIENT.open(req, timeout=10) as response:
        return json.load(response)


def evidence():
    tasks = get('/tasks?limit=100')['items']
    result = {}
    for task in tasks:
        detail = get('/tasks/' + task['id'])['task']
        codes = []
        for code in detail['codes']:
            if code['sourceRetained']:
                body = get('/tasks/' + task['id'] + '/codes/' + code['id'])
                if hashlib.sha256(body['source'].encode()).hexdigest() != code['sha256']:
                    raise RuntimeError('Retained source hash mismatch')
            codes.append({'id': code['id'], 'sha256': code['sha256'], 'sourceRetained': code['sourceRetained']})
        result[task['id']] = {'status': detail['status'], 'events': detail['eventCount'],
            'models': detail['models'], 'skills': detail['skills'], 'codes': codes}
    return result


def main():
    count = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "SELECT count(*) FROM agent_task_latest WHERE canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED') OR canonical_state IS NULL").strip()
    if count != '0' or get('/node')['sessions']['active'] != 0:
        raise RuntimeError('Restart verification requires no active parent or SDK task')
    before = evidence()
    if not before or not any(task['codes'] for task in before.values()):
        raise RuntimeError('Real code history must exist before verification')
    old_instance = get('/node')['instanceId']
    unrelated = run('docker', 'ps', '--format', '{{.Names}} {{.ID}}').splitlines()
    run('docker', 'restart', '--time', '15', WORKER)
    for _ in range(45):
        try:
            node = get('/node')
            if node['instanceId'] != old_instance:
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Worker did not recover')
    run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
    after = evidence()
    if before != after:
        raise RuntimeError('Retained monitor history changed across restart')
    if run('docker', 'ps', '--format', '{{.Names}} {{.ID}}').splitlines() != unrelated:
        raise RuntimeError('Container identity changed unexpectedly')
    report = {'status': 'PASSED', 'tasksPreserved': len(after),
        'codeVersionsVerified': sum(len(task['codes']) for task in after.values()),
        'modelReceiptsPreserved': sum(len(task['models']) for task in after.values()),
        'instanceChanged': True, 'containerIdsUnchanged': True, 'persistence': node['history']['persistence']}
    (ROOT / 'evidence/coding-node-monitor-20261020/restart.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
