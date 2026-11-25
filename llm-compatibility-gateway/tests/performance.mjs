import { performance } from 'node:perf_hooks';

const samples = Number(process.env.GATEWAY_BENCH_SAMPLES ?? 240);
const concurrency = Number(process.env.GATEWAY_BENCH_CONCURRENCY ?? 1);
const endpoint = '/v1/chat/completions';
const payload = { messages: [{ role: 'user', content: '性能测试' }] };
async function request(base, key, model, stream = false) {
  const start = performance.now();
  const response = await fetch(base + endpoint, { method: 'POST',
    headers: { authorization: 'Bearer ' + key, 'content-type': 'application/json' },
    body: JSON.stringify({ ...payload, model, stream }), signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  let first;
  const decoder = new TextDecoder();
  let prefix = '';
  for await (const chunk of response.body) {
    if (first !== undefined) continue;
    if (stream) prefix += decoder.decode(chunk, { stream: true });
    if (!stream || /\r?\n\r?\n/.test(prefix)) first = performance.now() - start;
  }
  return { total: performance.now() - start, first };
}
const direct = stream => request('http://mock:19090', 'fixture-upstream-credential', '/models/Qwen中文', stream);
const gateway = stream => request('http://127.0.0.1:18080', process.env.GATEWAY_API_KEY, 'example/模型+#?', stream);
const round = x => Number(x.toFixed(3));
const percentiles = values => {
  const sorted = values.toSorted((left, right) => left - right);
  return Object.fromEntries([50, 95, 99].map(p => ['p' + p, round(sorted[Math.ceil(sorted.length * p / 100) - 1])]));
};
async function measure(stream) {
  const rows = [];
  for (let index = 0; index < 12; index++) { await direct(stream); await gateway(stream); }
  const start = performance.now();
  await Promise.all(Array.from({ length: concurrency }, async (_, worker) => {
    for (let index = worker; index < samples; index += concurrency) {
      const before = index % 2 ? null : await direct(stream);
      const via = await gateway(stream);
      const baseline = before ?? await direct(stream);
      rows.push({ via, baseline });
    }
  }));
  return { samples: rows.length, concurrency, durationMs: round(performance.now() - start),
    gatewayMs: percentiles(rows.map(row => row.via.total)), directMs: percentiles(rows.map(row => row.baseline.total)),
    pairedDeltaMs: percentiles(rows.map(row => row.via.total - row.baseline.total)),
    firstChunkGatewayMs: percentiles(rows.map(row => row.via.first)), firstChunkDirectMs: percentiles(rows.map(row => row.baseline.first)) };
}
console.log(JSON.stringify({ json: await measure(false), sse: await measure(true) }, null, 2));
