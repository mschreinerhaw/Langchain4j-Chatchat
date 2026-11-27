import assert from 'node:assert/strict';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { execFileSync } from 'node:child_process';
import { request } from 'node:http';

const api = process.env.TEST_API ?? 'http://127.0.0.1:18080';
const admin = process.env.TEST_ADMIN ?? 'http://127.0.0.1:18081';
const fixture = process.env.TEST_FIXTURE ?? 'http://127.0.0.1:19090';
const auth = { authorization: 'Bearer fixture-gateway-credential', 'content-type': 'application/json' };
const restricted = { ...auth, authorization: 'Bearer fixture-restricted-credential' };
const alias = 'example/模型+#?';
const chatBody = (content = '你好', extra = {}) => ({ model: alias, messages: [{ role: 'user', content }], ...extra });
const chat = (body = chatBody(), signal) => fetch(api + '/v1/chat/completions', { method: 'POST', headers: auth, body: JSON.stringify(body), signal });
const read = async (response, status) => {
  const raw = await response.text();
  assert.equal(response.status, status, raw.slice(0, 400));
  return JSON.parse(raw);
};
const eventData = raw => raw.split('\n').filter(line => line.startsWith('data: ')).map(line => line.slice(6))
  .map(value => value === '[DONE]' ? value : JSON.parse(value));
const service = { id: 'fixture', name: 'Fixture', origin: 'http://mock:19090', apiKey: 'fixture-upstream-credential',
  firstMs: 450, idleMs: 650, concurrency: 2 };
const rules = [
  { stage: 'request', op: 'rename', path: 'max_completion_tokens', to: 'max_tokens' },
  { stage: 'request', op: 'default', path: 'chat_template_kwargs.enable_thinking', value: true },
  { stage: 'headers', op: 'set', path: 'x-test', value: 'configured' },
  { stage: 'response', op: 'rename', path: 'fixture', to: 'debug' },
  { stage: 'sse', op: 'rename', path: 'choices.0.delta.reasoning_content', to: 'choices.0.delta.reasoning' },
];
const config = { services: [service], models: [{ alias, upstream: '/models/Qwen中文', service: service.id, rules },
  { alias: 'other', upstream: '/models/Other', service: service.id }] };
