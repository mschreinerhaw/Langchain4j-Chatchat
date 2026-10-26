import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';

const contract = {
  schemaVersion: 'runtime_os.coding.v3', taskId: 'reviewed-task',
  scope: { tenantId: 'tenant', userId: 'owner', runId: 'reviewed-run' },
  objective: { instruction: 'Analyze the authorized immutable input and review the actual report.' },
  inputSnapshotRef: 'runtime-evidence:input', validationPlanRef: 'validation:fixture',
  metadataRefs: ['runtime-coding:analysis-reviewed-path:v1'],
  authorization: { allowedTools: ['python_execute', 'artifact_read'],
    allowedSideEffects: ['CODE_EXECUTION'], allowedDataRefs: [`c${'a'.repeat(63)}`], networkPolicy: 'NONE' },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] },
  budget: { maxModelCalls: 5 },
};
const python = (turn) => ({ toolCalls: [{ id: `code-${turn}`, name: 'python_execute',
  input: { fileName: 'main.py', source: `print(${turn})` } }], usage: { inputTokens: 10, outputTokens: 5 } });
const complete = { text: 'Reviewed the actual report and its limitations.', toolCalls: [],
  usage: { inputTokens: 10, outputTokens: 5 } };
const verified = (version) => ({ status: 'SUCCEEDED', executorStopped: true, resultAvailable: true,
  resultDatasetRef: `runtime-evidence:result-${version}`, validationRecordRef: `validation-${version}`,
  evidenceBundleRef: `bundle-${version}`, validationStatus: 'INCONCLUSIVE' });

function fixture(model, execute, read) {
  const counts = { models: 0, executions: 0, reads: 0, checkpoints: [], finish: null };
  const gateway = {
    async reserveModel() {}, async settleModel() {},
    async checkpoint(_task, snapshot) { counts.checkpoints.push(structuredClone(snapshot)); },
    async preparePython(_task, input) {
      const version = counts.executions + 1, fileName = `analysis_20261026T000000000Z_abcdef012345_v${version}.py`;
      return { fileName, scriptVersion: version, scriptPath: '/workspace/scripts/' + fileName,
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') };
    },
    async model(_task, request) { return model(++counts.models, request); },
    async executePython(_task, input) { return execute(++counts.executions, input); },
    async readArtifact(task, reference) { assert.equal(task.scope.userId, 'owner'); return read(++counts.reads, reference); },
    async finish(_task, outcome) { counts.finish = outcome; },
  };
  return { counts, gateway, run: createCodingRun(contract, gateway, { model: createRuntimeModel(gateway, contract) }) };
}

test('reviewed analysis delivers the actual authorized report to the next semantic turn without a routing model call', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    assert.equal(turn, 2);
    assert.match(JSON.stringify(request.messages), /ACTUAL_COMPUTED_REPORT/);
    assert.match(JSON.stringify(request.messages), /INCONCLUSIVE/);
    assert.match(request.systemPrompt, /finish with one concise confirmation/);
    assert.match(request.systemPrompt, /Keep all required review checks/);
    return complete;
  }, verified, (_read, reference) => {
    assert.equal(reference, 'runtime-evidence:result-1');
    return { reference, complete: true, rows: [{ reportMarkdown: 'ACTUAL_COMPUTED_REPORT' }],
      reportReferenceValidation: { valid: true } };
  });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.models, 2); assert.equal(counts.executions, 1); assert.equal(counts.reads, 1);
  assert.match(counts.finish.operationId, /^cline-[a-f0-9]{64}$/);
  assert(run.events.some(e => e.type === 'TOOL_FINISHED' && e.tool === 'artifact_read' && e.automatic === true));
  assert(counts.checkpoints.some(c => c.lastCollectedOperation
    && JSON.stringify(c.messages).includes('ACTUAL_COMPUTED_REPORT')));
});

test('invalid report references still reach Cline and require a repaired execution and fresh authorized read', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    if (turn === 2) { assert.match(JSON.stringify(request.messages), /UNAUTHORIZED_REFERENCE/); return python(turn); }
    assert.equal(turn, 3); assert.match(JSON.stringify(request.messages), /CORRECTED_REPORT/); return complete;
  }, verified, (read, reference) => ({ reference, complete: true,
    rows: [{ reportMarkdown: read === 1 ? 'INVALID_REPORT' : 'CORRECTED_REPORT' }],
    reportReferenceValidation: { valid: read !== 1, diagnostic: read === 1 ? 'UNAUTHORIZED_REFERENCE' : 'VALID' } }));
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.models, 3); assert.equal(counts.executions, 2); assert.equal(counts.reads, 2);
  const codes = run.events.filter(e => e.type === 'CODE_READY');
  assert.equal(counts.finish.operationId, codes[1].proposedOperationId);
});

test('a stopped process with missing output is repaired before any automatic report read', async () => {
  const { run, counts } = fixture((turn, request) => {
    if (turn === 1) return python(turn);
    if (turn === 2) { assert.match(JSON.stringify(request.messages), /REQUIRED_OUTPUT_MISSING/); return python(turn); }
    return complete;
  }, version => version === 1 ? { status: 'SUCCEEDED', executorStopped: true,
    resultAvailable: false, validationStatus: 'FAILED', outputStatus: 'REQUIRED_OUTPUT_MISSING' } : verified(version),
  (_read, reference) => {
    assert.equal(reference, 'runtime-evidence:result-2');
    return { reference, complete: true, rows: [{ reportMarkdown: 'ACTUAL_REPORT' }], reportReferenceValidation: { valid: true } };
  });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(counts.executions, 2); assert.equal(counts.reads, 1);
});

test('Runtime STOP takes precedence over a contradictory successful receipt and never reads an artifact', async () => {
  const { run, counts } = fixture(turn => python(turn), version => ({ ...verified(version),
    executionFeedback: { repair: { action: 'STOP', allowed: false, reason: 'POLICY_STOP' } } }),
  () => { throw Error('must not read'); });
  assert.equal((await run.run()).status, 'failed');
  assert.equal(counts.models, 1); assert.equal(counts.executions, 1); assert.equal(counts.reads, 0);
});

test('review metadata cannot grant artifact read authority', () => {
  assert.throws(() => createCodingRun({ ...contract, authorization: {
    ...contract.authorization, allowedTools: ['python_execute'] } },
  { reserveModel() {}, executePython() {}, readArtifact() {} }), /authorized Runtime artifact reads/);
});

for (const mode of ['authorization failure', 'different result reference']) {
  test(`automatic artifact ${mode} cannot publish an already stopped Python result`, async () => {
    const { run, counts } = fixture(turn => turn === 1 ? python(turn) : complete, verified,
      () => {
        if (mode === 'authorization failure') throw new Error('ARTIFACT_ACCESS_DENIED');
        return { reference: 'runtime-evidence:another-result', complete: true,
          rows: [{ reportMarkdown: 'WRONG_REPORT' }], reportReferenceValidation: { valid: true } };
      });
    const result = await run.run().catch(error => ({ status: 'failed', error }));
    assert.notEqual(result.status, 'completed');
    assert.equal(counts.executions, 1); assert.equal(counts.reads, 1);
    assert.notEqual(counts.finish?.status, 'completed');
    assert.equal(counts.finish?.operationId ?? null, null);
  });
}
