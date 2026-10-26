import test from 'node:test';
import assert from 'node:assert/strict';
import { sample, workspaceTask } from '../scripts/benchmark-workspace-codegen.mjs';
import { createCodingRun } from '../src/runner.js';
import { resolveWorkspaceFiles, restoreWorkspaceFiles } from '../src/sdk-adapter.js';
import { createHash } from 'node:crypto';

for (const language of ['python', 'sql']) {
  test(`${language} workspace uses one native model turn with an authorized prefetch and validated completion`, async () => {
    const before = await sample(false, language);
    const after = await sample(true, language);
    assert.equal(before.status, 'completed'); assert.equal(after.status, 'completed');
    assert.equal(before.modelCalls, 3); assert.equal(after.modelCalls, 1);
    assert.equal(after.workspaceReads, 1); assert.equal(after.proposals, 1);
    assert.equal(after.generatedSource, before.generatedSource);
    assert.match(after.finish.operationId, /^workspace-[0-9a-f]{64}$/);
    assert.equal(after.finish.status, 'completed');
    assert(after.requestBytes < before.requestBytes);
    assert(after.snapshots.some(s => s.lastCollectedOperation && s.messages.some(m => m.role === 'tool')));
    assert.equal(after.events.filter(e => e.type === 'MODEL_TIMING').length, 1);
    const timing = after.events.find(e => e.type === 'MODEL_TIMING');
    assert.equal(timing.modelCall, 1); assert.equal(timing.status, 'SUCCEEDED');
    assert(timing.durationMs >= 0); assert(timing.callbackFirstEventMs >= 0);
    assert(timing.requestBytes > 0); assert(timing.responseBytes > 0); assert.match(timing.requestHash, /^[a-f0-9]{64}$/);
    assert.equal('prompt' in timing, false); assert.equal('sourceCode' in timing, false);
    assert(after.events.some(e => e.type === 'TOOL_TIMING' && e.tool === 'workspace_read'));
  });
  test(`${language} parser failures repair in the same SDK session without resetting ordinals or re-reading`, async () => {
    const after = await sample(true, language, 2);
    assert.equal(after.status, 'completed'); assert.equal(after.modelCalls, 3);
    assert.equal(after.workspaceReads, 1); assert.equal(after.proposals, 3);
    assert.equal(new Set(after.snapshots.map(s => s.sdkSessionId)).size, 1);
    assert.deepEqual(after.events.filter(e => e.type === 'MODEL_STARTED').map(e => e.modelCall), [1, 2, 3]);
    assert(after.snapshots.some(s => JSON.stringify(s.messages).includes('SYNTAX')));
  });
}

test('valid=true without required Evidence references cannot complete the SDK fast path', async () => {
  let calls = 0, finish;
  const gateway = { reserveModel() {}, settleModel() {}, readWorkspace() { return { context: 'captured' }; },
    proposeWorkspace() { return { valid: true, resultAvailable: true, evidenceBundleRef: 'only-one-reference' }; },
    finish(_task, value) { finish = value; } };
  const task = workspaceTask(true); task.budget.maxModelCalls = 2;
  const model = { async *stream() {
    calls++; yield { type: 'tool-call-delta', toolCallId: 'invalid-' + calls, toolName: 'workspace_propose',
      input: { files: [{ path: 'main.py', content: 'pass' }], notes: '' } };
    yield { type: 'finish', reason: 'tool-calls' };
  } };
  const result = await createCodingRun(task, gateway, { model }).run();
  assert.notEqual(result.status, 'completed'); assert.equal(calls, 2); assert.equal(finish.operationId, null);
});

test('prefetch failure cannot retry an unknown operation or call a model', async () => {
  let reads = 0, models = 0, finish;
  const gateway = { reserveModel() {}, settleModel() {},
    readWorkspace() { reads++; throw new Error('authorization denied'); }, proposeWorkspace() {},
    finish(_task, value) { finish = value; } };
  const run = createCodingRun(workspaceTask(true), gateway, { model: { async *stream() { models++; } } });
  await assert.rejects(run.run(), /authorization denied/);
  assert.equal(reads, 1); assert.equal(models, 0); assert.equal(finish.status, 'failed');
});

