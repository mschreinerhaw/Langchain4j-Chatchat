"""Observe actual native command outputs, independently of shell wrapper status.

An `echo EXIT_CODE=$?` shell can return 0 after Python failed. Keep the original
native event unchanged and inspect its actual result, never source/diff text.
"""
import re

def command_results(event):
    if event.get('eventType') not in ['tool.completed','tool.failed']:return []
    payload=event.get('payload') or {}
    if payload.get('toolName')!='run_commands':return []
    output=payload.get('output')
    return output if isinstance(output,list) else [output] if isinstance(output,dict) else []

def observe_python_repair(events,error_type='TypeError'):
    failures=[]
    for event in events:
        for result in command_results(event):
            text=str(result.get('result',''))
            if 'Traceback (most recent call last)' in text and re.search(r'^'+re.escape(error_type)+r':',text,re.M):
                exit_code=re.search(r'(?:EXIT_CODE=|exited with code )(\d+)',text)
                failures.append({'eventId':event['eventId'],'toolCallId':event['payload'].get('toolCallId'),
                    'nativeEventType':event['eventType'],'pythonExitCode':int(exit_code[1]) if exit_code else None})
    repairs=[]
    for failure in failures:
        edits=[e for e in events if int(e['eventId'])>int(failure['eventId']) and e.get('eventType')=='tool.completed'
            and (e.get('payload') or {}).get('toolName') in ['editor','apply_patch']]
        if not edits:continue
        edit=edits[0]
        for event in events:
            if int(event['eventId'])<=int(edit['eventId']):continue
            if any(re.search(r'\bpython(?:3(?:\.11)?)?\b',str(r.get('query','')))
                and r.get('success') is True and 'Traceback (most recent call last)' not in str(r.get('result',''))
                and not re.search(r'EXIT_CODE=[1-9]|exited with code [1-9]',str(r.get('result','')))
                for r in command_results(event)):
                repairs.append({'failureEventId':failure['eventId'],'editEventId':edit['eventId'],'executionEventId':event['eventId']});break
    return {'failures':failures,'observedErrorEditExecutionChains':repairs,'actualErrorObserved':bool(failures),'subsequentPythonExecution':bool(repairs)}
