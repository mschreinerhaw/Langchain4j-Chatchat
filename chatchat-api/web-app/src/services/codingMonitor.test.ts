import { describe, expect, it } from 'vitest';
import { codingStage, codeVersions, mergeCodingEvents, modelCalls, pythonExecutions, pythonRepairs, pythonSummary, pythonTimeline, type CodingEvent, type CodingTask } from './codingMonitor';
const event = (sequence: number, payload = {}, extra = {}): CodingEvent => ({
  eventId: `e${sequence}`, taskId: 'task', sequence, occurredAt: `2026-10-19T01:00:0${sequence}Z`,
  type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'worker', payload, ...extra,
});

function receipt(ordinal: number, status: string, diagnostic: string | null, repairs: number, resultAvailable = false): any {
  return { schemaVersion: 'runtime_os.execution_feedback.v1', executionId: `cline-${ordinal}`, attemptId: `task:execution-${ordinal}`,
    codeHash: String(ordinal).repeat(64), status, stage: resultAvailable ? 'VALIDATED' : status === 'SUCCEEDED' ? 'RESULT_VALIDATION_FAILED' : 'EXECUTION_FAILED',
    script: { scriptVersion: ordinal, fileName: `analysis_v${ordinal}.py`, scriptPath: `/workspace/scripts/analysis_v${ordinal}.py`, workingDirectory: 'task' },
    resultAvailable, validationStatus: resultAvailable ? 'INCONCLUSIVE' : diagnostic === 'OUTPUT_CONTRACT' ? 'FAILED' : 'NOT_RUN',
    error: diagnostic ? { exceptionType: diagnostic === 'FIELD_ACCESS' ? 'KeyError' : diagnostic === 'SYNTAX_ERROR' ? 'SyntaxError' : null,
      diagnosticCode: diagnostic, category: diagnostic === 'OUTPUT_CONTRACT' ? 'OUTPUT_CONTRACT' : 'PROGRAM', message: 'Safe system guidance', stack: [{ file: `analysis_v${ordinal}.py`, line: 2 }] } : null,
    repair: { allowed: !resultAvailable, attemptsUsed: repairs, maxAttempts: 3, action: resultAvailable ? 'COMPLETE' : 'REPAIR_IN_CURRENT_CONTEXT' } };
}
describe('Python diagnostics from real receipt shapes', () => {
  it('keeps the actual A2 dispatch intent separate from proof of process startup', () => {
    const start=event(1,{}, {operationId:'cline-1',summary:'Start intent committed; execution result is not yet known'});
    const rows=pythonExecutions(task(),[start,start]);
    expect(rows).toHaveLength(1);expect(rows[0].requestedAt).toBe(start.occurredAt);
    expect(rows[0].startedAt).toBeUndefined();expect(rows[0].state).toBe('DISPATCHED');
    expect(pythonTimeline(rows,[],[start])[0].label).toContain('不是启动证明');
  });
  it('does not turn an absent historical output verdict into a known output failure', () => {
    const old=receipt(1,'SUCCEEDED',null,0);delete old.resultAvailable;
    const rows=pythonExecutions({...task(),executions:[old]},[]);
    expect(rows[0].state).toBe('EXECUTED');expect(pythonSummary(rows,[]).outputFailed).toBe(0);
    expect(pythonRepairs(rows,[])).toEqual([]);
  });
  it('keeps independent failures and repairs, and separates exit zero from valid output', () => {
    const t = { ...task(), executions: [receipt(1,'FAILED','SYNTAX_ERROR',0), receipt(2,'FAILED','FIELD_ACCESS',1),
      receipt(3,'SUCCEEDED','OUTPUT_CONTRACT',2), receipt(4,'SUCCEEDED',null,3,true)] };
    const rows = pythonExecutions(t, []), repairs = pythonRepairs(rows, []);
    expect(rows).toHaveLength(4); expect(rows[2].state).toBe('RESULT_VALIDATION_FAILED');
    expect(repairs.map(r => [r.from.ordinal,r.to.ordinal])).toEqual([[1,2],[2,3],[3,4]]);
    expect(pythonSummary(rows,repairs)).toMatchObject({total:4,processSucceeded:2,processFailed:2,outputFailed:1,repairs:3,repairFailed:2,
      latestVersion:4,lastValidation:'INCONCLUSIVE',lastError:{diagnosticCode:'OUTPUT_CONTRACT'}});
    expect(codeVersions([],rows).map(v => v.scriptVersion)).toEqual([1,2,3,4]);
  });
  it('deduplicates feedback and prefers the durable API over an older event copy', () => {
    const f=receipt(1,'FAILED','FIELD_ACCESS',0), fixed={...f,error:{...f.error,exceptionType:'KeyError'}};
    const events=[event(1,{}, {type:'EXECUTION_STARTED',operationId:'cline-1'}),event(2,{type:'EXECUTION_FEEDBACK',feedback:f},{operationId:'cline-1'})];
    const rows=pythonExecutions({...task(),executions:[fixed,fixed]},[...events,events[1]]);
    expect(rows).toHaveLength(1); expect(rows[0].startedAt).toBe(events[0].occurredAt);
    expect(rows[0].feedback?.error?.exceptionType).toBe('KeyError');
    expect(pythonTimeline(rows,[],[...events,events[1]])).toHaveLength(2);
  });
  it('never infers Python execution or repair from successful models or neighbouring version changes', () => {
    expect(pythonExecutions(task(),[event(1,{type:'MODEL_USAGE',status:'SUCCEEDED'}),event(2,{type:'CODE_READY',proposedOperationId:'cline-1'})])).toEqual([]);
    const rows=pythonExecutions({...task(),executions:[receipt(1,'SUCCEEDED',null,0,true),receipt(2,'SUCCEEDED',null,0,true)]},[]);
    expect(pythonRepairs(rows,[])).toEqual([]);
  });
  it('shows pending execution after a proven failed parent without inventing its result', () => {
    const events=[event(1,{}, {type:'EXECUTION_STARTED',operationId:'cline-1'}),event(2,{type:'CODE_READY',proposedOperationId:'cline-2',parentCodeHash:'1'.repeat(64)}),
      event(3,{}, {type:'EXECUTION_STARTED',operationId:'cline-2'})];
    const rows=pythonExecutions({...task(),executions:[receipt(1,'FAILED','FIELD_ACCESS',0)]},events);
    expect(rows[1].state).toBe('EXECUTING'); expect(rows[1].feedback).toBeUndefined();
    expect(pythonRepairs(rows,events)[0].status).toBe('执行中');
    expect(pythonSummary(rows,pythonRepairs(rows,events))).toMatchObject({total:2,repairs:1,processSucceeded:0});
  });
  it('associates Node operationId payloads without overwriting another code version', () => {
    const rows=codeVersions([event(1,{type:'CODE_READY',proposedOperationId:'cline-a'}),event(2,{type:'CODE_READY',proposedOperationId:'cline-b'}),
      event(3,{type:'TOOL_FINISHED',operationId:'cline-a',status:'FAILED'})]);
    expect(rows[0].state).toBe('FAILED');expect(rows[1].state).toBe('CODE_READY');
  });
});
const task = (outcome = {}): CodingTask => ({ taskId: 'task', attemptId: 'fence-1', objective: 'calculate',
  canCancel: true, canRetry: false, configuration: {}, allowedTools: [],
  outcome: { status: 'RUNNING', createdAt: '', updatedAt: '', cancellationRequested: false, lastEventSequence: 5, ...outcome } });
