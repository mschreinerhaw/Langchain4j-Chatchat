import test from 'node:test';
import assert from 'node:assert/strict';
import { summarize } from '../scripts/summarize-metrics.mjs';

test('stage statistics retain unknown measurements and never infer Runtime success from Node completion', () => {
  const result = summarize({ tasks: [{ taskId: 'task', status: 'COMPLETED', models: [{ ordinal: 1, status: 'SUCCEEDED',
    durationMs: 100, firstTokenMs: null, usage: { inputTokens: null, outputTokens: null } }], events: [] }] });
  assert.equal(result.runtimeTasks, null);
  assert.equal(result.models.successRate, 1);
  assert.equal(result.models.firstTokenMs.mean, null);
  assert.equal(result.models.inputTokens.total, null);
});

test('recovery merges admitted ordinals and compares execution success with output validation and Java final status', () => {
  const task = { taskId: 'task', tenantId: 't', userId: 'u', runId: 'r', eventsTruncated: true,
    models: [{ ordinal: 1, status: 'SUCCEEDED', durationMs: 10, usage: { inputTokens: 5, outputTokens: 8 } }],
    events: [{ type: 'TOOL_FINISHED', tool: 'python_execute', operationId: 'op', status: 'SUCCEEDED', resultAvailable: false },
      { type: 'TOOL_TIMING', tool: 'python_execute', toolCallId: 'call', status: 'RETURNED', toolDurationMs: 20 }] };
  const result = summarize({ tasks: [task, structuredClone(task)] }, [{ taskId: 'task', scope: { tenantId: 't', userId: 'u', runId: 'r' },
    status: 'FAILED', revision: 2 }]);
  assert.equal(result.models.observed, 1);
  assert.equal(result.models.inputTokens.total, 5);
  assert.equal(result.python.execution.successRate, 1);
  assert.equal(result.python.validatedOutput.successRate, 0);
  assert.equal(result.runtimeTasks.successRate, 0);
  assert.equal(result.tools.python_execute.durationMs.mean, 20);
  assert.equal(result.historyTruncated, true);
});
