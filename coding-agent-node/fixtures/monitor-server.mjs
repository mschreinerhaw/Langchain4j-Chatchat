import assert from 'node:assert/strict';
import { createCodingServer } from '../src/server.js';
import { createMonitorClient } from '../src/monitor-client.ts';
const secret = 'dispatch-secret-0123456789abcdef0123456789';
const monitorToken = 'monitor-secret-0123456789abcdef0123456789';
export const contract = {
  schemaVersion: 'runtime_os.coding.v3', taskId: 'task-1', scope: { tenantId: 'tenant-1', userId: 'user-1', runId: 'run-1' },
  objective: { instruction: '<script>window.pwned=1</script> Python aggregate' },
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'], networkPolicy: 'NONE', allowedDataRefs: [`c${'a'.repeat(63)}`] },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] }, budget: { maxModelCalls: 3 },
  runtimeContext: { model: { selectedModel: 'configured-model', apiKey: 'do-not-expose-model-key' },
    skills: { status: 'APPLIED', fingerprint: 'skill-fingerprint', skills: [{ skillId: 'aggregate', name: '指标汇总', version: '1' }], stages: { CODING: ['trusted methodology'] } } },
};
export async function startFixture(options = {}) {
  let count = 0;
  const source = 'import json\nprint("</script><script>window.pwned=1</script>")\n';
  const server = createCodingServer({ secret, monitorToken, callbackUrl: 'http://localhost:8080', ...options,
    modelFactory: () => ({ async *stream() {
      yield { type: 'runtime-model-metadata', metadata: { selectedModel: 'configured-model', providerModel: 'actual-model', protocol: 'openai', durationMs: 42 } };
      if (++count === 1) {
        yield { type: 'tool-call-delta', toolCallId: 'code-1', toolName: 'python_execute', input: { fileName: 'main.py', source } };
        yield { type: 'usage', usage: { inputTokens: 12, outputTokens: 34 } };
        yield { type: 'finish', reason: 'tool-calls' };
      } else {
        yield { type: 'usage', usage: { inputTokens: 9, outputTokens: 8 } };
        yield { type: 'text-delta', text: 'complete' }; yield { type: 'finish', reason: 'stop' };
      }
    } }), gatewayFactory: () => ({
      reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {},
      executePython: async () => ({ status: 'SUCCEEDED', executorStopped: true,
        resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }),
    }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (path, value, token = secret, headers = {}) => fetch(base + path, { method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...headers }, body: JSON.stringify(value) });
  const accepted = await post('/v1/runs', { contract, contractHash: 'a'.repeat(64), fencingToken: 1 });
  assert.equal(accepted.status, 202);
  const client = createMonitorClient({ baseUrl: base, token: monitorToken });
  let task;
  for (let i = 0; i < 100; i++) {
    task = (await client.tasks()).items[0];
    if (task?.status !== 'RUNNING') break;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return { server, base, post, client, task, source, secret, monitorToken };
}

