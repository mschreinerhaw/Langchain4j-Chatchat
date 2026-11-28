// Build the gateway first, then run: node tests/extreme-baseline.mjs [--wall-clock | --load | --field-timing | --combined]
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { modelSchema, serviceSchema } from '../dist/server/config.js';
import { inFlight, proxy, translate } from '../dist/server/proxy.js';

const frame = value => 'data: ' + JSON.stringify(value) + '\n\n';
const done = 'data: [DONE]\n\n';
const output = frame({ choices: [{ index: 0, delta: { content: 'ok' }, finish_reason: 'stop' }] });
const tool = (name, args) => frame({ choices: [{ index: 0, finish_reason: 'tool_calls',
  delta: { tool_calls: [{ index: 0, type: 'function', function: { name, arguments: args } }] } }] });
const contextSizes = [4096, 8192, 16384, 32768, 65536, 131072];

let hits = 0;
let aliasMatched = false;
const mock = createServer(async (req, res) => {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const input = JSON.parse(Buffer.concat(chunks));
  const scenario = input.messages[0].content;
  hits++;
  aliasMatched = input.model === 'mock/Vendor:low';
  const httpStatus = /^http-(\d+)$/.exec(scenario)?.[1];
  const limit = /^context-(\d+)$/.exec(scenario)?.[1];
  if (httpStatus || (limit && input.messages.slice(1).reduce((bytes, row) =>
    bytes + Buffer.byteLength(row.content), 0) > Number(limit))) {
    res.writeHead(Number(httpStatus ?? 400), { 'content-type': 'application/json' });
    return res.end('{"error":{"code":"fixture_rejection"}}');
  }
  if (scenario === 'silent') return;
  if (scenario === 'slow') await delay(600);
  const firstSeconds = /^first-(\d+)$/.exec(scenario)?.[1];
  if (firstSeconds) {
    const closed = new AbortController();
    res.once('close', () => closed.abort());
    try { await delay(Number(firstSeconds) * 1000, undefined, { signal: closed.signal }); }
    catch (error) { if (closed.signal.aborted) return; throw error; }
  }
  if (res.destroyed) return;
  if (!input.stream || scenario === 'wrong-content-type') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(({ empty: '', 'json-missing-choices': '{}', 'json-invalid': '{' })[scenario] ?? '{}');
  }
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  const chunksPerSecond = /^rate-(\d+)$/.exec(scenario)?.[1];
  if (chunksPerSecond) {
    for (let sequence = 0; sequence < 4; sequence++) {
      res.write(frame({ choices: [{ index: 0, delta: { content: String(sequence) } }] }));
      await delay(1000 / Number(chunksPerSecond));
      if (res.destroyed) return;
    }
    return res.end(output + done);
  }
  if (scenario === 'heartbeat') {
    res.write(': queued\n\n');
    await delay(650);
    if (res.destroyed) return;
  }
  if (scenario === 'heartbeat-without-output') {
    res.write(': queued\n\n');
    const heartbeat = setInterval(() => res.write(': queued\n\n'), 75);
    res.once('close', () => clearInterval(heartbeat));
    return;
  }
  if (scenario === 'disconnect') {
    res.write(frame({ choices: [{ index: 0, delta: { content: 'partial' } }] }));
    await delay(50);
    return res.destroy();
  }
  if (scenario === 'partial-tool-disconnect') {
    res.write(frame({ choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'fixture_call',
      type: 'function', function: { name: 'read_status', arguments: '{"instance":' } }] } }] }));
    await delay(50);
    return res.destroy();
  }
  if (scenario === 'split-utf8') {
    const bytes = Buffer.from(output.replace('ok', '慢') + done);
    const split = bytes.indexOf(Buffer.from('慢')) + 1;
    res.write(bytes.subarray(0, split));
    await delay(20);
    return res.end(bytes.subarray(split));
  }
  res.end(({
    'done-only': done, malformed: 'data: invalid\n\n', truncated: 'data: {"choices":[',
    'missing-done': output, 'error-event': 'event: error\n' + frame({ error: 'fixture' }),
    'missing-tool-id': tool('read_status', '{}') + done,
    'invalid-tool-json': tool('read_status', '{"broken":') + done,
    'unknown-tool': tool('unknown_tool', '{}') + done,
    'invalid-usage': output + frame({ choices: [], usage: { total_tokens: -1 } }) + done,
  })[scenario] ?? output + done);
});

