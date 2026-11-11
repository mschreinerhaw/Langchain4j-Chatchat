"""Compare Java monitor receipts with the real provider audit and replay SSE."""
import argparse,json,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--origin',required=True);p.add_argument('--auth-file',type=Path,required=True);p.add_argument('--task',type=Path,required=True);p.add_argument('--audit',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False);token=None
def call(path,body=None):
    req=urllib.request.Request(a.origin+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with urllib.request.urlopen(req,timeout=30) as r:v=json.load(r);return v.get('data',v)
def stream(path,after):
    req=urllib.request.Request(a.origin+path+'/events/stream?afterSequence='+str(after),headers={'Authorization':'Bearer '+token,'Last-Event-ID':str(after)})
    with urllib.request.urlopen(req,timeout=15) as r:
        assert r.headers['Content-Type'].startswith('text/event-stream')
        for _ in range(100):
            line=r.readline().decode()
            if line.startswith('data:'):return json.loads(line[5:])
    raise RuntimeError('SSE did not replay an event')
try:
    token=call('/api/v1/enterprise/auth/login',json.loads(a.auth_file.read_text()))['token'];task=json.loads(a.task.read_text());root='/api/v1/coding-monitor/tasks/'+task['runId']+'/'+task['taskId'];detail=call(root)
    original={e['requestId']:e for e in map(json.loads,a.audit.read_text().splitlines()) if e.get('type')=='model.request.completed' and e.get('sessionId')==detail['sessionId']}
    java={r['requestId']:r for r in detail['modelReceipts']}
    assert original and original.keys()==java.keys()
    assert all(original[k]['usage']==java[k]['usage'] and original[k]['status']==java[k]['status'] for k in original)
    first=stream(root,0);second=stream(root,first['sequence']);assert second['sequence']>first['sequence']
    (a.output/'sse-replay.json').write_text(json.dumps({'first':first,'afterReconnect':second},indent=2))
    result={'passed':True,'realProviderRequestCount':len(original),'inputTokens':sum(x['usage'].get('inputTokens') or 0 for x in original.values()),'outputTokens':sum(x['usage'].get('outputTokens') or 0 for x in original.values()),'checks':{'providerReceiptsMatchJava':True,'requestIdsUnique':len(java)==len(detail['modelReceipts']),'historicalSse':True,'lastEventIdReconnect':True}}
    (a.output/'verification.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
except Exception as e:(a.output/'failure.json').write_text(json.dumps({'message':str(e),'type':type(e).__name__}));raise
