// Deterministic model, real installed SDK and real isolated Docker Python.
// The HTTP Sandbox fixture is started by scripts/test-cline-sdk-sandbox.py.
// This does not stand in for Java authority or an external-model acceptance run.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createCodingRun } from '../src/runner.js';
import { CODE_LIFECYCLE_VERSION } from '../src/code-repair.js';
const endpoint = process.env.CLINE_SANDBOX_TEST_URL;
assert.ok(endpoint, 'Start the controlled Docker fixture first');
const hash = x => createHash('sha256').update(x).digest('hex');
const writer = '\nimport json\nfrom pathlib import Path\nPath("/workspace/output/rows.jsonl").write_text(json.dumps({"value": value}) + "\\n")\n';
const cases = [
  ['SyntaxError', 'value = (\n', 'value = (\n', 'value = 6\n'],
  ['IndentationError', 'if True:\nvalue = 6\n', 'if True:\nvalue = 6', 'if True:\n    value = 6'],
  ['NameError', 'value = missing\n', 'value = missing', 'value = 6'],
  ['KeyError', 'value = {}["missing"]\n', 'value = {}["missing"]', 'value = 6'],
  ['TypeError', 'value = "5" + 1\n', 'value = "5" + 1', 'value = 5 + 1'],
  ['ModuleNotFoundError', 'import sdk_fixture_missing_package\nvalue = 6\n', 'import sdk_fixture_missing_package', '# no dependency installation'],
  ['OUTPUT_MISSING', 'value = 6\n', 'value = 6', 'value = 6' + writer],
  ['OUTPUT_SCHEMA', 'value = "6"\n', 'value = "6"', 'value = 6'],
];

async function executeCase(name, source, changes, expectedErrors) {
  let modelCalls = 0, version = 0;
  const executions = [], admissions = [], settlements = [], checkpoints = [];
  let current = source;
  const contract = { schemaVersion: 'runtime_os.coding.v1', taskId: name,
    scope: { tenantId: 'fixture', userId: 'fixture', runId: name }, inputSnapshotRef: 'immutable:synthetic',
    objective: { instruction: 'Return the synthetic integer value 6; repair only the current source' },
    authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
      allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
    outputContract: { requiredFiles: ['rows.jsonl'] },
    runtimeContext: { codingLifecycle: { schemaVersion: CODE_LIFECYCLE_VERSION, patchOnlyAfterFirst: true } },
    budget: { maxModelCalls: changes.length + 3, deadlineAt: new Date(Date.now() + 120000).toISOString() } };
  const gateway = {
    async reserveModel(_, r) { admissions.push(r.operationId); },
    async settleModel(_, r) { settlements.push(r.operationId); },
    async checkpoint(_, c) { checkpoints.push(structuredClone(c)); }, async finish() {},
    async preparePython() { const scriptVersion = ++version, fileName = `fixture_v${scriptVersion}.py`;
      return { scriptVersion, fileName, scriptPath: '/workspace/scripts/' + fileName,
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async executePython(_, request) {
      const reply = await fetch(endpoint + '/execute', { method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ source: request.source, fileName: request.fileName }) });
      assert.equal(reply.status, 200);
      const actual = await reply.json();
      assert.equal(request.codeSha256, hash(request.source));
      executions.push({ source: request.source, codeHash: request.codeSha256, ...actual });
      return { operationId: request.operationId, status: actual.valid ? 'SUCCEEDED' : 'FAILED', executorStopped: true,
        resultAvailable: actual.valid, codeHash: request.codeSha256,
        ...(actual.valid ? { resultDatasetRef: 'fixture:result', validationRecordRef: 'fixture:validation', evidenceBundleRef: 'fixture:evidence' } : {}),
        executionFeedback: { codeHash: request.codeSha256,
          error: actual.valid ? null : { exceptionType: actual.error, stack: [{ file: request.fileName, line: actual.line ?? 1 }] },
          repair: { allowed: !actual.valid, action: actual.valid ? 'COMPLETE' : 'REPAIR_IN_CURRENT_CONTEXT', baseCodeHash: request.codeSha256 } } };
    },
  };
  const model = { async *stream(request) {
    modelCalls++;
    if (modelCalls <= changes.length + 1) {
      let input = { source };
      if (modelCalls > 1) {
        const receipt = request.messages.flatMap(m => m.content).filter(p => p.type === 'tool-result').at(-1);
        assert.equal(receipt.isError, true);
        assert.equal(receipt.output.executionFeedback.error.exceptionType, expectedErrors[modelCalls - 2]);
        assert.equal(receipt.output.executionFeedback.repair.allowed, true);
        const [oldText, newText] = changes[modelCalls - 2];
        input = { repair: { parentCodeVersion: modelCalls - 1, baseCodeSha256: hash(current), edits: [{ oldText, newText }] } };
        current = current.replace(oldText, newText);
      }
      yield { type: 'tool-call-delta', toolCallId: 'exec-' + modelCalls, toolName: 'python_execute', input };
    } else {
      assert.equal(executions.at(-1).valid, true);
      yield { type: 'text-delta', text: 'Verified synthetic value 6 from real Docker execution.' };
    }
    yield { type: 'usage', usage: { inputTokens: 1, outputTokens: 1 } };
    yield { type: 'finish', reason: modelCalls <= changes.length + 1 ? 'tool-calls' : 'stop' };
  } };
  const run = createCodingRun(contract, gateway, { nativeSdk: true, model });
  const result = await run.run();
  assert.equal(result.status, 'completed', result.error);
  assert.equal(executions.length, changes.length + 1);
  assert.equal(new Set(admissions).size, modelCalls); assert.deepEqual(admissions, settlements);
  assert.equal(executions.at(-1).source, current);
  const settled = checkpoints.findLast(c => c.codeLifecycle?.versions.at(-1)?.status === 'VALIDATED');
  assert.ok(settled);
  const before = executions.length;
  const restored = createCodingRun(contract, gateway, { nativeSdk: true, checkpoint: settled, model: { async *stream() {
    yield { type: 'text-delta', text: 'Retained verified execution' }; yield { type: 'finish', reason: 'stop' };
  } } });
  assert.equal((await restored.run()).status, 'completed');
  assert.equal(executions.length, before, 'resume must not replay Python');
}

for (const [name, base, oldText, newText] of cases) test(name, async () => {
  await executeCase(name, base + (name === 'OUTPUT_MISSING' ? '' : writer), [[oldText, newText]], [name]);
});

test('SyntaxError -> TypeError -> KeyError -> success retains every repair and resumes without replay', async () => {
  const source = 'syntax = (\nvalue = "5" + 1\nfee = {}["fee"]\n' + writer;
  await executeCase('multi-round', source, [['syntax = (', 'syntax = 0'], ['"5" + 1', '5 + 1'], ['{}["fee"]', '{}.get("fee", 0)']],
    ['SyntaxError', 'TypeError', 'KeyError']);
});

test('10000-line program executes a local SDK patch in the real Sandbox', async () => {
  const source = Array.from({ length: 10000 }, (_, i) => `# line ${i}\n`).join('') + 'value = "5" + 1\n' + writer;
  await executeCase('large-program', source, [['"5" + 1', '5 + 1']], ['TypeError']);
});
