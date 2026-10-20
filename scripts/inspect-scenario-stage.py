"""Project online scenario progress; keep answers and runtime diagnostics in stage evidence."""
import json
import pathlib
import urllib.request

root = pathlib.Path('/opt/runtime-os-docker-stage')
evidence = root/'evidence/runtime-os-refactor-20261020'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
base = 'http://127.0.0.1:18180/api/v1'
def get(path, body=None):
    req = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json', **({'Authorization':'Bearer '+token} if token else {})})
    with client.open(req, timeout=60) as response: return json.load(response)['data']
token = None
token = get('/enterprise/auth/login', json.loads((root/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))['token']
for path in sorted(evidence.glob('scenario*-*.json')):
    if path.stem.endswith(('-answer','-runtime')): continue
    data = json.loads(path.read_text())
    if not data.get('taskId'): continue
    endpoint = '/published-agents/'+data['agentId']+'/questions/'+data['taskId']
    state = get(endpoint+'/status?eventLimit=5')
    print(json.dumps({'case':path.stem,'taskId':data['taskId'],'state':state['canonicalState'],
        'error':state.get('errorMessage'),'coding':[(t.get('taskId'),t.get('outcome',{}).get('status')) for t in data.get('tasks',[])],
        'acceptance':data.get('status')}, ensure_ascii=False), flush=True)
    if state.get('answerAvailable'):
        answer = get(endpoint+'/answer')
        path.with_name(path.stem+'-answer.json').write_text(json.dumps(answer,ensure_ascii=False,indent=2))
    runtime = get('/agent/runtime/runs/'+data['runId'])
    path.with_name(path.stem+'-runtime.json').write_text(json.dumps(runtime,ensure_ascii=False,indent=2))
