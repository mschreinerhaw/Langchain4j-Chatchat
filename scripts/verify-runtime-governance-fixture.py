"""Submit one real, authorized task with a narrowed staging workflow, then restore its Agent.
No tool grant is added. Configuration is restored after the task freezes its model/workflow.
Private backups and conflict fingerprints make an interrupted fixture recoverable.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid

FIELDS = ('id name description usageScenarios skillTags defaultMode modelName systemPrompt '
          'firstUseGreeting preferredToolPrefixes boundMcpServiceIds boundMcpToolNames boundDocumentIds '
          'boundDomainSkillIds boundDocumentTags toolConfigs routingSettings workflowConfig defaultDataAsset '
          'assetSelectionPolicy quickQuestions marketStatus defaultAgent').split()


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-file', type=Path, required=True)
    parser.add_argument('--request-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--required-tools', nargs='+', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def call(path, body=None, method=None):
        request = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer '+token} if token else {})})
        with client.open(request, timeout=60) as response:
            envelope = json.load(response)
        if envelope.get('code') not in (None, 0, 200, 202):
            raise RuntimeError('Official API rejected fixture operation: ' + path)
        return envelope['data']

    auth = call('/enterprise/auth/login', json.loads(args.auth_file.read_text()))
    token = auth['token']
    endpoint = '/agents/workshop/' + urllib.parse.quote(args.agent)
    before = call(endpoint)
    original = {key: before.get(key) for key in FIELDS}
    (args.output/'agent-before.private.json').write_text(json.dumps(original, ensure_ascii=False, indent=2))
    declared = set(original.get('boundMcpToolNames') or [])
    assert set(args.required_tools).issubset(declared), 'Fixture cannot grant a new tool'
    fixture = copy.deepcopy(original)
    fixture['modelName'] = args.model
    config = fixture['workflowConfig']
    workflow = config.get('mcpWorkflow', config)
    assert isinstance(workflow, dict) and isinstance(workflow.get('steps'), list)
    workflow['steps'] = [step for step in workflow['steps'] if step.get('tool') in args.required_tools]
    assert {step['tool'] for step in workflow['steps']} == set(args.required_tools)
    for step in workflow['steps']:
        if isinstance(step.get('dependsOn'), list):
            step['dependsOn'] = [tool for tool in step['dependsOn'] if tool in args.required_tools]
    if isinstance(workflow.get('toolDependencies'), dict):
        workflow['toolDependencies'] = {tool: {**entry, 'dependsOn':
            [parent for parent in entry.get('dependsOn', []) if parent in args.required_tools]}
            for tool, entry in workflow['toolDependencies'].items() if tool in args.required_tools}
    # Bound tools, resource scopes and Agent identity remain exactly the same.
    unchanged = [key for key in FIELDS if key not in {'modelName', 'workflowConfig'}]
    assert all(original[key] == fixture[key] for key in unchanged)
    report = {'agentId': args.agent, 'originalSha256': fingerprint(original),
              'fixtureSha256': fingerprint(fixture), 'permissionsUnchanged': True,
              'requiredTools': args.required_tools, 'selectedModel': args.model, 'restored': False}
    updated = False
    frozen = False
    try:
        changed = call(endpoint, fixture, 'PUT')
        updated = True
        effective = {key: changed.get(key) for key in FIELDS}
        report['effectiveFixtureSha256'] = fingerprint(effective)
        request = json.loads(args.request_file.read_text())['request']
        assert not request.get('attachmentIds'), 'Business supply fixture cannot become file-only analysis'
        request.update(agentId=args.agent, skillId=args.agent, mode='agent_chat', modelName=args.model,
            sessionId=str(uuid.uuid4()), idempotencyKey=str(uuid.uuid4()), resumeTaskId=None)
        task = call('/agent/tasks', request)
        report['taskId'] = task['taskId']
        (args.output/'submission.private.json').write_text(json.dumps({'request': request, 'response': task}, ensure_ascii=False))
        (args.output/'fixture-control.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({'stage': 'SUBMITTED', 'taskId': task['taskId']}), flush=True)
        for _ in range(60):
            runs = call('/agent/runtime/runs?conversationId=' + urllib.parse.quote(request['sessionId']) + '&limit=20')
            for run in runs:
                run_id = run.get('runId') or run.get('id')
                if not run_id: continue
                detail = call('/agent/runtime/runs/' + run_id)
                captured = detail.get('request') or {}
                if captured.get('modelName') == args.model and set(captured.get('requiredToolNames') or []) == set(args.required_tools):
                    report['runId'] = run_id
                    report['taskConfigurationFrozen'] = True
                    frozen = True
                    break
            if frozen: break
            time.sleep(1)
        if not frozen: raise RuntimeError('Task configuration was not proven frozen; inspect the recorded task before retrying')
    finally:
        if updated:
            current = call(endpoint)
            current = {key: current.get(key) for key in FIELDS}
            if fingerprint(current) != report['effectiveFixtureSha256']:
                raise RuntimeError('Agent changed concurrently; retained backup requires explicit reconciliation')
            restored = call(endpoint, original, 'PUT')
            restored = {key: restored.get(key) for key in FIELDS}
            report['restoredSha256'] = fingerprint(restored)
            report['restored'] = restored == original
        (args.output/'fixture-control.json').write_text(json.dumps(report, indent=2))
    assert frozen and report['restored'], 'Fixture restoration or frozen-task check failed'
    print(json.dumps({'stage': 'RESTORED', **report}), flush=True)


if __name__ == '__main__':
    main()