let cookie;
async function login() {
  const response = await fetch(admin + '/admin/login', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: 'fixture-admin-password' }) });
  assert.equal(response.status, 200);
  cookie = response.headers.get('set-cookie').split(';')[0];
  await response.arrayBuffer();
}
const manage = (path, method = 'GET', body) => fetch(admin + '/admin/' + path, {
  method, headers: { cookie, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
});
let version;
const publish = async (target, expected = version, status = 200) => {
  const result = await read(await manage('config', 'PUT', { version: expected, config: target }), status);
  if (status === 200) version = result.version;
  return result;
};
const waitFor = async (fn, message) => {
  for (let attempt = 0; attempt < 80; attempt++) {
    try { if (await fn()) return; } catch {}
    await delay(50);
  }
  assert.fail(message);
};

test('Docker gateway contract', async t => {
  await t.test('listener isolation, authorization, publication and model path lookup', async () => {
    assert.equal((await fetch(api + '/ready')).status, 200);
    assert.equal((await fetch(api + '/v1/models')).status, 401);
    assert.equal((await fetch(admin + '/admin/config')).status, 401);
    assert.equal((await fetch(admin + '/v1/models', { headers: auth })).status, 404);
    assert.equal((await fetch(api + '/admin/config', { headers: auth })).status, 404);
    await login();
    version = (await read(await manage('config'), 200)).version;
    const revision = await publish(config);
    assert.equal(revision.config.services[0].hasKey, true);
    assert.doesNotMatch(JSON.stringify(revision), /fixture-upstream-credential/);
    const models = await read(await fetch(api + '/v1/models', { headers: auth }), 200);
    assert.deepEqual(models.data.map(item => item.id), [alias, 'other']);
    assert.deepEqual((await read(await fetch(api + '/v1/models', { headers: restricted }), 200)).data.map(item => item.id), ['other']);
    assert.equal((await fetch(api + '/v1/models/' + encodeURIComponent(alias), { headers: restricted })).status, 404);
    const single = await read(await fetch(api + '/v1/models/' + encodeURIComponent(alias), { headers: auth }), 200);
    assert.equal(single.id, alias);
    assert.equal((await chat(chatBody('hello', { model: 'missing' }))).status, 404);
    assert.equal((await fetch(api + '/v1/responses', { method: 'POST', headers: auth, body: '{}' })).status, 501);
    await publish({ ...config, models: [...config.models, config.models[0]] }, version, 409);
    await publish({ ...config, services: [{ ...service, origin: 'ftp://example.invalid' }] }, version, 400);
    await publish(config, version - 1, 409);
    assert.equal((await read(await manage('upstream/models', 'POST', { id: service.id }), 200)).status, 404);
  });
  await t.test('upstream base URL keeps its prefix for both configured endpoints', async () => {
    await publish({ ...config, services: [{ ...service, origin: service.origin + '/compatible-mode/v1' }] });
    const result = await read(await chat(chatBody('hello')), 200);
    assert.equal(result.model, alias);
    assert.equal((await read(await manage('upstream/models', 'POST', { id: service.id }), 200)).status, 200);
    await publish(config);
  });
  await t.test('online access tests use the published IDs, real listener and authorized client', async () => {
    const options = { protocol: 'http', clientId: 'fixture', alias };
    assert.equal((await fetch(admin + '/admin/integration/test', { method: 'POST', headers: auth,
      body: JSON.stringify({ ...options, mode: 'models' }) })).status, 401);
    for (const mode of ['models', 'chat', 'sse', 'tool']) {
      const result = await read(await manage('integration/test', 'POST', { ...options, mode }), 200);
      assert.equal(result.checks.length, mode === 'models' ? 2 : 4);
      assert.ok(result.checks.every(check => check.passed), JSON.stringify(result));
      if (mode !== 'models') {
        assert.equal(result.checks[2].request.body.model, alias);
        assert.equal(result.checks[3].response.upstreamRequest.body.model, config.models[0].upstream);
      }
      assert.doesNotMatch(JSON.stringify(result), /fixture-(?:gateway|upstream)-credential/);
    }
    assert.equal((await manage('integration/test', 'POST', { ...options, clientId: 'restricted', mode: 'models' })).status, 404);
  });
  await t.test('JSON parameters, native tools, usage and tool result round trip', async () => {
    const tools = [{ type: 'function', function: { name: 'gateway_echo', parameters: { type: 'object', properties: {} } } }];
    const input = chatBody('你好', { max_completion_tokens: 12, extra: { list: [], object: {} },
      tools, tool_choice: 'required', parallel_tool_calls: false });
    const result = await read(await chat(input), 200);
    assert.equal(result.model, alias);
    assert.equal(result.debug.input.model, '/models/Qwen中文');
    assert.equal(result.debug.input.max_tokens, 12);
    assert.equal(result.debug.input.max_completion_tokens, undefined);
    assert.deepEqual(result.debug.input.extra, input.extra);
    assert.deepEqual(result.debug.input.tools, tools);
    assert.equal(result.debug.input.parallel_tool_calls, false);
    assert.equal(result.debug.input.tool_choice, 'required');
    assert.equal(result.debug.marker, 'configured');
    assert.equal(result.debug.input.chat_template_kwargs.enable_thinking, true);
    assert.equal(result.usage.total_tokens, 7);
    const choice = result.choices[0];
    assert.equal(choice.finish_reason, 'tool_calls');
    const toolResult = { role: 'tool', tool_call_id: choice.message.tool_calls[0].id, content: '工具结果' };
    const followup = await read(await chat({ ...input, messages: [...input.messages, choice.message, toolResult] }), 200);
    assert.equal(followup.choices[0].message.content, '工具结果');
    assert.equal((await read(await chat(chatBody('no usage')), 200)).usage, undefined);
    // The gateway can reject the declared size before curl or fetch finishes uploading.
    const oversized = await new Promise((resolve, reject) => {
      const req = request(api + '/v1/chat/completions', { method: 'POST',
        headers: { ...auth, 'content-length': 8_500_000 }, signal: AbortSignal.timeout(5_000) }, response => {
        response.resume();
        response.once('end', () => { resolve(response.statusCode); req.destroy(); });
        response.once('error', reject);
      });
      req.once('error', reject);
      req.flushHeaders();
    });
    assert.equal(oversized, 413);
  });
  await t.test('SSE incrementality, fragments, usage, explicit termination and errors', async () => {
    const tools = [{ type: 'function', function: { name: 'gateway_echo' } }];
    const response = await chat(chatBody('你好', { stream: true, tools }));
    assert.match(response.headers.get('content-type'), /^text\/event-stream/);
    const events = eventData(await response.text());
    assert.equal(events[0].choices[0].delta.content, '你好');
    assert.equal(events[0].choices[0].delta.reasoning, '推理');
    assert.equal(events[0].model, alias);
    const calls = events.flatMap(event => event.choices?.[0]?.delta.tool_calls ?? []);
    assert.equal(JSON.parse(calls.map(call => call.function.arguments).join('')).value, 'gateway-check');
    assert.equal(events.find(event => event.usage)?.usage.total_tokens, 7);
    assert.equal(events.at(-1), '[DONE]');
    for (const mode of ['disconnect', 'malformed', 'no done']) {
      const result = await chat(chatBody(mode, { stream: true }));
      assert.ok(eventData(await result.text()).at(-1).error);
    }
    for (const mode of ['stall-first', 'stall']) {
      const delayed = await chat(chatBody(mode, { stream: true }));
      assert.equal(delayed.status, 200);
      assert.equal(eventData(await delayed.text()).at(-1), '[DONE]');
    }
    await publish({ ...config, models: [{ ...config.models[0], sseEnd: 'done_on_finished_eof' }, config.models[1]] });
    assert.equal(eventData(await (await chat(chatBody('no done', { stream: true }))).text()).at(-1).error.code, 'upstream_protocol_error');
    await publish({ ...config, services: [{ ...service, chatPath: '/redirect' }] });
    const redirect = await read(await chat(), 502);
    assert.equal(redirect.error.code, 'upstream_protocol_error');
    await publish(config);
  });
  await t.test('abort closes upstream and frees the concurrency slot', async () => {
    const original = (await (await fetch(fixture + '/fixture/metrics')).json()).cancellations;
    const controller = new AbortController();
    const response = await chat(chatBody('cancel', { stream: true }), controller.signal);
    const reader = response.body.getReader();
    const first = await reader.read();
    assert.match(Buffer.from(first.value).toString(), /你好/);
    controller.abort();
    await waitFor(async () => (await (await fetch(fixture + '/fixture/metrics')).json()).cancellations > original,
      'upstream did not observe cancellation');
    await waitFor(async () => (await read(await manage('overview'), 200)).metrics.some(row => row.clientDisconnected), 'disconnect metadata missing');
    assert.equal((await chat()).status, 200);
  });
  await t.test('ongoing requests keep their config snapshot while published rules apply to new requests', async () => {
    const original = await chat(chatBody('snapshot', { stream: true }));
    await publish({ ...config, models: [{ ...config.models[0], rules: rules.filter(rule => rule.stage !== 'sse') }, config.models[1]] });
    const fresh = await chat(chatBody('snapshot', { stream: true }));
    const [oldEvents, newEvents] = await Promise.all([original.text(), fresh.text()]).then(values => values.map(eventData));
    assert.equal(oldEvents[1].choices[0].delta.reasoning, '第二段');
    assert.equal(newEvents[1].choices[0].delta.reasoning_content, '第二段');
    await publish(config);
  });
  await t.test('lab trace, diagnostic evidence, history and restart persistence', async () => {
    const lab = await manage('lab', 'POST', { alias, body: chatBody() });
    const trace = (await lab.text()).trim().split('\n').map(JSON.parse);
    assert.deepEqual(trace.map(row => row.stage), ['client', 'transport', 'upstream_request', 'upstream_response', 'gateway', 'compatibility', 'result']);
    assert.doesNotMatch(JSON.stringify(trace), /fixture-upstream-credential/);
    const direct = await manage('lab', 'POST', { alias, service: service.id, direct: true, body: chatBody('你好', { model: '/models/Qwen中文' }) });
    const directTrace = (await direct.text()).trim().split('\n').map(JSON.parse);
    assert.equal(directTrace.find(row => row.stage === 'gateway').value.body.model, '/models/Qwen中文');
    const check = await manage('check', 'POST', { alias });
    const findings = (await check.text()).trim().split('\n').map(line => JSON.parse(line).value);
    assert.equal(findings.find(row => row.test === 'Cline 完整任务').status, 'UNVERIFIED');
    assert.equal(findings.find(row => row.test === 'Native Tool Calling').status, 'PASS');
    assert.equal(findings.find(row => row.test === '流式 Tool Calling').status, 'PASS');
    const target = version;
    await publish({ ...config, services: [{ ...service, apiKey: '' }] });
    assert.equal((await chat()).status, 401);
    const rollback = await read(await manage('rollback', 'POST', { version, target }), 200);
    version = rollback.version;
    assert.equal((await chat()).status, 200);
    assert.doesNotMatch(await (await manage('backup')).text(), /fixture-upstream-credential/);
    if (process.env.TEST_RESTART === '1') {
      execFileSync('docker', ['compose', '-p', 'llm-gateway-devtest', '-f', 'docker-compose.yml',
        '-f', 'tests/compose.test.yml', 'restart', 'llm-gateway']);
      await waitFor(async () => (await fetch(api + '/ready')).ok, 'Docker gateway did not recover');
      await login();
      assert.equal((await read(await manage('config'), 200)).version, version);
      assert.equal((await chat()).status, 200);
    }
  });
});
