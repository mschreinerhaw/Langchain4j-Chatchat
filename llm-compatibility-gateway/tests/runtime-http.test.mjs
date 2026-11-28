import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
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
 let cancelStarted;
 let cancelObserved;
 const cancelForwarded = new Promise(resolve => { cancelStarted = resolve; });
 const cancelClosed = new Promise(resolve => { cancelObserved = resolve; });
 let release;
 const gate = new Promise(resolve => { release = resolve; });
 const mock = createServer(async (request, response) => {
  if (request.method === 'GET') {
   response.writeHead(200, { 'content-type': 'application/json' });
   response.end(JSON.stringify({ data: [{ id: 'vendor/new:v2' }] }));
   return;
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const body = JSON.parse(Buffer.concat(chunks).toString());
  calls.push({ model: body.model, leakedRuntimeHeaders: Object.keys(request.headers).some(name => name.startsWith('x-runtime-')) });
  if (body.messages[0].content === 'hold') await gate;
  if (body.messages[0].content === 'slow') await delay(220);
  if (body.messages[0].content === 'cancel-before-headers') {
   response.once('close', cancelObserved);
   cancelStarted();
   return;
  }
  if (body.stream && body.stream_options?.include_usage) {
   response.writeHead(400, { 'content-type': 'application/json', 'x-request-id':'upstream-fixture-400' });
   response.end(JSON.stringify({ error: { message: 'Fixture rejects stream_options\nAuthorization: Bearer sk-fixture-secret',
    code:'unsupported_parameter', param:'stream_options' }, request:{ message:'private fixture payload' } }));
   return;
  }
  if (body.stream) {
   response.writeHead(200, { 'content-type': 'text/event-stream' });
   if (body.messages[0].content === 'empty-stream') {
    response.end('data: [DONE]\n\n');
    return;
   }
   response.end('data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}\n\n'
    + 'data: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}\n\ndata: [DONE]\n\n');
   return;
  }
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
  GATEWAY_DIAGNOSTIC_FIRST_MS:'100', GATEWAY_DIAGNOSTIC_IDLE_MS:'100',
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
 assert.equal((await (await fetch(admin + '/admin/config', { headers:adminHeaders })).json())
  .config.models[0].requestTimeoutMinutes,5);
 const emptyStream = await fetch(api + '/v1/chat/completions', { method:'POST',
  headers:{ ...json, authorization:'Bearer fixture-ordinary-key' },
  body:JSON.stringify({model:alias,stream:true,messages:[{role:'user',content:'empty-stream'}]}) });
 assert.equal(emptyStream.status,502);
 assert.equal((await emptyStream.json()).error.code,'upstream_protocol_error');
 calls.length = 0;
 const chat = (content, key, trace) => fetch(api + '/v1/chat/completions', { method:'POST', headers:{ ...json,
  authorization:'Bearer ' + key, 'x-runtime-task-id':'native-task', 'x-runtime-session-id':'native-session',
  'x-runtime-attempt-id':'native-task-fence-1', 'x-runtime-request-id':trace, 'x-runtime-deadline-ms':'1' },
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
 const ordinary = await chat('slow','fixture-ordinary-key','ignored-trace');
 assert.equal(ordinary.status,200);
 await ordinary.json();
 assert.deepEqual(calls,[{ model:'vendor/old:v1',leakedRuntimeHeaders:false },{ model:'vendor/new:v2',leakedRuntimeHeaders:false }]);
 const metrics = (await (await fetch(admin + '/admin/overview',{ headers:adminHeaders })).json()).metrics;
 assert.ok(metrics.some(row => row.id === gatewayRequestId && row.requestId === 'runtime-42' &&
  row.taskId === 'native-task' && row.sessionId === 'native-session' && row.attemptId === 'native-task-fence-1' && row.mode === 'runtime_managed'));
 assert.ok(metrics.some(row => row.mode === 'ordinary' && row.requestId === undefined));
 for (const direct of [false, true]) {
  const response = await fetch(admin + '/admin/lab', { method:'POST', headers:adminHeaders,
   body:JSON.stringify({ alias, direct, body:{ model:direct ? 'vendor/new:v2' : alias, messages:[{ role:'user', content:'slow' }] } }) });
  const stages = (await response.text()).trim().split('\n').map(JSON.parse);
  assert.equal(stages.find(row => row.stage === 'result').value.status,200);
 }
 const controller = new AbortController();
 const pending = fetch(api + '/v1/chat/completions', { method:'POST', signal:controller.signal,
  headers:{ ...json, authorization:'Bearer fixture-ordinary-key' },
  body:JSON.stringify({ model:alias, messages:[{ role:'user', content:'cancel-before-headers' }] }) });
 const cancelled = assert.rejects(pending, { name:'AbortError' });
 await cancelForwarded;
 await delay(220);
 controller.abort();
 await cancelled;
 await cancelClosed;
 const cancelMetrics = (await (await fetch(admin + '/admin/overview', { headers:adminHeaders })).json()).metrics;
 assert.ok(cancelMetrics.some(row => row.clientDisconnected && row.error === 'client_disconnected'));
 const unauthorized = await chat('ingress rejection','fixture-never-store-this-token','ignored');
 assert.equal(unauthorized.status,401);
 const ingress = (await (await fetch(admin + '/admin/overview',{ headers:adminHeaders })).json()).metrics
  .filter(row => row.id === unauthorized.headers.get('x-request-id'));
 assert.equal(ingress.length,1);
 assert.equal(ingress[0].phase,'gateway_ingress');
 assert.ok(!JSON.stringify(ingress).includes('fixture-never-store-this-token'));
 const check = await fetch(admin + '/admin/check', { method:'POST', headers:adminHeaders, body:JSON.stringify({ alias, profile:'cline' }) });
 assert.equal(check.status,200);
 const findings = (await check.text()).trim().split('\n').map(line => JSON.parse(line).value);
 assert.equal(findings.find(row => row.test === 'SSE 流式协议').status,'PASS');
 const usage = findings.find(row => row.test === '流式 Usage');
 assert.equal(usage.httpStatus,400);
 assert.match(JSON.stringify(usage.evidence),/Fixture rejects stream_options/);
 assert.equal(findings.find(row => row.test === '流式计量完整性').reason,'UNKNOWN');
 const saved = (await (await fetch(admin + '/admin/overview',{ headers:adminHeaders })).json()).checks.find(row => row.model === alias);
 assert.equal(saved.profile,'cline');
 assert.ok(saved.findings.every(finding => finding.evidence === undefined));
 const config = configuration('vendor/new:v2');
 config.models.push({ alias:'adapted-fixture', upstream:'vendor/new:v2', service:'fixture',
  rules:[{ stage:'request', op:'delete', path:'stream_options' }] });
 const revision = initial.version + 2;
 const change = await fetch(admin + '/admin/config',{ method:'PUT', headers:adminHeaders,
  body:JSON.stringify({ version:revision,config }) });
 assert.equal(change.status,200,await change.text());
 const stream = model => fetch(api + '/v1/chat/completions', { method:'POST',
  headers:{ ...json, authorization:'Bearer fixture-runtime-key' },
  body:JSON.stringify({ model, stream:true, stream_options:{ include_usage:true }, messages:[{role:'user',content:'check'}] }) });
 const unaffected = await stream(alias);
 assert.equal(unaffected.status,400);
 await unaffected.text();
 const logged = await readFile(join(root, 'model-errors.ndjson'), 'utf8');
 const error = logged.trim().split('\n').map(line=>JSON.parse(line))
  .find(row=>row.gatewayRequestId===unaffected.headers.get('x-request-id'));
 assert.equal(error.upstreamRequestId,'upstream-fixture-400');
 assert.equal(error.upstreamError.code,'unsupported_parameter');
 assert.equal(error.upstreamError.message,'Fixture rejects stream_options');
 assert.doesNotMatch(logged,/sk-fixture-secret|private fixture payload/);
 const adapted = await stream('adapted-fixture');
 assert.equal(adapted.status,200);
 assert.match(await adapted.text(),/"prompt_tokens":2,"completion_tokens":1,"total_tokens":3/);
 const rollback = await fetch(admin + '/admin/rollback',{ method:'POST', headers:adminHeaders,
  body:JSON.stringify({ version:revision + 1,target:revision }) });
 assert.equal(rollback.status,200);
 assert.ok(!(await rollback.json()).config.models.some(model => model.alias === 'adapted-fixture'));
 const capturePath = admin + '/admin/request-capture';
 assert.equal((await fetch(capturePath)).status,401);
 const captureStatus = async (method = 'GET', body, headers = adminHeaders) => {
  const response = await fetch(capturePath, { method,headers:body ? headers : { cookie:headers.cookie },
   body:body && JSON.stringify(body) });
  return { status:response.status, value:await response.json() };
 };
 assert.equal((await captureStatus('POST',{ alias,clientId:'runtime' })).value.state,'armed');
 const otherLogin = await fetch(admin + '/admin/login', { method:'POST', headers:json,
  body:JSON.stringify({ password:'fixture-admin-password' }) });
 const other = { ...json, cookie:otherLogin.headers.get('set-cookie').split(';')[0] };
 assert.equal((await captureStatus('GET',undefined,other)).value.state,'idle');
 assert.equal((await captureStatus('POST',{ alias },other)).status,409);
 await (await chat('outside requested client','fixture-ordinary-key','ordinary-trace')).text();
 assert.equal((await captureStatus()).value.state,'armed');
 const received = { model:alias,stream:true,stream_options:{ include_usage:true },max_tokens:80,
  messages:[{role:'user',content:'customer private text'},
   { role:'assistant',tool_calls:[{ index:0, function:{ name:'known_tool',arguments:'{"account":"000123"}' } }] }],
  tools:[{type:'function',function:{name:'known_tool',description:'private description',parameters:{type:'object'}}}],
  client_api_key:'private key',metadata:{account:12345} };
 const actual = await fetch(api + '/v1/chat/completions', { method:'POST', headers:{ ...json,
  authorization:'Bearer fixture-runtime-key', 'x-runtime-task-id':'capture-task', 'x-runtime-request-id':'capture-runtime' },
  body:JSON.stringify(received) });
 assert.equal(actual.status,400);
 await actual.text();
 const { value: captured } = await captureStatus();
 assert.equal(captured.state,'captured');
 const evidence = captured.evidence;
 assert.equal(evidence.gatewayRequestId,actual.headers.get('x-request-id'));
 assert.equal(evidence.requestId,'capture-runtime');
 assert.equal(evidence.taskId,'capture-task');
 assert.equal(evidence.inbound.body.model,alias);
 assert.equal(evidence.forwarded.body.model,'vendor/new:v2');
 assert.equal(evidence.inbound.body.stream_options.include_usage,true);
 assert.equal(evidence.inbound.body.max_tokens,80);
 assert.equal(evidence.inbound.body.tools[0].function.name,'known_tool');
 assert.equal(evidence.result.upstreamStatus,400);
 assert.doesNotMatch(JSON.stringify(evidence),/customer private text|000123|private description|private key|12345|fixture-runtime-key/);
 await (await chat('armed capture is single use','fixture-runtime-key','second-runtime')).text();
 assert.equal((await captureStatus()).value.evidence.requestId,'capture-runtime');
 const persisted = (await (await fetch(admin + '/admin/overview',{ headers:adminHeaders })).json()).metrics;
 assert.ok(!persisted.some(row => row.inbound || row.forwarded));
 assert.equal((await captureStatus('DELETE')).value.state,'idle');
 const filename = join(root,'field-report.json');
 const fixtureCli = spawn(process.execPath,['tests/gateway-protocol-acceptance.mjs',alias,filename,'cline'],{
  env:{...process.env, TEST_GATEWAY_ADMIN:admin, TEST_GATEWAY_ADMIN_PASSWORD:'fixture-admin-password'},
  stdio:['ignore','pipe','pipe'],
 });
 let cliOutput = '';
 for (const stream of [fixtureCli.stdout, fixtureCli.stderr]) stream.on('data', chunk => { cliOutput += chunk; });
 const [code] = await once(fixtureCli,'exit');
 assert.equal(code,2,cliOutput);
 const fieldReport = JSON.parse(await readFile(filename,'utf8'));
 assert.equal(fieldReport.profile,'cline');
 assert.ok(Number.isInteger(fieldReport.revision));
 assert.equal(fieldReport.findings.find(row => row.test === '流式 Usage').httpStatus,400);
 assert.ok(fieldReport.findings.every(row => row.evidence === undefined));
 t.diagnostic(JSON.stringify({ taskId:'native-task', sessionId:'native-session', attemptId:'native-task-fence-1',
  requestId:'runtime-42', gatewayRequestId, oldRevision:initial.version + 1, status:old.status }));
});
