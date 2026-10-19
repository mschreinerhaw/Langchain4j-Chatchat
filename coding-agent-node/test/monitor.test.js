import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createCodingRun } from '../src/runner.js';
import { createCodingServer } from '../src/server.js';

const contract = {
  schemaVersion: 'runtime_os.coding.v3', taskId: 'task', scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Calculate an authorized aggregate' }, inputSnapshotRef: 'input',
  authorization: { networkPolicy: 'NONE', allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: [`c${'a'.repeat(63)}`, `c${'b'.repeat(63)}`] },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] }, budget: { maxModelCalls: 3 },
  runtimeContext: { model: { selectedModel: 'selected-by-java' }, skills: { status: 'APPLIED', fingerprint: 'approved-guidance',
    skills: [{ skillId: 'authorized-skill' }], stages: { ANALYSIS: ['Use measured values only'] } } },
};
test('SDK receives task configuration and chunk bindings; final code receipt references exact returned source', async () => {
  const source = '\nprint(42)\n'; let turn = 0, received;
  const run = createCodingRun(contract, {
    reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {},
    executePython: async (_task, value) => { received = value; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
  }, { attemptId: 'task:fence-3', model: { async *stream(request) {
    assert.match(request.systemPrompt, /selected-by-java/); assert.match(request.systemPrompt, /authorized-skill/);
    if (++turn === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'generate-1', toolName: 'python_execute', input: { fileName: 'main.py', source } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else { yield { type: 'text-delta', text: 'Runtime archived result' }; yield { type: 'finish', reason: 'stop' }; }
  } } });
  const result = await run.run();
  assert.equal(received.source, source);
  assert.equal(received.codeSha256, createHash('sha256').update(source).digest('hex'));
  assert.equal(result.taskId, 'task'); assert.equal(result.attemptId, 'task:fence-3');
  assert.equal(result.codeFiles[0].sha256, received.codeSha256);
  assert.ok(result.codeFiles[0].artifactRef.endsWith('/code'));
  assert.ok(run.events.some(e => e.type === 'SKILLS_LOADED'));
  assert.ok(run.events.some(e => e.type === 'SKILLS_APPLIED'));
  assert.ok(run.events.some(e => e.type === 'CODE_READY' && e.codeSha256 === received.codeSha256));
  assert.ok(run.events.every(e => !Object.hasOwn(e, 'source') || e.source === 'NODE_CLINE'));
  assert.equal(JSON.stringify(run.events).includes('print(42)'), false);
});
test('worker graceful shutdown cancels active SDK execution and records finish before exiting', async () => {
  const secret = '0123456789abcdef0123456789abcdef';
  let entered, stopped = false;
  const started = new Promise(resolve => { entered = resolve; });
  const server = createCodingServer({ secret, callbackUrl: 'http://localhost:8080',
    modelFactory: () => ({ async *stream(request) {
      entered();
      await new Promise(resolve => { if (request.signal.aborted) resolve(); else request.signal.addEventListener('abort', resolve, { once: true }); });
      yield { type: 'finish', reason: 'stop' };
    } }),
    gatewayFactory: () => ({ reserveModel: async () => {}, settleModel: async () => {},
      executePython: async () => {}, finish: async () => { stopped = true; } }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const response = await fetch(`${url}/v1/runs`, { method: 'POST', headers: { authorization: `Bearer ${secret}` },
    body: JSON.stringify({ contract, contractHash: 'a'.repeat(64), fencingToken: 1 }) });
  assert.equal(response.status, 202); await response.json(); await started;
  await server.shutdown(2000);
  assert.equal(server.listening, false); assert.equal(stopped, true);
});
