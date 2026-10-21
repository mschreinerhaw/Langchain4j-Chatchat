"""Read-only runtime retention checks and temporary user-file pagination fixtures on the stage."""
import hashlib,json,os,urllib.request,urllib.parse,uuid
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/user-space-pagination-20261021';root.mkdir(mode=0o700,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,method=method,
        headers={'Content-Type':content_type,'Accept':'*/*' if raw else 'application/json','X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if globals().get('token') else {})})
    with client.open(request,timeout=60) as response:
        value=response.read()
        if raw:return value
        result=json.loads(value);assert result.get('code',0) in (0,200)
        return result.get('data')
auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token=auth['token'];(root/'browser-auth-private.json').write_text(json.dumps(auth))
route='/data-science/user-assets';checks={};uploads=[];folder=None;success=False
prefix='paging-'+uuid.uuid4().hex[:10]
def user_file(file):
    return file['kind']=='DOCUMENT' and not file.get('taskId') and not file.get('runId') and (file.get('sourceRef')=='upload' or file.get('sourceRef','').startswith('upload:'))
try:
    runtime=[file for file in call(route+'?personalOnly=false&limit=100') if not user_file(file)]
    assert runtime,'Stage must contain existing runtime evidence to verify hiding and retention'
    before={file['id']:file['sha256'] for file in runtime}
    for index in range(13):
        name=f'{prefix}-{index:02d}.csv';boundary=uuid.uuid4().hex
        content=b'category,value\nfirst,12.25\n'
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/csv\r\n\r\n').encode()+content+(f'\r\n--{boundary}--\r\n').encode()
        uploads.append(call(route,body,content_type='multipart/form-data; boundary='+boundary))
    first=call(route+'/browser/files?page=1&pageSize=10&sort=name&q='+prefix)
    second=call(route+'/browser/files?page=2&pageSize=10&sort=name&q='+prefix)
    checks['serverPagesHaveExactTotal']=first['total']==second['total']==13 and len(first['items'])==10 and len(second['items'])==3
    ids=[item['sourceId'] for item in first['items']+second['items']]
    checks['pagesHaveNoDuplicatesOrMissingFiles']=len(set(ids))==13 and set(ids)=={file['id'] for file in uploads}
    checks['globalSortBeforePaging']=[item['original']['fileName'] for item in first['items']+second['items']]==sorted(file['fileName'] for file in uploads)
    folder=call(route+'/browser/folders',{'parentId':'data','name':prefix})
    call(route+'/browser/locations',{'folderId':folder['id'],'entries':[{'source':'asset','sourceId':file['id']} for file in uploads]},'PUT')
    grouped=call(route+'/browser/files?folder='+folder['id']+'&page=2&pageSize=10&sort=name')
    checks['folderTotalsAndPaginationMatch']=grouped['total']==13 and len(grouped['items'])==3 and grouped['folderCounts'][folder['id']]==13
    visible=[];page=1
    while True:
        result=call(route+'/browser/files?page='+str(page)+'&pageSize=100')
        visible.extend(result['items'])
        if page*100>=result['total']:break
        page+=1;assert page<=100
    visible_ids={item['sourceId'] for item in visible if item['source']=='asset'}
    checks['runtimeHiddenFromAllPagesAndTotal']=not visible_ids.intersection(before) and len(visible)==result['total']==result['totalFiles']
    checks['onlyUserOriginalsInBrowser']=all(user_file(item['original']) for item in visible if item['source']=='asset')
    checks['defaultCatalogHidesRuntime']=all(user_file(file) for file in call(route+'?limit=100'))
    checks['runtimeMetadataRetained']=all(call(route+'/'+file_id)['sha256']==digest for file_id,digest in before.items())
    sample=runtime[0];checks['runtimeOriginalBytesRetained']=hashlib.sha256(call(route+'/'+sample['id']+'/content',raw=True)).hexdigest()==sample['sha256']
    hits=call(route+'/knowledge/search?q='+urllib.parse.quote(sample['fileName'])+'&personalOnly=true')
    checks['personalKnowledgeSearchHidesRuntime']=all(hit['fileId'] not in before for hit in hits)
    success=all(checks.values());assert success,checks
finally:
    for file in uploads:call(route+'/'+file['id'],method='DELETE')
    if folder:call(route+'/browser/folders/'+folder['id'],method='DELETE')
    report={'status':'PASSED' if success else 'FAILED','checks':checks,'runtimeFilesChecked':len(locals().get('runtime',[])),
        'temporaryResourcesRemoved':True,'modelCallsSubmitted':0,'scriptsExecuted':0}
    (root/'api-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
