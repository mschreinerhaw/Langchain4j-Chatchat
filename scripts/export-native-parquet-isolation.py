"""Export allowlisted isolation evidence, excluding credentials and model reasoning."""
from pathlib import Path
import gzip,hashlib,json,re,shutil

ROOT=Path('/opt/cline-parquet-isolation-20261110')
OUT=ROOT/'public';OUT.mkdir(exist_ok=True)
settings=json.loads((ROOT/'private/settings.json').read_text())
secrets=[settings[k] for k in ['apiKey','apiToken','proxyToken']]
def safe(text):
 for secret in secrets:text=text.replace(secret,'[REDACTED]')
 return text
def save(path,value):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(safe(json.dumps(value,ensure_ascii=False,indent=2)))
all_cases=[];hashes=[]
for folder in sorted((ROOT/'runs').glob('*')):
 case=folder.name;dest=OUT/case;dest.mkdir(exist_ok=True)
 summary=json.loads((folder/'summary.json').read_text()) if (folder/'summary.json').exists() else {'case':case,'terminal':'NOT_TERMINAL'}
 receipts=[]
 for p in sorted((folder/'model').glob('*-receipt.json')):
  receipt=json.loads(p.read_text());request=p.with_name(p.name.replace('-receipt.json','-request.json'))
  if request.exists():
   payload=json.loads(request.read_text());receipt['toolSchemaSha256']=hashlib.sha256(json.dumps(payload.get('tools'),sort_keys=True,ensure_ascii=False).encode()).hexdigest()
  receipts.append(receipt)
 summary['modelReceipts']=receipts
 known=[r['usage'] for r in receipts if r.get('usage')]
 summary['knownTokenUsage']={k:sum(u.get(k,0) for u in known) for k in ['prompt_tokens','completion_tokens','total_tokens']}
 summary['missingUsageFields']={k:sum(k not in u for u in known) for k in ['prompt_tokens','completion_tokens','total_tokens']}
 summary['unknownUsageCalls']=sum(not r.get('usageKnown') for r in receipts if r.get('index') is not None)
 events=[]
 if (folder/'events.json').exists():events=json.loads((folder/'events.json').read_text())['events']
 elif (folder/'stdout.jsonl').exists():
  for l in (folder/'stdout.jsonl').read_text().splitlines():
   try:row=json.loads(l)
   except json.JSONDecodeError:continue
   if (row.get('event') or {}).get('contentType')=='reasoning':continue
   events.append(row)
  summary['officialSessionIds']=[p.name for p in (folder/'state/home/.cline/data/sessions').glob('*') if p.is_dir()]
  summary['officialConversationIds']=sorted({e['taskId'] for e in events if e.get('type')=='hook_event' and e.get('hookEventName')=='agent_start' and e.get('taskId')})
 journals=list((folder/'state').glob('*/events.jsonl'))
 if journals:
  events=[json.loads(line) for line in journals[0].read_text().splitlines()]
  state=json.loads((journals[0].parent/'session.json').read_text())
  summary['persistedNativeState']={k:state.get(k) for k in ['id','taskId','nativeSessionId','status','editTool']}
 clean=[];tools=[]
 for e in events:
  a=(e.get('payload') or {}).get('payload',{}).get('event') if e.get('eventType')=='native.event' else e.get('event')
  if a and a.get('contentType')=='reasoning':continue
  clean.append(e)
  if a and a.get('contentType')=='tool' and a.get('type')=='content_end':tools.append(a)
 summary['toolsCompleted']=len(tools)
 summary['toolsUsed']=sorted({e['toolName'] for e in tools})
 summary['toolOutputsWithPythonException']=[{'toolCallId':e.get('toolCallId'),'toolName':e['toolName'],'exceptions':sorted(set(re.findall(r'SyntaxError|IndentationError|NameError|TypeError|ValueError|KeyError|FileNotFoundError|PermissionError|ModuleNotFoundError',json.dumps(e.get('output'),ensure_ascii=False))))} for e in tools if re.search(r'Traceback|SyntaxError|IndentationError',json.dumps(e.get('output'),ensure_ascii=False))]
 summary['toolFailures']=[e for e in tools if '"success": false' in json.dumps(e.get('output'),ensure_ascii=False)]
 summary['pythonToolResults']=[e for e in tools if e.get('toolName')=='run_commands' and re.search(r'python(?:3|3\.11)?\b',json.dumps(e.get('output'),ensure_ascii=False))]
 if (folder/'failure-observation.json').exists():summary['failureObservation']=json.loads((folder/'failure-observation.json').read_text())
 if (folder/'final-review.json').exists():summary['finalReview']=json.loads((folder/'final-review.json').read_text())
 for name in ['intent.json','session-created.json','submission.json','final-status.json','failure.json','failure-observation.json','stop.json','stop-error.json','independent-acceptance.json','reproduction-summary.json','final-review.json']:
  p=folder/name
  if p.exists():save(dest/name,json.loads(p.read_text()))
 save(dest/'summary.json',summary)
 (dest/'events.json.gz').write_bytes(gzip.compress(safe(json.dumps(clean,ensure_ascii=False)).encode(),mtime=0))
 save(dest/'tools.json',tools)
 for name in ['analysis.py','report.md']:
  p=folder/'workspace'/name
  if p.exists():
   raw=p.read_text(encoding='utf-8');(dest/name).write_text(safe(raw),encoding='utf-8')
 version_records=[]
 for p in (folder/'versions').rglob('*'):
  if p.is_file():version_records.append({'path':str(p.relative_to(folder/'versions')),'text':safe(p.read_text(encoding='utf-8')),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 (dest/'versions.json.gz').write_bytes(gzip.compress(json.dumps(version_records,ensure_ascii=False).encode(),mtime=0))
 all_cases.append({k:v for k,v in summary.items() if k not in ['modelReceipts','pythonToolResults']})
 for p in folder.rglob('*'):
  if p.is_file() and p.name!='test.parquet':hashes.append({'path':str(p),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
save(OUT/'cases.json',{'cases':all_cases,'preparationAttempts':[f'native-{i}' for i in range(1,6)],'nativeFormalCases':[f'native-{i}' for i in range(6,11)],'officialFormalCases':[f'official-{i}' for i in range(1,6)],'strictEquivalentConfiguration':False,'unknownUsageIsNotZero':True})
save(OUT/'private-evidence-hashes.json',hashes)
for name in ['preparation.json','source-manifest.json','frozen-plan.json','independent-baseline.json','provenance.json','final-review.json']:
 p=ROOT/'evidence'/name
 if p.exists():save(OUT/name,json.loads(p.read_text()))
print(json.dumps({'exportedCases':len(all_cases),'output':str(OUT)}))