describe('Coding monitor durable projections', () => {
  it('never presents code archival as Sandbox execution or result acceptance', () => {
    const events=[event(1,{type:'CODE_READY',proposedOperationId:'cline-code',codeSha256:'hash',fileName:'main.py'}),
      event(2,{}, {type:'CODE_COMMITTED',operationId:'cline-code'})];
    expect(codeVersions(events)[0].state).toBe('CODE_ARCHIVED');
    expect(codingStage(task(),events)).toBe('CODE_ARCHIVED');
    expect(codeVersions([...events,event(3,{type:'TOOL_FINISHED',status:'SUCCEEDED',resultAvailable:false},
      {operationId:'cline-code'})])[0].state).toBe('EXECUTED');
    expect(codeVersions([...events,event(3,{type:'TOOL_FINISHED',status:'SUCCEEDED',resultAvailable:true},
      {operationId:'cline-code'})])[0].state).toBe('RESULT_READY');
  });
  it('restores actual identities and usage from Java receipts when Node completion telemetry was lost', () => {
    const events = [event(2, { type: 'MODEL_STARTED', modelCall: 2, attemptId: 'task:fence-1' })];
    const calls = modelCalls(events, [1, 2].map(ordinal => ({ operationId: `model-${ordinal}`, attemptId: 'task:fence-1',
      modelCall: ordinal, state: 'SUCCEEDED', providerModel: `actual-${ordinal}`, protocol: 'openai',
      finishedAt: '2026-10-19T01:00:02Z', usage: { inputTokens: ordinal }, source: 'JAVA_MODEL_RECEIPT' as const })));
    expect(calls).toHaveLength(2);
    expect(calls).toMatchObject([{ providerModel: 'actual-1', state: 'SUCCEEDED' },
      { providerModel: 'actual-2', state: 'SUCCEEDED', usage: { inputTokens: 2 } }]);
  });
  it('preserves older actual event metadata when a legacy receipt has missing optional fields', () => {
    const calls = modelCalls([event(1, { type: 'MODEL_RESOLVED', modelCall: 1, attemptId: 'task:fence-1', providerModel: 'actual-model' }),
      event(2, { type: 'MODEL_USAGE', modelCall: 1, attemptId: 'task:fence-1', usage: { inputTokens: 7 } })],
      [{ operationId: 'model-1', attemptId: 'task:fence-1', modelCall: 1, state: 'SUCCEEDED',
        providerModel: null as any, usage: {}, source: 'JAVA_MODEL_RECEIPT' }]);
    expect(calls).toMatchObject([{ providerModel: 'actual-model', usage: { inputTokens: 7 }, state: 'SUCCEEDED' }]);
  });
  it('restores out-of-order pages and deduplicates repeated reconnect pages', () => {
    expect(mergeCodingEvents([event(3), event(1)], [event(2), event(3)]).map(e => e.sequence)).toEqual([1, 2, 3]);
  });
  it('does not equate code generation with completion', () => {
    expect(codingStage(task(), [event(1, { type: 'CODE_READY', stage: 'CODE_READY' })])).toBe('CODE_READY');
    expect(codingStage(task({ status: 'COMPLETED' }), [])).toBe('COMPLETED');
    expect(codingStage(task({ status: 'COMPLETED', validationStatus: 'FAILED' }), [])).toBe('FAILED');
    expect(codingStage(task({ status: 'BUDGET_EXCEEDED', reasonCode: 'DEADLINE_EXCEEDED' }), [])).toBe('TIMED_OUT');
    expect(codingStage(task({ cancellationRequested: true }), [])).toBe('CANCELLING');
  });
  it('distinguishes model execution, context requests and Python execution', () => {
    expect(codingStage(task(), [event(1, {}, { type: 'EXECUTION_STARTED', operationId: 'model-1' })])).toBe('CONTEXT_READY');
    expect(codingStage(task(), [event(1, { type: 'MODEL_STARTED' })])).toBe('CODING');
    expect(codingStage(task(), [event(1, { type: 'TOOL_STARTED', tool: 'data_request' })])).toBe('WAITING_CONTEXT');
    expect(codingStage(task(), [event(1, {}, { type: 'EXECUTION_STARTED', operationId: 'cline-abc' })])).toBe('EXECUTING');
    expect(codingStage(task(), [event(1, { stage: 'EXECUTING', source: 'JAVA_RUNTIME' }, { operationId: 'cline-abc' })])).toBe('EXECUTING');
    expect(codingStage(task(), [event(1, { stage: 'RESULT_VALIDATING', source: 'JAVA_RUNTIME' }, { operationId: 'cline-abc' })])).toBe('RESULT_VALIDATING');
  });
  it('tracks returned, executed and failed code versions without storing source in events', () => {
    const events = [event(1, { type: 'CODE_READY', proposedOperationId: 'cline-abc', codeSha256: 'hash', fileName: 'main.py' }),
      event(2, {}, { type: 'EXECUTION_STARTED', operationId: 'cline-abc' }),
      event(3, { type: 'TOOL_FINISHED', status: 'FAILED' }, { operationId: 'cline-abc' })];
    expect(codeVersions(events)).toMatchObject([{ hash: 'hash', state: 'FAILED' }]);
    expect(codeVersions(events)[0]).not.toHaveProperty('content');
  });
  it('keeps actual models and usage separate across fenced attempts', () => {
    const events = [event(1, { type: 'MODEL_STARTED', modelCall: 1, attemptId: 'a' }),
      event(2, { type: 'MODEL_RESOLVED', modelCall: 1, attemptId: 'a', providerModel: 'actual-A' }),
      event(3, { type: 'MODEL_USAGE', modelCall: 1, attemptId: 'a', usage: { inputTokens: 12, outputTokens: 3 } }),
      event(4, { type: 'MODEL_STARTED', modelCall: 1, attemptId: 'b' })];
    expect(modelCalls(events)).toMatchObject([{ providerModel: 'actual-A', state: 'SUCCEEDED', usage: { inputTokens: 12 } }, { state: 'RUNNING' }]);
    expect(modelCalls(events)[1]).not.toHaveProperty('providerModel');
  });
});
