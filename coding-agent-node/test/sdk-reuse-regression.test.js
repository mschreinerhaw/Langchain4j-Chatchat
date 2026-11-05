import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { editProgram } from '../src/sdk-adapter.js';
import { CodeHistory } from '../src/code-repair.js';
import { createCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';
import { proposeProject, decodeProject } from '../src/python-project.js';

const hash = text => createHash('sha256').update(text).digest('hex');
const repair = (source, oldText, newText, parentCodeVersion = 1) => ({ repair: {
  baseCodeSha256: hash(source), parentCodeVersion, edits: [{ oldText, newText }],
} });

for (const count of [1000, 10000]) test(`SDK editor repairs ${count} lines with LF anchors on CRLF without changing unrelated bytes`, async () => {
  const lines = Array.from({ length: count }, (_, i) => `v_${i} = ${i}`);
  const source = lines.join('\r\n') + '\r\n';
  const anchor = `v_${count - 2} = ${count - 2}\nv_${count - 1} = ${count - 1}`;
  const next = await editProgram(repair(source, anchor, `v_${count - 2} = -1\nv_${count - 1} = -2`), source);
  assert.equal(next, source.replace(anchor.replaceAll('\n', '\r\n'),
    `v_${count - 2} = -1\r\nv_${count - 1} = -2`));
  const third = await editProgram(repair(next, 'v_0 = 0', 'v_0 = 42', 2), next);
  assert.equal(third, next.replace('v_0 = 0', 'v_0 = 42'));
  await assert.rejects(editProgram(repair(source, 'v_0 = 0', 'v_0 = 42'), next), /hash/);
});

test('SDK project editor changes two modules and preserves previous corrections, CRLF and the Runtime adapter', async () => {
  const files = [{ path: 'main.py', content: 'from calc import total\r\nprint(total())\r\n' },
    { path: 'calc.py', content: 'def total():\r\n    return 1\r\n' }, { path: '__init__.py', content: '' }];
  const payload = JSON.stringify({ entryPath: 'main.py', files });
  const config = { schemaVersion: 'runtime_os.python.code-project.v1', sha256: hash(payload), payloadChunks: [payload],
    adapterTemplate: readFileSync('../chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py', 'utf8') };
  const first = await proposeProject({ project: { edits: [{ path: 'calc.py',
    oldText: 'def total():\n    return 1', newText: 'def total():\n    return 2' }] } }, null, config);
  const second = await proposeProject({ project: { baseCodeSha256: hash(first.source), parentCodeVersion: 1,
    edits: [{ path: 'main.py', oldText: 'print(total())', newText: 'print(total() + 1)' }] } }, first.source, config);
  const result = decodeProject(second.source, config);
  assert.equal(result.files.find(f => f.path === 'calc.py').content, 'def total():\r\n    return 2\r\n');
  assert.equal(result.files.find(f => f.path === 'main.py').content, 'from calc import total\r\nprint(total() + 1)\r\n');
  for (const path of ['../escape.py', '/tmp/escape.py', 'C:/escape.py', 'link/../../escape.py'])
    await assert.rejects(proposeProject({ project: { edits: [{ path, oldText: 'x', newText: 'y' }] } }, null, config));
  const abort = AbortSignal.abort(new Error('cancelled project edit'));
  await assert.rejects(proposeProject({ project: { edits: [{ path: 'calc.py', oldText: 'return 1', newText: 'return 9' }] } }, null, config, abort), /cancelled/);
});

const contract = () => ({ schemaVersion: 'runtime_os.coding.v1', taskId: 'sdk-reuse',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' }, inputSnapshotRef: 'immutable:input',
  objective: { instruction: 'Calculate authorized input' },
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
  outputContract: { requiredFiles: ['rows.jsonl'] },
  budget: { maxModelCalls: 5, deadlineAt: new Date(Date.now() + 10000).toISOString() } });

test('progress endpoint failure cannot strand model admission or Python execution; required callbacks still fail closed', async () => {
  for (const failAdmission of [false, true]) {
    let calls = 0, executed = 0, settled = 0, finished = 0;
    const task = contract();
    const gateway = {
      async event() { throw new Error('monitor unavailable'); },
      async checkpoint() {},
      async reserveModel() { if (failAdmission) throw new Error('admission denied'); },
      async settleModel() { settled++; },
      async finish() { finished++; },
      async preparePython() { return { scriptVersion: 1, fileName: 'reuse_v1.py', scriptPath: '/workspace/scripts/reuse_v1.py',
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
      async executePython() { executed++; return { status: 'SUCCEEDED', executorStopped: true, resultAvailable: true,
        resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' }; },
      async model() { return ++calls === 1
        ? { toolCalls: [{ id: 'execute', name: 'python_execute', input: { source: 'print(1)' } }], usage: { inputTokens: 1, outputTokens: 1 } }
        : { text: 'done', finishReason: 'STOP', usage: { inputTokens: 1, outputTokens: 1 } }; },
    };
    const run = createCodingRun(task, gateway, { nativeSdk: true, model: createRuntimeModel(gateway, task, 0, { nativeSdk: true }) });
    const result = await run.run();
    assert.equal(result.status, failAdmission ? 'failed' : 'completed');
    assert.equal(executed, failAdmission ? 0 : 1);
    assert.equal(settled, failAdmission ? 0 : 2);
    assert.equal(finished, 1);
    assert.ok(run.events.some(e => e.type === 'TELEMETRY_DELIVERY_FAILED'));
  }
});

test('legacy checkpoints retain lineage and discard unused fragment policy state on the next save', () => {
  const task = contract(), history = new CodeHistory(task), source = 'value = 1';
  history.record(source, { source }, { scriptVersion: 1 }, 'op-1');
  const saved = structuredClone(history.snapshot());
  saved.protectedFragments = ['obsolete'.repeat(1000)]; saved.forbiddenFragments = ['obsolete'];
  saved.versions[0].fragments = ['value = 1']; saved.versions[0].forbidden = ['value = 0'];
  const restored = new CodeHistory(task, saved), next = restored.snapshot();
  assert.equal(restored.currentSource, source);
  assert.equal(next.versions[0].codeHash, hash(source));
  assert.equal(next.versions[0].editCount, 1);
  assert.match(next.versions[0].patchHash, /^[a-f0-9]{64}$/);
  assert.equal(next.protectedFragments, undefined); assert.equal(next.forbiddenFragments, undefined);
  assert.equal(next.versions[0].fragments, undefined); assert.equal(next.versions[0].forbidden, undefined);
  assert.ok(JSON.stringify(next).length < JSON.stringify(saved).length / 4);
});

test('authorized output truncation enters SDK recovery, admits each attempt and executes a tool only once', async () => {
  const task = contract(); let calls = 0, executions = 0;
  const admitted = [], settled = [];
  const gateway = { async reserveModel(_, r) { admitted.push(r.operationId); },
    async settleModel(_, r) { settled.push(r.operationId); }, async finish() {},
    async preparePython() { return { scriptVersion: 1, fileName: 'reuse_v1.py', scriptPath: '/workspace/scripts/reuse_v1.py',
      workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async executePython() { executions++; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' }; },
    async model(_, request) {
      if (++calls === 1) return { error: 'truncated', text: 'partial', finishReason: 'LENGTH', usage: { inputTokens: 10, outputTokens: 5 },
        modelResponseFeedback: { schemaVersion: 'runtime_os.coding.model_feedback.v1', diagnosticCode: 'MODEL_OUTPUT_TRUNCATED', repairAllowed: true, maxRepairAttempts: 2 } };
      if (calls === 2) {
        assert.match(JSON.stringify(request.messages), /token|truncat|concise/i);
        return { toolCalls: [{ id: 'once', name: 'python_execute', input: { source: 'print(1)' } }], usage: { inputTokens: 10, outputTokens: 5 } };
      }
      return { text: 'done', finishReason: 'STOP', usage: { inputTokens: 10, outputTokens: 5 } };
    } };
  const run = createCodingRun(task, gateway, { nativeSdk: true, model: createRuntimeModel(gateway, task, 0, { nativeSdk: true }) });
  const result = await run.run();
  assert.equal(result.status, 'completed', result.error);
  assert.equal(executions, 1); assert.deepEqual(admitted, ['model-1', 'model-2', 'model-3']); assert.deepEqual(settled, admitted);
});
