"""Create a temporary query service from an existing authorized configuration, or recall/delete only that fixture."""
import argparse
import json
from pathlib import Path
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--auth', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--source', default='financial_indicator')
parser.add_argument('--cleanup', action='store_true')
args = parser.parse_args()
auth = json.loads(args.auth.read_text())
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(path, body=None, method=None):
    request = urllib.request.Request('http://127.0.0.1:18180/api/v1/agents/workshop' + path,
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + auth['token']})
    with client.open(request, timeout=90) as response:
        result = json.load(response)
    if result.get('code') not in (None, 0, 200):
        raise RuntimeError('Fixture API rejected operation: ' + str(result.get('message')))
    return result.get('data')

if args.cleanup:
    fixture = json.loads(args.output.read_text())
    assert fixture['agentId'].startswith('unified_acceptance_')
    call('/' + fixture['agentId'] + '/recall', {}, 'POST')
    call('/' + fixture['agentId'], method='DELETE')
    print(json.dumps({'status': 'CLEANED_UP', 'agentId': fixture['agentId']}))
else:
    source = call('/' + args.source)
    fields = ['name', 'description', 'usageScenarios', 'skillTags', 'defaultMode', 'modelName',
        'systemPrompt', 'firstUseGreeting', 'preferredToolPrefixes', 'boundMcpServiceIds',
        'boundMcpToolNames', 'boundDocumentIds', 'boundDomainSkillIds', 'boundDocumentTags',
        'toolConfigs', 'routingSettings', 'workflowConfig', 'defaultDataAsset', 'assetSelectionPolicy', 'quickQuestions']
    draft = {key: source[key] for key in fields if key in source}
    draft.update(id='unified_acceptance_' + uuid.uuid4().hex[:12], name='Unified runtime acceptance',
                 description='Synthetic temporary query service; same authorized resources as its source.',
                 marketStatus='draft', defaultAgent=False)
    created = call('', draft)
    # Persist identity before publishing so failures remain recoverable.
    evidence = {'agentId': created['id'], 'sourceAgentId': args.source, 'status': 'CREATED'}
    args.output.write_text(json.dumps(evidence, indent=2))
    published = call('/' + created['id'] + '/publish', {}, 'POST')
    evidence.update(status='PUBLISHED', marketStatus=published['marketStatus'])
    args.output.write_text(json.dumps(evidence, indent=2))
    print(json.dumps(evidence))
