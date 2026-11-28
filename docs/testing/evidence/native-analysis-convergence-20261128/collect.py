import json,glob,os,hashlib,collections,re,datetime,sys
from pathlib import Path
root=Path(sys.argv[1])
audit=Path(sys.argv[2])
def load(f):return [json.loads(l) for l in f.open()]
def digest(o):return hashlib.sha256(json.dumps(o,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def measure(o):return len(json.dumps(o,ensure_ascii=False,separators=(',',':')).encode())
rows=load(audit)
result={}
for pair in sys.argv[3:]:
 name,prefix=pair.split('=',1)
 w=next(root.glob(prefix+'*'))
 evidence=next(w.glob('state/*/events.jsonl')); ev=load(evidence)
 history=next(w.glob('state/*/cline/data/sessions/*/*.messages.json'))
 hist=json.loads(history.read_text())
 cfg=json.loads((w/'config.json').read_text())
 rr=sorted((a for a in rows if a.get('workerId')==w.name and a['type']=='model.request.completed'),key=lambda a:a['startedAt'])
 done=[a for a in ev if a['eventType']=='turn.completed']
 attempts=collections.Counter()
 args=collections.Counter()
 stack={}
 tools=[]
 repeated_files=collections.Counter()
 for e in ev:
  t=e['eventType']; p=e['payload']
  if t=='tool.started':
   h=digest(p.get('input')); args[(p['toolName'],h)]+=1
   d={'eventId':e['eventId'],'at':e['timestamp'],'name':p['toolName'],'id':p['toolCallId'],'argumentHash':h,'argumentBytes':measure(p.get('input')),'sameArgumentsOrdinal':args[(p['toolName'],h)]}
   if p['toolName']=='read_files':
    files=(p.get('input') or {}).get('files',[])
    if isinstance(files,list):
     for f in files:
      path=f.get('path') if isinstance(f,dict) else f
      if isinstance(path,str):repeated_files[digest(path)]+=1
   stack[p['toolCallId']]=d;tools.append(d)
  if t in ('tool.completed','tool.failed') and p.get('toolCallId') in stack:
   d=stack[p['toolCallId']];d.update(endedAt=e['timestamp'],status=t,resultBytes=measure(p.get('output')),resultHash=digest(p.get('output')))
 commands=[]
 for e in ev:
  if e['eventType']!='command.completed':continue
  p=e['payload']; command=p.get('command')
  commands.append({'eventId':e['eventId'],'at':e['timestamp'],'commandHash':digest(command),'python':bool(re.search(r'\bpython(?:[23](?:\.\d+)?)?\b',str(command))),'exitCode':p.get('exitCode'),'stdoutBytes':p.get('stdoutBytes'),'stderrBytes':p.get('stderrBytes'),'sourceSnapshots':[(digest(x.get('path')),x.get('sha256')) for x in p.get('sourceSnapshots',[]) if isinstance(x,dict)]})
 reports=[]
 for report in (w/'workspace/output').rglob('*'):
  if report.is_file() and report.suffix.lower() in {'.md','.html'}:
   reports.append({'path':str(report.relative_to(w/'workspace')),'size':report.stat().st_size,'modifiedAt':datetime.datetime.fromtimestamp(report.stat().st_mtime,datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z'),'sha256':hashlib.sha256(report.read_bytes()).hexdigest()})
 failures={}
 for command in commands:
  if command['python'] and command['exitCode']!=0:failures[command['commandHash']]=command
  if command['python'] and command['exitCode']==0 and command['commandHash'] in failures:
   fail=failures[command['commandHash']]
   old_files=dict(fail['sourceSnapshots']);new_files=dict(command['sourceSnapshots'])
   changed=[k for k in old_files.keys()&new_files.keys() if old_files[k]!=new_files[k]]
   if changed:command['changedSourceRepairOf']=fail['eventId']
 receipts=[];totals=collections.Counter()
 for i,a in enumerate(rr):
  s=a['startedAt']; f=a['finishedAt']
  next_s=rr[i+1]['startedAt'] if i+1<len(rr) else 'z'
  n=next((e for e in done if s<=e['timestamp']<next_s),None)
  t=[d for d in tools if s<=d['at']<next_s]
  bad=[e for e in ev if e['eventType']=='model.error' and s<=e['timestamp']<next_s]
  def action(e):
   p=e['payload']; data=p.get('input')
   if not data:return ''
   return str(data.get('path','')) if e['payload']['toolName']=='editor' and isinstance(data,dict) else ''
  edits=[action(e) for e in ev if e['eventType']=='tool.started' and s<=e['timestamp']<next_s]
  evidence_window=[x for x in commands if s<=x['at']<next_s]
  if done and s>done[0]['timestamp']: stage='DELIVERY_CHECK'
  elif bad or a.get('httpStatus',0)>=400:stage='RETRY'
  elif any(x.startswith('output/') and x.lower().endswith(('.html','.md')) for x in edits) or any(s<=x['modifiedAt']<next_s for x in reports):stage='REPORT_GENERATION'
  elif any(x.get('changedSourceRepairOf') for x in evidence_window):stage='SCRIPT_REPAIR'
  elif t and all(x['name']=='read_files' for x in t):stage='EXPLORATION'
  elif any(x and x.endswith('.py') for x in edits):stage='ANALYSIS'
  elif any(x['python'] for x in evidence_window) and t:stage='ANALYSIS'
  elif n:stage='DELIVERY_CHECK'
  else:stage='UNKNOWN'
  usage={k:v for k,v in (a.get('usage') or {}).items() if k!='raw'}
  totals.update({k:v for k,v in usage.items() if isinstance(v,int)})
  receipts.append({'seq':i+1,'stage':stage,'relationship':'native tool starts from current broker request start until next broker request start; inferred unless explicit','stageReason':('after first native completion' if done and s>done[0]['timestamp'] else 'inferred from following native events' if stage!='UNKNOWN' else 'no uniquely identifying action'),'startedAt':s,'finishedAt':f,'requestId':a['requestId'],'gatewayRequestId':a.get('gatewayRequestId'),'providerRequestId':a.get('providerRequestId'),'httpStatus':a.get('httpStatus'),'errorType':a.get('errorType'),'usage':usage,'cumulativeProviderTokens':totals['totalTokens'],'requestContext':a.get('requestContext'),'requestHash':a.get('requestSha256'),'responseTiming':a.get('responseTiming'),'followingToolIds':[d['id'] for d in t],'followingModelErrorEventIds':[e['eventId'] for e in bad]})
 boundaries=[]
 for e in ev:
  if e['eventType'] not in ('task.accepted','turn.completed','delivery.selection.invalid','delivery.previewed','delivery.sealed','task.cancelled'):continue
  b={k:e[k] for k in ['eventId','timestamp','eventType']}
  p=e['payload']
  for k in ('reason','message','deliveryVersion','selectionSha256'):
   if k in p:b[k]=p[k]
  boundaries.append(b)
 allowed=['taskId','runId','inputSnapshotVersion']
 result[name]={'binding':{k:cfg['deliveryBinding'][k] for k in allowed},'brokerSessionId':rr[0]['sessionId'],'nativeSessionId':ev[0]['sessionId'],'journalSha256':hashlib.sha256(evidence.read_bytes()).hexdigest(),'auditSha256':hashlib.sha256(audit.read_bytes()).hexdigest(),'boundaries':boundaries,'reports':reports,'nativeEvents':dict(collections.Counter(e['eventType'] for e in ev)),'receipts':receipts,'admissionRejections':[{k:a.get(k) for k in ('requestId','timestamp','code','providerDispatched')} for a in rows if a.get('workerId')==w.name and a['type']=='model.request.rejected'],'tools':tools,'commands':commands,'repeatedReadPaths':{k:v for k,v in repeated_files.items() if v>1},'providerTotals':dict(totals),'historyBytes':history.stat().st_size,'visualPrompt': 'interactive HTML report delivery' in history.read_text()}
print(json.dumps(result,ensure_ascii=True,separators=(',',':')))
