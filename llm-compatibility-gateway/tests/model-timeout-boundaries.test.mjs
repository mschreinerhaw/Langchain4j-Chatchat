import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
}

test('isolated Gateway total deadlines, first output and cancellation', { timeout: 335_000 }, async t => {
  const root = await mkdtemp(join(tmpdir(), 'gateway-timeout-acceptance-'));
  const disconnected = new Set();
  const upstream = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const mode = JSON.parse(Buffer.concat(chunks)).messages[0].content;
    let pulse;
    let finish;
    response.once('close', () => {
      clearInterval(pulse);
      clearTimeout(finish);
      if (!response.writableEnded) disconnected.add(mode);
    });
    if (mode === 'cancel') return;
    if (mode === 'heartbeat') {
      response.writeHead(200, { 'content-type': 'text/event-stream' });
      response.write(': thinking\n\n');
      pulse = setInterval(() => response.write(': thinking\n\n'), 2_000);
      return;
    }
    finish = setTimeout(() => {
      response.writeHead(200, { 'content-type': 'text/event-stream' });
      response.end('data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');
    }, Number(mode) * 1_000);
  });
  const origin = 'http://127.0.0.1:' + await listen(upstream);
  const portServers = [createServer(), createServer()];
  const [apiPort, adminPort] = await Promise.all(portServers.map(listen));
  await Promise.all(portServers.map(server => new Promise(resolve => server.close(resolve))));
  const child = spawn(process.execPath, ['dist/server/main.js'], {
    env: { ...process.env, DATA_DIR: root, GATEWAY_HTTP_ONLY: 'true',
      HTTP_TEST_PORT: String(apiPort), ADMIN_PORT: String(adminPort), UPSTREAM_ALLOWED_ORIGINS: origin,
      ADMIN_PASSWORD: 'fixture-admin-timeout-key', CONFIG_ENCRYPTION_KEY: 'b'.repeat(64),
      GATEWAY_API_KEY: 'fixture-client-timeout-key' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const exited = once(child, 'exit');
  let output = '';
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { output += chunk; });
  t.after(async () => {
    upstream.closeAllConnections();
    upstream.close();
    if (child.exitCode === null) child.kill();
    await exited;
    await rm(root, { recursive: true, force: true });
  });
  for (let i = 0; i < 100 && !output.includes('ready'); i++) await delay(50);
  assert.match(output, /Gateway .* ready/);
  const admin = 'http://127.0.0.1:' + adminPort;
  const api = 'http://127.0.0.1:' + apiPort;
  const json = { 'content-type': 'application/json' };
  const login = await fetch(admin + '/admin/login', {
    method: 'POST', headers: json, body: JSON.stringify({ password: 'fixture-admin-timeout-key' }),
  });
  assert.equal(login.status, 200);
  const adminHeaders = { ...json, cookie: login.headers.get('set-cookie').split(';')[0] };
  const revision = (await (await fetch(admin + '/admin/config', { headers: adminHeaders })).json()).version;
  const models = [
    { alias: 'one-minute', requestTimeoutMinutes: 1 },
    { alias: 'default' },
    { alias: 'unlimited', requestTimeoutMinutes: -1 },
  ].map(model => ({ ...model, service: 'fixture', upstream: 'fixture' }));
  const published = await fetch(admin + '/admin/config', {
    method: 'PUT', headers: adminHeaders, body: JSON.stringify({ version: revision,
      config: { services: [{ id: 'fixture', name: 'fixture', origin }], models } }),
  });
  assert.equal(published.status, 200);
  assert.equal((await published.json()).config.models.find(model => model.alias === 'default').requestTimeoutMinutes, 5);
  const chat = (model, content, signal) => new Promise((resolve, reject) => {
    const body = JSON.stringify({ model, stream: true, messages: [{ role: 'user', content }] });
    const request = httpRequest(api + '/v1/chat/completions', {
      method: 'POST', signal, headers: { ...json, authorization: 'Bearer fixture-client-timeout-key',
        'content-length': Buffer.byteLength(body) },
    }, response => {
      const parts = [];
      response.on('data', part => parts.push(part));
      response.once('end', () => resolve(new Response(Buffer.concat(parts), {
        headers: response.headers, status: response.statusCode,
      })));
      response.once('error', reject);
    });
    request.once('error', reject);
    request.end(body);
  });
  const cases = [
    ['one-minute', '59', true], ['one-minute', '60', null], ['one-minute', '61', false],
    ['one-minute', 'heartbeat', false], ['unlimited', '90', true], ['unlimited', '120', true],
    ['unlimited', '180', true], ['default', '298', true], ['default', '302', false],
  ];
  const responses = cases.map(async ([model, input, shouldComplete]) => {
    const started = performance.now();
    const response = await chat(model, input);
    const body = await response.text();
    const requestId = response.headers.get('x-request-id');
    const elapsedMs = Math.round(performance.now() - started);
    const completed = body.includes('[DONE]');
    t.diagnostic(JSON.stringify({ model, input, requestId, elapsedMs, status: response.status, completed }));
    if (shouldComplete != null) assert.equal(completed, shouldComplete);
    if (!completed) assert.match(body, /model_request_timeout/);
    return { requestId, input, completed };
  });
  const controller = new AbortController();
  const cancellation = assert.rejects(chat('unlimited', 'cancel', controller.signal), { name: 'AbortError' });
  await delay(1_000);
  controller.abort();
  await cancellation;
  for (let i = 0; i < 30 && !disconnected.has('cancel'); i++) await delay(100);
  assert.ok(disconnected.has('cancel'));
  const results = await Promise.all(responses);
  const { metrics } = await (await fetch(admin + '/admin/overview', { headers: adminHeaders })).json();
  const heartbeats = metrics.find(row => row.id === results.find(result => result.input === 'heartbeat').requestId);
  assert.ok(Number.isFinite(heartbeats.firstSseEventMs));
  assert.equal(heartbeats.firstTokenMs, undefined);
  assert.equal(heartbeats.terminationComponent, 'gateway_model_deadline');
});
