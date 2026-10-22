import json,time,uuid,urllib.request,urllib.parse,os
from pathlib import Path
os.umask(0o077)
out=Path('/opt/runtime-os-docker-stage/evidence/retired-agents-2.1.0-rc1/live')
auth=json.loads((out/'admin-session-private.json').read_text())
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None):
 req=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+auth['token']})
 with client.open(req,timeout=90) as r:v=json.load(r)
 assert v.get('code') in (None,0,200,202),path
 return v['data']
session=str(uuid.uuid4())
req={'agentId':'general','mode':'llm_chat','modelName':'qwen3.6-plus','query':'Explain the difference between mean and median in a short paragraph. This is ordinary knowledge Q&A: do not access files, MCP or business data, do not generate code or a report.','tenantId':auth['user']['tenantId'],'userId':auth['user']['id'],'sessionId':session,'idempotencyKey':str(uuid.uuid4()),'toolInput':{'reportMode':'off'}}
task=call('/agent/tasks',req)['taskId']
(out/'knowledge-submission-private.json').write_text(json.dumps({'taskId':task,'request':req}))
print(json.dumps({'stage':'SUBMITTED','taskId':task}),flush=True)
query='?tenantId='+urllib.parse.quote(auth['user']['tenantId'])
deadline=time.monotonic()+600
while time.monotonic()<deadline:
 state=call('/agent/tasks/'+task+query)
 status=state.get('canonicalState') or state.get('status')
 if status in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'):break
 time.sleep(5)
else:raise RuntimeError('Knowledge task still active; resume recorded task')
result=call('/agent/tasks/'+task+'/result'+query+'&timeoutMs=1000')
events=call('/agent/tasks/'+task+'/events'+query+'&limit=1000')
(out/'knowledge-result-private.json').write_text(json.dumps({'state':state,'result':result,'events':events}))
text=json.dumps(result).lower()
checks={'taskSucceeded':status=='SUCCEEDED','answersQuestion':'mean' in text and 'median' in text,'noProfessionalWorkflow':not any(s in text for s in ['federated_agent','domain_intelligence','a2a'])}
report={'status':'PASSED' if all(checks.values()) else 'FAILED','taskId':task,'checks':checks}
(out/'knowledge-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
assert all(checks.values()),checks
