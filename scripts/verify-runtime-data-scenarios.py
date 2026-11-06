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
        snapshot=next(v for k,v in journal['privateState'].items() if k.startswith('inputs:'))[0]
        part=next(v for v in snapshot['chunks'] if v['fileName'].endswith('.parquet'))
        paths=list(Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').rglob(part['fileId']+'/'+part['fileName']))
        assert len(paths)==1 and hashlib.sha256(paths[0].read_bytes()).hexdigest()==part['sha256']
        asset=api('/data-science/user-assets',multipart=('actual-market-input.parquet',paths[0].read_bytes()))
        assert asset['sha256']==part['sha256']
        workspace=api('/data-science/explorations',{'title':'Runtime OS 真实行情问题回归'})
        cases=[]
        for repeat in range(2):
            for model,label in [('qwen3.8-flash','flash'),('qwen3.7-plus','plus')]:
                workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
                    'fileIds':[asset['id']],'question':original['request']['query'],'modelName':model})
                cases.append({'case':label+'-'+str(repeat+1),'model':model,'nodeId':workspace['nodes'][-1]['id']})
        # Same original Agent/question, independent from the frozen-file performance cohort.
        workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
            'fileIds':[],'question':original['request']['query'],'modelName':original['request']['modelName'],'agentId':original['request']['skillId']})
        agent={'case':'actual-agent','model':original['request']['modelName'],'nodeId':workspace['nodes'][-1]['id']}
        workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
            'fileIds':[],'question':'请检索公开网页，核对华是科技的证券代码、交易所和官方来源链接。区分搜索摘要、原文与权威行情，未取得当前报价时明确说明。',
            'modelName':'qwen3.8-omni-flash','agentId':original['request']['skillId']})
        search={'case':'authorized-internet','model':'qwen3.8-omni-flash','nodeId':workspace['nodes'][-1]['id']}
        workspace=api('/data-science/explorations/'+workspace['id']+'/nodes',{'version':workspace['version'],'parentId':'',
            'fileIds':[],'question':'分析华是科技（301218，深交所）在已收集的2026-11-02当日行情中相对于当日同源市场数据的位置。请依据该日已授权完整数据文件计算对比并过滤目标证券，核对证券类型、数据覆盖、价格和金额单位，交付报告和图表。不能用有界样例冒充完整市场，也不能将历史收集值表述为实时价。',
            'modelName':original['request']['modelName'],'agentId':original['request']['skillId']})
        daily={'case':'authorized-day-file','model':original['request']['modelName'],'nodeId':workspace['nodes'][-1]['id']}
        save('scenario-prepared-private.json',{'workspaceId':workspace['id'],'cases':cases,'agent':agent,
            'extraCases':[search,daily],
            'originalTaskId':journal['contract']['taskId'],'originalInputSha256':part['sha256'],'inputAssetId':asset['id']})
        print(json.dumps({'prepared':len(cases)+3,'inputSha256':part['sha256'],'workspaceId':workspace['id']}),flush=True)
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
        with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(execute,prepared['cases']))
        execute(prepared['agent'])
        for case in prepared.get('extraCases',[]):execute(case)
        return

    assert command=='collect'
    spec=importlib.util.spec_from_file_location('existing_collector',root/'collect-coding-progress.py')
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    monitor=json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
    results=[]
    for case in prepared['cases']+[prepared['agent']]+prepared.get('extraCases',[]):
        state=api(prefix+case['nodeId']+'/status');save(case['case']+'-status-private.json',state)
        node=next(n for n in state['nodes'] if n['id']==case['nodeId']);receipt=node.get('receipt',{})
        row=dict(case,status=receipt.get('status',node.get('status')),resultAvailable=receipt.get('resultAvailable') is True,
                 taskId=receipt.get('taskId'),agentTaskId=receipt.get('agentTaskId'))
        matching=[n for n in monitor['tasks'] if n['taskId']==row['taskId'] or n['runId']=='asset-analysis-'+hashlib.sha256(('\0'.join([n['tenantId'],n['userId'],case['nodeId']])).encode()).hexdigest()]
        if matching:
            matching.sort(key=lambda v:v['fencingToken']);task,responses=collector.journal(matching[-1])
            save(case['case']+'-journal-private.json',task);save(case['case']+'-models-private.json',responses)
            models={r['payload']['operationId']:r['payload']['response'] for r in responses}
            events=sorted([e for n in matching for e in n['events']],key=lambda e:e['timestamp'])
            tool_times=[e for e in events if e.get('type')=='TOOL_TIMING']
            selected=lambda names:[e['toolDurationMs'] for e in tool_times if e.get('tool') in names and isinstance(e.get('toolDurationMs'),(int,float))]
            known_sum=lambda values:sum(values) if values else None
            feedback=[v for k,v in task.get('privateState',{}).items() if k.startswith('feedback:')]
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
            row.update(taskId=task['contract']['taskId'],status=task['outcome']['status'],budget=task['contract']['budget'],
                inputSnapshotRef=task['contract']['inputSnapshotRef'],permissions=task['contract']['authorization'],
                logicalInputs=manifests,modelCalls=len(operation_ids),resolvedModelCalls=len(models),
                unknownModelCalls=len(set(operation_ids)-set(models)),usageKnownCalls=sum(1 for v in models.values() if v.get('usage')),
                tokenUsageComplete=len(operation_ids)==sum(1 for v in models.values() if v.get('usage')),
                inputTokens=sum(v.get('usage',{}).get('inputTokens',0) for v in models.values()),
                outputTokens=sum(v.get('usage',{}).get('outputTokens',0) for v in models.values()),
                durationMs=round((datetime.fromisoformat(finished.replace('Z','+00:00'))-datetime.fromisoformat(matching[0]['createdAt'].replace('Z','+00:00'))).total_seconds()*1000) if finished else None,
                pythonExecutions=[{k:v.get(k) for k in ['executionId','status','exitCode','durationMs','cpuTimeMs','maxRssBytes','codeHash','resultAvailable','outputStatus','validationStatus']} for v in feedback],
                phases={'planningMs':None,'dataDiscoveryMs':known_sum(selected({'market_search','market_resolve_security','market_get_day_snapshot','market_get_history','web_search','web_fetch','knowledge_search'})),
                    'fileReadMs':known_sum(selected({'input_read','read_files'})),
                    'codeGenerationMs':None,'toolExecutionMs':known_sum([e['toolDurationMs'] for e in tool_times if isinstance(e.get('toolDurationMs'),(int,float))]),
                    'pythonMs':known_sum([v['durationMs'] for v in feedback if isinstance(v.get('durationMs'),(int,float))]),
                    'reportGenerationMs':None,'deliveryValidationMs':known_sum(selected({'artifact_read','report_submit'}))},
                measurementPolicy='null=UNKNOWN; tool/Python phases overlap, not additive; model reasoning/report generation not separately observable',
                models=[{'ordinal':m['ordinal'],'status':m['status'],'durationMs':m.get('durationMs'),'actualTools':m.get('actualTools'),'usage':m.get('usage')} for n in matching for m in n.get('models',[])])
        report=receipt.get('visualReport') or {}
        row['delivery']={k:report[k] for k in ['status','artifactValidationStatus','chartValidationStatus','availableChartCount'] if k in report}
        row['chartCount']=len(report.get('charts',[]));results.append(row)
    save('real-scenario-results.json',{'originalInputSha256':prepared['originalInputSha256'],'cases':results})
    print(json.dumps([{'case':r['case'],'status':r['status'],'resultAvailable':r['resultAvailable'],'modelCalls':r.get('modelCalls'),'chartCount':r['chartCount']} for r in results]),flush=True)


if __name__=='__main__':
    main()
