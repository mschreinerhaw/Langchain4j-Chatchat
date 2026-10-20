"""Export only this acceptance's scoped answers and submitted source via authenticated APIs."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request

os.umask(0o077)
root = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
root.chmod(0o700)
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None
def http(path,body=None):
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with client.open(request,timeout=30) as response: value=json.load(response)
    if value.get('code') not in (None,0,200): raise RuntimeError('Artifact API rejected request')
    return value['data']
token=http('/enterprise/auth/login',json.loads((root.parent/'cline-monitor-20261019/stage-auth.json').read_text()))['token']
for case_path in sorted(root.glob('scenario[12]-final.json')):
    case=json.loads(case_path.read_text())
    summary={'case':case_path.stem,'code':[]}
    for index,submission in enumerate(case.get('codeSubmissions',[]),1):
        path='/agent/runtime/runs/'+case['runId']+'/coding/'+submission['taskId']+'/'+submission['operationId']+'/code'
        code=http(path)
        digest=hashlib.sha256(code['content'].encode()).hexdigest()
        if not code.get('redacted') and digest!=submission['sha256']: raise RuntimeError('Archived source hash differs from submitted program')
        file=root/(case_path.stem+'-code-'+str(index)+'.py')
        file.write_text(code['content'])
        summary['code'].append({'file':file.name,'sha256':digest,'redacted':code.get('redacted',False),
            'lines':len(code['content'].splitlines()),'operationId':submission['operationId']})
    if case.get('parent',{}).get('answerAvailable'):
        answer=http('/published-agents/'+case['agentId']+'/questions/'+case['taskId']+'/answer')
        text=answer.get('answer') or ''
        (root/(case_path.stem+'-report.md')).write_text(text)
        blocks=re.findall(r'```(?:sql|mysql|postgresql|postgres|hive)\s*\n(.*?)```',text,re.DOTALL|re.IGNORECASE)
        sql_files=[]
        for index,block in enumerate(blocks,1):
            file=root/(case_path.stem+'-sql-'+str(index)+'.sql')
            file.write_text(block)
            sql_files.append({'file':file.name,'blockIndex':index,'sha256':hashlib.sha256(block.encode()).hexdigest()})
        summary['sqlFiles']=sql_files
        summary.update(answerSha256=hashlib.sha256(text.encode()).hexdigest(),answerCharacters=len(text),sqlBlocks=len(blocks))
    (root/(case_path.stem+'-artifacts.json')).write_text(json.dumps(summary,indent=2))
    print(json.dumps(summary),flush=True)
