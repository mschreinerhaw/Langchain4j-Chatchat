"""Cancel an already dispatched synthetic exploration task; no submit or retry path."""
import argparse
import json
from pathlib import Path
import time
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--auth', type=Path, required=True)
    a = p.parse_args()
    prepared = json.loads((a.root / 'prepared.json').read_text())
    token = json.loads(a.auth.read_text())['token']
    assert len(prepared['cases']) == 1
    node_id = prepared['cases'][0]['nodeId']
    base = '/data-science/explorations/' + prepared['workspaceId'] + '/nodes/' + node_id
    def api(suffix, body=None):
        req = urllib.request.Request('http://127.0.0.1:18180/api/v1' + base + suffix,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization':'Bearer ' + token,'X-Workspace-Id':'data-exploration','Content-Type':'application/json'})
        with urllib.request.urlopen(req, timeout=60) as r:
            value = json.load(r)
        assert value['code'] == 200
        return next(n for n in value['data']['nodes'] if n['id'] == node_id)['receipt']
    before = api('/status')
    assert before.get('taskId') and not before.get('terminal'), 'Requires accepted active task'
    intent = a.root / 'stop-intent.json'
    assert not intent.exists(), 'Stop intent already exists; reconcile status'
    intent.write_text(json.dumps({'taskId':before['taskId'],'atEpochSeconds':time.time()}))
    api('/stop', {})
    observations = []
    for _ in range(90):
        value = api('/status')
        observations.append({k:value.get(k) for k in ['taskId','status','terminal','eventCursor','resultAvailable']})
        if value.get('terminal'):
            break
        time.sleep(2)
    first = value
    # A late model response must not move a cancelled task back to RUNNING.
    time.sleep(10)
    after = api('/status')
    passed = first.get('status') == after.get('status') == 'CANCELLED' and first.get('taskId') == after.get('taskId') == before['taskId']
    result = {'status':'PASS' if passed else 'FAIL','workspaceId':prepared['workspaceId'],'nodeId':node_id,
        'taskId':before['taskId'],'beforeStatus':before.get('status'),'finalStatus':after.get('status'),
        'terminal':after.get('terminal'),'observations':observations,
        'sameArchivedReportAfterLateResponse':first.get('visualReport') == after.get('visualReport')}
    (a.root / 'cancellation-public.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'status':result['status'],'finalStatus':result['finalStatus']}),flush=True)


if __name__ == '__main__':
    main()
