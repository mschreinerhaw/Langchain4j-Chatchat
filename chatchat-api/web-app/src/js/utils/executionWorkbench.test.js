import { describe, it, expect } from 'vitest';
import { executionWorkbench as project } from './runtimeObservationPresentation.js';

const receipt = (sequence, state, extra = {}, attemptId = 'a') => ({ sequence, attemptId,
  type: 'RUNTIME_OBSERVATION', timestamp: 100000 + sequence * 1000,
  payload: { metadata: { eventKind: 'CODING_TRACE', taskId: 'coding', operationId: 'op',
    codingEventType: state === 'RUNNING' ? 'EXECUTION_STARTED' : 'EXECUTION_FINISHED',
    eventState: state, ...extra } } });

describe('execution workbench receipt integrity', () => {
  it('deduplicates replay and orders late receipts without mutating diagnostics', () => {
    const start = receipt(1, 'RUNNING'), finish = receipt(2, 'COMPLETED');
    const events = [finish, start, finish, start], before = JSON.stringify(events);
    const result = project(events);
    expect(result.completedOperations).toBe(1);
    expect(result.allActivities).toHaveLength(1);
    expect(result.allActivities[0].durationLabel).toBe('00:01');
    expect(JSON.stringify(events)).toBe(before);
  });
  it('uses authoritative snapshot attempt and excludes a previous failure', () => {
    const result = project([receipt(1,'FAILED'), receipt(2,'RUNNING',{},'b')],
      {attemptId:'a', executionSnapshot:{attemptId:'b'}}, 110000);
    expect(result.attemptId).toBe('b'); expect(result.status).toBe('active');
    expect(result.allActivities).toHaveLength(1);
  });
  it('does not turn an unknown execution finish into success', () => {
    expect(project([receipt(1,'UNKNOWN')]).completedOperations).toBe(0);
  });
  it('requires explicit retry evidence before reopening a failed operation', () => {
    const failed = receipt(2,'FAILED');
    expect(project([receipt(1,'RUNNING'),failed,receipt(3,'RUNNING')]).status).toBe('error');
    expect(project([failed,receipt(3,'COMPLETED')]).completedOperations).toBe(0);
    const retry = receipt(4,'RUNNING',{payload:{repairAttempt:1}});
    const running = project([receipt(1,'RUNNING'),failed,retry]);
    expect(running.status).toBe('retrying');
    expect(running.allActivities[0].finishedAt).toBe(0);
    const done=project([receipt(1,'RUNNING'),failed,retry,receipt(5,'COMPLETED',{payload:{repairAttempt:1}})]);
    expect(done.status).not.toBe('error'); expect(done.completedOperations).toBe(1);
    const final=project([failed,retry,receipt(5,'COMPLETED',{payload:{repairAttempt:1}})],{status:'SUCCEEDED'});
    expect(final.status).toBe('done'); expect(final.terminal).toBe(true);
    expect(project([failed,retry],{status:'FAILED'}).status).toBe('error');
    const recoveredTask=project([failed,receipt(5,'COMPLETED',{operationId:'new-op'})],{canonicalState:'SUCCEEDED'});
    expect(recoveredTask.status).toBe('done');expect(recoveredTask.terminal).toBe(true);
    expect(recoveredTask.allActivities.some(row=>row.state==='error')).toBe(true);
  });
  it('keeps same-tool concurrent calls separate and unions their durations', () => {
    const tool=(sequence,type,id)=>({sequence,type,eventId:type==='TOOL_CALL'?id:`end-${id}`,
      parentEventId:id,timestamp:100000+sequence*1000});
    const result=project([tool(1,'TOOL_CALL','x'),tool(2,'TOOL_CALL','y'),
      tool(3,'TOOL_RESULT','x'),tool(4,'TOOL_RESULT','y')]);
    expect(result.completedOperations).toBe(2);
    expect(result.timings.find(row=>row.kind==='DATA').durationLabel).toBe('00:03');
  });
  it('leaves finished-only operation duration unknown', () => {
    const result=project([receipt(1,'COMPLETED')],{status:'SUCCEEDED'});
    expect(result.allActivities[0].durationLabel).toBe('未记录');
    expect(result.timings.find(row=>row.kind==='EXECUTION').durationLabel).toBe('未单独记录');
  });
  it('corrects clock skew and distinguishes stale status transport from task timeout', () => {
    const task={runtimeHeartbeat:{timestamp:110000,receivedAt:900000,heartbeatIntervalMs:10000}};
    const events=[receipt(1,'RUNNING')];
    expect(project(events,task,905000).allActivities[0].durationLabel).toBe('00:14');
    expect(project(events,task,940000).status).toBe('unconfirmed');
    expect(project(events,{...task,status:'TIMED_OUT'},940000).status).toBe('timeout');
    expect(project(events,{...task,status:'CANCELLED'},940000).status).toBe('cancelled');
  });
  it('does not interpret a proposed Python tool call as executor confirmation', () => {
    expect(project([receipt(1,'RUNNING',{codingEventType:'RECOVERY_RECONCILED',
      payload:{type:'TOOL_STARTED',tool:'python_execute',toolCallId:'proposal'}})]).allActivities).toHaveLength(0);
  });
  it('marks missing earlier phases and preserves simultaneous active phases', () => {
    const result=project([receipt(1,'RUNNING'),{sequence:2,attemptId:'a',type:'TOOL_CALL',eventId:'data'}]);
    expect(result.steps[0].status).toBe('unrecorded');
    expect(result.steps[2].status).toBe('active'); expect(result.steps[3].status).toBe('active');
  });
});
