"""Use the existing Worker fault verifier with the unchanged REAL frozen input.

All fault isolation, native checkpoint/operation checks and stop proof handling
remain in verify-native-worker-recovery.py. This changes test submission only;
it never supplies analysis code, a business tool plan, model settings or budgets.
Pass the existing acceptance-frozen-inputs-private.json through --fixtures.
"""
import hashlib
import importlib.util
import json
from pathlib import Path


def dispatch_real(args, api, save):
    frozen = json.loads(args.fixtures.read_text(encoding='utf-8'))
    files = frozen['inputFiles']
    question = frozen['question']
    assert len(files) == 4 and question.strip()
    assert any(item['sha256'] == '32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4'
               for item in files), 'Use the original real day snapshot'
    assert all(len(item['assetId']) == 64 for item in files)
    save('intent.json', {'model': args.model, 'syntheticOnly': False,
        'questionSha256': hashlib.sha256(question.encode()).hexdigest(),
        'fileSha256': [item['sha256'] for item in files],
        'plannedFault': 'ISOLATED_WORKER_SIGKILL_AT_EXISTING_CHECKPOINT_BOUNDARY'})
    state = api('/data-science/explorations', {'title': 'Phase 2 真实行情 Worker 恢复验证'})
    base = '/data-science/explorations/' + state['id']
    state = api(base + '/nodes', {'version': state['version'], 'parentId': '',
        'fileIds': [item['assetId'] for item in files], 'modelName': args.model, 'question': question})
    node_id = state['nodes'][-1]['id']
    save('prepared.json', {'workspaceId': state['id'], 'nodeId': node_id, 'model': args.model})
    try:
        api(base + '/nodes/' + node_id + '/execute', {}, timeout=2)
        save('disconnect.json', {'responseReturnedWithin2Seconds': True, 'executeRequests': 1})
    except Exception as error:
        save('disconnect.json', {'status': 'PENDING_RECONCILIATION',
            'errorType': type(error).__name__, 'executeRequests': 1})


if __name__ == '__main__':
    spec = importlib.util.spec_from_file_location('native_recovery',
        Path(__file__).with_name('verify-native-worker-recovery.py'))
    existing = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(existing)
    existing.dispatch = dispatch_real
    existing.main()
