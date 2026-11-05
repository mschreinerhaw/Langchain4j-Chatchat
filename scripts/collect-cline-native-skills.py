"""Read-only native Skills acceptance export; journals and report text stay private.

Run after collect-coding-progress.py: --root RELEASE/benchmark.
Only fixed scalar fields, Skill identities/hashes and synthetic case names leave the host.
"""
import argparse
import hashlib
import json
from pathlib import Path


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    args = parser.parse_args()
    root = args.root
    private = root / 'native-evidence-private'
    cases = []
    for request_file in sorted((root / 'native').glob('*-request.json')):
        case = request_file.name.removesuffix('-request.json')
        nodes_file = private / (case + '-nodes.json')
        if not nodes_file.exists():
            continue
        nodes = json.loads(nodes_file.read_text())
        journal = json.loads((private / (case + '-journal.json')).read_text())
        receipts = json.loads((private / (case + '-model-receipts.json')).read_text())
        native_calls = []
        for receipt in receipts:
            payload = receipt['payload']
            for call in payload['response'].get('toolCalls', []):
                if call.get('name') not in ['skills', 'read_files']:
                    continue
                arguments = call.get('arguments', call.get('input', {}))
                if isinstance(arguments, str):
                    try:
                        arguments = json.loads(arguments)
                    except ValueError:
                        arguments = {}
                arguments = arguments if isinstance(arguments, dict) else {}
                entry = {'operationId': payload['operationId'], 'tool': call['name']}
                if call['name'] == 'skills':
                    selected = arguments.get('skill')
                    entry['selectedSkill'] = selected if isinstance(selected, str) else None
                else:
                    items = arguments.get('files', [])
                    paths = [f['path'] for f in items if isinstance(f, dict) and isinstance(f.get('path'), str)] \
                        if isinstance(items, list) else []
                    entry['paths'] = [{'sha256': digest(p), 'scope':
                        'sandbox-data' if p.startswith('/data/input/') else
                        'task-skill' if p.startswith('/tmp/runtime-cline-authorized-skills/') else
                        'other'} for p in paths]
                native_calls.append(entry)
        events = [e for n in nodes for e in n['events']]
        model_keys = ['ordinal', 'status', 'startedAt', 'finishedAt', 'durationMs', 'purpose', 'actualTools',
                      'finishReason', 'providerFinishReason', 'diagnosticCode', 'usage',
                      'compactionCount', 'contextSizeBefore', 'contextSizeAfter', 'contextChangeReason']
        models = {m['ordinal']: {k: m[k] for k in model_keys if k in m}
                  for n in nodes for m in n['models']}
        discovery_keys = ['type', 'timestamp', 'nativeSkills', 'discovery', 'loader', 'nativeSkillCount']
        load_keys = ['type', 'timestamp', 'nativeSkills', 'skillId', 'skillVersion', 'skillPath',
                     'skillContentHash', 'skillReference', 'skillSourceBytes', 'skillContentBytes',
                     'skillEstimatedTokens', 'duplicateRead', 'contentReused']
        diagnostic_keys = ['type', 'timestamp', 'tool', 'status', 'diagnosticCode', 'resultAvailable']
        diagnostics = [{k: e[k] for k in diagnostic_keys if k in e} for e in events
            if e['type'] in ['CODE_PROPOSAL_REJECTED', 'MODEL_FAILED', 'MODEL_RESPONSE_REJECTED']
            or (e['type'] == 'TOOL_FINISHED' and e.get('tool') == 'python_execute')]
        python_feedback = []
        for key, value in journal.get('privateState', {}).items():
            if key.startswith('feedback:') and isinstance(value, dict):
                error = value.get('error') or {}
                python_feedback.append({'operationId': key[len('feedback:'):], 'status': value.get('status'),
                    'exceptionType': error.get('exceptionType'), 'diagnosticCode': error.get('diagnosticCode')})
        receipt_file = root / 'native' / (case + '-receipt.json')
        response = json.loads(receipt_file.read_text())['receipt'] if receipt_file.exists() else {}
        reports = []
        for result in response.get('results') or []:
            text = result.get('reportMarkdown', '')
            reports.append({'sha256': digest(text), 'bytes': len(text.encode()),
                'analysisStatus': result.get('analysisStatus'),
                'fieldProvenanceStatuses': sorted({p.get('status', 'UNKNOWN')
                    for p in result.get('fieldProvenance', [])})})
        cases.append({'case': case, 'taskId': nodes[-1]['taskId'], 'nodeStatus': nodes[-1]['status'],
            'eventsTruncated': any(n.get('eventsTruncated', False) for n in nodes),
            'discovery': [{k: e[k] for k in discovery_keys if k in e} for e in events
                if e['type'] == 'SKILLS_DISCOVERED' and e.get('nativeSkills') is True],
            'successfulLoads': [{k: e[k] for k in load_keys if k in e} for e in events
                if e['type'] == 'SKILLS_LOADED'],
            'nativeCalls': native_calls, 'modelTimeline': [models[k] for k in sorted(models)],
            'sdkCompactionCount': journal.get('sdkCheckpoint', {}).get('compactionCount'),
            'diagnostics': diagnostics, 'pythonFeedback': python_feedback, 'reports': reports})
    out = {'scope': 'synthetic real-provider tasks; no replay; successful loads are SDK results, not tool attempts',
        'cases': cases, 'tasksWithSuccessfulLoads': sum(bool(c['successfulLoads']) for c in cases),
        'successfulBodyLoads': sum(not e.get('skillReference') for c in cases for e in c['successfulLoads']),
        'successfulReferenceLoads': sum(bool(e.get('skillReference')) for c in cases for e in c['successfulLoads'])}
    (root / 'native-skills-lifecycle.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in out.items() if k != 'cases'}))


if __name__ == '__main__':
    main()
