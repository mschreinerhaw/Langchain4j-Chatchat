import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingServer, httpGateway } from '../src/server.js';
import { createHash, createHmac } from 'node:crypto';
import { createServer } from 'node:http';

const secret = '0123456789abcdef0123456789abcdef';
const contract = {
  schemaVersion: 'runtime_os.coding.v1',
  taskId: 'task', scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Compute fixed amount' }, inputSnapshotRef: 'input',
  validationPlanRef: 'validation', authorization: { allowedTools: [], allowedSideEffects: [], networkPolicy: 'NONE' },
  budget: { maxModelCalls: 1 },
};

test('Runtime callback timeout bounds stalled admission while expired tasks can still request cancellation', async () => {
  let calls = 0;
  const callback = createServer((request, response) => {
    calls++;
    if (request.url.endsWith('/cancel-python')) { response.writeHead(200, { 'content-type': 'application/json' }); response.end('{}'); }
  });
  await new Promise(resolve => callback.listen(0, '127.0.0.1', resolve));
  try {
    const gateway = httpGateway(`http://127.0.0.1:${callback.address().port}`, secret, {}, { timeoutMs: 100 });
    await assert.rejects(gateway.reserveModel(contract, {}), /timeout|aborted/i);
    const expired = { ...contract, budget: { deadlineAt: '2020-01-01T00:00:00Z' } };
    const before = calls;
    await assert.rejects(gateway.reserveModel(expired, {}), /deadline reached/);
    assert.equal(calls, before);
    await gateway.cancelPython(expired, 'existing-operation');
    assert.equal(calls, before + 1);
  } finally { callback.closeAllConnections(); await new Promise(resolve => callback.close(resolve)); }
});

test('Runtime callbacks carry a fresh authenticated request signature', async () => {
  let received;
  const callback = createServer(async (request, response) => {
    received = { path: request.url, method: request.method, headers: request.headers };
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    received.body = Buffer.concat(chunks);
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end('{}');
  });
  await new Promise(resolve => callback.listen(0, '127.0.0.1', resolve));
  try {
    const gateway = httpGateway(`http://127.0.0.1:${callback.address().port}`, secret,
      { scope: contract.scope, taskId: contract.taskId, contractHash: 'a'.repeat(64), fencingToken: 1 });
    await gateway.event({ type: 'MODEL_STARTED' });
    const h = received.headers;
    const digest = createHash('sha256').update(received.body).digest('hex');
    const expected = createHmac('sha256', secret)
      .update(`v2\n${received.method}\n${received.path}\n${h['x-chatchat-timestamp']}\n${h['x-chatchat-nonce']}\n${digest}`)
      .digest('hex');
    assert.equal(received.path, '/internal/coding/cline/event');
    assert.equal(h['x-chatchat-signature'], expected);
    assert.equal(h['x-chatchat-signature-version'], '2');
    assert.equal(JSON.parse(received.body).taskId, contract.taskId);
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
    assert.equal((await (await fetch(`${url}/healthz`, {
      headers: { authorization: `Bearer ${secret}` },
    })).json()).status, 'UP');
    assert.equal((await fetch(`${url}/readyz`, { headers: { authorization: `Bearer ${secret}` } })).status, 200);
    assert.equal((await post('/v1/input-capabilities', {}, 'wrong')).status, 401);
    assert.deepEqual(await (await post('/v1/input-capabilities', {})).json(),
      { schemaVersion: 'runtime_os.coding.input.v1', formats: ['PARQUET', 'CSV'] });
    const request = { contract, contractHash: 'a'.repeat(64), fencingToken: 1 };
    const unsupported = await post('/v1/runs', { ...request,
      contract: { ...contract, schemaVersion: 'runtime_os.coding.v2' } });
    assert.equal(unsupported.status, 422);
    assert.equal((await unsupported.json()).code, 'VERSION_UNSUPPORTED');
    assert.equal(calls, 0);
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
      fencingToken: 2 })).status, 202);
    assert.equal((await post('/v1/runs', { ...request, fencingToken: 2 })).status, 202);
    assert.equal((await post('/v1/runs', request)).status, 409);
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

test('session capacity rejects new tasks while retaining active and duplicate identities', async () => {
  let release;
  const server = createCodingServer({ secret, callbackUrl: 'http://127.0.0.1:8080', maxSessions: 1,
    sessionRetentionMs: 0,
    modelFactory: () => ({ async *stream() {
      await new Promise(resolve => { release = resolve; });
      yield { type: 'text-delta', text: 'done' }; yield { type: 'finish', reason: 'stop' };
    } }),
    gatewayFactory: () => ({ executePython: async () => {}, reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {} }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const post = (path, body) => fetch(`http://127.0.0.1:${server.address().port}${path}`, {
    method: 'POST', headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
  const first = { contract, contractHash: 'a'.repeat(64), fencingToken: 1 };
  const second = { ...first, contract: { ...contract, taskId: 'second-task' } };
  try {
    assert.equal((await post('/v1/runs', first)).status, 202);
    for (let i = 0; i < 100 && !release; i++) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal((await post('/v1/runs', second)).status, 503);
    assert.equal((await post('/v1/runs', first)).status, 202);
    release();
    for (let i = 0; i < 100; i++) {
      const view = await (await post('/v1/runs/get', { ...contract, contractHash: first.contractHash, fencingToken: 1 })).json();
      if (view.status !== 'RUNNING') break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal((await post('/v1/runs', second)).status, 202);
  } finally {
    release?.();
    await new Promise(resolve => server.close(resolve));
  }
});

test('new fencing token cannot start a second loop while the old loop is active', async () => {
  let release;
  const server = createCodingServer({ secret, callbackUrl: 'http://127.0.0.1:8080',
    modelFactory: () => ({ async *stream() {
      await new Promise(resolve => { release = resolve; });
      yield { type: 'text-delta', text: 'done' };
      yield { type: 'finish', reason: 'stop' };
    } }),
    gatewayFactory: () => ({ executePython: async () => {}, reserveModel: async () => {}, settleModel: async () => {},
      finish: async () => {} }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const post = (path, value) => fetch(`${url}${path}`, {
    method: 'POST', headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json' },
    body: JSON.stringify(value),
  });
  const first = { contract, contractHash: 'a'.repeat(64), fencingToken: 1 };
  try {
    assert.equal((await post('/v1/runs', first)).status, 202);
    for (let i = 0; i < 100 && !release; i++)
      await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(typeof release, 'function');
    const next = { ...first, fencingToken: 2 };
    assert.equal((await post('/v1/runs', next)).status, 409);
    assert.equal((await post('/v1/runs/get', { ...contract, contractHash: first.contractHash,
      fencingToken: 2 })).status, 200);
    assert.equal((await post('/v1/runs', first)).status, 202);
    release();
    let state;
    for (let i = 0; i < 100; i++) {
      state = (await (await post('/v1/runs/get', { ...contract, contractHash: first.contractHash,
        fencingToken: 2 })).json()).status;
      if (state !== 'RUNNING') break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.notEqual(state, 'RUNNING');
    assert.equal((await post('/v1/runs', next)).status, 202);
  } finally {
    release?.();
    await new Promise(resolve => server.close(resolve));
  }
});
