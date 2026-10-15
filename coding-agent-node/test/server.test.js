import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingServer } from '../src/server.js';

const secret = '0123456789abcdef0123456789abcdef';
const contract = {
  taskId: 'task', scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute fixed amount' }, inputSnapshotRef: 'input',
  validationPlanRef: 'validation', authorization: { allowedTools: [], allowedSideEffects: [] },
  budget: { maxModelCalls: 1 },
};

test('Node HTTP boundary authenticates, dispatches once, and hides SDK session IDs', async () => {
  let calls = 0;
  const server = createCodingServer({ secret, callbackUrl: 'http://127.0.0.1:8080',
    modelFactory: () => ({ async *stream() {
      calls++;
      yield { type: 'text-delta', text: 'done' };
      yield { type: 'usage', usage: { inputTokens: 1, outputTokens: 1 } };
      yield { type: 'finish', reason: 'stop' };
    } }),
    gatewayFactory: () => ({ executePython: async () => {}, reserveModel: async () => {},
      settleModel: async () => {}, finish: async () => {} }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const post = (path, value, credential = secret) => fetch(`${url}${path}`, {
    method: 'POST', headers: { authorization: `Bearer ${credential}`, 'content-type': 'application/json' },
    body: JSON.stringify(value),
  });
  try {
    const request = { contract, contractHash: 'a'.repeat(64), fencingToken: 1 };
    assert.equal((await post('/v1/runs', request, 'wrong')).status, 401);
    const accepted = await post('/v1/runs', request);
    assert.equal(accepted.status, 202, await accepted.text());
    assert.equal((await post('/v1/runs', request)).status, 202);
    let view;
    for (let i = 0; i < 30; i++) {
      view = await (await post('/v1/runs/get', { ...contract, contractHash: request.contractHash,
        fencingToken: request.fencingToken })).json();
      if (view.status === 'COMPLETED') break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(view.status, 'COMPLETED');
    assert.equal(calls, 1);
    assert.equal('agentId' in view, false);
    assert.equal((await post('/v1/runs/cancel', { ...contract, contractHash: request.contractHash,
      fencingToken: 2 })).status, 404);
    assert.equal((await post('/v1/runs', { ...request, fencingToken: 2 })).status, 409);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});
