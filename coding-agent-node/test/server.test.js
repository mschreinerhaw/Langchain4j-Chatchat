import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingServer, httpGateway } from '../src/server.js';
import { createHmac } from 'node:crypto';
import { createServer } from 'node:http';

const secret = '0123456789abcdef0123456789abcdef';
const contract = {
  taskId: 'task', scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute fixed amount' }, inputSnapshotRef: 'input',
  validationPlanRef: 'validation', authorization: { allowedTools: [], allowedSideEffects: [], networkPolicy: 'NONE' },
  budget: { maxModelCalls: 1 },
};

test('Runtime callbacks carry a fresh authenticated request signature', async () => {
  let received;
  const callback = createServer((request, response) => {
    received = { path: request.url, method: request.method, headers: request.headers };
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end('{}');
  });
  await new Promise(resolve => callback.listen(0, '127.0.0.1', resolve));
  try {
    const gateway = httpGateway(`http://127.0.0.1:${callback.address().port}`, secret,
      { scope: contract.scope, taskId: contract.taskId, contractHash: 'a'.repeat(64), fencingToken: 1 });
    await gateway.event({ type: 'MODEL_STARTED' });
    const h = received.headers;
    const expected = createHmac('sha256', secret)
      .update(`${received.method}\n${received.path}\n${h['x-chatchat-timestamp']}\n${h['x-chatchat-nonce']}`)
      .digest('hex');
    assert.equal(received.path, '/internal/coding/cline/event');
    assert.equal(h['x-chatchat-signature'], expected);
    assert.equal(h.authorization, `Bearer ${secret}`);
  } finally {
    await new Promise(resolve => callback.close(resolve));
  }
});

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
    assert.equal((await fetch(`${url}/healthz`)).status, 401);
    assert.deepEqual(await (await fetch(`${url}/healthz`, {
      headers: { authorization: `Bearer ${secret}` },
    })).json(), { status: 'UP' });
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

test('container callback host requires an explicit allowlist', () => {
  assert.throws(() => createCodingServer({ secret,
    callbackUrl: 'http://host.docker.internal:8080' }), /explicitly allowed/);
  const server = createCodingServer({ secret,
    callbackUrl: 'http://host.docker.internal:8080',
    allowedCallbackHosts: ['host.docker.internal'] });
  server.close();
});
