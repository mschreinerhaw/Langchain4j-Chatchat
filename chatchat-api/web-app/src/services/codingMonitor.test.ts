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
