"""Replay the real logged security-analysis question with a frozen input, no model/policy edits.

Server usage: SCRIPT prepare|run|collect RELEASE_DIR.
Private inputs/auth/receipts stay on the server. Each execute intent is written before IO;
unknown responses are polled, never resubmitted. The original failed task is never replayed.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime


def main():
    os.umask(0o077)
    command, directory = sys.argv[1:3]
    files_only='--files-only' in sys.argv[3:]
    root = Path(directory).resolve()
    token = json.loads((root/'auth-private.json').read_text())['token']

    def api(path, body=None, multipart=None):
        headers={'Authorization':'Bearer '+token,'X-Workspace-Id':'data-exploration','Content-Type':'application/json'}
        data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
        if multipart:
            filename,content=multipart;boundary='runtime-data-'+uuid.uuid4().hex
            data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+content+f'\r\n--{boundary}--\r\n'.encode()
            headers['Content-Type']='multipart/form-data; boundary='+boundary
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,headers=headers)
        with urllib.request.urlopen(request,timeout=1800) as response:value=json.load(response)
        if value.get('code') not in [200,201,202]:raise RuntimeError('Application rejected '+path+': '+str(value.get('message')))
        return value['data']

    def save(name,value):
        (root/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))

    if command=='prepare':
        assert not (root/'scenario-prepared-private.json').exists(), 'Existing preparation must not be repeated'
        assert not (root/'scenario-prepare-intent-private.json').exists(), 'Reconcile an existing prepare intent before issuing new writes'
        save('scenario-prepare-intent-private.json',{'startedAt':time.time(),'purpose':'Actual logged question; fixed snapshot and unchanged shared configuration'})
        original=json.loads((root/'parent-run-private.json').read_text())
        journal=json.loads(Path('/opt/chatchat-releases/native-analysis-failure-20261106/journal-latest-private.json').read_text())
        frozen_path=root/'acceptance-frozen-inputs-private.json'
        if frozen_path.exists():
            frozen=json.loads(frozen_path.read_text())
            assert 1<=len(frozen['inputFiles'])<=8
            assert len({f['assetId'] for f in frozen['inputFiles']})==len(frozen['inputFiles'])
            for file in frozen['inputFiles']:
                assert len(file['assetId'])==64 and len(file['sha256'])==64
            assets=[{'id':f['assetId']} for f in frozen['inputFiles']]
            input_files=[{k:f[k] for k in ['fileName','sha256']} for f in frozen['inputFiles']]
            part=next(f for f in frozen['inputFiles'] if f['fileName'].endswith('.parquet'))
            original['request']['query']=frozen['question']
        else:
            snapshot=next(v for k,v in journal['privateState'].items() if k.startswith('inputs:'))[0]
            part=next(v for v in snapshot['chunks'] if v['fileName'].endswith('.parquet'))
            paths=list(Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').rglob(part['fileId']+'/'+part['fileName']))
            assert len(paths)==1 and hashlib.sha256(paths[0].read_bytes()).hexdigest()==part['sha256']
            asset=api('/data-science/user-assets',multipart=(part['fileName'],paths[0].read_bytes()))
            assert asset['sha256']==part['sha256']
            assets=[asset];input_files=[{'fileName':part['fileName'],'sha256':part['sha256']}]
            for file in [snapshot]+[v for v in snapshot['chunks'] if v['fileName'].endswith('.json')]:
                matches=list(Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').rglob(file['fileId']+'/'+file['fileName']))
                assert len(matches)==1 and hashlib.sha256(matches[0].read_bytes()).hexdigest()==file['sha256']
                delivered=api('/data-science/user-assets',multipart=(file['fileName'],matches[0].read_bytes()))
                assert delivered['sha256']==file['sha256'];assets.append(delivered);input_files.append({'fileName':file['fileName'],'sha256':file['sha256']})
            original_evidence=json.loads((root/'original-input-evidence-private.json').read_text())
            # Preserve producer declarations and layout while excluding old task authority IDs.
            payload=original_evidence['payload'];semantics={k:payload[k] for k in ['inputVersions','qualitySummary','sourceDatasetRef','schemaJson','inputManifest'] if k in payload}
            context_bytes=json.dumps(semantics,ensure_ascii=False,sort_keys=True).encode()
            delivered=api('/data-science/user-assets',multipart=('original-input-context.json',context_bytes));assets.append(delivered)
            input_files.append({'fileName':'original-input-context.json','sha256':hashlib.sha256(context_bytes).hexdigest(),
                'originEvidenceSha256':hashlib.sha256((root/'original-input-evidence-private.json').read_bytes()).hexdigest()})
            # Existing governed catalog declarations resolve business meanings; no values
            # or formulas are invented and the same frozen dictionary goes to both models.
            source_read=json.loads((root/'market_get_day_snapshot-private.json').read_text())
            dictionary_bytes=json.dumps({'dataset':source_read['dataset'],'asset':source_read['asset'],
                'source':'Existing MCP financial catalog; declarations, not proof of observation compliance',
                'readSnapshotSha256':hashlib.sha256((root/'market_get_day_snapshot-private.json').read_bytes()).hexdigest()},ensure_ascii=False,sort_keys=True).encode()
            delivered=api('/data-science/user-assets',multipart=('market-producer-declarations.json',dictionary_bytes));assets.append(delivered)
            input_files.append({'fileName':'market-producer-declarations.json','sha256':hashlib.sha256(dictionary_bytes).hexdigest()})
        workspace=api('/data-science/explorations',{'title':'Runtime OS 真实行情问题回归'})
        cases=[]
        repetitions=int(os.environ.get('RUNTIME_ACCEPTANCE_REPETITIONS','2'))
        assert 2<=repetitions<=3,'Only the documented two/three paired rounds are supported'
        for repeat in range(repetitions):
            for model,label in [('qwen3.8-flash','flash'),('qwen3.7-plus','plus')]:
                workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
                    'fileIds':[a['id'] for a in assets],'question':original['request']['query'],'modelName':model})
                cases.append({'case':label+'-'+str(repeat+1),'model':model,'nodeId':workspace['nodes'][-1]['id']})
        # Keep the timed cohort isolated from unrelated Agent work when requested.
        agent=None;extra=[]
        if not files_only:
            workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
                'fileIds':[],'question':original['request']['query'],'modelName':original['request']['modelName'],'agentId':original['request']['skillId']})
            agent={'case':'actual-agent','model':original['request']['modelName'],'nodeId':workspace['nodes'][-1]['id']}
            workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
                'fileIds':[],'question':'分析华是科技（301218，深交所）在已收集的2026-11-02当日行情中相对于当日同源市场数据的位置。请依据该日已授权完整数据文件计算对比并过滤目标证券，核对证券类型、数据覆盖、价格和金额单位，交付报告和图表。不能用有界样例冒充完整市场，也不能将历史收集值表述为实时价。',
                'modelName':original['request']['modelName'],'agentId':original['request']['skillId']})
            extra=[{'case':'authorized-day-file','model':original['request']['modelName'],'nodeId':workspace['nodes'][-1]['id']}]
        save('scenario-prepared-private.json',{'workspaceId':workspace['id'],'cases':cases,'agent':agent,
            'questionSha256':hashlib.sha256(original['request']['query'].encode()).hexdigest(),
            'frozenOriginTaskId':frozen.get('originTaskId') if frozen_path.exists() else None,
            'extraCases':extra,
            'originalTaskId':journal['contract']['taskId'],'originalInputSha256':part['sha256'],'inputFiles':input_files,'inputAssetIds':[a['id'] for a in assets]})
        print(json.dumps({'prepared':len(cases)+(1 if agent else 0)+len(extra),'inputSha256':part['sha256'],'workspaceId':workspace['id']}),flush=True)
        return

    prepared=json.loads((root/'scenario-prepared-private.json').read_text())
    prefix='/data-science/explorations/'+prepared['workspaceId']+'/nodes/'
    if command=='run':
        def execute(case):
            intent=root/(case['case']+'-intent-private.json')
            if intent.exists():return # Never renew budget/replay an unknown execute.
            save(intent.name,case)
            started=time.monotonic()
            try:
                state=api(prefix+case['nodeId']+'/execute',{})
                save(case['case']+'-response-private.json',{'elapsedMs':round((time.monotonic()-started)*1000),'state':state})
                print(json.dumps({'case':case['case'],'httpReturned':True,'elapsedMs':round((time.monotonic()-started)*1000)}),flush=True)
            except Exception as error:
                save(case['case']+'-error-private.json',{'type':type(error).__name__,'message':str(error),'outcome':'UNKNOWN_POLL_ONLY'})
        with ThreadPoolExecutor(max_workers=2) as pool:
            for offset in range(0,len(prepared['cases']),2):
                wave=prepared['cases'][offset:offset+2]
                list(pool.map(execute,wave))
                # An HTTP timeout does not establish task completion. Isolate every
                # paired wave using authoritative read-only status; never re-execute.
                pending=list(wave)
                while pending:
                    remaining=[]
                    for case in pending:
                        state=api(prefix+case['nodeId']+'/status')
                        node=next(n for n in state['nodes'] if n['id']==case['nodeId'])
                        receipt=node.get('receipt',{})
                        if receipt.get('terminal') is not True and receipt.get('status',node.get('status')) not in ['COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED','TIMED_OUT','REJECTED']:
                            remaining.append(case)
                    pending=remaining
                    if pending:time.sleep(15)
        if prepared.get('agent'):execute(prepared['agent'])
        for case in prepared.get('extraCases',[]):execute(case)
        return

    assert command=='collect'
    spec=importlib.util.spec_from_file_location('existing_collector',root/'collect-coding-progress.py')
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    monitor=json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
    results=[]
    for case in prepared['cases']+([prepared['agent']] if prepared.get('agent') else [])+prepared.get('extraCases',[]):
        state=api(prefix+case['nodeId']+'/status');save(case['case']+'-status-private.json',state)
        node=next(n for n in state['nodes'] if n['id']==case['nodeId']);receipt=node.get('receipt',{})
        row=dict(case,status=receipt.get('status',node.get('status')),resultAvailable=receipt.get('resultAvailable') is True,
                 taskId=receipt.get('taskId'),agentTaskId=receipt.get('agentTaskId'))
        # Agent receipts initially identify the parent task. Only follow Coding IDs
        # actually recorded in this node's server-projected progress events.
        coding_ids={e.get('payload',{}).get('metadata',{}).get('taskId') for e in receipt.get('analysisEvents',[])}
        matching=[n for n in monitor['tasks'] if n['taskId']==row['taskId'] or n['taskId'] in coding_ids
            or n['runId']=='asset-analysis-'+hashlib.sha256(('\0'.join([n['tenantId'],n['userId'],case['nodeId']])).encode()).hexdigest()]
        if matching:
            matching.sort(key=lambda v:v['fencingToken']);task,responses=collector.journal(matching[-1])
            save(case['case']+'-journal-private.json',task);save(case['case']+'-models-private.json',responses)
            models={r['payload']['operationId']:r['payload']['response'] for r in responses}
            usages=[v.get('usage') or {} for v in models.values()]
            usage_known=sum(all(isinstance(v.get(k),(int,float)) for k in ['inputTokens','outputTokens']) for v in usages)
            events=sorted([e for n in matching for e in n['events']],key=lambda e:e['timestamp'])
            tool_times=[e for e in events if e.get('type')=='TOOL_TIMING']
            selected=lambda names:[e['toolDurationMs'] for e in tool_times if e.get('tool') in names and isinstance(e.get('toolDurationMs'),(int,float))]
            known_sum=lambda values:sum(values) if values else None
            model_timings=[m for n in matching for m in n.get('models',[])]
            proposal_ms=[m['durationMs'] for m in model_timings if 'python_execute' in (m.get('actualTools') or []) and isinstance(m.get('durationMs'),(int,float))]
            def latency(predicate):
                event=next((e for e in events if predicate(e)),None)
                return round((datetime.fromisoformat(event['timestamp'].replace('Z','+00:00'))-datetime.fromisoformat(matching[0]['createdAt'].replace('Z','+00:00'))).total_seconds()*1000) if event else None
            feedback=[v for k,v in task.get('privateState',{}).items() if k.startswith('feedback:')]
            checkpoint_calls=[part for message in task.get('sdkCheckpoint',{}).get('messages',[])
                if isinstance(message.get('content'),list) for part in message['content'] if part.get('type')=='tool-call']
            seen_calls=set();duplicates={};proposals=[]
            for call in checkpoint_calls:
                tool=call.get('toolName');arguments=call.get('input',{})
                if tool in ['code_read','input_read','artifact_read','read_files']:
                    signature=json.dumps([tool,arguments],sort_keys=True,ensure_ascii=False)
                    if signature in seen_calls:duplicates[tool]=duplicates.get(tool,0)+1
                    seen_calls.add(signature)
                if tool=='python_execute':proposals.append({'mode':'source' if 'source' in arguments else 'repair' if 'repair' in arguments else 'other',
                    'sourceBytes':len(arguments['source'].encode()) if isinstance(arguments.get('source'),str) else None})
            purpose_ms={}
            for model_timing in model_timings:
                if isinstance(model_timing.get('durationMs'),(int,float)) and model_timing.get('purpose'):
                    purpose=model_timing['purpose'];purpose_ms[purpose]=purpose_ms.get(purpose,0)+model_timing['durationMs']
            operation_ids=[k for k in task.get('operations',{}) if k.startswith('model-')]
            snapshots=next((v for k,v in task.get('privateState',{}).items() if k.startswith('inputs:')),[])
            manifests=[]
            for snapshot in snapshots:
                if snapshot.get('fileName')!='manifest.json':continue
                paths=list(Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').rglob(snapshot['fileId']+'/manifest.json'))
                assert len(paths)==1 and hashlib.sha256(paths[0].read_bytes()).hexdigest()==snapshot['sha256']
                manifest=json.loads(paths[0].read_text());manifests.append({'logicalContentSha256':manifest.get('logicalContentSha256'),
                    'rowCount':manifest.get('rowCount'),'format':manifest.get('format'),'logicalBytes':manifest.get('logicalBytes')})
            finished=matching[-1].get('finishedAt')
            runtime=task['contract']['runtimeContext'];model_identity=runtime.get('model',{});execution_profile=runtime.get('executionProfile',{})
            row.update(taskId=task['contract']['taskId'],status=task['outcome']['status'],budget=task['contract']['budget'],
                actualModel=model_identity.get('selectedModel'),executionModel=execution_profile.get('selectedModel'),
                reasonCode=task['outcome'].get('reasonCode'),summary=task['outcome'].get('summary'),
                inputSnapshotRef=task['contract']['inputSnapshotRef'],permissions=task['contract']['authorization'],
                logicalInputs=manifests,modelCalls=len(operation_ids),resolvedModelCalls=len(models),
                unknownModelCalls=len(set(operation_ids)-set(models)),usageKnownCalls=usage_known,
                unknownUsageCalls=max(0,len(operation_ids)-usage_known),
                repeatedReadRequests=duplicates,codeProposals=proposals,modelPurposeMs=purpose_ms,
                tokenUsageComplete=len(operation_ids)==usage_known,
                inputTokens=sum(v['inputTokens'] for v in usages if isinstance(v.get('inputTokens'),(int,float))),
                outputTokens=sum(v['outputTokens'] for v in usages if isinstance(v.get('outputTokens'),(int,float))),
                durationMs=round((datetime.fromisoformat(finished.replace('Z','+00:00'))-datetime.fromisoformat(matching[0]['createdAt'].replace('Z','+00:00'))).total_seconds()*1000) if finished else None,
                pythonExecutions=[{k:v.get(k) for k in ['executionId','status','exitCode','durationMs','cpuTimeMs','maxRssBytes','codeHash','resultAvailable','outputStatus','validationStatus']} for v in feedback],
                phases={'planningMs':None,'dataDiscoveryMs':known_sum(selected({'market_search','market_resolve_security','market_get_day_snapshot','market_get_history','web_search','web_fetch','knowledge_search'})),
                    'fileReadMs':known_sum(selected({'input_read','read_files'})),
                    'codeGenerationMs':None,'toolExecutionMs':known_sum([e['toolDurationMs'] for e in tool_times if isinstance(e.get('toolDurationMs'),(int,float))]),
                    'pythonMs':known_sum([v['durationMs'] for v in feedback if isinstance(v.get('durationMs'),(int,float))]),
                    'reportGenerationMs':None,'deliveryValidationMs':known_sum([e['validationDurationMs'] for e in tool_times if isinstance(e.get('validationDurationMs'),(int,float))]),
                    'artifactReviewMs':known_sum(selected({'artifact_read','report_submit'})),
                    'codeProposalModelMs':known_sum(proposal_ms),
                    'firstPlanLatencyMs':latency(lambda e:e.get('type')=='PLAN_READY'),
                    'firstCodeLatencyMs':latency(lambda e:e.get('type')=='CODE_READY'),
                    'firstReportLatencyMs':latency(lambda e:e.get('type')=='TOOL_FINISHED' and e.get('tool')=='python_execute' and e.get('resultAvailable') is True)},
                measurementPolicy='null=UNKNOWN; latencies start at Node task acceptance; tool/Python phases overlap, not additive; codeProposalModelMs includes reasoning and provider IO; pure planning/code/report generation are not separately observable',
                requestModelParameters=[{'operationId':key,'maxOutputTokens':value.get('maxOutputTokens'),
                    'modelInfo':value.get('modelInfo')} for key,value in models.items()],
                frozenExecutionProfile=runtime.get('executionProfile'),
                models=[{k:m.get(k) for k in ['ordinal','status','selectedModel','providerModel','protocol','durationMs','actualTools','usage','purpose','queueTimeMs','firstTokenMs','generationTimeMs','retryTimeMs','requestBytes','responseBytes','messageCount','toolCount','repeatedContextBytes','duplicateContextBytes','avoidableRepeatedContextBytes','contextSizeBefore','contextSizeAfter','compactionCount','contextChangeReason']} for m in model_timings])
        report=receipt.get('visualReport') or {}
        row['delivery']={k:report[k] for k in ['status','analysisStatus','requirementStatus','artifactValidationStatus','chartValidationStatus','availableChartCount'] if k in report}
        row['chartCount']=len(report.get('charts',[]));results.append(row)
        response_path=root/(case['case']+'-response-private.json')
        row['httpDurationMs']=json.loads(response_path.read_text()).get('elapsedMs') if response_path.exists() else None
        row['submissionToDeliveredReportMs']=row['httpDurationMs'] if row['status']=='COMPLETED' and row['resultAvailable'] else None
    save('real-scenario-results.json',{'originalInputSha256':prepared['originalInputSha256'],'cases':results})
    print(json.dumps([{'case':r['case'],'status':r['status'],'resultAvailable':r['resultAvailable'],'modelCalls':r.get('modelCalls'),'chartCount':r['chartCount']} for r in results]),flush=True)


if __name__=='__main__':
    main()
