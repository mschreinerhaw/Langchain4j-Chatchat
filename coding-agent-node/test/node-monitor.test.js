import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { MonitorStore, createMonitorHandler } from '../src/monitor.ts';
import { createServer } from 'node:http';
import { createMonitorClient } from '../src/monitor-client.ts';
import { createCodingServer } from '../src/server.js';
import { contract } from '../fixtures/monitor-server.mjs';

import { startFixture } from '../fixtures/monitor-server.mjs';
const secret = 'dispatch-secret-0123456789abcdef0123456789';
const monitorToken = 'monitor-secret-0123456789abcdef0123456789';

test('monitor consumes actual SDK observations without granting dispatch authority or leaking private context', async () => {
  const f = await startFixture();
  try {
    assert.equal((await fetch(f.base + '/monitor/')).status, 200);
    assert.equal((await fetch(f.base + '/v1/monitor/node')).status, 401);
    assert.equal((await fetch(f.base + '/v1/monitor/node', { headers: { authorization: `Bearer ${secret}` } })).status, 401);
    assert.equal((await f.post('/v1/runs', {}, monitorToken)).status, 401);
    assert.equal((await f.post('/v1/monitor/node', {}, monitorToken)).status, 405);
    const node = await f.client.node();
    assert.equal(node.authority, 'NODE_OBSERVATION_ONLY'); assert.equal(node.tasks.COMPLETED, 1);
    const { task } = await f.client.task(f.task.id);
    assert.equal(task.status, 'COMPLETED'); assert.equal(task.models.length, 2);
    assert.equal(task.models[0].providerModel, 'actual-model'); assert.equal(task.models[0].usage.inputTokens, 12);
    assert.equal(task.skills.applications, 2); assert.ok(task.skills.loadedAt);
    assert.equal(task.skills.configured[0].skillId, 'aggregate');
    assert.equal(task.codeCount, 1); assert.equal(task.codes[0].source, undefined);
    const code = await f.client.code(task.id, task.codes[0].id);
    assert.equal(code.source, f.source);
    assert.equal(code.sha256, createHash('sha256').update(f.source).digest('hex'));
    const first = await f.client.events(task.id, 0, 2), second = await f.client.events(task.id, first.nextCursor, 2);
    assert.equal(first.items.length, 2); assert.equal(first.hasMore, true);
    assert.ok(second.items.every(event => event.cursor > first.nextCursor));
    assert.equal((await f.client.tasks({ q: 'does-not-exist' })).total, 0);
    assert.equal((await f.client.tasks({ status: 'COMPLETED' })).total, 1);
    const serialized = JSON.stringify(task);
    for (const sensitive of [secret, monitorToken, 'do-not-expose-model-key', 'trusted methodology', 'systemPrompt', 'checkpoint'])
      assert.equal(serialized.includes(sensitive), false, sensitive);
    assert.equal((await fetch(f.base + '/v1/monitor/tasks?limit=999', { headers: { authorization: `Bearer ${monitorToken}` } })).status, 400);
    assert.equal((await f.client.tasks()).items[0].usageKnownCalls, 2);
  } finally { await f.server.shutdown(); }
});

test('browser login uses same-origin HttpOnly sessions; logout revokes the browser cookie', async () => {
  const f = await startFixture();
  try {
    assert.equal((await f.post('/monitor/session', { token: monitorToken }, '', { origin: 'https://other.example' })).status, 403);
    assert.equal((await f.post('/monitor/session', { token: {} })).status, 401);
    const login = await f.post('/monitor/session', { token: monitorToken }, '');
    assert.equal(login.status, 200); const cookie = login.headers.get('set-cookie');
    assert.match(cookie, /HttpOnly/); assert.match(cookie, /SameSite=Strict/); assert.ok(!cookie.includes(monitorToken));
    const view = await fetch(f.base + '/v1/monitor/node', { headers: { cookie } }); assert.equal(view.status, 200);
    const logout = await f.post('/monitor/logout', {}, ''); assert.match(logout.headers.get('set-cookie'), /Max-Age=0/);
    const shell = await fetch(f.base + '/monitor/'); assert.match(shell.headers.get('content-security-policy'), /frame-ancestors 'none'/);
    assert.equal(shell.headers.get('access-control-allow-origin'), null);
  } finally { await f.server.shutdown(); }
});

