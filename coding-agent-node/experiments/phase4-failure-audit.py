"""Read-only Phase 4 audit of the three frozen Phase 3 failures.

Run beside the existing acceptance helpers on the isolated host. Export hashes,
generic tool arguments and bounded diagnostics, never model prompts/free text.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import urllib.request

r = Path('/var/tmp/runtime-native-coding-acceptance-20261107')
spec = importlib.util.spec_from_file_location('projection', r / 'project-evidence-projection.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
h = p.h
token = json.loads((r / 'api-auth-private.json').read_text())['token']
out = r / 'phase4-failure-audit'
out.mkdir(exist_ok=False)


def download(asset):
    req = urllib.request.Request('http://127.0.0.1:18280/api/v1/interactions/user-assets/' + asset['id'] + '/content',
        headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(req, timeout=30) as response:
        content = response.read(1048577)
    assert h.sha(content) == asset['sha256'] and len(content) == asset['size']
    return json.loads(content)


def receipt_view(value):
    keys = ('manifestHash', 'operationId', 'commandHash', 'exitCode', 'resultAvailable', 'executorStopped',
        'stopProofRef', 'verificationPolicy', 'declaredCommandMatched', 'declaredCommandSha256', 'resultArtifactSha256')
    return {key: value.get(key) for key in keys}


results = []
for case in ['A', 'C', 'E-name']:
    d = r / ('runs/C-general-20261107-' + case + '-r4')
    task = json.loads((d / 'intent.json').read_text())['taskId']
    before = h.data(h.call('GET', '/api/v1/coding/projects/' + task, token))
    records = p.rows(task)
    state = p.journal(records, task)
    sources = h.data(h.call('GET', '/api/v1/interactions/user-assets?taskId=' + task + '&kind=ARTIFACT&personalOnly=false&limit=100', token))
    assets = {item['sourceRef']: item for item in sources}
    intents = {v['toolCallId']: v for key, v in state['privateState'].items() if key.startswith('native-tool:')}
    models = []
    for entry in records:
        record = entry['document']
        if record.get('evidenceType') != 'CODING_MODEL_RESPONSE_RECEIPT':
            continue
        value = record['payload']
        response = value['response']
        info = response['modelInfo']
        model = {'operationId': value['operationId'], 'startedAt': info['startedAt'], 'finishedAt': info['finishedAt'],
            'messageCount': info['messageCount'], 'requestBytes': info['requestBytes'], 'usage': response['usage'],
            'toolSchemaSha256': info['toolSchemaSha256'], 'finishReason': response['finishReason'], 'calls': []}
        for call in response.get('toolCalls', []):
            name, args = call['name'], call['input']
            view = {'toolCallId': call['id'], 'name': name, 'inputSha256': p.digest(args)}
            if name == 'run_commands':
                view['commands'] = args.get('commands')
            elif name == 'editor':
                view.update(path=args.get('path'), oldTextSha256=h.sha(args.get('old_text', '').encode()),
                    newTextSha256=h.sha(args.get('new_text', '').encode()))
            elif name in ['read_files', 'search_codebase']:
                view['arguments'] = args
            intent = intents.get(call['id'])
            view['durableAdmissionPresent'] = intent is not None
            if intent:
                view['startManifestHash'] = intent['workspaceHash']
                source = 'native-tool-result:' + intent['operationId']
                if source in assets:
                    raw = download(assets[source])
                    view.update(resultSha256=assets[source]['sha256'], isError=raw.get('isError'))
                    entries = raw.get('output')
                    view['entries'] = []
                    for output in entries if isinstance(entries, list) else []:
                        item = {'success': output.get('success')}
                        wire = output.get('result') or output.get('error') or ''
                        try:
                            decoded, _ = json.JSONDecoder().raw_decode(wire[wire.index('{'):])
                            if isinstance(decoded, dict) and 'operationId' in decoded:
                                item['execution'] = receipt_view(decoded)
                                text = decoded.get('stdout', '') + '\n' + decoded.get('stderr', '')
                                item['diagnosticLines'] = [line[:320] for line in text.splitlines() if re.match(
                                    r'^(?:FAIL:|ERROR:|AssertionError:|NameError:|TypeError:|FAILED |Ran \d+|OK$)', line)][:12]
                        except (ValueError, TypeError):
                            item['errorCategories'] = sorted(set(re.findall(
                                r'BUDGET_EXCEEDED|Native tool budget denied|Code did not change|EXECUTION_OUTCOME_UNKNOWN', str(output.get('error', '')))))
                        view['entries'].append(item)
            model['calls'].append(view)
        models.append(model)
    history = json.loads((d / 'history-probe-public.json').read_text())
    final = h.data(json.loads((d / 'files.json').read_text()))
    verification = state['privateState'].get('native-last-verification', {})
    execution = state['privateState'].get('native-last-execution', {})
    messages = state['sdkCheckpoint']['messages']
    memory = [block.get('text', '') for message in messages for block in message.get('content', [])
        if block.get('type') == 'text' and block.get('text', '').startswith('Latest Runtime execution facts,')]
    texts = json.dumps(messages, ensure_ascii=False)
    result = {'case': case, 'taskId': task, 'originalStatus': before['status'], 'usage': before['usage'],
        'executionControl': before['executionControl'], 'models': models,
        'versions': [{k: v[k] for k in ['version', 'manifestHash', 'elapsedToArchiveMs']} | {'independentPassed': v['independent']['passed']}
            for v in history['versions']],
        'finalManifest': final['manifest'], 'lastExecution': receipt_view(execution), 'lastVerification': receipt_view(verification),
        'lastVerificationMatchesFinal': verification.get('manifestHash') == final['manifest']['sha256'],
        'sdkCheckpoint': {k: state['sdkCheckpoint'].get(k) for k in ['sdkSessionId', 'compactionCount', 'checkpointOrdinal', 'contractHash']},
        'finalCheckpointContainsLastVerificationOperation': bool(verification) and verification.get('operationId', '') in texts,
        'finalRuntimeMemoryContainsVerification': any('lastVerification' in value or 'verificationStatus' in value for value in memory),
        'rawModelRequestBodiesAvailablePerRound': False,
        'requestEvidenceLimitation': 'Only latest SDK messages and bound per-model checkpoint ordinal/hash survive; original per-round request bodies are not retained. No reconstructed request is represented as original.',
        'taskUnchangedByAudit': before == h.data(h.call('GET', '/api/v1/coding/projects/' + task, token))}
    assert result['taskUnchangedByAudit']
    h.write(out / (case + '.json'), result)
    results.append(result)
h.write(out / 'summary.json', {'scope': 'READ_ONLY_ORIGINAL_MODEL_TOOL_SOURCE_AND_CHECKPOINT_AUDIT', 'baseline': 'ca0f7a47d', 'results': results})
print(json.dumps([{'case': v['case'], 'models': len(v['models']), 'currentVerification': v['lastVerificationMatchesFinal'],
    'verificationInFinalMessages': v['finalCheckpointContainsLastVerificationOperation']} for v in results]))
