import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { afterAll, afterEach, expect, it } from 'vitest';
import { runtimeRequest } from '../protocol/runtime.js';
import { serviceSchema, modelSchema } from '../server/config.js';
import { proxy, inFlight, translate } from '../server/proxy.js';

const upstreamModel = 'Vendor/Model:版本';
const alias = 'gateway/model:tools';
const upstreamCalls: string[] = [];
let port: number;
let disconnected = 0;
const server = createServer(async (req, res) => {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = JSON.parse(Buffer.concat(chunks).toString());
  upstreamCalls.push(body.model);
  const scenario = body.messages[0].content;
  res.on('close', () => { if (!res.writableFinished) disconnected++; });
  if (['slow', 'never'].includes(scenario)) await delay(240);
  if (scenario === 'never' || res.destroyed) return;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  const frame = (value: unknown) => res.write('data: ' + JSON.stringify(value) + '\n\n');
  frame({ model: upstreamModel, choices: [{ index: 0, delta: { content: 'first',
    reasoning: 'kept', reasoning_content: 'also kept', tool_calls: [{ index: 0, function: { arguments: '{"a":' } }] } }] });
  if (scenario === 'pause' || scenario === 'cancel') await delay(240);
  if (res.destroyed) return;
  if (scenario === 'error') {
    frame({ error: { message: 'sensitive upstream detail' } });
    res.end();
    return;
  }
  if (scenario !== 'unfinished') frame({ choices: [{ index: 0,
    finish_reason: 'tool_calls', delta: { tool_calls: [{ index: 0, function: { arguments: '1}' } }] } }], usage: { total_tokens: 8 } });
  res.end(scenario === 'eof' ? '' : 'data: [DONE]\n\n');
});
await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
const address = server.address();
if (!address || typeof address === 'string') throw new Error('Mock listener unavailable');
port = address.port;
afterEach(() => {
  expect(inFlight()).toBe(0);
  upstreamCalls.length = 0;
});
afterAll(() => { server.closeAllConnections(); server.close(); });

function call(scenario: string, mode: 'ordinary' | 'runtime_managed', signal = new AbortController().signal,
  frames: string[] = [], onFrame?: () => void) {
  const service = serviceSchema.parse({ id: 'mock', name: 'mock', origin: 'http://127.0.0.1:' + port,
    firstMs: 100, idleMs: 100, concurrency: 1 });
  const model = modelSchema.parse({ alias, upstream: upstreamModel, service: service.id, sseEnd: 'done_on_finished_eof' });
  return proxy({ model, service, mode, signal, metrics: {},
    body: { model: alias, stream: true, messages: [{ role: 'user', content: scenario }] },
    writer: { start() {}, async write(value) { frames.push(value); onFrame?.(); } } });
}

it.each(['slow', 'pause'])('managed %s outlives ordinary thresholds and preserves exact tool data', async scenario => {
  const frames: string[] = [];
  expect(await call(scenario, 'runtime_managed', undefined, frames)).toMatchObject({ status: 200 });
  expect(upstreamCalls).toEqual([upstreamModel]);
  expect(frames.join('')).toContain('"reasoning":"kept"').toContain('"reasoning_content":"also kept"')
    .toContain('"tool_calls"').toContain('"finish_reason":"tool_calls"').toContain('"total_tokens":8');
  expect(frames.at(-1)).toBe('data: [DONE]\n\n');
});

it('ordinary mode retains its first-packet timeout and does not retry', async () => {
  const error = await call('slow', 'ordinary').catch(translate);
  expect(error).toMatchObject({ status: 504 });
  expect(upstreamCalls).toEqual([upstreamModel]);
});

it.each(['eof', 'unfinished', 'error'])('reports %s without emitting a fake DONE or terminal success', async scenario => {
  const frames: string[] = [];
  await expect(call(scenario, 'runtime_managed', undefined, frames)).rejects.toMatchObject({
    code: scenario === 'error' ? 'upstream_error_event' : 'upstream_protocol_error',
  });
  expect(frames.join('')).not.toContain('[DONE]');
  expect(upstreamCalls).toEqual([upstreamModel]);
});

it('honors a Runtime deadline and separates it from client disconnect', async () => {
  const runtime = runtimeRequest({ 'x-runtime-deadline-ms': String(Date.now() + 150), 'x-runtime-task-id': 'native-42' },
    new AbortController().signal);
  try {
    expect(runtime.trace).toEqual({ taskId: 'native-42' });
    await expect(call('never', 'runtime_managed', runtime.signal)).rejects.toMatchObject({ code: 'runtime_deadline_exceeded' });
  } finally { runtime.close(); }
});

it('releases concurrency and closes the upstream when the client cancels midstream', async () => {
  const controller = new AbortController();
  const before = disconnected;
  const reason = new Error('caller disconnected');
  let started!: () => void;
  const pending = new Promise<void>(resolve => { started = resolve; });
  const result = call('cancel', 'runtime_managed', controller.signal, [], started);
  const rejected = expect(result).rejects.toBe(reason);
  await pending;
  await expect(call('slow', 'runtime_managed')).rejects.toMatchObject({ code: 'concurrency_limit' });
  controller.abort(reason);
  await rejected;
  await expect.poll(() => disconnected).toBeGreaterThan(before);
  expect(await call('slow', 'runtime_managed')).toMatchObject({ status: 200 });
});
