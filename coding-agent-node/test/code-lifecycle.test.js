import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { CodeHistory, CODE_LIFECYCLE_VERSION } from '../src/code-repair.js';
import { createCodingRun } from '../src/runner.js';
import { sdkPrepareTurn } from '../src/sdk-adapter.js';

const sha = value => createHash('sha256').update(value).digest('hex');
const contract = () => ({ schemaVersion: 'runtime_os.coding.v1', taskId: 'lifecycle-task',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' }, inputSnapshotRef: 'immutable:input',
  objective: { instruction: 'Calculate from authorized files' },
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
  outputContract: { requiredFiles: ['rows.jsonl'] },
  runtimeContext: { codingLifecycle: { schemaVersion: CODE_LIFECYCLE_VERSION, patchOnlyAfterFirst: true } },
  budget: { maxModelCalls: 8, deadlineAt: new Date(Date.now() + 10000).toISOString() } });
const passed = { status: 'SUCCEEDED', executorStopped: true, resultAvailable: true,
  validationStatus: 'INCONCLUSIVE', resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' };
const failed = { status: 'FAILED', executorStopped: true, resultAvailable: false,
  executionFeedback: { repair: { allowed: true, action: 'REPAIR_IN_CURRENT_CONTEXT' } } };
const patch = (version, source, oldText, newText) => ({ repair: {
  parentCodeVersion: version, baseCodeSha256: sha(source), edits: [{ oldText, newText }] } });

test('validated patches survive later failures, rollback, restart and stale-parent rejection', () => {
  const task = contract(); const history = new CodeHistory(task);
  const one = 'value = 1\nanswer = value[0]\nextra = 0';
  history.record(history.propose({ source: one }), { source: one }, { scriptVersion: 1 }, 'op-1'); history.receipt(failed);
  const edit = patch(1, one, 'answer = value[0]', 'answer = value');
  const two = history.propose(edit); history.record(two, edit, { scriptVersion: 2 }, 'op-2'); history.receipt(passed);
  assert.throws(() => history.propose({ source: one }), /PATCH_REQUIRED/);
  assert.throws(() => history.propose(patch(1, one, 'extra = 0', 'extra = 1')), /PATCH_REQUIRED/);
  assert.throws(() => history.propose(patch(2, two, 'answer = value', 'answer = value[0]')), /REGRESSION_ASSERTION/);
  const next = patch(2, two, 'extra = 0', 'extra = 1');
  const three = history.propose(next); history.record(three, next, { scriptVersion: 3 }, 'op-3'); history.receipt(failed);
  const saved = structuredClone(history.snapshot()); const restored = new CodeHistory(task, saved);
  const rollback = { rollback: { parentCodeVersion: 3, parentCodeSha256: sha(three), targetCodeSha256: sha(two) } };
  assert.equal(restored.propose(rollback), two);
  restored.record(two, rollback, { scriptVersion: 4 }, 'op-4');
  assert.equal(restored.versions.at(-1).status, 'STARTED'); // No old result can certify a rollback.
  restored.receipt(passed);
  assert.equal(restored.versions.at(-1).kind, 'ROLLBACK');
  assert.throws(() => new CodeHistory({ ...task, taskId: 'different-task' }, saved), /binding mismatch/);
  assert.throws(() => new CodeHistory(task, { ...saved, currentSource: 'tampered' }), /source hash/);
});

test('SDK basic compaction retains the latest bound program without another model', async () => {
  const source = '# latest corrected program\nanswer = 1';
  const messages = [{ role: 'user', content: [{ type: 'text', text: 'authorized objective' }] },
    ...Array.from({ length: 8 }, () => ({ role: 'assistant', content: [{ type: 'text', text: 'x'.repeat(24000) }] }))];
  const compact = await sdkPrepareTurn('task', () => ({ source, version: 3 }))({
    agentId: 'task', iteration: 3, messages, tools: [], model: { info: { contextWindow: 4096, maxInputTokens: 3500 } },
    systemPrompt: 'authorized analysis', signal: new AbortController().signal,
  });
  assert.ok(compact);
  assert.ok(JSON.stringify(compact.messages).length < JSON.stringify(messages).length / 4);
  assert.match(JSON.stringify(compact.messages), /authorized objective/);
  assert.match(JSON.stringify(compact.messages), /latest corrected program/);
  assert.equal(messages.length, 9); // Canonical history is not replaced.
});

test('assertion admission limit preserves a recoverable checkpoint without discarding validated guards', () => {
  const task = contract(), history = new CodeHistory(task);
  let source = Array.from({ length: 128 }, (_, i) => `v_${i} = 0`).join('\n') + '\nextra = 0';
  history.record(source, { source }, { scriptVersion: 1 }, 'initial'); history.receipt(passed);
  for (let group = 0; group < 4; group++) {
    const input = { repair: { parentCodeVersion: group + 1, baseCodeSha256: sha(source),
      edits: Array.from({ length: 32 }, (_, i) => ({ oldText: `v_${group * 32 + i} = 0`, newText: `v_${group * 32 + i} = 1` })) } };
    source = history.propose(input);
    history.record(source, input, { scriptVersion: group + 2 }, 'patch-' + group); history.receipt(passed);
  }
  assert.throws(() => history.propose(patch(5, source, 'extra = 0', 'extra = 1')), /REGRESSION_ASSERTION_LIMIT/);
  const restored = new CodeHistory(task, structuredClone(history.snapshot()));
  assert.equal(restored.protectedFragments.length, 128);
  assert.equal(restored.currentSource, source);
});

test('real SDK uses exact patches, exposes local proposal rejection and resumes without replay', async () => {
  const task = contract(), original = 'value = 1\nanswer = value[0]';
  let modelCalls = 0, executions = 0, version = 0; const checkpoints = []; const admissions = []; const finishes = [];
  const gateway = { async reserveModel(_task, value) { admissions.push(value.operationId); }, async settleModel() {},
    async checkpoint(_task, value) { checkpoints.push(structuredClone(value)); },
    async preparePython() { const scriptVersion = ++version; const fileName = `case_20261026T000000000Z_abcdef012345_v${scriptVersion}.py`;
      return { scriptVersion, fileName, scriptPath: '/workspace/scripts/' + fileName,
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async executePython() { return ++executions === 1 ? failed : passed; },
    async finish(_task, outcome) { finishes.push(outcome); } };
  const model = { async *stream(request) {
    modelCalls++;
    if (modelCalls === 1) yield { type: 'tool-call-delta', toolCallId: 'initial', toolName: 'python_execute', input: { source: original } };
    if (modelCalls === 2) {
      assert.ok(request.messages.some(message => message.role === 'tool'));
      assert.match(JSON.stringify(request.messages), /value\[0\]/);
      yield { type: 'tool-call-delta', toolCallId: 'repair', toolName: 'python_execute', input: patch(1, original, 'answer = value[0]', 'answer = value') };
    }
    if (modelCalls === 3) {
      const result = request.messages.flatMap(message => message.content).filter(part => part.type === 'tool-result').at(-1);
      assert.equal(result.output.lastValidatedHash, sha(original.replace('value[0]', 'value')));
      yield { type: 'tool-call-delta', toolCallId: 'regression', toolName: 'python_execute',
        input: patch(2, original.replace('value[0]', 'value'), 'answer = value', 'answer = value[0]') };
    }
    if (modelCalls === 4) {
      assert.match(JSON.stringify(request.messages), /REGRESSION_ASSERTION_FAILED/);
      yield { type: 'text-delta', text: 'Use the latest validated result.' };
    }
    yield { type: 'usage', usage: { inputTokens: 10, outputTokens: 10 } };
    yield { type: 'finish', reason: modelCalls < 4 ? 'tool-calls' : 'stop' };
  } };
  const result = await createCodingRun(task, gateway, { model }).run();
  assert.equal(result.status, 'completed', result.error);
  assert.equal(executions, 2);
  const checkpoint = checkpoints.find(value => value.codeLifecycle?.versions.length === 2);
  assert.ok(checkpoint);
  const restored = await createCodingRun(task, gateway, { checkpoint, model: { async *stream(request) {
    assert.match(JSON.stringify(request.messages), /answer = value/);
    yield { type: 'text-delta', text: 'Recovered validated result.' }; yield { type: 'finish', reason: 'stop' };
  } } }).run();
  assert.equal(restored.status, 'completed', restored.error);
  assert.equal(executions, 2);
  assert.equal(finishes.at(-1).operationId, finishes[0].operationId);
  assert.equal(admissions.at(-1), 'model-' + (checkpoint.modelOrdinal + 1));
});
