"""Derive execution observations from real Native or official ACP receipts.

Includes failed tools and mixed command batches. Command receipts are not OS
process counts, and failed commands are not an invented auto-repair counter.
Private command/exception evidence stays beside the original task journal.
"""
from pathlib import Path
import argparse,json,re

p=argparse.ArgumentParser();p.add_argument('--case-dir',type=Path,required=True);a=p.parse_args()
folder=a.case_dir;official=(folder/'acp.jsonl').exists();starts={};receipts={};tools=set()
def strings(value):
    if isinstance(value,str):return [value]
    if isinstance(value,dict):return [text for child in value.values() for text in strings(child)]
    if isinstance(value,list):return [text for child in value for text in strings(child)]
    return []
def observe(ident,commands,output,reference):
    if not isinstance(commands,list) or not isinstance(output,list):return
    for index,result in enumerate(output):
        if index>=len(commands) or not isinstance(result,dict):continue
        command=commands[index]
        if not isinstance(command,str) or not re.search(r'(?:^|&&|\|\||;|\n)\s*(?:[A-Za-z_][A-Za-z_0-9]*=\S+\s+)*(?:/[^ \t\n]+/)?python(?:3(?:\.11)?)?(?:\s|$)',command):continue
        text='\n'.join(strings(result))
        exceptions=sorted(set(re.findall(r'(?m)^([A-Za-z_][A-Za-z_0-9]*(?:Error|Exception)):',text)))
        receipts[(ident,index)]={'toolCallId':ident,'commandIndex':index,'command':command,'success':result.get('success'),'reportedError':result.get('error'),
            'containsTraceback':'Traceback (most recent call last)' in text,'observedExceptionNames':exceptions,'receiptReference':reference,'stdoutAndStderrSeparatedByThisObserver':False}
if official:
    for line_number,line in enumerate((folder/'acp.jsonl').read_text().splitlines(),1):
        record=json.loads(line);message=record['message']
        if record['direction']!='official-cli-to-client' or message.get('method')!='session/update':continue
        update=message.get('params',{}).get('update',{});ident=update.get('toolCallId')
        if update.get('sessionUpdate')=='tool_call':starts[ident]=update;tools.add(ident)
        call=starts.get(ident,{})
        if call.get('title','').startswith('run_commands'):observe(ident,call.get('rawInput',{}).get('commands'),update.get('rawOutput'),{'source':'actual official ACP rawOutput','journalLine':line_number})
else:
    for event in json.loads((folder/'events.json').read_text())['events']:
        payload=event.get('payload',{});ident=payload.get('toolCallId')
        if event['eventType']=='tool.started':starts[ident]=payload;tools.add(ident)
        if event['eventType'] in ['tool.completed','tool.failed'] and payload.get('toolName')=='run_commands':
            observe(ident,starts.get(ident,{}).get('input',{}).get('commands'),payload.get('output'),{'source':event['eventType'],'eventId':event['eventId']})
values=list(receipts.values());failed=[v for v in values if v['success'] is False];exceptions=[v for v in values if v['observedExceptionNames']]
proof={'schemaVersion':'coding_execution_observation.v2','source':'official ACP' if official else 'Native includes tool.completed AND tool.failed','nativeToolCallIdsObserved':len(tools),
    'pythonCommandReceipts':len(values),'successfulPythonCommandReceipts':sum(v['success'] is True for v in values),'failedPythonCommandReceipts':len(failed),'receiptsWithObservedPythonExceptionText':len(exceptions),
    'observedExceptionNames':sorted(set(name for v in exceptions for name in v['observedExceptionNames'])),'pythonCommandEvidence':values,
    'automaticRepairCount':None,'automaticRepairCountNote':'No explicit native repair counter is emitted. Command failures and actual later executions are retained; this observer does not infer a repair from every failure.',
    'sourceOrArtifactModified':False,'osPythonProcessCountInferred':False}
target=folder/'execution-observation-v2.private.json'
with target.open('x') as out:json.dump(proof,out,ensure_ascii=False,indent=2)
print(json.dumps({k:v for k,v in proof.items() if k not in ['pythonCommandEvidence','automaticRepairCountNote']},ensure_ascii=False))