test('persistent history marks unfinished execution as interrupted and keeps exact code across restart', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'node-monitor-'));
  try {
    const store = new MonitorStore({ directory, maxEvents: 3, maxTasks: 2, maxSourceBytes: 12 });
    const id = store.start(contract, 1, 'NEW_SDK');
    store.event(id, { type: 'MODEL_STARTED', modelCall: 1 });
    store.code(id, { operationId: 'one', fileName: 'main.py', source: '12345678' });
    store.code(id, { operationId: 'two', fileName: 'main.py', source: 'abcdefgh' });
    for (let i = 0; i < 6; i++) store.event(id, { type: 'TOOL_STARTED', operationId: String(i) });
    await store.flush();
    const history = await readFile(join(directory, 'observations.json'), 'utf8'); assert.ok(!history.includes('trusted methodology'));
    const restored = new MonitorStore({ directory, maxEvents: 3, maxTasks: 2, maxSourceBytes: 12 });
    const task = restored.detail(id);
    assert.equal(task.status, 'INTERRUPTED'); assert.equal(task.reconciliationRequired, true);
    assert.equal(task.finishedAt, null); assert.equal(task.models[0].status, 'UNKNOWN');
    assert.equal(task.events.length, 3); assert.equal(task.eventsTruncated, true);
    assert.equal(task.codes[0].sourceRetained, false); assert.equal(restored.source(id, task.codes[1].id).source, 'abcdefgh');
    const second = restored.start({ ...contract, scope: { ...contract.scope, tenantId: 'tenant-2' } }, 1, 'NEW_SDK');
    assert.notEqual(second, id);
    restored.finish(second, 'COMPLETED');
    const third = restored.start({ ...contract, taskId: 'third' }, 1, 'NEW_SDK');
    assert.equal(restored.list('', '', 0, 10).total, 2); assert.ok(third);
    await restored.flush();
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('unknown usage remains null; model failure and observer redaction do not fabricate successful calls', () => {
  const store = new MonitorStore({ secrets: [secret, monitorToken] }); const id = store.start(contract, 1, 'NEW_SDK');
  store.event(id, { type: 'MODEL_STARTED', modelCall: 1 });
  store.event(id, { type: 'MODEL_FAILED', modelCall: 1, message: `Bearer ${secret}`, source: 'never copy this', prompt: 'private' });
  store.finish(id, 'FAILED', monitorToken);
  const task = store.detail(id);
  assert.equal(task.models[0].status, 'FAILED'); assert.equal(task.models[0].usage.inputTokens, null);
  assert.equal(task.usageKnownCalls, 0);
  assert.equal(JSON.stringify(task).includes(secret), false); assert.equal(JSON.stringify(task).includes(monitorToken), false);
});

test('dedicated monitor credential is required and monitoring defaults to disabled', async () => {
  assert.throws(() => createCodingServer({ secret, callbackUrl: 'http://localhost:8080', monitorToken: secret }), /separate/);
  const server = createCodingServer({ secret, callbackUrl: 'http://localhost:8080' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const r = await fetch(`http://127.0.0.1:${server.address().port}/v1/monitor/node`);
    assert.equal(r.status, 503); assert.equal((await r.json()).code, 'MONITOR_DISABLED');
  } finally { await server.shutdown(); }
});

test('retention exposes missing source as 410 and never evicts an active task', async () => {
  const store = new MonitorStore({ maxTasks: 1, maxSourceBytes: 4 });
  const id = store.start(contract, 1, 'NEW_SDK');
  store.code(id, { operationId: 'code-1', fileName: 'main.py', source: '0123456789' });
  assert.equal(store.start({ ...contract, taskId: 'other' }, 1, 'NEW_SDK'), null);
  const handle = createMonitorHandler({ token: monitorToken, store, snapshot: () => store.node(false, 1, 1, 90000) });
  const server = createServer((req, res) => { void handle(req, res); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const code = store.detail(id).codes[0];
    const r = await fetch(`${base}/v1/monitor/tasks/${id}/codes/${code.id}`, { headers: { authorization: `Bearer ${monitorToken}` } });
    assert.equal(r.status, 410); const expired = await r.json();
    assert.equal(expired.sourceRetained, false); assert.equal(expired.sha256, code.sha256); assert.equal(expired.source, undefined);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('monitor persistence failure reports degradation without changing execution state', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'node-monitor-write-'));
  try {
    const blocker = join(directory, 'not-a-directory'); await writeFile(blocker, 'blocked');
    const store = new MonitorStore({ directory: blocker }); const id = store.start(contract, 1, 'NEW_SDK');
    store.finish(id, 'COMPLETED'); await store.flush();
    assert.equal(store.node(false, 0, 1, 90000).history.persistence, 'DEGRADED');
    assert.equal(store.detail(id).status, 'COMPLETED');
  } finally { await rm(directory, { recursive: true, force: true }); }
});