await new Promise(resolve => mock.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + mock.address().port;
const service = serviceSchema.parse({ id: 'fixture', name: 'fixture', origin, concurrency: 1 });
const model = modelSchema.parse({ alias: 'fixture-low', upstream: 'mock/Vendor:low', service: service.id });
process.env.GATEWAY_DIAGNOSTIC_FIRST_MS = '300';
process.env.GATEWAY_DIAGNOSTIC_IDLE_MS = '1000';

async function run(scenario, { probe = false, mode = 'ordinary', contextChars, stream = true,
  unsupported = false, timeoutMs = 1400, targetService = service, datasetSizes } = {}) {
  const before = hits;
  const started = performance.now();
  const metrics = {};
  const frames = [];
  const signal = AbortSignal.timeout(timeoutMs);
  const messages = [{ role: 'user', content: scenario }];
  if (contextChars !== undefined) messages.push({ role: 'user', content: 'x'.repeat(contextChars) });
  for (const bytes of datasetSizes ?? []) messages.push({ role: 'user', content: 'd'.repeat(bytes) });
  let outcome;
  try {
    const result = await proxy({ model: unsupported ? { ...model, capabilities: { ...model.capabilities, tools: 'unsupported' } } : model,
      service: targetService, signal, probe, mode, metrics, body: { model: model.alias, messages, stream, tools: [
        { type: 'function', function: { name: 'read_status', parameters: { type: 'object' } } },
      ] }, writer: { start() {}, async write(value) { frames.push(value); } } });
    outcome = { status: result.status };
  } catch (error) {
    const failure = translate(error);
    outcome = signal.aborted && error === signal.reason
      ? { outcome: 'caller_timeout' } : { status: failure.status, code: failure.code };
  }
  return { scenario, mode, probe, ...outcome, elapsedMs: Math.round(performance.now() - started),
    firstTokenMs: metrics.firstTokenMs ?? null, usageStatus: metrics.usageStatus ?? null,
    done: frames.includes(done), forwarded: hits > before, aliasMatched: hits > before && aliasMatched,
    inFlightAfter: inFlight(), ...(contextChars === undefined ? {} : { contextChars }),
    ...(datasetSizes ? { datasetSizes } : {}) };
}

function concurrentResult({ forwarded, aliasMatched, inFlightAfter, ...result }) {
  return result;
}

async function wallClockSamples() {
  const samples = await Promise.all([5, 30, 60, 120, 180].map(seconds => run('first-' + seconds, {
      timeoutMs: (seconds + 12) * 1000,
      targetService: { ...service, id: 'timing-' + seconds },
  })));
  return samples.map(concurrentResult);
}

async function loadSamples() {
  const admission = [];
  for (const concurrency of [1, 2, 4, 8]) {
    const requests = await Promise.all(Array.from({ length: concurrency + 2 }, () => run('slow', {
      targetService: { ...service, concurrency },
    })));
    admission.push({ concurrency, offered: requests.length,
      statusCodes: requests.map(request => request.status) });
  }
  const paced = await Promise.all([1, 3, 5, 10, 30].map(rate => run('rate-' + rate, {
    timeoutMs: 6500, targetService: { ...service, id: 'paced-' + rate },
  })));
  return { admission, paced: paced.map(concurrentResult) };
}

async function fieldTimingSamples() {
  process.env.GATEWAY_DIAGNOSTIC_FIRST_MS = '60000';
  process.env.GATEWAY_DIAGNOSTIC_IDLE_MS = '60000';
  const modes = ['ordinary', 'runtime_managed', 'diagnostic'];
  const delays = [59, 60, 61, 90, 120, 180];
  const samples = await Promise.all(modes.flatMap(mode => delays.map(seconds =>
    run('first-' + seconds, {
      ...(mode === 'diagnostic' ? { probe: true } : { mode }),
      timeoutMs: (seconds + 15) * 1000,
      targetService: { ...service, id: mode + '-' + seconds },
    }).then(row => ({ ...concurrentResult(row), mode })))));
  process.env.GATEWAY_DIAGNOSTIC_FIRST_MS = '300';
  process.env.GATEWAY_DIAGNOSTIC_IDLE_MS = '300';
  samples.push(await run('heartbeat-without-output', { probe: true, timeoutMs: 1500 }));
  return samples;
}

async function combinedSamples() {
  const samples = [];
  for (const datasetSizes of [[1000, 1640, 1456], [1000, 1640, 1457]]) {
    samples.push(await run('context-4096', { datasetSizes, mode: 'runtime_managed' }));
  }
  samples.push(await run('slow', { timeoutMs: 130, mode: 'runtime_managed' }));
  samples.push(await run('partial-tool-disconnect', { mode: 'runtime_managed' }));
  for (const datasetSizes of [[2048, 2048], [2048, 2048, 2048], [2048, 2048, 2048, 2048]]) {
    samples.push(await run('context-4096', { datasetSizes, mode: 'runtime_managed' }));
  }
  return samples;
}

async function protocolSamples() {
  const results = [];
  for (const status of [400, 401, 403, 404, 413, 429, 500, 502, 503, 504])
    results.push(await run('http-' + status));
  for (const size of contextSizes) {
    results.push(await run('context-' + size, { contextChars: size }));
    results.push(await run('context-' + size, { contextChars: size + 1 }));
  }
  for (const scenario of ['empty', 'json-missing-choices', 'json-invalid'])
    results.push(await run(scenario, { stream: false }));
  for (const scenario of ['split-utf8', 'wrong-content-type', 'done-only', 'malformed', 'truncated', 'missing-done',
    'disconnect', 'error-event', 'missing-tool-id', 'invalid-tool-json', 'unknown-tool', 'invalid-usage'])
    results.push(await run(scenario));
  results.push(await run('tools-disabled', { unsupported: true }));
  for (const mode of ['ordinary', 'runtime_managed']) {
    results.push(await run('slow', { mode }), await run('silent', { mode }));
  }
  results.push(await run('slow', { probe: true }), await run('heartbeat', { probe: true }));
  return results;
}

try {
  const suite = process.argv.includes('--wall-clock') ? wallClockSamples
    : process.argv.includes('--load') ? loadSamples
    : process.argv.includes('--field-timing') ? fieldTimingSamples
    : process.argv.includes('--combined') ? combinedSamples : protocolSamples;
  const results = await suite();
  if (inFlight() !== 0) throw new Error('Gateway still has requests in flight');
  console.log(JSON.stringify(results, null, 2));
} finally {
  mock.closeAllConnections();
  mock.close();
}
