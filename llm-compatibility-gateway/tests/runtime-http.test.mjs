import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';

async function listen(server) {
 await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
 return server.address().port;
}

test('real Gateway HTTP routes pin configuration and correlate Runtime requests', { timeout: 10000 }, async t => {
 const root = await mkdtemp(join(tmpdir(), 'gateway-runtime-http-'));
 const calls = [];
 let release;
 const gate = new Promise(resolve => { release = resolve; });
 const mock = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const body = JSON.parse(Buffer.concat(chunks).toString());
  calls.push({ model: body.model, leakedRuntimeHeaders: Object.keys(request.headers).some(name => name.startsWith('x-runtime-')) });
  if (body.messages[0].content === 'hold') await gate;
  response.writeHead(200, { 'content-type': 'application/json' });
  response.end(JSON.stringify({ model:body.model, choices:[{ index:0, finish_reason:'stop', message:{ content:'ok' } }] }));
 });
 const mockOrigin = 'http://127.0.0.1:' + await listen(mock);
 const reservedApi = createServer();
 const reservedAdmin = createServer();
 const apiPort = await listen(reservedApi);
 const adminPort = await listen(reservedAdmin);
 await Promise.all([reservedApi, reservedAdmin].map(server => new Promise(resolve => server.close(resolve))));
 const child = spawn(process.execPath, ['dist/server/main.js'], { env:{ ...process.env, DATA_DIR:root,
  GATEWAY_HTTP_ONLY:'true', HTTP_TEST_PORT:String(apiPort), ADMIN_PORT:String(adminPort), UPSTREAM_ALLOWED_ORIGINS:mockOrigin,
  ADMIN_PASSWORD:'fixture-admin-password', CONFIG_ENCRYPTION_KEY:'a'.repeat(64),
  GATEWAY_CLIENTS:JSON.stringify([{ id:'runtime', key:'fixture-runtime-key', models:['*'], mode:'runtime_managed' },
    { id:'ordinary', key:'fixture-ordinary-key', models:['*'] }]),
 }, stdio:['ignore','pipe','pipe'] });
 const exited = once(child, 'exit');
 let output = '';
 for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { output += chunk; });
 t.after(async () => {
  release();
  mock.closeAllConnections();
  mock.close();
  if (child.exitCode === null) child.kill();
  await exited;
  await rm(root, { recursive:true, force:true });
 });
 const api = 'http://127.0.0.1:' + apiPort;
 const admin = 'http://127.0.0.1:' + adminPort;
 const window = Date.now() + 4000;
 while (!output.includes('ready') && child.exitCode === null && Date.now() < window) await delay(20);
 assert.match(output, /Gateway .* ready/);
 const json = { 'content-type':'application/json' };
 const login = await fetch(admin + '/admin/login', { method:'POST', headers:json, body:JSON.stringify({ password:'fixture-admin-password' }) });
 assert.equal(login.status,200);
 const adminHeaders = { ...json, cookie:login.headers.get('set-cookie').split(';')[0] };
 const alias = 'Team/Model:中文版';
 const configuration = upstream => ({ services:[{ id:'fixture',name:'fixture',origin:mockOrigin,firstMs:100,idleMs:100 }],
  models:[{ alias, upstream, service:'fixture' }] });
 const publish = async (version, upstream) => {
  const published = await fetch(admin + '/admin/config', { method:'PUT',headers:adminHeaders,
   body:JSON.stringify({ version, config:configuration(upstream) }) });
  assert.equal(published.status,200,await published.text());
 };
 const initial = await (await fetch(admin + '/admin/config', { headers:adminHeaders })).json();
 await publish(initial.version,'vendor/old:v1');
 const chat = (content, key, trace) => fetch(api + '/v1/chat/completions', { method:'POST', headers:{ ...json,
  authorization:'Bearer ' + key, 'x-runtime-task-id':'native-task', 'x-runtime-session-id':'native-session',
  'x-runtime-attempt-id':'native-task-fence-1', 'x-runtime-request-id':trace },
  body:JSON.stringify({ model:alias,messages:[{ role:'user', content }] }) });
 const held = chat('hold','fixture-runtime-key','runtime-42');
 while (!calls.length && Date.now() < window) await delay(10);
 assert.equal(calls.length,1);
 await publish(initial.version + 1,'vendor/new:v2');
 await delay(160);
 release();
 const old = await held;
 const gatewayRequestId = old.headers.get('x-request-id');
 assert.equal(old.status,200);
 assert.equal((await old.json()).model,alias);
 assert.equal((await chat('fresh','fixture-ordinary-key','ignored-trace')).status,200);
 assert.deepEqual(calls,[{ model:'vendor/old:v1',leakedRuntimeHeaders:false },{ model:'vendor/new:v2',leakedRuntimeHeaders:false }]);
 const metrics = (await (await fetch(admin + '/admin/overview',{ headers:adminHeaders })).json()).metrics;
 assert.ok(metrics.some(row => row.id === gatewayRequestId && row.requestId === 'runtime-42' &&
  row.taskId === 'native-task' && row.sessionId === 'native-session' && row.attemptId === 'native-task-fence-1' && row.mode === 'runtime_managed'));
 assert.ok(metrics.some(row => row.mode === 'ordinary' && row.requestId === undefined));
 t.diagnostic(JSON.stringify({ taskId:'native-task', sessionId:'native-session', attemptId:'native-task-fence-1',
  requestId:'runtime-42', gatewayRequestId, oldRevision:initial.version + 1, status:old.status }));
});
