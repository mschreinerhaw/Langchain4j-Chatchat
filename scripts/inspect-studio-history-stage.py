"""Inspect only this run's public Studio history projection lengths and code equality."""
import json
from pathlib import Path
import urllib.request
import re
import urllib.error
root=Path('/opt/runtime-os-docker-stage/evidence/runtime-os-studios-20261021')
auth=json.loads((root/'browser-auth-private.json').read_text())
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(path):
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,headers={'Authorization':'Bearer '+auth['token']})
    with client.open(request,timeout=30) as response:return json.load(response)['data']
for label in ('sql-fixed','python-fixed'):
    path=root/(label+'-private.json')
    if not path.exists():continue
    case=json.loads(path.read_text());state=get(case['endpoint'])
    try:history=get('/conversations/'+case['conversationId'])
    except urllib.error.HTTPError:
        headers=get('/conversations/user/'+auth['user']['id']+'/summaries?limit=100')
        possible=[h for h in headers if h.get('agentName')=='PythonStudio']
        print(json.dumps({'pythonHeaders':[{'id':h['id'],'status':h['status']} for h in possible]}));continue
    (root/(label+'-history-private.json')).write_text(json.dumps(history,ensure_ascii=False,indent=2))
    files=state.get('files',state.get('output',{}).get('files',[]))
    for message in history.get('messages',[]):
        if message.get('role')=='assistant':
            extracted=re.findall(r'````(?:sql|python)\n([\s\S]*?)\n````',message.get('content') or '')
            print(json.dumps({'fencedCodeLengths':[len(text) for text in extracted],
                'codeStarts':[repr(text[:100]) for text in extracted],
                'actualCodeStarts':[repr(file['content'][:100]) for file in files]}))
    print(json.dumps({'case':label,'status':state.get('status',state.get('task',{}).get('status')),
        'files':[{'path':f['path'],'length':len(f['content'])} for f in files],
        'messages':[{'id':m['id'],'role':m['role'],'status':m.get('status'),'taskId':m.get('taskId'),
            'length':len(m.get('content') or ''),'prefix':(m.get('content') or '')[:180],
            'containsCode':[f['content'] in (m.get('content') or '') for f in files],
            'missingMessageCount':history.get('missingMessageCount')} for m in history.get('messages',[])]},ensure_ascii=False))
