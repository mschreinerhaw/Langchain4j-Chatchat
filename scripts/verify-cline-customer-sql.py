"""Real Cline metadata-only regression. Never executes generated SQL or grants data permissions."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True)
    parser.add_argument('--identity',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--resume',action='store_true')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    identity=json.loads(args.identity.read_text(encoding='utf-8'))
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}));token=None
    def call(path,method='GET',body=None):
        headers={'Content-Type':'application/json','X-Workspace-Id':'data-science'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(args.base+'/api/v1'+path,method=method,headers=headers,
            data=None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8'))
        try:
            with client.open(request,timeout=180) as response:result=json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise RuntimeError(path+': HTTP '+str(error.code)+' '+error.read().decode('utf-8')[:1500]) from error
        if result['code']!=200:raise RuntimeError(path+': '+str(result.get('message')))
        return result.get('data')
    session=call('/enterprise/auth/login','POST',{'username':identity['username'],'password':identity['password']});token=session['token']
    # Never include the login token or password in acceptance artifacts.
    project=identity['projectId'];root='/data-science/sql/projects/'+project
    started=time.monotonic()
    if args.resume:
        generation=json.loads((args.output/'request.json').read_text(encoding='utf-8'))['generationId']
    else:
        assets=call(root+'/metadata/assets?serviceId='+urllib.parse.quote(identity['serviceId']))['metadata']
        assert assets['status']=='SUCCEEDED'
        # Oracle-compatible catalog selection is source/dialect scope, not a list of business tables.
        compatible=[a for a in assets['items'] if str(a.get('sourceDialect','')).lower() in ['oracle','dm']]
        oracle=[a for a in compatible if str(a.get('sourceDialect','')).lower()=='oracle']
        if oracle:compatible=oracle
        if not compatible:raise RuntimeError('No Oracle-compatible catalog available; select the customer datasource explicitly')
        context=call(root+'/metadata/contexts','POST',{'purpose':'QUERY','targetDialect':'oracle','bindings':[
            {'serviceId':identity['serviceId'],'assetRef':a['assetRef'],'role':'SOURCE'} for a in compatible]})
        (args.output/'scope.json').write_text(json.dumps({'metadataAssetsReadableWithoutExecutionGrants':True,'preselectedTables':[],
            'bindings':context['bindings'],'targetDialect':'oracle','customerNumber':'070200046604'},ensure_ascii=False,indent=2),encoding='utf-8')
        prompt='请为客户号字符串 070200046604 生成交易、资产和盈亏查询 SQL。先自主探索当前数据源的业务元数据、字段含义、指标定义和已知关联；没有提前指定任何表。使用真实发现的表字段，客户号保留前导零。对缺少定义的指标和未确认关联明确标注，不要臆造公式或关联。按 Oracle/达梦兼容方言生成完整 SQL 提案，不执行 SQL，也不读取客户业务明细。'
        prompt+='请检索现有业务术语及指标定义目录，并查询关联证据；未收录的定义请保留检索结果并明确说明。检索和深入顺序由你自主决定。'
        prepared=call(root+'/generations','POST',{'prompt':prompt,'action':'generate','dialect':'oracle',
            'metadataBindingContextId':context['discoveryId'],'mappingIds':[],'referenceIds':[],'mappingDocumentIds':[]})
        generation=prepared['generationId']
        (args.output/'request.json').write_text(json.dumps({'generationId':generation,'projectId':project,
            'prompt':prompt,'preselectedTables':[]},ensure_ascii=False,indent=2),encoding='utf-8')
        try:call(root+'/generations/'+generation+'/coding','POST',{})
        except OSError:
            # Inspect the durable generation before retrying the same identity.
            if not call(root+'/generations/'+generation).get('task'):
                call(root+'/generations/'+generation+'/coding','POST',{})
    deadline=time.monotonic()+1020
    while True:
        result=call(root+'/generations/'+generation)
        (args.output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        task=result.get('task') or {};status=task.get('status','UNKNOWN')
        progress=(result.get('metadataExploration')or{}).get('codingExploration')or{}
        print(json.dumps({'status':status,'tools':progress.get('toolCalls',0),'validationAttempts':progress.get('validationAttempts',0),'elapsedSeconds':round(time.monotonic()-started,1)}),flush=True)
        if status in ['SUCCESS','PARTIAL','FAILED','CANCELLED','REJECTED','MODEL_BUDGET_EXHAUSTED','TIME_BUDGET_EXHAUSTED']:
            summary={'mockedRequests':False,'customerNumber':'070200046604','dataExecutionGrantsAdded':False,'sqlExecuted':False,
                'taskId':task.get('taskId'),'generationId':generation,'status':status,'elapsedSeconds':round(time.monotonic()-started,2),
                'fileCount':len(result.get('files')or[]),'validation':result.get('validation'),'diagnostics':progress}
            (args.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
            if status!='SUCCESS' or not progress.get('finalSqlValid'):
                raise RuntimeError('Real customer regression did not reach a validated SQL proposal: '+status)
            source='\n'.join(file.get('content','') for file in result.get('files')or[])
            if '070200046604' not in source or 'FIELDS_CHECKED' not in str(result.get('validation')):
                raise RuntimeError('Validated output does not retain the requested customer filter and checked fields')
            if not any(row.get('tool')=='workspace_metadata' for row in progress.get('operations',[])):
                raise RuntimeError('No autonomous metadata operation was recorded')
            return
        if time.monotonic()>deadline:raise TimeoutError('Regression remains active; preserve its evidence and resume polling')
        time.sleep(3)


if __name__=='__main__':
    main()
