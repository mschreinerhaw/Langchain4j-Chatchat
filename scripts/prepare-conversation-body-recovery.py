"""Prepare auditable offline body repairs using persisted task responses, never model inference.

Only single-turn sessions with exactly one task and one user/assistant pair are eligible.
Ambiguous/multi-turn records remain in the unresolved report. Never alters either database.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone, timedelta
import json
import os
from pathlib import Path
import re
import subprocess


def prepare(indexes, tasks, events, missing):
    by_session, by_task = defaultdict(list), defaultdict(list)
    for row in indexes:
        by_session[(row['tenant_id'], row['user_id'], row['session_id'])].append(row)
    for row in tasks:
        by_task[(row['tenant_id'], row['user_id'], row['session_id'])].append(row)
    responses = defaultdict(list)
    for row in events:
        responses[row['task_id']].append(row)
    candidates, unresolved = [], []
    for index in missing:
        identity = (index['tenant_id'], index['user_id'], index['session_id'])
        turns = by_session[identity]
        matches = by_task[identity]
        if len(turns) != 2 or {r['role'] for r in turns} != {'user', 'assistant'} or len(matches) != 1:
            unresolved.append({'messageId': index['message_id'], 'reason': 'AMBIGUOUS_TURN_OR_TASK'})
            continue
        task = matches[0]
        if task['canonical_state'] not in ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REJECTED'):
            unresolved.append({'messageId': index['message_id'], 'reason': 'NONTERMINAL_TASK'})
            continue
        request = json.loads(task.get('request_payload_json') or '{}')
        detail = dict(messageId=index['message_id'], sessionId=index['session_id'], tenantId=index['tenant_id'],
                      userId=index['user_id'], role=index['role'], createdAt=index['created_at'],
                      modelName=request.get('modelName'), streaming=False)
        source = 'agent_task_latest.question'
        if index['role'] == 'user':
            detail['content'] = task.get('question')
        else:
            rows = [e for e in responses[task['task_id']] if e['tenant_id'] == index['tenant_id']
                    and e['user_id'] == index['user_id'] and e['session_id'] == index['session_id']
                    and (not task.get('execution_attempt_id') or e.get('attempt_id') == task['execution_attempt_id'])]
            rows.sort(key=lambda e: e['created_at'], reverse=True)
            if not rows:
                unresolved.append({'messageId': index['message_id'], 'reason': 'NO_PERSISTED_RESPONSE'})
                continue
            event = rows[0]
            body = json.loads(event.get('payload_json') or '{}')
            if body.get('conversationId') and body['conversationId'] != index['session_id']:
                raise ValueError('Response session mismatch')
            detail.update(content=body.get('answer'), taskId=task['task_id'],
                          status={'SUCCEEDED': 'completed', 'PARTIAL': 'partial', 'FAILED': 'failed',
                                  'CANCELLED': 'cancelled', 'REJECTED': 'rejected'}[task['canonical_state']])
            for src, dest in (('sources','sources'), ('toolTraces','traces'), ('steps','steps'),
                              ('uiResponse','uiResponse'), ('visualization','visualizationSpec'),
                              ('evidencePremises','evidencePremises')):
                if body.get(src) is not None:detail[dest] = body[src]
            detail['modelName'] = (body.get('metadata') or {}).get('modelName') or detail['modelName']
            source = 'agent_execution_event:' + event['event_id']
        if not str(detail.get('content') or '').strip():
            unresolved.append({'messageId': index['message_id'], 'reason': 'EMPTY_PERSISTED_CONTENT'})
            continue
        # The immutable body key retains the original Instant. Some historical SQL
        # migrations shifted timestamptz indexes; do not apply a guessed time-zone offset.
        safe=lambda value: str(value or 'default').strip().replace(':','_')
        prefix='chat:'+safe(index['tenant_id'])+':'+safe(index['session_id'])+':'
        suffix=':'+safe(index['message_id'])
        key=index['rocks_key']
        if not key.startswith(prefix) or not key.endswith(suffix):raise ValueError('Index key identity mismatch')
        epoch=key[len(prefix):-len(suffix)]
        if not re.fullmatch('[0-9]{13}',epoch):raise ValueError('Invalid immutable body timestamp')
        millis=int(epoch)
        detail['createdAt']=(datetime.fromtimestamp(millis//1000,timezone.utc)+timedelta(milliseconds=millis%1000)).isoformat().replace('+00:00','Z')
        candidates.append({**index, 'detail': detail, 'recoverySource': source})
    return candidates, unresolved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--prepare', action='store_true', help='Read missing.jsonl produced by the Java scan')
    args = parser.parse_args()
    if not re.fullmatch('[a-zA-Z0-9_]+', args.database):raise ValueError('Invalid database name')
    os.umask(0o077)
    args.directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    def rows(query):
        p=subprocess.run(['docker','exec','-u','postgres','postgres','psql','-X','-At','-d',args.database,
                          '-v','ON_ERROR_STOP=1','-c','SELECT row_to_json(r) FROM ('+query+') r'],capture_output=True,text=True)
        if p.returncode:raise RuntimeError('Private database read failed')
        return [json.loads(line) for line in p.stdout.splitlines() if line]
    indexes=rows('SELECT * FROM chat_message_index')
    if not args.prepare:
        (args.directory/'indexes.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in indexes))
        print(json.dumps({'exportedIndexes':len(indexes)}));return
    tasks=rows('SELECT * FROM agent_task_latest')
    events=rows("SELECT task_id,tenant_id,user_id,session_id,attempt_id,event_id,created_at,payload_json FROM agent_execution_event WHERE event_type IN ('RESULT','ANSWER')")
    missing=[json.loads(line) for line in (args.directory/'missing.jsonl').read_text().splitlines()]
    candidates,unresolved=prepare(indexes,tasks,events,missing)
    (args.directory/'candidates.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in candidates))
    (args.directory/'unresolved.json').write_text(json.dumps(unresolved,indent=2))
    print(json.dumps({'candidates':len(candidates),'unresolved':len(unresolved), 'existingBodiesOverwritten':False}))


if __name__=='__main__':main()