test('expired or cancelled tasks never prefetch or start inference', async () => {
  let reads = 0, models = 0;
  const gateway = { reserveModel() {}, settleModel() {}, readWorkspace() { reads++; }, proposeWorkspace() {}, finish() {} };
  for (const cancelled of [false, true]) {
    const task = workspaceTask(true);
    if (!cancelled) task.budget.deadlineAt = '2020-01-01T00:00:00Z';
    const run = createCodingRun(task, gateway, { model: { async *stream() { models++; } } });
    if (cancelled) run.abort('user cancelled');
    await assert.rejects(run.run(), /deadline|cancelled/i);
  }
  assert.equal(reads, 0); assert.equal(models, 0);
});

test('model exceptions still emit bounded failed-call timing', async () => {
  const gateway = { reserveModel() {}, settleModel() {}, readWorkspace() { return { context: 'captured' }; },
    proposeWorkspace() {}, finish() {} };
  const run = createCodingRun(workspaceTask(true), gateway, { model: { async *stream() { throw new Error('provider timeout'); } } });
  assert.equal((await run.run()).status, 'failed');
  assert(run.events.some(e => e.type === 'MODEL_TIMING' && e.status === 'FAILED' && e.responseBytes === 0));
});

test('SDK workspace patches retain other files and reconstruct from canonical checkpoint tool pairs', async () => {
  const original = new Map([['a.py', 'value = 1\nassert value > 0\n'], ['b.py', 'unchanged = True\n']]);
  const repair = { path: 'a.py', repair: {
    baseCodeSha256: createHash('sha256').update(original.get('a.py')).digest('hex'),
    edits: [{ oldText: 'value = 1', newText: 'value = 2' }] } };
  const next = await resolveWorkspaceFiles([repair], original);
  assert.equal(next.get('a.py'), 'value = 2\nassert value > 0\n');
  assert.equal(next.get('b.py'), original.get('b.py'));
  await assert.rejects(resolveWorkspaceFiles([repair], next), /hash mismatch/);
  await assert.rejects(resolveWorkspaceFiles([{ path: 'a.py', content: 'regenerated' }], next), /exact repair/);
  const restored = await restoreWorkspaceFiles([
    { content: [{ type: 'tool-call', toolName: 'workspace_propose', toolCallId: 'initial',
      input: { files: [...original].map(([path, content]) => ({ path, content })) } }] },
    { content: [{ type: 'tool-result', toolCallId: 'initial', output: { valid: false } }] },
    { content: [{ type: 'tool-call', toolName: 'workspace_propose', toolCallId: 'repair', input: { files: [repair] } }] },
    { content: [{ type: 'tool-result', toolCallId: 'repair', output: { valid: true } }] },
  ]);
  assert.deepEqual(restored, next);
  assert.equal(original.get('a.py'), 'value = 1\nassert value > 0\n');
});

test('cancellation during authorized prefetch stops before inference', async () => {
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  let models = 0;
  const gateway = { reserveModel() {}, settleModel() {}, proposeWorkspace() {}, finish() {},
    readWorkspace(_task, _id, signal) { entered(); return new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    }); } };
  const run = createCodingRun(workspaceTask(true), gateway, { model: { async *stream() { models++; } } });
  const pending = run.run(); await started; run.abort('user cancelled');
  await assert.rejects(pending, /user cancelled/); assert.equal(models, 0);
});

test('task deadline aborts prefetch without extending the model budget', async () => {
  let models = 0;
  const gateway = { reserveModel() {}, settleModel() {}, proposeWorkspace() {}, finish() {},
    readWorkspace(_task, _id, signal) { return new Promise((_resolve, reject) => {
      const guard = setTimeout(() => reject(new Error('deadline did not abort prefetch')), 1000);
      signal.addEventListener('abort', () => { clearTimeout(guard); reject(signal.reason); }, { once: true });
    }); } };
  const task = workspaceTask(true); task.budget.deadlineAt = new Date(Date.now() + 40).toISOString();
  const run = createCodingRun(task, gateway, { model: { async *stream() { models++; } } });
  await assert.rejects(run.run(), /timeout|aborted/i); assert.equal(models, 0);
});
