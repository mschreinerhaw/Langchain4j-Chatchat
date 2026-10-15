import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';

const task = {
  taskId: 'task-1',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute the authorized financial total' },
  inputSnapshotRef: 'runtime-evidence:input', validationPlanRef: 'validation:fixture',
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: [`c${'a'.repeat(63)}`], networkPolicy: 'NONE' },
  outputContract: { requiredFiles: ['rows.jsonl'] },
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
        ? { status: 'FAILED', executorStopped: true, technicalError: 'ValueError: bad' }
        : { status: 'SUCCEEDED', executorStopped: true,
          resultDatasetRef: 'runtime-evidence:result',
          validationRecordRef: 'runtime-evidence:validation',
          evidenceBundleRef: 'runtime-evidence:bundle' };
    },
    async finish(_task, item) { requests.push(['finish', item]); },
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
  assert.equal(requests.find(([type]) => type === 'finish')[1].operationId,
    executions[1].operationId);
});

test('Python operation is polled until Docker confirms stop before a result is accepted', async () => {
  let polls = 0;
  let turns = 0;
  const model = { async *stream() {
    if (++turns === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'run-1', toolName: 'python_execute',
        input: { fileName: 'main.py', source: 'print(1)' } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else {
      yield { type: 'text-delta', text: 'done' };
      yield { type: 'finish', reason: 'stop' };
    }
  } };
  const gateway = {
    reserveModel() {}, settleModel() {},
    executePython() { return { status: 'RUNNING', executorStopped: false }; },
    getPython() {
      polls++;
      return { status: 'SUCCEEDED', executorStopped: true,
        resultDatasetRef: 'runtime-evidence:result',
        validationRecordRef: 'runtime-evidence:validation',
        evidenceBundleRef: 'runtime-evidence:bundle' };
    },
    finish(_task, outcome) { assert.match(outcome.operationId, /^cline-[0-9a-f]{64}$/); },
  };
  const run = createCodingRun({ ...task,
    budget: { ...task.budget, deadlineAt: new Date(Date.now() + 10_000).toISOString() },
  }, gateway, { model });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(polls, 1);
});

test('unrecognized or unauthorized tools fail closed before a model call', () => {
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'], networkPolicy: 'NONE',
  } }, {}, { model: { stream() {} } }), /Runtime gateway|Unrecognized/);
  const gateway = { executePython() {}, reserveModel() {}, settleModel() {} };
  assert.throws(() => createCodingRun({ ...task, authorization: {
    allowedTools: ['host_shell'], allowedSideEffects: ['CODE_EXECUTION'], networkPolicy: 'NONE',
  } }, gateway, { model: { stream() {} } }), /Unrecognized Coding tool/);
  const run = createCodingRun({ ...task, authorization: {
    allowedTools: ['python_execute'], allowedSideEffects: [], networkPolicy: 'NONE',
  } }, gateway, { model: { stream() {} } });
  assert.equal(run.snapshot().status, 'idle');
  assert.throws(() => createCodingRun({ ...task, authorization: {
    ...task.authorization, networkPolicy: 'ALLOWLIST',
  } }, gateway, { model: { stream() {} } }), /no-network/);
});

test('metadata tool rejects a reference outside the frozen contract', async () => {
  let reads = 0;
  let turns = 0;
  const model = { async *stream() {
    if (++turns === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'meta-1', toolName: 'metadata_read',
        input: { reference: 'metadata:other' } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else {
      yield { type: 'text-delta', text: 'Metadata was unavailable.' };
      yield { type: 'finish', reason: 'stop' };
    }
  } };
  const gateway = { executePython() {}, reserveModel() {}, settleModel() {},
    readMetadata() { reads++; return {}; } };
  const run = createCodingRun({ ...task, metadataRefs: ['metadata:authorized'],
    authorization: { ...task.authorization, allowedTools: ['metadata_read'], allowedSideEffects: [] },
  }, gateway, { model });
  await run.run();
  assert.equal(reads, 0);
});

test('a failed finish callback preserves the original Runtime admission error', async () => {
  const gateway = {
    executePython() { throw new Error('not reached'); },
    reserveModel() { throw new Error('reserve callback HTTP 404'); },
    finish() { throw new Error('finish callback HTTP 404'); },
  };
  const model = { async *stream() { throw new Error('model must not be called'); } };
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  assert.equal(result.status, 'failed');
  assert.match(result.error, /reserve callback HTTP 404/);
  assert.equal(run.events.at(-1).type, 'RUNTIME_FINISH_FAILED');
});
