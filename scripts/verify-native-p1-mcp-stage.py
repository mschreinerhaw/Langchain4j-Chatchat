"""Real model + existing MCP server + Sandbox + archive acceptance, not a mock harness.

Uses the P0 independent holdout unchanged. Adds temporary, expiring user grants
only in the isolated acceptance database, then removes only the rules it created.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import subprocess
import time
import urllib.error
import urllib.request

os.umask(0o077)
ROOT = Path('/var/tmp/runtime-native-coding-p1-20261107')
BASE = Path('/var/tmp/runtime-native-coding-acceptance-20261107')
SERVER = 'chatchat-mcp-server'
CALCULATOR = 'mcp_chatchat_mcp_server_calculator'
SECOND = 'mcp_chatchat_mcp_server_server_capability_query'
TERMINAL = {'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'BUDGET_EXCEEDED'}


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    label = sys.argv[1]
    assert label.replace('-', '').isalnum()
    directory = ROOT / label
    directory.mkdir(exist_ok=False)
    h = load('p1_independent', BASE / 'runtime-generic-acceptance.py')
    projection = load('p1_projection', BASE / 'project-evidence-projection.py')
    token = json.loads((ROOT / 'api-auth-private.json').read_text())['token']

    def request(method, path, value=None):
        reply = h.call(method, path, token, value)
        return h.data(reply)

    for attempt in range(120):
        try:
            user = request('GET', '/api/v1/enterprise/auth/me')
            break
        except Exception:
            if attempt == 119:
                raise
            time.sleep(1)
    role = request('GET', '/api/v1/enterprise/roles/' + user['roleIds'][0] + '/authorization')
    api_image = json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-native-acceptance-api']))[0]['Image']
    deployments = [json.loads(path.read_text()) for path in ROOT.glob('mcp-deployment*-public.json')]
    deployed = [value for value in deployments if value.get('images', {}).get('api') == api_image and value.get('apiReady')]
    assert len(deployed) == 1, 'Acceptance requires one matching API-ready deployment proof'
    agent = (role.get('agentIds') or ['native-coding-p1'])[0]
    tools = request('GET', '/api/v1/mcp/tools')
    names = [item['localToolName'] for item in tools if item['serviceId'] == SERVER]
    assert CALCULATOR in names and SECOND in names
    rules = []
    task = None
    status = None
    try:
        # Explicit fixture scope: two authorized tools, every other registered tool denied.
        for name in names:
            saved = request('POST', '/api/v1/enterprise/resource-grants', {
                'tenantId': user['tenantId'], 'resourceType': 'MCP_TOOL', 'resourceId': name,
                'principalType': 'USER', 'principalId': user['id'], 'effect': 'ALLOW' if name in (CALCULATOR, SECOND) else 'DENY',
                'enabled': True, 'expiresAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 3600))})
            rules.append(saved['id'])
            write(directory / 'fixture-grants-private.json', {'createdIds': rules})
        fixture = {'id': 'T05', 'preserveFunctions': [],
            'files': {'tests/test_delivery.py': '''import json
from pathlib import Path
import unittest

class DeliveryTests(unittest.TestCase):
    def test_values(self):
        result = json.loads(Path('calculation.json').read_text())
        self.assertEqual(result, {'first': 33, 'second': 66})
    def test_loader(self):
        from solution import load_results
        self.assertEqual(load_results('calculation.json'), {'first': 33, 'second': 66})
    def test_report(self):
        report = Path('report.md').read_text()
        self.assertGreater(len(report), 100)
        self.assertIn('MCP', report)
        self.assertIn('33', report)
        self.assertIn('66', report)
''', 'README.md': 'Deliver solution.py, calculation.json and report.md. Keep tests unchanged.\n'},
            'readOnlyPaths': ['tests/test_delivery.py'],
            'acceptanceCommand': 'python -m unittest discover -s tests -v',
            'independentHoldout': "import solution,json\nfrom pathlib import Path\nassert solution.load_results('/workspace/code/calculation.json') == {'first':33,'second':66}\nassert Path('/workspace/code/report.md').stat().st_size > 100\n"}
        instruction = ('完成一个通用 Coding 任务。先用可用的 MCP 计算工具计算 sqrt(144)+7*3；读取真实返回结果后，'
            '再通过 MCP 将第一次的数值乘以 2。将实际结果写入 calculation.json，字段为 first 和 second。'
            '创建 solution.py，实现 load_results(path) 读取这个 JSON。生成超过 100 字的 report.md，说明 MCP 调用、表达式、'
            '真实结果和验证过程。保留所有现有测试，运行声明的验证命令，检查实际执行反馈后交付完整项目。'
            '请自主选择工具，不要用本地计算替代要求的 MCP 调用。')
        body = {key: fixture[key] for key in ('files', 'readOnlyPaths', 'acceptanceCommand')}
        body.update(instruction=instruction, modelName='qwen3.8-flash', editStrategy='auto', skills=[],
            mcpServers=[{'serviceId': SERVER, 'agentId': agent}])
        write(directory / 'request.json', body)
        request_id = 'p1-mcp-' + label
        task = 'native-' + h.sha((user['tenantId'] + '\0' + user['id'] + '\0' + request_id).encode())[:48]
        intent = {'case': 'T05', 'cohort': 'P1_RUNTIME_MCP', 'taskId': task, 'requestId': request_id,
            'sourceCommit': deployed[0]['sourceCommit'], 'harnessSha256': h.sha(Path(__file__).read_bytes()),
            'fixtureSha256': h.sha(json.dumps(fixture, sort_keys=True).encode()),
            'startedAtEpochMs': int(time.time() * 1000), 'originalBudget': {'maxModelCalls': 8, 'maxModelTokens': 262144, 'maxToolCalls': 16, 'maxCodeExecutions': 4, 'durationSeconds': 300}}
        write(directory / 'intent.json', intent)
        submitted = h.call('PUT', '/api/v1/coding/projects/' + request_id, token, body)
        write(directory / 'submit.json', submitted)
        h.data(submitted)
        end = time.monotonic() + 360
        while time.monotonic() < end:
            status = request('GET', '/api/v1/coding/projects/' + task)
            write(directory / 'last-status.json', status)
            if status['status'] in TERMINAL:
                break
            time.sleep(2)
        if status is None or status['status'] not in TERMINAL:
            raise RuntimeError('Task did not reach a stopped terminal state')
        for suffix in ('files', 'history', 'events'):
            write(directory / (suffix + '.json'), h.call('GET', '/api/v1/coding/projects/' + task + '/' + suffix, token))
        tree = h.data(json.loads((directory / 'files.json').read_text()))
        independent = h.independent(fixture, tree, directory, label)
        reread = request('GET', '/api/v1/coding/projects/' + task + '/files')
        after = request('GET', '/api/v1/coding/projects/' + task)
        state = projection.journal(projection.rows(task), task)
        write(directory / 'state-keys-public.json', {'keys': list(state), 'privateKeys': list(state.get('privateState', {}))})
        mcp = []
        for key, value in state.get('privateState', {}).items():
            if key.startswith('native-mcp:'):
                operation = key.removeprefix('native-mcp:')
                admitted = state['privateState']['native-tool:' + operation]
                mcp.append({'operationId': operation, 'tool': value['tool'], 'state': value['state'],
                    'input': json.loads(admitted['inputJson']), 'result': value.get('result'),
                    'modelOperationId': admitted['modelOperationId'], 'toolCallId': admitted['toolCallId']})
        records = projection.rows(task)
        disclosed = []
        for row in records:
            record = row['document']
            if row['kind'] == 'EVIDENCE' and record.get('evidenceType') == 'CODING_MODEL_RESPONSE_RECEIPT':
                response = record['payload']['response']
                disclosed.append(response.get('modelInfo', {}).get('disclosedTools', []))
        allowed = set((CALCULATOR, SECOND)) | {'read_files', 'search_codebase', 'editor', 'apply_patch', 'run_commands'}
        scope_ok = bool(disclosed) and all(set(names) <= allowed for names in disclosed)
        two_tools = any(CALCULATOR in names and SECOND in names for names in disclosed)
        proof = {**intent, 'endedAtEpochMs': int(time.time() * 1000), 'status': status['status'], 'usage': status['usage'],
            'executionControl': status['executionControl'], 'independent': independent,
            'archivedFilesReadAgain': reread == tree, 'taskUsageUnchangedByHoldout': after['usage'] == status['usage'],
            'finalFileHashes': {name: h.sha(content.encode()) for name, content in tree['files'].items()},
            'manifestHash': tree['manifest']['sha256'], 'mcpCalls': mcp, 'disclosedTools': disclosed,
            'unauthorizedToolsHidden': scope_ok, 'multipleAuthorizedToolsDiscovered': two_tools,
            'sdkSessionId': (state.get('sdkCheckpoint') or {}).get('sdkSessionId'), 'completed': False}
        proof['completed'] = (status['status'] == 'COMPLETED' and independent['passed'] and reread == tree
            and status['executionControl']['executorStopped'] and not status['executionControl']['reconciliationRequired']
            and scope_ok and two_tools and len(mcp) >= 2 and all(item['state'] == 'RETURNED' for item in mcp))
        write(directory / 'result-public.json', proof)
        projection.project(directory, token)
        print(json.dumps({key: proof[key] for key in ('taskId', 'status', 'completed', 'usage', 'unauthorizedToolsHidden', 'multipleAuthorizedToolsDiscovered')}), flush=True)
        if not proof['completed']:
            raise RuntimeError('Real MCP delivery acceptance did not pass')
    finally:
        if task and (status is None or status.get('status') not in TERMINAL):
            try:
                request('POST', '/api/v1/coding/projects/' + task + '/cancel')
            except Exception:
                pass
        removed = []
        for rule in rules:
            request('DELETE', '/api/v1/enterprise/resource-grants/' + rule)
            removed.append(rule)
        write(directory / 'fixture-cleanup-public.json', {'createdRuleCount': len(rules), 'removedRuleCount': len(removed)})


if __name__ == '__main__':
    main()
