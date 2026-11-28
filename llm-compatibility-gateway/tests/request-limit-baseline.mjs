// Run after npm run build. This starts the unmodified Gateway HTTP server.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return server.address().port;
}

async function reserve() {
  const reserved = createServer();
  const port = await listen(reserved);
  await new Promise(resolve => reserved.close(resolve));
  return port;
}

const limit = 2048;
const adminKey = 'fixture-admin-password';
const clientKey = 'fixture-budget-client';
const dataDir = await mkdtemp(join(tmpdir(), 'gw-budget-baseline-'));
let upstreamCalls = 0;
const upstream = createServer(async (req, res) => {
  for await (const chunk of req) void chunk;
  upstreamCalls++;
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ choices: [{ index: 0, message: { content: 'ok' }, finish_reason: 'stop' }] }));
});
const origin = 'http://127.0.0.1:' + await listen(upstream);
const api = 'http://127.0.0.1:' + await reserve();
const admin = 'http://127.0.0.1:' + await reserve();
const child = spawn(process.execPath, ['dist/server/main.js'], {
  env: { ...process.env, DATA_DIR: dataDir, GATEWAY_HTTP_ONLY: 'true',
    HTTP_TEST_PORT: new URL(api).port, ADMIN_PORT: new URL(admin).port,
    GATEWAY_MAX_REQUEST_BYTES: String(limit), UPSTREAM_ALLOWED_ORIGINS: origin,
    ADMIN_PASSWORD: adminKey, CONFIG_ENCRYPTION_KEY: 'a'.repeat(64), GATEWAY_API_KEY: clientKey },
  stdio: ['ignore', 'pipe', 'pipe'],
});
const exited = once(child, 'exit');
let output = '';
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { output += chunk; });
const request = (url, options) => fetch(url, { ...options, signal: AbortSignal.timeout(5000) });
const json = { 'content-type': 'application/json' };
try {
  const deadline = Date.now() + 5000;
  while (!output.includes(' ready') && child.exitCode === null && Date.now() < deadline) await delay(20);
  assert.match(output, /Gateway .* ready/);
  const login = await request(admin + '/admin/login', { method: 'POST', headers: json,
    body: JSON.stringify({ password: adminKey }) });
  assert.equal(login.status, 200);
  const headers = { ...json, cookie: login.headers.get('set-cookie').split(';')[0] };
  const revision = await (await request(admin + '/admin/config', { headers })).json();
  const configured = await request(admin + '/admin/config', { method: 'PUT', headers,
    body: JSON.stringify({ version: revision.version, config: {
      services: [{ id: 'fixture', name: 'fixture', origin }],
      models: [{ alias: 'low', upstream: 'vendor-low', service: 'fixture' }],
    } }),
  });
  assert.equal(configured.status, 200, await configured.text());

  const payload = { model: 'low', messages: [{ role: 'system', content: 'two inputs' },
    { role: 'user', content: '一'.repeat(200) }, { role: 'user', content: '' }] };
  const fixedBytes = Buffer.byteLength(JSON.stringify(payload));
  const results = [];
  for (const bytes of [limit - 1, limit, limit + 1, limit + 1, limit * 2, limit - 1]) {
    payload.messages[2].content = 'x'.repeat(bytes - fixedBytes);
    const body = JSON.stringify(payload);
    const before = upstreamCalls;
    const response = await request(api + '/v1/chat/completions', { method: 'POST',
      headers: { ...json, authorization: 'Bearer ' + clientKey }, body });
    const answer = await response.json();
    results.push({ bytes: Buffer.byteLength(body), status: response.status,
      forwarded: upstreamCalls > before, code: answer.error?.code ?? null });
  }
  console.log(JSON.stringify(results, null, 2));
  assert.deepEqual(results.map(row => row.status), [200, 200, 413, 413, 413, 200]);
  assert.deepEqual(results.map(row => row.forwarded), [true, true, false, false, false, true]);
} finally {
  upstream.closeAllConnections();
  upstream.close();
  if (child.exitCode === null) child.kill();
  await exited;
  await rm(dataDir, { recursive: true, force: true });
}
