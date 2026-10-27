import { describe, expect, it } from 'vitest';
import { codingStage, codeVersions, mergeCodingEvents, modelCalls, type CodingEvent, type CodingTask } from './codingMonitor';
const event = (sequence: number, payload = {}, extra = {}): CodingEvent => ({
  eventId: `e${sequence}`, taskId: 'task', sequence, occurredAt: `2026-10-19T01:00:0${sequence}Z`,
  type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'worker', payload, ...extra,
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
