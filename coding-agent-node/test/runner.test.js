import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';

const task = {
  taskId: 'task-1',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute the authorized financial total' },
  inputSnapshotRef: 'runtime-evidence:input', validationPlanRef: 'validation:fixture',
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'] },
  budget: { maxModelCalls: 4 },
};

test('actual Cline Agent loop repairs a technical Python error through one Runtime tool', async () => {
  const requests = [];
  let modelCalls = 0;
  const model = {
    async *stream(request) {
      modelCalls++;
      assert.deepEqual(request.tools.map(tool => tool.name), ['python_execute']);
      if (modelCalls <= 2) {
        yield { type: 'tool-call-delta', toolCallId: `call-${modelCalls}`,
          toolName: 'python_execute', input: { fileName: 'main.py',
            source: modelCalls === 1 ? 'raise ValueError("bad")' : 'print(20)' } };
        yield { type: 'usage', usage: { inputTokens: 10, outputTokens: 5 } };
        yield { type: 'finish', reason: 'tool-calls' };
      } else {
        yield { type: 'text-delta', text: 'Execution verified by Runtime.' };
        yield { type: 'usage', usage: { inputTokens: 8, outputTokens: 6 } };
        yield { type: 'finish', reason: 'stop' };
      }
    },
  };
  const gateway = {
    async reserveModel(_task, item) { requests.push(['reserve', item]); },
    async settleModel(_task, item) { requests.push(['settle', item]); },
    async executePython(_task, item) {
      requests.push(['execute', item]);
      return item.source.includes('raise')
        ? { status: 'FAILED', technicalError: 'ValueError: bad' }
        : { status: 'SUCCEEDED', resultDatasetRef: 'runtime-evidence:result' };
    },
  };
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  assert.equal(result.status, 'completed');
  assert.match(result.text, /verified/);
  assert.equal(modelCalls, 3);
  const executions = requests.filter(([type]) => type === 'execute').map(([, item]) => item);
  assert.equal(executions.length, 2);
  assert.notEqual(executions[0].operationId, executions[1].operationId);
  assert.equal(requests.filter(([type]) => type === 'reserve').length, 3);
  assert.equal(run.events.filter(event => event.type === 'MODEL_USAGE').length, 3);
});

test('unrecognized or unauthorized tools fail closed before a model call', () => {
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'],
  } }, {}, { model: { stream() {} } }), /Runtime gateway|Unrecognized/);
  const gateway = { executePython() {}, reserveModel() {}, settleModel() {} };
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'],
  } }, gateway, { model: { stream() {} } }), /Unrecognized Coding tool/);
  const run = createCodingRun({ ...task, authorization: {
    allowedTools: ['python_execute'], allowedSideEffects: [],
  } }, gateway, { model: { stream() {} } });
  assert.equal(run.snapshot().status, 'idle');
});
