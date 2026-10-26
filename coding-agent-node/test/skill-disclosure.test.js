import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';

function fixture(useSkill = true) {
  const contract = { schemaVersion: 'runtime_os.coding.v3', taskId: 'skill-task',
    scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
    objective: { instruction: 'Compute from the authorized input.' },
    authorization: { allowedTools: ['python_execute', 'skill_read'], allowedSideEffects: ['CODE_EXECUTION'],
      allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
    outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] }, budget: { maxModelCalls: 5 },
    runtimeContext: { skills: { status: 'AVAILABLE', skills: [
      { id: 'method', name: 'Sum', version: 'v1', contentDelivery: 'ON_DEMAND' },
      { id: 'unused', name: 'Unused capability', version: 'v1', contentDelivery: 'ON_DEMAND' },
    ] } } };
  let models = 0, reads = 0, executions = 0;
  const calls = [];
  const gateway = { async reserveModel() {}, async settleModel() {}, async finish() {},
    async preparePython() { return { fileName: 'sum_20261026T000000000Z_abcdef012345_v1.py', scriptVersion: 1,
      scriptPath: '/workspace/scripts/sum_20261026T000000000Z_abcdef012345_v1.py',
      workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async readSkill(task, id, path) {
      reads++; calls.push([task.scope.tenantId, task.scope.userId, id, path]);
      return { skillId: id, version: 'v1', instructions: 'ON_DEMAND_METHOD_BODY' };
    },
    async executePython() { executions++; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
  };
  const model = { async *stream(request) {
    models++;
    if (models === 1) assert.doesNotMatch(JSON.stringify(request.messages), /ON_DEMAND_METHOD_BODY/);
    if (useSkill && models === 1)
      yield { type: 'tool-call-delta', toolCallId: 'skill', toolName: 'skill_read', input: { skillId: 'method' } };
    else if (models === (useSkill ? 2 : 1)) {
      if (useSkill) assert.match(JSON.stringify(request.messages), /ON_DEMAND_METHOD_BODY/);
      yield { type: 'tool-call-delta', toolCallId: 'code', toolName: 'python_execute', input: { fileName: 'main.py', source: 'print(153.17)' } };
    } else yield { type: 'text-delta', text: 'Computed.' };
    yield { type: 'finish', reason: 'stop' };
  } };
  return { contract, gateway, model, calls, counts: () => ({ models, reads, executions }) };
}

test('Cline loads only its requested Skill in the same model loop', async () => {
  const f = fixture(); const run = createCodingRun(f.contract, f.gateway, { model: f.model });
  assert.equal((await run.run()).status, 'completed');
  assert.deepEqual(f.counts(), { models: 3, reads: 1, executions: 1 });
  assert.deepEqual(f.calls, [['tenant', 'owner', 'method', '']]);
  assert.equal(run.events.filter(e => e.type === 'SKILLS_LOADED').length, 1);
});

test('available unused Skills make zero content reads', async () => {
  const f = fixture(false);
  assert.equal((await createCodingRun(f.contract, f.gateway, { model: f.model }).run()).status, 'completed');
  assert.deepEqual(f.counts(), { models: 2, reads: 0, executions: 1 });
});

test('Skill capabilities do not grant additional tools', () => {
  const f = fixture(); f.contract.authorization.allowedTools.push('shell_execute');
  assert.throws(() => createCodingRun(f.contract, f.gateway, { model: f.model }), /Unrecognized Coding tool/);
});
