"""Real authenticated Runtime analysis benchmark and evidence-only trace projection.

No workload, asset, template, model, business field or formula is embedded here.
Samples from different labels/deployments are never silently pooled.
"""
import argparse
from collections import deque
import hashlib
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import urllib.request
import uuid


def millis(value):
    if isinstance(value, (int, float)):
        return int(value if value > 1e12 else value * 1000)
    if isinstance(value, str):
        try:
            return int(datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() * 1000)
        except ValueError:
            return None
    return None


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    low, high = math.floor(position), math.ceil(position)
    return values[low] + (values[high] - values[low]) * (position - low)


def records(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from records(item)
    elif isinstance(value, list):
        for item in value:
            yield from records(item)


def decoded_records(value):
    if isinstance(value, str) and value.lstrip().startswith(('{', '[')):
        try:
            yield from decoded_records(json.loads(value))
        except (ValueError, TypeError):
            pass
    elif isinstance(value, dict):
        yield value
        for child in value.values():
            yield from decoded_records(child)
    elif isinstance(value, list):
        for child in value:
            yield from decoded_records(child)


def python_diagnostics(run_id):
    if not run_id or not re.fullmatch(r'[A-Za-z0-9-]{1,128}', run_id):
        return []
    sql = ("SELECT row_to_json(t) FROM (SELECT operation_id,task_id,created_at,"
        "container_started_at,updated_at,workspace_path,container_name,status,"
        "executor_stopped,cleanup_failed,exit_code FROM mcp_python_managed_operation "
        "WHERE run_id='" + run_id + "' ORDER BY created_at) t")
    output = subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At',
        '-d','runtime_os_acceptance_mcp','-c',sql], text=True)
    result = []
    approved_root = Path('/opt/runtime-os-docker-stage/mcp/data/python-assets/operations').resolve()
    for line in output.splitlines():
        row = json.loads(line)
        path = Path(row.pop('workspace_path')).resolve()
        container = row.pop('container_name')
        if not path.is_relative_to(approved_root):
            raise RuntimeError('Python operation workspace escaped approved root')
        receipt = path/'output/logs/receipt.json'
        row['receipt'] = json.loads(receipt.read_text()) if receipt.is_file() else None
        inspect = subprocess.run(['docker','container','inspect',container],capture_output=True,text=True)
        if inspect.returncode and not ('No such container' in inspect.stderr or 'No such object' in inspect.stderr):
            raise RuntimeError('Docker cleanup verification failed: ' + inspect.stderr[:200])
        row['containerRemoved'] = inspect.returncode != 0
        result.append(row)
    return result


