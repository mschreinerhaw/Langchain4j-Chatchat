"""Project official receipts for acceptance cases; never export prompts, source rows or credentials."""
import importlib.util
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request
import urllib.error

root = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
spec = importlib.util.spec_from_file_location('benchmark',Path('/tmp/benchmark-runtime-analysis.py'))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None
def http(path,body=None,optional=False):
    request = urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(request,timeout=60) as response: value=json.load(response)
    except urllib.error.HTTPError as error:
        if optional and error.code==404: return None
        raise
    if optional and value.get('code')==404: return None
    if value.get('code') not in (None,0,200): raise RuntimeError('Official evidence query rejected')
    return value['data']
auth = http('/enterprise/auth/login',json.loads((root.parent/'cline-monitor-20261019/stage-auth.json').read_text()))
token = auth['token']
tenant = urllib.parse.quote(auth['user']['tenantId'])
results = {}
for path in sorted(root.glob('scenario*-*.json')):
    if not re.fullmatch(r'scenario[12]-(?:before|after|final)',path.stem): continue
    case = json.loads(path.read_text())
    task,run = case['taskId'],case['runId']
    parent = http('/agent/tasks/'+task+'?tenantId='+tenant)
    if parent['canonicalState'] not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'): continue
    events,cursor = [],0
    for _ in range(64):
        page = http('/agent/tasks/'+task+'/events?tenantId='+tenant+'&limit=200&afterSequence='+str(cursor))
        events.extend(page)
        if len(page)<200: break
        next_cursor = max(int(event.get('sequence') or event.get('sequenceNumber') or 0) for event in page)
        if next_cursor<=cursor: raise RuntimeError('Evidence cursor did not advance')
        cursor=next_cursor
    runtime = http('/agent/runtime/runs/'+run,optional=True)
    coding = http('/agent/runtime/runs/'+run+'/coding') if runtime else {'tasks':[]}
    for child in coding['tasks']:
        values,after = [],0
        for _ in range(64):
            page = http('/agent/runtime/runs/'+run+'/coding/'+child['taskId']+'/events?limit=128&afterSequence='+str(after))
            values.extend(page['events'])
            if not page['hasMore']: break
            after=page['nextSequence']
        child['events']=values
    usage = [item for item in benchmark.decoded_records(runtime) if item.get('contractVersion')=='agent_model_usage_v1']
    metrics = benchmark.project({'parent':parent,'events':events,'taskId':task},coding,usage,benchmark.python_diagnostics(run))
    if not usage:
        for key in ('javaModelCalls','javaModelDurationMs','javaPlanningModelDurationMs','javaInputTokens','javaOutputTokens'):
            metrics[key]=None
    elif metrics['javaTokenUsageObserved']<metrics['javaModelCalls']:
        metrics['javaInputTokens']=metrics['javaOutputTokens']=None
    records = list(benchmark.decoded_records(events))
    delivery = [item for item in records if item.get('stage')=='SKILL_ANALYSIS_CONTEXT' and item.get('status')=='COMPLETED']
    metrics['skillContextEngines']=sorted({item.get('engine','UNKNOWN') for item in delivery})
    metrics['skillDeliveryWithoutJavaInference']=bool(delivery) and all(
        item.get('engine')=='RUNTIME_CONTEXT_DELIVERY' and item.get('modelCalls')==0 for item in delivery)
    starts,ends=[],[]
    for event in events:
        for item in benchmark.decoded_records(event.get('payload')):
            if item.get('stage')!='SKILL_ANALYSIS_CONTEXT': continue
            at=benchmark.millis(event.get('createTime'))
            if at is None: continue
            if item.get('status')=='RUNNING': starts.append(at)
            if item.get('status')=='COMPLETED': ends.append(at)
    metrics['skillContextDurationMs']=max(ends)-min(starts) if starts and ends else None
    metrics['acceptanceStatus']=case.get('status')
    metrics['resultAdmitted']=case.get('resultAdmitted',False)
    metrics['javaSemanticReviewInvoked']=case.get('javaSemanticReviewInvoked')
    metrics['publicAnswerSha256']=case.get('publicAnswerSha256')
    metrics['publicAnswerCharacters']=case.get('publicAnswerCharacters')
    metrics['codeSubmissions']=case.get('codeSubmissions',[])
    metrics['inputBindings']=[{'taskId':binding['taskId'],'operationId':binding['operationId'],
        'snapshots':[{'format':snapshot.get('format'),'rowCount':snapshot.get('rowCount'),
            'manifestSha256':snapshot.get('sha256'),
            'datasets':[{'datasetReference':version.get('datasetReference'),'rowCount':version.get('rowCount')}
                for version in snapshot.get('inputVersions',[])]}
            for snapshot in binding.get('snapshots',[])]} for binding in case.get('inputBindings',[])]
    metrics['codingConfiguration']=[{'taskId':child['taskId'],'model':child.get('configuration',{}).get('model'),
        'skillStatus':child.get('configuration',{}).get('skills',{}).get('status'),
        'skillFingerprint':child.get('configuration',{}).get('skills',{}).get('fingerprint'),
        'skillIds':[item.get('id') for item in child.get('configuration',{}).get('skills',{}).get('skills',[])],
        'executorStopped':child['outcome']['executionControl']['executorStopped'],
        'reconciliationRequired':child['outcome']['executionControl']['reconciliationRequired']}
        for child in coding['tasks']]
    metrics['comparison']='One observation per version/scenario; failed cases and different objectives are not pooled. No P50/P95 or improvement percentage is established.'
    results[path.stem]=metrics
(root/'scenario-metrics.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print(json.dumps({key:{field:value.get(field) for field in ('status','acceptanceStatus','endToEndMs',
    'javaModelCalls','codingModelCalls','pythonExecutions','runtimeSemanticReviews','skillDeliveryWithoutJavaInference')}
    for key,value in results.items()},ensure_ascii=False),flush=True)
