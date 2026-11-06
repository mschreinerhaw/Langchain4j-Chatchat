"""Run one bounded native Cline task or collect it; never implements a search loop.

Usage: SCRIPT run|collect search|day RELEASE_DIR. Uses the already Internet-enabled
model and explicitly selected, already authorized Agent. Shared settings stay unchanged.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import sys
import time
import urllib.request


def main():
    os.umask(0o077)
    command,kind,directory=sys.argv[1:4]
    assert command in ['run','collect','resume-preflight'] and kind in ['search','day']
    root=Path(directory).resolve()
    token=json.loads((root/'auth-private.json').read_text())['token']
    original=json.loads((root/'parent-run-private.json').read_text())['request']
    prepared=json.loads((root/'scenario-prepared-private.json').read_text())
    request_id='real-'+kind+'-resources-20261107-'+root.name.rsplit('-',1)[-1]
    prefix='autonomous-'+kind
    def api(path,body=None):
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,
            data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),
            headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        with urllib.request.urlopen(request,timeout=1800) as response:value=json.load(response)
        assert value.get('code')==200,value.get('message')
        return value['data']
    def save(name,value):
        (root/(prefix+name)).write_text(json.dumps(value,ensure_ascii=False,indent=2))
    if command in ['run','resume-preflight']:
        resume=command=='resume-preflight'
        if resume:
            assert json.loads((root/'preflight-admission-proof.json').read_text())['knownBeforeTaskAdmission'] is True
            assert json.loads((root/(prefix+'-error-private.json')).read_text())['message'].startswith('HTTP Error 507')
            assert not (root/(prefix+'-response-private.json')).exists() and not (root/(prefix+'-preflight-recovery-intent-private.json')).exists()
        else:assert not (root/(prefix+'-intent-private.json')).exists(),'Existing task must be polled, never replayed'
        material=next(identity for file,identity in zip(prepared['inputFiles'],prepared['inputAssetIds'])
            if file['fileName']=='market-producer-declarations.json')
        questions={
            'search':'请检索公开网页，核对原问题中华是科技的证券代码、交易所和官方来源链接。'
                '附件只是已有生产者目录声明，不是证券代码映射证据；请自主检索和读取决定性公开来源。'
                '保留来源URL、检索时间及证据；区分摘要、原文和权威行情，不把搜索摘要当成报价。'
                '缺少当前行情时明确说明，不猜测实时价。',
            'day':'核对华是科技（301218，深交所）在2026-11-02已收集行情中的表现，并计算其在当日同源数据中的位置。'
                '附件是已有生产者目录声明，不含实际行情。请自主发现和读取已授权数据；本次核对范围是'
                'market_quote_daily数据集、source_id=38、交易日2026-11-02。'
                '需要取得该日完整授权行情文件，在受控Python中验证快照并过滤目标证券和相同市场、证券类型，'
                '计算目标涨跌幅的同源样本排名、有效样本数和并列处理，提交计算报告。'
                '核对字段定义、覆盖、单位、大小及资源限制。单日存储快照不证明全交易所采集完整，'
                '不要把少量查询样例当全量，也不要把历史快照表述成实时价。'
        }
        body={'requestId':request_id,'fileIds':[material],'contextIds':[],
              'instruction':questions[kind],'modelName':'qwen3.8-omni-flash','agentId':original['skillId']}
        scenario=root/'acceptance-scenario-private.json'
        if scenario.exists():
            definition=json.loads(scenario.read_text())
            assert set(definition)<= {'scenario','instruction','modelName','fileIds'}
            assert definition['scenario'] in ['A','B','C','D']
            assert isinstance(definition['instruction'],str) and definition['instruction'].strip()
            assert '?????' not in definition['instruction'] and '\ufffd' not in definition['instruction'],'Corrupted acceptance objective; do not dispatch'
            assert definition.get('modelName','qwen3.8-omni-flash') in ['qwen3.8-omni-flash','qwen3.8-flash','qwen3.7-plus']
            body.update({k:definition[k] for k in ['instruction','modelName','fileIds'] if k in definition})
            assert body['fileIds'] and all(isinstance(value,str) and len(value)==64 for value in body['fileIds'])
        if resume:
            assert json.loads((root/(prefix+'-intent-private.json')).read_text())['request']==body,'Preflight recovery cannot change the request'
            import shutil
            shutil.copy2(root/(prefix+'-error-private.json'),root/(prefix+'-preflight-error-private.json'))
        save('-preflight-recovery-intent-private.json' if resume else '-intent-private.json',{'request':body,'startedAt':time.time(),'policy':'One native task; known 507 pre-admission recovery only; no shared configuration edits or budget override'})
        started=time.monotonic()
        try:
            receipt=api('/data-science/user-assets/analyses',body)
            save('-response-private.json',{'elapsedMs':round((time.monotonic()-started)*1000),'receipt':receipt})
            print(json.dumps({'kind':kind,'status':receipt.get('status'),'resultAvailable':receipt.get('resultAvailable'),'taskId':receipt.get('taskId')}))
        except Exception as error:
            save('-error-private.json',{'type':type(error).__name__,'message':str(error),'outcome':'UNKNOWN_POLL_ONLY'})
            raise
        return
    spec=importlib.util.spec_from_file_location('collector',root/'collect-coding-progress.py')
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    monitor=json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
    intent=json.loads((root/(prefix+'-intent-private.json')).read_text())
    run='asset-analysis-'+hashlib.sha256(('\0'.join([original['tenantId'],original['userId'],request_id])).encode()).hexdigest()
    matches=[n for n in monitor['tasks'] if n['runId']==run]
    assert matches,'No admitted Coding task: inspect capacity/storage before deciding whether anything ran'
    task,receipts=collector.journal(sorted(matches,key=lambda n:n['fencingToken'])[-1])
    save('-journal-private.json',task);save('-models-private.json',receipts)
    tools=[call['name'] for receipt in receipts for call in receipt['payload']['response'].get('toolCalls',[])]
    feedback=[v for k,v in task.get('privateState',{}).items() if k.startswith('feedback:')]
    snapshots=[v for k,v in task.get('privateState',{}).items() if k.startswith('inputs:')]
    external=[];day_reads=[]
    for message in task.get('sdkCheckpoint',{}).get('messages',[]):
        if message.get('role')!='tool' or not isinstance(message.get('content'),list):continue
        for part in message['content']:
            tool=part.get('toolName');value=part.get('output',{})
            if not isinstance(value,dict):continue
            if tool in ['web_search','web_fetch']:
                urls={v for k,v in value.items() if k in ['url','source_url'] and isinstance(v,str)}
                for item in value.get('items',[]):
                    if isinstance(item,dict):urls.update(v for k,v in item.items() if k in ['url','source_url'] and isinstance(v,str))
                external.append({'tool':tool,'evidence_id':value.get('evidence_id'),'retrieved_at':value.get('retrieved_at'),
                    'status':value.get('status'),'sourceUrls':sorted(urls),
                    'sourceRecords':[{k:item[k] for k in ['url','source_url','evidence_id','retrieved_at','published_at'] if k in item}
                        for item in value.get('items',[]) if isinstance(item,dict)],
                    'authority':'SEARCH_SUMMARIES_ARE_NOT_VERIFIED_QUOTES'})
            if tool=='market_get_day_snapshot':
                day_reads.append({**{k:value[k] for k in ['status','dataset','evidence_id','snapshot','calculationInput'] if k in value},
                    'rawItemsInjectedIntoModel':'items' in value})
    proof={'kind':kind,'taskId':task['contract']['taskId'],'status':task['outcome']['status'],
        'requestObjectivePreserved':intent['request']['instruction'] in task['contract']['objective']['instruction'],
        'requestInstructionSha256':hashlib.sha256(intent['request']['instruction'].encode()).hexdigest(),
        'reasonCode':task['outcome'].get('reasonCode'),'toolsCalled':tools,
        'model':{k:v for k,v in task['contract']['runtimeContext'].get('model',{}).items() if k in ['selectedModel','providerModel','protocol']},'budget':task['contract']['budget'],
        'allowedTools':task['contract']['authorization']['allowedTools'],
        'internetProfile':task['contract']['runtimeContext'].get('internetSearch'),
        'internetAvailability':task['contract']['runtimeContext'].get('internetAvailability'),
        'externalEvidence':external,'daySnapshotReceipts':day_reads,
        'resultDatasetRef':task['outcome'].get('resultDatasetRef'),
        'pythonExecutions':[{k:v.get(k) for k in ['executionId','exitCode','durationMs','resultAvailable','outputStatus','inputDataRefs']} for v in feedback],
        'frozenInputVersions':[[{k:s.get(k) for k in ['fileId','fileName','sha256','rowCount','size','sourceDatasetRef']} for s in values] for values in snapshots]}
    if (root/'acceptance-scenario-private.json').exists():
        proof['scenario']=json.loads((root/'acceptance-scenario-private.json').read_text())['scenario']
    # Preserve ordered calls and bounded scalar receipts, not source rows/snippets.
    # A model request alone does not prove a zero-row response or a fallback.
    trace=[]
    for message in task.get('sdkCheckpoint',{}).get('messages',[]):
        if not isinstance(message.get('content'),list):continue
        for part in message['content']:
            if part.get('type')=='tool-call':
                trace.append({'event':'CALL','tool':part.get('toolName'),'callId':part.get('toolCallId'),
                    'arguments':{k:v for k,v in part.get('input',{}).items() if k in ['dataset','filters','query','source_id','trade_date','start_date','end_date','history_mode','exchange','limit']}})
            elif part.get('type')=='tool-result' and isinstance(part.get('output'),dict):
                value=part['output']; trace.append({'event':'RESULT','tool':part.get('toolName'),'callId':part.get('toolCallId'),
                    **{k:value[k] for k in ['status','count','complete','errorCode','evidence_id','resultAvailable','outputStatus','executorStopped','exitCode'] if k in value},
                    **({'itemsCount':len(value['items'])} if isinstance(value.get('items'),list) else {})})
    proof['orderedToolTrace']=trace
    save('-proof.json',proof)
    print(json.dumps({'kind':kind,'status':proof['status'],'toolsCalled':tools,'pythonExecutions':len(feedback)}))


if __name__=='__main__':main()
