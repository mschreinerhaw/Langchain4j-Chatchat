"""Opt-in live Worker fault for one synthetic task; never resubmit an unknown execution.

Requires the existing read-only collect-coding-progress.py next to this script.
Run only on the explicitly authorized validation host with no other active tasks.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--fixtures', type=Path, required=True)
    p.add_argument('--auth', type=Path, required=True)
    p.add_argument('--model', required=True)
    p.add_argument('--inject-worker-kill', action='store_true', required=True)
    p.add_argument('--reconcile', action='store_true', help='Read and reconcile existing fault; never dispatch again')
    p.add_argument('--window', choices=['success','precode'], default='success')
    a = p.parse_args()
    os.umask(0o077)
    a.root.mkdir(parents=True, exist_ok=True)
    if (a.root / 'intent.json').exists() and not a.reconcile:
        raise RuntimeError('Existing intent requires reconciliation; do not replay')
    worker = 'runtime-os-app-stage-coding-agent-1'
    def shell(*args):
        return subprocess.check_output(args, text=True).strip()
    def ready():
        code = "import fs from 'node:fs';const s=fs.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim();const r=await fetch('http://127.0.0.1:3100/readyz',{headers:{Authorization:'Bearer '+s}});if(!r.ok)throw Error('Not ready');console.log(JSON.stringify(await r.json()));"
        return json.loads(shell('docker', 'exec', worker, 'node', '--input-type=module', '-e', code))
    def save(name, value):
        (a.root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
    if not a.reconcile:
        assert ready()['activeSessions'] == 0, 'Other Coding sessions active'
    parents = shell('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
                    "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    assert parents == '0', 'Other parent tasks active'
    token = json.loads(a.auth.read_text())['token']
    def api(path, body=None, timeout=60):
        req = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization': 'Bearer ' + token, 'X-Workspace-Id': 'data-exploration', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            value = json.load(r)
        assert value['code'] == 200, 'API rejected request'
        return value['data']
    if a.reconcile:
        prepared = json.loads((a.root / 'prepared.json').read_text())
        state = {'id':prepared['workspaceId']}
        base = '/data-science/explorations/' + state['id']
        node_id = prepared['nodeId']
    else:
        dispatch(a, api, save)
        prepared = json.loads((a.root / 'prepared.json').read_text())
        state = {'id':prepared['workspaceId']}
        base = '/data-science/explorations/' + state['id']
        node_id = prepared['nodeId']
    spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collect-coding-progress.py'))
    collector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(collector)
    killed = (a.root / 'fault-intent.json').exists()
    before = json.loads((a.root / 'before-fault-public.json').read_text()) if killed else None
    start = time.monotonic()
    last_print = 0
    while time.monotonic() - start < 1450:
        state = api(base + '/nodes/' + node_id + '/status')
        receipt = next(n for n in state['nodes'] if n['id'] == node_id).get('receipt', {})
        task_id = receipt.get('taskId')
        monitor = json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
        nodes = sorted([n for n in monitor['tasks'] if n['taskId'] == task_id], key=lambda n:n['fencingToken'])
        if nodes:
            task, _ = collector.journal(nodes[-1])
            save('latest-journal-private.json', task)
            codes = {k:v for k,v in task['operations'].items() if v.get('kind') == 'CODE' and v.get('stopProofRef')
                and task.get('privateState', {}).get('feedback:' + k, {}).get('status') == 'SUCCEEDED'}
            sdk = task.get('sdkCheckpoint') or {}
            pending_models = [k for k,v in task['operations'].items() if v.get('kind') == 'MODEL' and v.get('startIntent') and not v.get('stopProofRef')]
            fault_boundary = bool(codes and sdk.get('lastCollectedOperation') in codes) if a.window=='success' else not any(v.get('kind')=='CODE' for v in task['operations'].values())
            if not killed and fault_boundary and pending_models and sdk.get('sdkSessionId') and not receipt.get('terminal'):
                # Scope isolation and stopped Python are rechecked immediately before fault.
                assert ready()['activeSessions'] == 1, 'Cannot isolate validation task'
                assert not shell('docker', 'ps', '-q', '--filter', 'name=chatchat-py-'), 'Python still running'
                current = json.loads(shell('docker', 'inspect', worker))[0]
                before = {'containerId': current['Id'], 'imageId': current['Image'],
                    'taskId': task_id, 'sdkSessionId': sdk['sdkSessionId'], 'fencingToken': task['lease']['fencingToken'],
                    'budget': task['contract']['budget'], 'usage': task['outcome']['usage'],
                    'restartPolicy':current['HostConfig']['RestartPolicy'],
                    'pendingModelOperations':pending_models,
                    'checkpointOrdinal':sdk['checkpointOrdinal'],'modelOrdinal':sdk['modelOrdinal'],
                    'checkpointSha256':hashlib.sha256(json.dumps(sdk,sort_keys=True).encode()).hexdigest(),
                    'codeVersions':sdk.get('codeLifecycle',{}).get('versions',[]),'window':a.window,
                    'successfulPython': {k:hashlib.sha256(json.dumps(v,sort_keys=True).encode()).hexdigest() for k,v in codes.items()}}
                save('before-fault-public.json', before)
                save('before-fault-journal-private.json', task)
                shell('docker', 'kill', '--signal', 'KILL', worker)
                killed = True
                save('fault-intent.json', {'signal': 'KILL', 'containerId': current['Id'], 'atEpochSeconds': time.time()})
                shell('docker', 'start', worker)
                save('restart-method.json', {'method':'EXPLICIT_START_SAME_CONTAINER_AFTER_DOCKER_KILL','policyUnchanged':True})
                for _ in range(60):
                    try:
                        ready()
                        break
                    except Exception:
                        time.sleep(1)
                else:
                    raise RuntimeError('Worker did not recover under original restart policy')
                print('WORKER_RESTARTED_SAME_CONTAINER', flush=True)
            if receipt.get('terminal'):
                current = json.loads(shell('docker', 'inspect', worker))[0]
                unchanged = bool(before) and all(k in task['operations'] and hashlib.sha256(json.dumps(task['operations'][k],sort_keys=True).encode()).hexdigest() == h for k,h in before['successfulPython'].items())
                same_session = bool(before) and sdk.get('sdkSessionId') == before['sdkSessionId']
                same_budget = bool(before) and task['contract']['budget'] == before['budget']
                original = json.loads((a.root / 'before-fault-journal-private.json').read_text()) if before else {}
                intents = {original['operations'][k]['intentSha256'] for k in before['successfulPython']} if before else set()
                matching_code_ids = {k for k,v in task['operations'].items() if v.get('kind') == 'CODE' and v.get('intentSha256') in intents}
                no_duplicate_python = bool(before) and matching_code_ids == set(before['successfulPython'])
                higher_fence = bool(before) and task['lease']['fencingToken'] > before['fencingToken']
                fence_preserved = bool(before) and task['lease']['fencingToken'] >= before['fencingToken']
                restored = nodes[-1].get('recoveryMode') == 'CHECKPOINT_RESTORED_NEW_SDK'
                sdk_restore_events=[e for e in task['events'] if e.get('payload',{}).get('stage')=='SDK_CHECKPOINT_RESTORED']
                checkpoint_advanced=bool(before) and sdk.get('checkpointOrdinal',0)>before.get('checkpointOrdinal',0)
                version_chain=bool(before) and sdk.get('codeLifecycle',{}).get('versions',[])[:len(before.get('codeVersions',[]))]==before.get('codeVersions',[])
                old_models={k:v for k,v in original.get('operations',{}).items() if v.get('kind')=='MODEL'}
                model_identity=bool(before) and all(all(task['operations'][k].get(f)==v.get(f) for f in ['intentSha256','startingFencingToken','startSequence','executionHandle']) for k,v in old_models.items())
                # Missing Node process can restore under the still-valid Java owner lease.
                # ClineCodingAdapter advances fencing for expired leases or ended live SDKs.
                passed = killed and unchanged and no_duplicate_python and same_session and same_budget and higher_fence and restored and bool(sdk_restore_events) and checkpoint_advanced and version_chain and model_identity and receipt.get('status') == 'COMPLETED'
                save('recovery-reconciled-public.json' if a.reconcile else 'recovery-public.json', {'status':'PASS' if passed else 'FAIL', 'taskId':task_id,
                    'executionStatus':receipt.get('status'), 'faultInjected':killed, 'executeRequests':1,
                    'successfulPythonOperationsUnchanged':unchanged, 'sameSdkSession':same_session,
                    'successfulPythonNotRedispatchedUnderAnotherOperation':no_duplicate_python,
                    'budgetAndDeadlineUnchanged':same_budget, 'fencingAdvanced':higher_fence,
                    'fencingNotRegressed':fence_preserved,'checkpointRestored':restored,
                    'sdkRestoreEventSequences':[e['sequence'] for e in sdk_restore_events],
                    'checkpointAdvanced':checkpoint_advanced,'originalCodeVersionChainPreserved':version_chain,'modelOperationIdentityPreserved':model_identity,
                    'beforeCheckpointSha256':before.get('checkpointSha256') if before else None,
                    'afterCheckpointSha256':hashlib.sha256(json.dumps(sdk,sort_keys=True).encode()).hexdigest(),
                    'afterCheckpointOrdinal':sdk.get('checkpointOrdinal'),'afterModelOrdinal':sdk.get('modelOrdinal'),
                    'afterCodeVersions':sdk.get('codeLifecycle',{}).get('versions',[]),
                    'sameContainerAndImage':bool(before) and current['Id']==before['containerId'] and current['Image']==before['imageId'],
                    'afterUsage':task['outcome']['usage'], 'reasonCode':task['outcome'].get('reasonCode'),
                    'workspaceId':state['id'], 'nodeId':node_id})
                print(json.dumps({'status':'PASS' if passed else 'FAIL','executionStatus':receipt.get('status'),'faultInjected':killed}), flush=True)
                return
        if time.monotonic() - last_print > 30:
            print(json.dumps({'elapsedSeconds':round(time.monotonic()-start),'taskId':task_id,'status':receipt.get('status'),'faultInjected':killed}), flush=True)
            last_print = time.monotonic()
        time.sleep(2)
    save('recovery-public.json', {'status':'FAIL','reason':'VALIDATION_WINDOW_EXCEEDED','faultInjected':killed,'executeRequests':1})


def dispatch(a, api, save):
    save('intent.json', {'model':a.model,'syntheticOnly':True,'plannedFault':'SIGKILL_AFTER_STOP_PROVEN_PYTHON'})
    state = api('/data-science/explorations', {'title':'Synthetic Worker recovery acceptance'})
    base = '/data-science/explorations/' + state['id']
    state = api(base + '/nodes', {'version':state['version'],'parentId':'',
        'fileIds':[json.loads(a.fixtures.read_text())['simple.csv']['id']],'modelName':a.model,
        'question':'合成数据验收。生产者声明：region 是地区类别，amount 是可相加的演示金额单位，不代表真实交易或时间序列。仅分析本次授权 simple.csv，按地区汇总 amount 并核对总和，交付表格和地区比较图。执行后复核图表与表格一致性，不联网不安装依赖。'})
    node_id = state['nodes'][-1]['id']
    save('prepared.json', {'workspaceId':state['id'],'nodeId':node_id,'model':a.model})
    try:
        api(base + '/nodes/' + node_id + '/execute', {}, timeout=2)
        save('disconnect.json', {'responseReturnedWithin2Seconds':True})
    except Exception as e:
        save('disconnect.json', {'status':'PENDING_RECONCILIATION','errorType':type(e).__name__,'executeRequests':1})


if __name__ == '__main__':
    main()
