"""Read-only benchmark evidence export on the deployment host. Never replays requests.

Private full journals stay under --root; public metrics exclude source and input data.
Times are from Node acceptance, separately from end-to-end HTTP duration.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import statistics
import subprocess


def sql(query):
    return subprocess.check_output(['docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At',
        '-d', 'runtime_os_acceptance_api', '-c', query], text=True).strip()


def journal(node):
    partition = hashlib.sha256(('\0'.join(node[k] for k in ['tenantId', 'userId', 'runId'])).encode()).hexdigest()
    where = "partition_id='" + partition + "'"
    envelope = json.loads(sql("select record_json from runtime_evidence_entry where " + where
        + " and entry_kind='EXEC_CHECKPOINT' and entry_id='coding.tasks.v1'"))
    if envelope['schemaVersion'] == 'runtime_execution_checkpoint.v1':
        raw = envelope['value']
    else:
        chunks = [json.loads(line) for line in sql("select record_json from runtime_evidence_entry where " + where
            + " and entry_kind='EXEC_PAYLOAD' and record_json::jsonb->>'contentSha256'='" + envelope['contentSha256'] + "'").splitlines()]
        raw = ''.join(c['value'] for c in sorted(chunks, key=lambda c: c['index']))
        assert hashlib.sha256(raw.encode()).hexdigest() == envelope['contentSha256']
    task = json.loads(raw)['tasks'][node['taskId']]
    assert node['taskId'].startswith('analysis-coding-') and all(c in '0123456789abcdef' for c in node['taskId'][16:])
    receipts = [json.loads(line) for line in sql("select record_json from runtime_evidence_entry where " + where
        + " and evidence_type='CODING_MODEL_RESPONSE_RECEIPT' and record_json::jsonb->'payload'->>'taskId'='"
        + node['taskId'] + "'").splitlines()]
    receipts.sort(key=lambda r: int(r['payload']['operationId'].split('-')[1]))
    return task, receipts


def milliseconds(start, end):
    if not end:
        return None
    return round((datetime.datetime.fromisoformat(end.replace('Z', '+00:00'))
        - datetime.datetime.fromisoformat(start.replace('Z', '+00:00'))).total_seconds() * 1000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    args = parser.parse_args()
    root = args.root
    monitor = json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
    rows = []
    for variant in ['baseline', 'candidate', 'final', 'verified']:
        directory = root / variant
        if not directory.exists():
            continue
        private = root / (variant + '-evidence-private')
        private.mkdir(exist_ok=True, mode=0o700)
        for request_file in sorted(directory.glob('*-request.json')):
            case = request_file.name.removesuffix('-request.json')
            request = json.loads(request_file.read_text())
            receipt_file = directory / (case + '-receipt.json')
            receipt = json.loads(receipt_file.read_text()) if receipt_file.exists() else {}
            error_file = directory / (case + '-error.json')
            response = receipt.get('receipt', {})
            # Match the actual canonical run, never a fuzzy objective string.
            request_id = request['requestId']
            if case.startswith('repair'):
                run_id = request_id
                prepared_file = directory / (case + '-prepared.json')
                prepared = json.loads(prepared_file.read_text()) if prepared_file.exists() else {}
                run_id = prepared.get('runId', run_id)
            else:
                run_id = None
            nodes = [t for t in monitor['tasks'] if t['taskId'] == response.get('taskId') or (run_id and t['runId'] == run_id)]
            if not nodes:
                # Task run names are computed by the platform, using each actual owner scope.
                prefix = 'python-reuse-' if case.startswith('repair') else 'asset-analysis-'
                nodes = [t for t in monitor['tasks'] if t['runId'] == prefix
                    + hashlib.sha256(('\0'.join([t['tenantId'], t['userId'], request_id])).encode()).hexdigest()]
            row = {'variant': variant, 'case': case, 'status': response.get('status', 'HTTP_ERROR' if error_file.exists() else 'PENDING'),
                'httpDurationMs': receipt.get('elapsedMs'), 'taskId': response.get('taskId'),
                'resultAvailable': response.get('resultAvailable') is True}
            rows.append(row)
            if not nodes:
                continue
            nodes.sort(key=lambda t: t['fencingToken'])
            (private / (case + '-nodes.json')).write_text(json.dumps(nodes, ensure_ascii=False))
            task, responses = journal(nodes[-1])
            (private / (case + '-journal.json')).write_text(json.dumps(task, ensure_ascii=False))
            (private / (case + '-model-receipts.json')).write_text(json.dumps(responses, ensure_ascii=False))
            events = sorted([e for t in nodes for e in t['events']], key=lambda e: e['timestamp'])
            first = lambda predicate: next((e['timestamp'] for e in events if predicate(e)), None)
            start = nodes[0]['createdAt']
            calls = [r['payload']['response'] for r in responses]
            proposed_at = first(lambda e: e['type'] == 'CODE_PROPOSED')
            pre_models = {c['ordinal']: c for n in nodes for c in n['models']
                if proposed_at is None or c['startedAt'] <= proposed_at}
            pre_events = [e for e in events if proposed_at is None or e['timestamp'] <= proposed_at]
            pre_usage = [c.get('usage') or {} for c in pre_models.values()]
            row.update(taskId=nodes[0]['taskId'], nodeStatus=nodes[-1]['status'], nodeError=nodes[-1].get('error'), fences=len(nodes),
                eventsTruncated=any(t.get('eventsTruncated') for t in nodes),
                firstProposalMs=milliseconds(start, proposed_at),
                preCodeModelCalls=len(pre_models),
                preCodeInputTokensKnown=sum(u['inputTokens'] for u in pre_usage if u.get('inputTokens') is not None),
                preCodeKnownUsageCalls=sum(u.get('inputTokens') is not None for u in pre_usage),
                preCodeSkillReads=sum(e['type'] == 'SKILLS_LOADED' for e in pre_events),
                preCodeInputReads=sum(e['type'] == 'TOOL_STARTED' and e.get('tool') == 'input_read' for e in pre_events),
                preCodeMaxSkillContextBytes=max((e['contextSkillsBytes'] for e in pre_events if 'contextSkillsBytes' in e), default=None),
                preCodeMaxRequestBytes=max((e['requestBytes'] for e in pre_events if 'requestBytes' in e), default=None),
                firstCodeMs=milliseconds(start, first(lambda e: e['type'] == 'CODE_READY')),
                firstNewCodeMs=milliseconds(start, first(lambda e: e['type'] == 'CODE_READY'
                    and (not case.startswith('repair') or (e.get('parentCodeVersion') or 0) >= 1))),
                firstPythonMs=milliseconds(start, first(lambda e: e['type'] == 'TOOL_STARTED' and e.get('tool') == 'python_execute')),
                firstResultMs=milliseconds(start, first(lambda e: e['type'] == 'TOOL_FINISHED' and e.get('tool') == 'python_execute' and e.get('resultAvailable'))),
                modelCalls=len({c['ordinal'] for t in nodes for c in t['models']}),
                knownUsageCalls=sum(all(isinstance((c.get('usage') or {}).get(k), (int, float)) for k in ['inputTokens', 'outputTokens']) for c in calls),
                knownTokens=sum(((c.get('usage') or {}).get('inputTokens') or 0) + ((c.get('usage') or {}).get('outputTokens') or 0) for c in calls),
                codeProposals=sum(e['type'] == 'CODE_READY' for e in events),
                rejectedProposals=sum(e['type'] == 'CODE_PROPOSAL_REJECTED' for e in events),
                pythonExecutions=sum((o.get('actual') or {}).get('codeExecutions', 0)
                    for o in task.get('operations', {}).values() if o.get('kind') == 'CODE' and o.get('stopProofRef')),
                pythonToolResults=sum(e['type'] == 'TOOL_FINISHED' and e.get('tool') == 'python_execute' for e in events),
                pythonStopUnconfirmed=sum(o.get('kind') == 'CODE' and not o.get('stopProofRef')
                    for o in task.get('operations', {}).values()),
                pythonDiagnostics=[e.get('diagnosticCode') for e in events if e['type'] == 'TOOL_FINISHED' and e.get('tool') == 'python_execute'],
                skillReads=sum(e['type'] == 'TOOL_TIMING' and e.get('tool') == 'skill_read' for e in events),
                discoveryCalls=sum(e['type'] == 'TOOL_TIMING' and e.get('tool') == 'tool_describe' for e in events),
                modelErrors=[{'type': e['type'], 'diagnosticCode': e.get('diagnosticCode'), 'errorCategory': e.get('errorCategory')}
                    for e in events if e['type'] in ['MODEL_FAILED', 'MODEL_RESPONSE_REJECTED']],
                toolsByCall=[[c['name'] for c in v.get('toolCalls', [])] for v in calls],
                providerModels=sorted({c.get('modelInfo', {}).get('providerModel', 'UNKNOWN') for c in calls}),
                finishReasons=[c.get('finishReason') for c in calls],
                maxSkillContextBytes=max((e.get('contextSkillsBytes', 0) for e in events), default=0),
                repairObserved=any(e['type'] == 'TOOL_FINISHED' and e.get('tool') == 'python_execute'
                    and e.get('status') == 'FAILED' for e in events) and row['resultAvailable'])
            row['successfulExecution'] = row['nodeStatus'] == 'COMPLETED' and row['resultAvailable'] and row['pythonExecutions'] > 0
    aggregate = {}
    for variant in ['baseline', 'candidate', 'final', 'verified']:
        group = [r for r in rows if r['variant'] == variant]
        if not group:
            continue
        metrics = {}
        for key in ['firstProposalMs','firstCodeMs', 'firstNewCodeMs', 'firstPythonMs', 'firstResultMs', 'httpDurationMs', 'modelCalls', 'knownTokens',
                    'preCodeModelCalls','preCodeInputTokensKnown','preCodeSkillReads','preCodeInputReads','preCodeMaxSkillContextBytes','preCodeMaxRequestBytes']:
            values = [r[key] for r in group if r.get(key) is not None]
            metrics[key] = {'observed': len(values), 'median': statistics.median(values) if values else None}
        aggregate[variant] = {'tasks': len(group), 'successfulExecutions': sum(bool(r.get('successfulExecution')) for r in group),
            'totalModelCalls': sum(r.get('modelCalls', 0) for r in group),
            'knownUsageCalls': sum(r.get('knownUsageCalls', 0) for r in group),
            'unknownUsageCalls': sum(max(0, r.get('modelCalls', 0) - r.get('knownUsageCalls', 0)) for r in group),
            'knownTokensTotal': sum(r.get('knownTokens', 0) for r in group),
            'repairTasks': sum(r['case'].startswith('repair') for r in group),
            'repairSuccesses': sum(r['case'].startswith('repair') and r.get('repairObserved', False) for r in group), 'metrics': metrics}
    report = {'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'rows': rows, 'aggregate': aggregate,
        'method': 'Five synthetic cases, two repeats each, real configured model and Java/Python. Same immutable assets. Node acceptance clocks; unknown usage excluded, failures retained. Sequential variants, max concurrency 2; descriptive small sample, no p95 claim.'}
    (root / 'metrics-public.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(aggregate, ensure_ascii=False))


if __name__ == '__main__':
    main()
