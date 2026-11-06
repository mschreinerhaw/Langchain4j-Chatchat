"""One real-day cancellation regression after the six isolated model trials.

Uses existing native submit/status/stop routes and the unchanged real snapshot.
This intentionally cancelled task is never counted as successful analysis.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import sys
import time
import urllib.request


def main():
    os.umask(0o077)
    root=Path('/var/tmp/runtime-cline-acceptance-20261107-stop-validation')
    root.mkdir(mode=0o700,exist_ok=True)
    intent=root/'stop-validation-intent.json'
    resume=sys.argv[1:]==['--resume-preflight']
    if resume:
        assert intent.exists() and not (root/'before-stop-journal-private.json').exists()
        assert json.loads((root/'stop-real-day-error-private.json').read_text())['message'].startswith('HTTP Error 507')
        assert not (root/'preflight-recovery-intent-private.json').exists(),'Never replay an unknown recovery'
        (root/'preflight-recovery-intent-private.json').write_text(json.dumps({'startedAt':time.time(),'reason':'Known storage rejection before task admission; recover original persisted node only'}))
    else:
        assert not sys.argv[1:] and not intent.exists(),'Never replay a test with an existing intent'
        intent.write_text(json.dumps({'startedAt':time.time(),'policy':'one existing native task; same real snapshot and original budget; intentional cancellation'}))
    release=Path('/var/tmp/runtime-cline-acceptance-20261107-progress')
    while not (release/'finalize-completed.json').exists():time.sleep(10)
    base=Path('/var/tmp/runtime-data-remediation-20261107-r7')
    for name in ['auth-private.json','collect-coding-progress.py']:shutil.copy2(base/name,root/name)
    auth=json.loads((root/'auth-private.json').read_text())
    frozen=json.loads(Path('/var/tmp/runtime-cline-acceptance-20261107-ui/acceptance-frozen-inputs-private.json').read_text())
    def save(name,value):
        (root/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
    def api(path,body=None):
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),
            headers={'Authorization':'Bearer '+auth['token'],'Content-Type':'application/json','X-Workspace-Id':'data-exploration'})
        with urllib.request.urlopen(request,timeout=1800) as response:value=json.load(response)
        assert value.get('code') in [200,201,202],value.get('message')
        return value['data']
    files=[f['assetId'] for f in frozen['inputFiles']]
    if resume:
        previous=json.loads((root/'scenario-prepared-private.json').read_text());node_id=previous['cases'][0]['nodeId']
        space=api('/data-science/explorations/'+previous['workspaceId']+'/nodes/'+node_id+'/status')
        node=next(n for n in space['nodes'] if n['id']==node_id)
        assert not (node.get('receipt') or {}).get('taskId'),'A known admitted task requires reconciliation, not a preflight retry'
        assert node['fileIds']==files and node['question']==frozen['question'] and node['modelName']=='qwen3.7-plus'
    else:
        space=api('/data-science/explorations',{'title':'真实行情停止状态核对'})
        save('workspace-created-private.json',space)
        space=api('/data-science/explorations/'+space['id']+'/nodes',{'version':space['version'],'parentId':'','fileIds':files,
            'question':frozen['question'],'modelName':'qwen3.7-plus'})
        node=space['nodes'][-1];node_id=node['id']
    prefix='/data-science/explorations/'+space['id']+'/nodes/'+node_id
    save('scenario-prepared-private.json',{'workspaceId':space['id'],'cases':[{'case':'stop-real-day','model':'qwen3.7-plus','nodeId':node_id}],
        'agent':None,'extraCases':[],'inputAssetIds':files,'inputFiles':[{k:f[k] for k in ['fileName','sha256']} for f in frozen['inputFiles']],
        'originalInputSha256':next(f['sha256'] for f in frozen['inputFiles'] if f['fileName'].endswith('.parquet')),
        'questionSha256':hashlib.sha256(frozen['question'].encode()).hexdigest(),'intentionalCancellation':True})
    spec=importlib.util.spec_from_file_location('collector',root/'collect-coding-progress.py')
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    def observation():
        monitor=json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
        matches=[n for n in monitor['tasks'] if n['runId']=='asset-analysis-'+hashlib.sha256(('\0'.join([n['tenantId'],n['userId'],node_id])).encode()).hexdigest()]
        return max(matches,key=lambda n:n['fencingToken']) if matches else None
    def execute():
        save('stop-real-day-resumed-intent-private.json' if resume else 'stop-real-day-intent-private.json',{'nodeId':node_id,'submittedAt':time.time()})
        start=time.monotonic()
        try:
            state=api(prefix+'/execute',{})
            save('stop-real-day-response-private.json',{'elapsedMs':round((time.monotonic()-start)*1000),'state':state})
        except Exception as error:save('stop-real-day-error-private.json',{'type':type(error).__name__,'message':str(error),'policy':'never replay'})
    pool=ThreadPoolExecutor(max_workers=1);pending=pool.submit(execute)
    before=None;until=time.monotonic()+90
    while time.monotonic()<until:
        observed=observation()
        if observed and any(e.get('type')=='MODEL_STARTED' for e in observed.get('events',[])):
            task,_=collector.journal(observed)
            if any(k.startswith('model-') and v.get('startIntent') for k,v in task.get('operations',{}).items()):before=task;break
        time.sleep(.25)
    if before is None:
        observed=observation();assert observed,'No admitted native task; inspect the preserved intent'
        before,_=collector.journal(observed)
    save('before-stop-journal-private.json',before)
    save('stop-intent-private.json',{'taskId':before['contract']['taskId'],'requestedAt':time.time(),'knownModelStart':any(k.startswith('model-') and v.get('startIntent') for k,v in before['operations'].items())})
    start=time.monotonic();stopped=api(prefix+'/stop',{});stop_ms=round((time.monotonic()-start)*1000)
    save('stop-response-private.json',stopped)
    first=next(n for n in stopped['nodes'] if n['id']==node_id)['receipt']
    final=first;until=time.monotonic()+360
    while not final.get('terminal') and time.monotonic()<until:
        time.sleep(1);state=api(prefix+'/status');save('stop-real-day-status-private.json',state)
        final=next(n for n in state['nodes'] if n['id']==node_id)['receipt']
    assert final.get('terminal'),'Stop remains unknown; preserve it, never fabricate terminal completion'
    pending.result(timeout=30);pool.shutdown(wait=True)
    after,receipts=collector.journal(observation());save('after-stop-journal-private.json',after)
    assert before['contract']==after['contract'],'Cancellation cannot renew or alter a contract'
    started=lambda j:{k for k,v in j['operations'].items() if k.startswith(('model-','cline-')) and v.get('startIntent')}
    assert started(after)<=started(before),'Stop cannot admit another model or code operation'
    assert after['outcome']['status']=='CANCELLED' and after['outcome']['executionControl']['executorStopped'] is True
    assert not final.get('resultAvailable') and not after['outcome'].get('resultDatasetRef')
    metrics=first.get('stopReconciliation') or {}
    proof={'status':'PASSED','taskId':after['contract']['taskId'],'workspaceId':space['id'],'nodeId':node_id,
        'intentionalCancellation':True,'analysisAcceptance':False,'sameRealSnapshot':True,'sameContractAndBudget':True,
        'knownPreAdmissionStorageFailureRecovered':resume,
        'newModelOrCodeStartsAfterStop':0,'finalStatus':after['outcome']['status'],'localExecutorStopVerified':True,
        'firstStopTerminal':first.get('terminal') is True,'stopHttpDurationMs':stop_ms,'stopReconciliation':metrics,
        'boundedDrainLiveExercised':bool(metrics.get('attempts')) if metrics else None,'boundedDrainObservedTerminal':bool(metrics.get('terminalObserved')) if metrics else None,
        'stopMetricsAvailability':'PRESENT' if metrics else 'NOT_PRESENT_IN_WINNING_TERMINAL_RECEIPT',
        'reportNotPromotedToSuccess':True,'providerUsagePolicy':'Local stop proof is not proof of provider billing; unknown usage remains reserved',
        'nativeModelOperationCount':len([k for k in after['operations'] if k.startswith('model-')]),
        'sourceParquetSha256':next(f['sha256'] for f in frozen['inputFiles'] if f['fileName'].endswith('.parquet'))}
    save('stop-validation-proof.json',proof);print(json.dumps(proof),flush=True)


if __name__=='__main__':main()