def project(report, coding, runtime=None, diagnostics=None):
    spans, model_ids = [], set()
    batch_ids = set()
    java_ids = set()
    for item in records(runtime or {}):
        if item.get('contractVersion') != 'agent_model_usage_v1':
            continue
        for call in item.get('calls', []):
            identity = (call.get('callId'), call.get('startedAt'), call.get('nodeName'))
            if identity in java_ids:
                continue
            java_ids.add(identity)
            spans.append(dict(layer='JAVA_MODEL', phase=call.get('nodeName'), operationId=str(call.get('callId')),
                startedAt=call.get('startedAt'), finishedAt=call.get('finishedAt'), durationMs=call.get('elapsedMs'),
                state=call.get('status'), queueTimeMs=call.get('queueTimeMs'),
                usage={key:call[key] for key in ('inputTokens','outputTokens','cacheReadTokens','reasoningTokens') if call.get(key) is not None},
                inputTokensEstimated=call.get('inputTokensEstimated'), outputTokensEstimated=call.get('outputTokensEstimated'),
                errorType=call.get('errorType')))
    events = report.get('events', [])
    normalized = []
    for event in events:
        payload = event.get('payload') or {}
        if isinstance(payload, str):
            payload = json.loads(payload)
        normalized.append((millis(event.get('createTime')), payload))
        for batch in decoded_records(payload):
            if not (batch.get('batchId') and batch.get('startedAt') and batch.get('completedAt')
                    and isinstance(batch.get('summary'), dict)):
                continue
            identity = (batch['batchId'], batch['startedAt'], batch['completedAt'])
            if identity in batch_ids:
                continue
            batch_ids.add(identity)
            before, after = millis(batch['startedAt']), millis(batch['completedAt'])
            spans.append(dict(layer='JAVA_TEMPLATE', phase='controlled_batch', batchId=batch['batchId'],
                startedAt=batch['startedAt'], finishedAt=batch['completedAt'], durationMs=after-before,
                executionMode=batch.get('executionMode'), status=batch.get('status') or batch.get('batchStatus'),
                summary=batch['summary']))
    analysis_start = None
    review_events = []
    for timestamp, payload in normalized:
        for item in records(payload):
            if item.get('stage') == 'MODEL_NATIVE_ANALYSIS' and item.get('eventKind') == 'HARNESS_TURN' and item.get('eventState') == 'STARTED':
                analysis_start = min(analysis_start or timestamp, timestamp)
            if item.get('eventKind') in ('CODING_SEMANTIC_REVIEW', 'FINAL_REPORT_SEMANTIC_REVIEW'):
                review_events.append((timestamp, item.get('eventKind'), item.get('turn'), item.get('taskId')))
    for task in coding.get('tasks', []):
        task_id = task['taskId']
        pending_python = deque()
        for receipt in task.get('modelReceipts', []):
            identity = (task_id, receipt['operationId'])
            if identity in model_ids:
                continue
            model_ids.add(identity)
            spans.append(dict(layer='CODING_MODEL', phase='provider_call', taskId=task_id,
                operationId=receipt['operationId'], startedAt=receipt.get('startedAt'), finishedAt=receipt.get('finishedAt'),
                durationMs=receipt.get('durationMs'), state=receipt.get('state'), usage=receipt.get('usage', {}),
                cacheReadTokens=receipt.get('usage', {}).get('cacheReadTokens'),
                cacheWriteTokens=receipt.get('usage', {}).get('cacheWriteTokens'),
                selectedModel=receipt.get('selectedModel'), providerModel=receipt.get('providerModel')))
        for event in task.get('events', []):
            item = event.get('payload') or {}
            if item.get('type') == 'TOOL_STARTED' and item.get('tool') == 'python_execute':
                pending_python.append(millis(event.get('occurredAt')))
            if item.get('type') == 'TOOL_FINISHED' and item.get('tool') == 'python_execute':
                finished = millis(event.get('occurredAt'))
                started = pending_python.popleft() if pending_python else None
                # Older event projections omit resultDatasetRef. A successful execution
                # with a recorded structural validation and no output error is evidence
                # of a result; otherwise the result state is unknown, never guessed false.
                available = item.get('resultAvailable')
                availability_source = 'EXPLICIT' if isinstance(available, bool) else 'UNKNOWN'
                if not isinstance(available, bool) and item.get('resultDatasetRef'):
                    available, availability_source = True, 'RESULT_REFERENCE'
                if not isinstance(available, bool) and item.get('status') == 'SUCCEEDED' \
                        and item.get('validationStatus') in ('PASSED', 'INCONCLUSIVE') \
                        and not item.get('outputStatus'):
                    available, availability_source = True, 'HISTORICAL_STRUCTURAL_RECEIPT'
                if not isinstance(available, bool) and (item.get('status') != 'SUCCEEDED'
                        or item.get('outputStatus')):
                    available, availability_source = False, 'HISTORICAL_FAILURE_RECEIPT'
                spans.append(dict(layer='PYTHON', phase='dispatch_execution_collection_roundtrip', taskId=task_id,
                    operationId=event.get('operationId') or item.get('operationId'),
                    startedAt=started, finishedAt=finished,
                    durationMs=finished-started if finished is not None and started is not None else None,
                    state=item.get('status'), outputStatus=item.get('outputStatus'),
                    validationStatus=item.get('validationStatus'),
                    resultAvailable=available if isinstance(available, bool) else None,
                    resultAvailabilitySource=availability_source))
    for row in diagnostics or []:
        receipt = row.get('receipt') or {}
        common = dict(layer='PYTHON_RUNTIME', taskId=row['task_id'], operationId=row['operation_id'],
            state=row['status'], executorStopped=row['executor_stopped'],
            containerRemoved=row['containerRemoved'], cleanupFailed=row['cleanup_failed'])
        phases = (
            ('accepted_to_container_start', row.get('created_at'), row.get('container_started_at')),
            ('container_process', receipt.get('startedAt'), receipt.get('finishedAt')),
            ('process_finish_to_reconciled_archive', receipt.get('finishedAt'), row.get('updated_at')))
        for phase, before, after in phases:
            a, b = millis(before), millis(after)
            spans.append(dict(common, phase=phase, startedAt=before, finishedAt=after,
                durationMs=max(0,b-a) if a is not None and b is not None else None,
                timingSource='POSTGRES_ROW_AND_IMMUTABLE_EXECUTION_RECEIPT'))
    parent = report.get('parent') or {}
    started = millis(parent.get('createTime'))
    finished = millis(parent.get('finishedAt'))
    total = finished-started if started is not None and finished is not None else report.get('elapsedSeconds', 0)*1000
    if analysis_start is not None and started is not None:
        spans.append(dict(layer='JAVA', phase='planning_binding_acquisition_combined', startedAt=started,
            finishedAt=analysis_start, durationMs=analysis_start-started, state='DELIVERED'))
    models = [span for span in spans if span['layer'] == 'CODING_MODEL']
    python = [span for span in spans if span['layer'] == 'PYTHON']
    java_models = [span for span in spans if span['layer'] == 'JAVA_MODEL']
    batches = [span for span in spans if span['layer'] == 'JAVA_TEMPLATE']
    runtime_python = [span for span in spans if span['layer'] == 'PYTHON_RUNTIME']
    def measured_python_phase(name):
        measured=[span['durationMs'] for span in runtime_python if span['phase']==name
            and span['durationMs'] is not None]
        return sum(measured) if len(measured)==len(python) else None
    first_batch_start = min((millis(span['startedAt']) for span in batches), default=None)
    retry_reasons = {}
    for span in python:
        reason = span['outputStatus'] or (span['state'] if span['state'] != 'SUCCEEDED' else None)
        if reason: retry_reasons[reason] = retry_reasons.get(reason, 0) + 1
    cache_observed = [span['usage'].get('cacheReadTokens') for span in models + java_models
        if span['usage'].get('cacheReadTokens') is not None]
    cache_denominator=sum(span['usage'].get('inputTokens') or 0 for span in models + java_models
        if span['usage'].get('cacheReadTokens') is not None)
    return dict(schemaVersion='runtime_os.analysis.performance.v1', taskId=report['taskId'],
        runId=next((event.get('runId') for event in events if event.get('runId')), None),
        status=parent.get('canonicalState') or parent.get('status'), endToEndMs=total,
        codingTasks=len(coding.get('tasks', [])), codingModelCalls=len(models),
        codingModelDurationMs=sum(span.get('durationMs') or 0 for span in models),
        javaModelCalls=len(java_ids), javaModelDurationMs=sum(span.get('durationMs') or 0 for span in java_models),
        javaPlanningModelDurationMs=sum(span.get('durationMs') or 0 for span in java_models
            if first_batch_start is not None and millis(span.get('startedAt')) is not None
            and millis(span['startedAt']) < first_batch_start),
        planningBeforeFirstBatchMs=first_batch_start-started if first_batch_start is not None and started is not None else None,
        templateBatchCount=len(batches),templateBatchDurationMs=sum(span['durationMs'] for span in batches),
        inputTokens=sum(span['usage'].get('inputTokens', 0) for span in models),
        outputTokens=sum(span['usage'].get('outputTokens', 0) for span in models),
        javaInputTokens=sum(span['usage'].get('inputTokens', 0) for span in java_models),
        javaOutputTokens=sum(span['usage'].get('outputTokens', 0) for span in java_models),
        javaTokenUsageObserved=sum('inputTokens' in span['usage'] and 'outputTokens' in span['usage'] for span in java_models),
        cacheTelemetry='OBSERVED' if cache_observed else 'NOT_RECORDED',
        cacheReadTokens=sum(cache_observed) if cache_observed else None,
        cacheObservedCalls=len(cache_observed),cacheEligibleCalls=len(models)+len(java_models),
        cacheHitRatioObserved=sum(cache_observed)/cache_denominator if cache_denominator else None,
        pythonExecutions=len(python), pythonRoundtripMs=sum(span.get('durationMs') or 0 for span in python),
        pythonContainersReleased=sum(row.get('containerRemoved') is True for row in diagnostics or []),
        pythonCleanupPending=sum(row.get('containerRemoved') is not True or row.get('cleanup_failed') is True
            for row in diagnostics or []),
        pythonProcessMs=measured_python_phase('container_process'),
        pythonStartMs=measured_python_phase('accepted_to_container_start'),
        pythonArchiveMs=measured_python_phase('process_finish_to_reconciled_archive'),
        retryReasons=retry_reasons,
        firstExecutionProducedResult=python[0]['resultAvailable'] if python else None,
        runtimeSemanticReviews=len(set(review_events)), spans=spans,
        measurementLimits=['Python roundtrip includes startup, execution, polling and collection; it is not process CPU time.',
            'Python post-process timing includes log preservation, manifest archive and final row update; these are not separately metered.',
            'Missing provider cache telemetry is unknown, not zero.',
            'Execution/structural validity does not measure business-result correctness.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-file', required=True)
    parser.add_argument('--request-file', required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:18180')
    parser.add_argument('--label', required=True)
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--first-task-id')
    parser.add_argument('--timeout', type=int, default=1800)
    parser.add_argument('--reproject-existing', action='store_true',
        help='Fetch official Runtime receipts and rebuild traces from captured raw evidence without starting tasks')
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 100:
        raise ValueError('Expected 1..100 real repetitions')
    os.umask(0o077)
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=args.reproject_existing)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def http(path, body=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.base_url + path, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        with client.open(request, timeout=60) as response:
            return json.load(response)['data']

    auth = http('/api/v1/enterprise/auth/login', json.loads(Path(args.auth_file).read_text()))
    token = auth['token']
    tenant = auth['user']['tenantId']
    envelope = json.loads(Path(args.request_file).read_text())['request']
    workload = {key:value for key,value in envelope.items()
        if key not in ('sessionId','idempotencyKey','resumeTaskId','requestId','conversationId')}
    workload_hash=hashlib.sha256(json.dumps(workload,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    if args.reproject_existing:
        captured = sorted(output.glob('coding-*.json'), key=lambda path: int(path.stem.split('-')[1]))
        samples = []
        for path in captured:
            index = int(path.stem.split('-')[1])
            report = json.loads((output/f'run-{index}.json').read_text())
            coding = json.loads(path.read_text())
            run_id = next((event.get('runId') for event in report['events'] if event.get('runId')), None)
            runtime = http('/api/v1/agent/runtime/runs/' + run_id) if run_id else {}
            (output/f'runtime-{index}.json').write_text(json.dumps(runtime, ensure_ascii=False))
            diagnostics = python_diagnostics(run_id) if run_id else []
            (output/f'python-runtime-{index}.json').write_text(json.dumps(diagnostics, ensure_ascii=False))
            trace = project(report, coding, runtime, diagnostics)
            (output/f'trace-{index}.json').write_text(json.dumps(trace, ensure_ascii=False, indent=2))
            samples.append(trace)
        write_summary(output, args.label, args.repetitions, samples, workload_hash)
        return
    images = subprocess.check_output(['docker', 'ps', '--format', '{{.Names}} {{.Image}}'], text=True)
    (output/'deployment.txt').write_text(images)
    samples = []
    for index in range(args.repetitions):
        current = subprocess.check_output(['docker', 'ps', '--format', '{{.Names}} {{.Image}}'], text=True)
        # Python transient containers must not change the deployment identity.
        stable = lambda text: sorted(line for line in text.splitlines() if 'runtime-os-app-stage-' in line)
        if stable(current) != stable(images):
            raise RuntimeError('Deployment changed during a benchmark group; do not pool versions')
        if index == 0 and args.first_task_id:
            task_id = args.first_task_id
        else:
            request = dict(envelope, sessionId=str(uuid.uuid4()), idempotencyKey=str(uuid.uuid4()), resumeTaskId=None)
            task_id = http('/api/v1/agent/tasks', request)['taskId']
        root = '/api/v1/agent/tasks/' + urllib.parse.quote(task_id)
        query = '?tenantId=' + urllib.parse.quote(tenant)
        report = dict(taskId=task_id, startedAt=time.time(), events=[])
        cursor = 0
        deadline = time.monotonic() + args.timeout
        terminal = {'SUCCEEDED', 'FAILED', 'PARTIAL', 'CANCELLED', 'REJECTED', 'DLQ'}
        while time.monotonic() < deadline:
            parent = http(root + query)
            while True:
                page = http(root + '/events' + query + '&limit=200&afterSequence=' + str(cursor))
                report['events'].extend(page)
                for event in page:
                    cursor = max(cursor, int(event.get('sequenceNumber') or event.get('sequence') or 0))
                if len(page) < 200:
                    break
            report.update(parent=parent, elapsedSeconds=round(time.time()-report['startedAt'], 3))
            (output/f'run-{index+1}.json').write_text(json.dumps(report, ensure_ascii=False))
            if (parent.get('canonicalState') or parent.get('status')) in terminal:
                break
            time.sleep(3)
        else:
            raise RuntimeError('Benchmark observation timed out; running task remains explicitly identified in evidence')
        run_id = next((event.get('runId') for event in report['events'] if event.get('runId')), None)
        coding = http('/api/v1/agent/runtime/runs/' + run_id + '/coding') if run_id else {'tasks': []}
        for task in coding.get('tasks', []):
            task['events'] = []
            after = 0
            while True:
                page = http('/api/v1/agent/runtime/runs/' + run_id + '/coding/' + task['taskId'] + '/events?limit=128&afterSequence=' + str(after))
                task['events'].extend(page.get('events', []))
                if not page.get('hasMore'):
                    break
                after = page['nextSequence']
            control = task.get('outcome', {}).get('executionControl', {})
            if not control.get('executorStopped') or control.get('reconciliationRequired'):
                # Do not overlap another sample with unsettled work or invent a stop acknowledgment.
                (output/f'coding-{index+1}.json').write_text(json.dumps(coding, ensure_ascii=False))
                raise RuntimeError('Terminal parent has unsettled coding work; settle it through the official cancel/recovery API before another sample')
        (output/f'coding-{index+1}.json').write_text(json.dumps(coding, ensure_ascii=False))
        runtime = http('/api/v1/agent/runtime/runs/' + run_id) if run_id else {}
        (output/f'runtime-{index+1}.json').write_text(json.dumps(runtime, ensure_ascii=False))
        diagnostics = python_diagnostics(run_id) if run_id else []
        (output/f'python-runtime-{index+1}.json').write_text(json.dumps(diagnostics, ensure_ascii=False))
        trace = project(report, coding, runtime, diagnostics)
        (output/f'trace-{index+1}.json').write_text(json.dumps(trace, ensure_ascii=False, indent=2))
        samples.append(trace)
        write_summary(output, args.label, args.repetitions, samples, workload_hash)


def write_summary(output, label, planned, samples, workload_hash):
    successful = [sample for sample in samples if sample['status'] == 'SUCCEEDED']
    durations = [sample['endToEndMs'] for sample in successful]
    observed_first = [sample['firstExecutionProducedResult'] for sample in samples
        if sample['firstExecutionProducedResult'] is not None]
    summary = dict(schemaVersion='runtime_os.analysis.benchmark.v1', label=label,
        workloadSha256=workload_hash,planned=planned,
        completed=len(samples), succeeded=len(successful),
        partial=sum(sample['status']=='PARTIAL' for sample in samples),
        failed=sum(sample['status']=='FAILED' for sample in samples),
        otherTerminal=sum(sample['status'] not in ('SUCCEEDED','PARTIAL','FAILED') for sample in samples),
        quantileMethod='linear interpolation on observed successful samples, not a statistical tail guarantee',
        endToEndP50Ms=percentile(durations, .5), endToEndP95Ms=percentile(durations, .95),
        successfulSampleP50P95={key:{'p50':percentile([sample[key] for sample in successful],.5),
            'p95':percentile([sample[key] for sample in successful],.95)}
            for key in ('planningBeforeFirstBatchMs','templateBatchDurationMs','javaModelCalls',
                'javaModelDurationMs','codingModelCalls','codingModelDurationMs','pythonExecutions',
                'pythonRoundtripMs','pythonProcessMs','inputTokens','outputTokens')
            if successful and all(sample.get(key) is not None for sample in successful)},
        firstExecutionSuccessRate=sum(value is True for value in observed_first)/len(observed_first)
            if observed_first else None,
        firstExecutionObserved=len(observed_first),
        businessCorrectness='NOT_MEASURED_BY_EXECUTION_SUCCESS', samples=samples)
    (output/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps({key:value for key,value in summary.items() if key != 'samples'}), flush=True)


if __name__ == '__main__':
    main()
