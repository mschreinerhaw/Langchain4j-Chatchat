"""Probe live revocation using a dedicated acceptance task whose result queue was retained.

Run only after other acceptance tasks finish. Agent settings and a temporary user DENY
are restored in finally blocks. Credentials and source contents stay in private release files.
"""
import argparse
import copy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--admin-credentials-file', type=Path, required=True)
    parser.add_argument('--case-id', default='RQ01')
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir
    fixture = json.loads((root / 'fixture.private.json').read_text())
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, method=None, token=None):
        assert path.startswith('/') and not path.startswith('//')
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        try:
            with client.open(urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
                data=None if body is None else json.dumps(body).encode(), method=method, headers=headers), timeout=45) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, None

    def data(path, body=None, method=None, token=None):
        status, value = request(path, body, method, token)
        assert status == 200 and value.get('code') == 200, (path.split('?')[0], status)
        return value.get('data')

    admin = data('/enterprise/auth/login', json.loads(args.admin_credentials_file.read_text()))['token']
    auth = data('/enterprise/auth/login', {key: fixture[key] for key in ['username', 'password']})
    token = auth['token']
    measured = json.loads((root / 'acceptance' / (args.case_id + '.json')).read_text())
    private = json.loads((root / 'acceptance' / (args.case_id + '.private.json')).read_text())
    assert measured['resultQueueRetained'] and measured['status'] in ['SUCCEEDED', 'PARTIAL']
    source = next(c for c in measured['citations'] if c['resourceId'] in ['financial_news', 'financial_market'])
    resource = source['resourceId']
    citation = '/agent/knowledge/citations/' + source['ref'].split(':')[-1]
    task = private['task']
    task_path = '/agent/tasks/' + task['taskId']
    tenant = '?tenantId=' + fixture['tenant']
    coding = '/agent/runtime/runs/' + task['attemptId'] + '/coding'
    agent_path = '/agents/workshop/' + fixture['agent']
    card = data(agent_path, token=token)
    fields = 'id name description usageScenarios skillTags defaultMode modelName systemPrompt firstUseGreeting preferredToolPrefixes boundMcpServiceIds boundMcpToolNames boundDocumentIds boundDomainSkillIds boundDocumentTags toolConfigs routingSettings workflowConfig defaultDataAsset assetSelectionPolicy quickQuestions marketStatus defaultAgent'.split()
    original = {key: value for key, value in card.items() if key in fields}
    (root / 'revocation-agent-original.private.json').write_text(json.dumps(original))
    checks = []
    revoked_result_request_rejected = False

    def check(name, path, actor, expected):
        nonlocal revoked_result_request_rejected
        status, value = request(path, token=actor)
        if name == 'agent-revoked-retained-result':
            if status == 200 and value and value.get('code') == 200 and value.get('data') is None:
                checks.append({'name': 'agent-revoked-result-empty', 'httpStatus': 200, 'passed': True})
                return
            revoked_result_request_rejected = status == 403
        if status != expected:
            (root / 'revocation-unexpected-response.private.json').write_text(json.dumps(value))
        assert status == expected, (name, status, expected, value.get('code') if value else None,
                                    value.get('data') is not None if value else False)
        checks.append({'name': name, 'httpStatus': status, 'passed': True})

    check('owner-current-source', citation, token, 200)
    check('other-user-source', citation, admin, 403)
    check('other-user-coding-monitor', coding, admin, 403)
    changed = copy.deepcopy(original)
    permissions = changed['workflowConfig']['resourceExploration']['resourcePermissions']
    next(row for row in permissions if row['resourceId'] == resource)['enabled'] = False
    try:
        data(agent_path, changed, 'PUT', token)
        check('agent-revoked-source', citation, token, 403)
        check('agent-revoked-task', task_path + tenant, token, 403)
        check('agent-revoked-events', task_path + '/events' + tenant, token, 403)
        check('agent-revoked-coding-monitor', coding, token, 403)
        # Skipping /result does not prove another consumer left the queue nonempty.
        # Neither 200/null nor 403 independently certifies retained-payload protection.
        check('agent-revoked-retained-result', task_path + '/result' + tenant + '&timeoutMs=0', token, 403)
    finally:
        data(agent_path, original, 'PUT', token)
    check('agent-restored-source', citation, token, 200)
    deny = {'tenantId': fixture['tenant'], 'resourceType': 'DATA_RESOURCE', 'resourceId': resource,
            'principalType': 'USER', 'principalId': auth['user']['id'], 'effect': 'DENY', 'enabled': True,
            'expiresAt': (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat()}
    grant = None
    try:
        grant = data('/enterprise/resource-grants', deny, 'POST', admin)
        (root / 'revocation-temporary-grant.private.json').write_text(json.dumps(grant))
        check('user-denied-source', citation, token, 403)
        check('user-denied-events', task_path + '/events' + tenant, token, 403)
        check('user-denied-coding-monitor', coding, token, 403)
    finally:
        if grant:
            data('/enterprise/resource-grants/' + grant['id'], method='DELETE', token=admin)
    check('user-restored-source', citation, token, 200)
    assert data(agent_path, token=token)['workflowConfig'] == original['workflowConfig']
    remaining = data('/enterprise/resource-grants?tenantId=' + fixture['tenant'] + '&resourceType=DATA_RESOURCE', token=admin)
    assert all(row['id'] != grant['id'] for row in remaining)
    report = {'checks': checks, 'retainedPublishedResultTested': False,
              'resultQueueRevocationCoverage': 'REQUEST_REJECTED_NONEMPTY_QUEUE_NOT_ESTABLISHED' if revoked_result_request_rejected else 'EMPTY_QUEUE_ONLY_DESPITE_RETAIN_OPTION',
              'agentSettingsRestored': True,
              'temporaryDenyDeleted': True, 'syntheticSources': False}
    (root / 'resource-security-summary.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
