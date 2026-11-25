import { describe, expect, it, vi } from 'vitest';
import { applyRules, ruleSchema } from '../protocol/rules.js';
import { eventText, readEvents } from '../protocol/sse.js';
import { Store } from '../server/config.js';
import { normalizeUpstreamUrl, upstreamEndpoint } from '../protocol/origin.js';
import { integrationBase, integrationCurl } from '../protocol/integration.js';

it('normalizes SDK endpoints and renders file-free shell commands with exact model IDs', () => {
  const base = 'https://model.example.com/v1';
  expect(integrationBase(base + '/v1/', 'https')).toBe(base);
  expect(integrationBase('https://model.example.com/compatible-mode', 'https'))
    .toBe('https://model.example.com/compatible-mode/v1');
  expect(integrationBase('https://user:secret@model.example.com/v1', 'https')).toBe('');
  const bash = integrationCurl(base, { model: "Vendor/Model's", stream: false }, false, 'bash');
  expect(bash).toContain('curl -i ');
  expect(bash).toContain('Vendor/Model\'"\'"\'s');
  expect(bash).not.toContain('request.json');
  const ps = integrationCurl(base, { model: 'Qwen/Qwen3.8-27B', stream: true }, true, 'powershell', 'gateway.crt');
  expect(ps).toContain('curl.exe -i -N');
  expect(ps).toContain('$env:GATEWAY_API_KEY');
  expect(ps).toContain('Qwen/Qwen3.8-27B');
  expect(ps).toContain("--data-binary '@-'");
});

it('aggregates retained request logs by completion time and averages only observed successful timings', () => {
  const store = new Store(':memory:', Buffer.alloc(32), new Set());
  const now = Date.parse('2026-11-24T12:00:00Z');
  vi.useFakeTimers({ toFake: ['Date'] });
  try {
    vi.setSystemTime(now - 61 * 60_000);
    store.record('older', { status: 200, elapsedMs: 60 });
    vi.setSystemTime(now - 60 * 60_000);
    store.record('start', { status: 200, elapsedMs: 100, firstTokenMs: 20 });
    vi.setSystemTime(now - 1000);
    store.record('failed', { status: 502, elapsedMs: 1000, firstTokenMs: 90 });
    store.record('cancelled', { status: 499, elapsedMs: 80 });
    vi.setSystemTime(now);
    store.record('end', { status: 200, elapsedMs: 300 });
    vi.setSystemTime(now + 1000);
    store.record('future', { status: 200, elapsedMs: 500 });

    const trend = store.traffic('1h', now);
    expect(trend.buckets).toHaveLength(12);
    expect(trend.totals).toEqual({ requests: 4, success: 2, failed: 2, averageMs: 200, averageFirstTokenMs: 20 });
    expect(trend.buckets[0].requests).toBe(1);
    expect(trend.buckets[1].averageMs).toBeNull();
    expect(trend.buckets.at(-1)).toMatchObject({ requests: 3, averageMs: 300, averageFirstTokenMs: null });
    expect(store.traffic('6h', now).totals.requests).toBe(5);
  } finally {
    vi.useRealTimers();
  }
});

it('records a new origin together with the administrator configuration and retains its base path', () => {
  const original = 'https://existing.example';
  const next = 'http://10.2.25.111:32004';
  const store = new Store(':memory:', Buffer.alloc(32), new Set([original]));
  const base = next + '/compatible-mode/v1';
  const candidate = { services: [{ id: 'new', name: '内网模型', origin: base + '/' }], models: [] };
  expect(() => store.publish(1, candidate)).toThrow(/尚未保存/);
  expect(() => store.publish(2, candidate, { allowNewOrigins: true })).toThrow(/重新加载/);
  expect(() => store.publish(1, { ...candidate, services: [...candidate.services, ...candidate.services] },
    { allowNewOrigins: true })).toThrow(/重复/);
  expect(store.allowedOrigins.has(next)).toBe(false);
  expect(store.publish(1, candidate, { allowNewOrigins: true }).config.services[0].origin).toBe(base);
  expect(store.allowedOrigins.has(next)).toBe(true);
  expect(store.history()[0].action).toContain(next);
  expect(store.publish(2, candidate).version).toBe(3);
});

it('resolves upstream endpoint prefixes without repeating API version segments', () => {
  const base = 'https://model.example.com';
  expect(normalizeUpstreamUrl(base + '/compatible-mode/')).toBe(base + '/compatible-mode');
  expect(normalizeUpstreamUrl('ftp://model.example.com')).toBeUndefined();
  expect(normalizeUpstreamUrl('https://key@model.example.com')).toBeUndefined();
  expect(upstreamEndpoint(base, '/v1/models').href).toBe(base + '/v1/models');
  expect(upstreamEndpoint(base + '/compatible-mode', '/v1/chat/completions').href).toBe(base + '/compatible-mode/v1/chat/completions');
  expect(upstreamEndpoint(base + '/compatible-mode/v1', '/v1/models').href).toBe(base + '/compatible-mode/v1/models');
  expect(upstreamEndpoint(base + '/compatible-mode/v1', '/compatible-mode/v1/models').href).toBe(base + '/compatible-mode/v1/models');
  expect(upstreamEndpoint(base + '/compatible-mode/v1/chat/completions', '/v1/chat/completions').href)
    .toBe(base + '/compatible-mode/v1/chat/completions');
  expect(upstreamEndpoint(base + '/compatible-mode/v1/chat/completions', '/v1/models').href)
    .toBe(base + '/compatible-mode/v1/models');
  expect(() => upstreamEndpoint(base, '//different.example.com/models')).toThrow();
});

describe('declarative rules', () => {
  it('preserves unconfigured data and nested empty containers', () => {
    const input = { max_completion_tokens: 20, temperature: 3, messages: [{ content: '多语言' }], extra: { a: [], b: {} } };
    const rules = [
      { stage: 'request', op: 'rename', path: 'max_completion_tokens', to: 'max_tokens' },
      { stage: 'request', op: 'clamp', path: 'temperature', min: 0, max: 2 },
      { stage: 'request', op: 'default', path: 'chat_template_kwargs.enable_thinking', value: true },
    ].map(rule => ruleSchema.parse(rule));
    expect(applyRules(input, rules, 'request')).toEqual({ temperature: 2, max_tokens: 20,
      chat_template_kwargs: { enable_thinking: true }, messages: input.messages, extra: input.extra });
    expect(input.max_completion_tokens).toBe(20);
  });
  it.each(['tools', 'messages.0.content', '__proto__.polluted', 'model', 'stream'])('rejects writes to %s', path => {
    expect(ruleSchema.safeParse({ stage: 'request', op: 'delete', path }).success).toBe(false);
  });
  it('reports collisions without discarding data', () => {
    expect(() => applyRules({ old: 1, new: 2 }, [ruleSchema.parse({ stage: 'response', op: 'rename', path: 'old', to: 'new' })], 'response'))
      .toThrow('Rename target already exists');
  });
  it('supports nested array member rules without rewriting unrelated fields', () => {
    const input = { choices: [{ delta: { thought: '推理', content: '' } }] };
    expect(applyRules(input, [ruleSchema.parse({ stage: 'sse', op: 'rename', path: 'choices.0.delta.thought',
      to: 'choices.0.delta.reasoning_content' })], 'sse')).toEqual({ choices: [{ delta: { reasoning_content: '推理', content: '' } }] });
  });
});

describe('SSE framing', () => {
  it('parses byte-fragmented CRLF, UTF-8, comments and multiline data', async () => {
    async function* input() { for (const byte of Buffer.from(': ping\r\n\r\ndata: {"a":\r\ndata: "中"}\r\n\r\ndata: [DONE]\n\n')) yield Buffer.from([byte]); }
    const items = await Array.fromAsync(readEvents(input()));
    expect(items.map(eventText)).toEqual([': ping\n\n', 'data: {"a":\ndata: "中"}\n\n', 'data: [DONE]\n\n']);
    expect(JSON.parse(items[1].data!)).toEqual({ a: '中' });
  });
  it('rejects EOF within an event', async () => {
    async function* input() { yield Buffer.from('data: {"truncated":'); }
    await expect(Array.fromAsync(readEvents(input()))).rejects.toThrow('SSE Event');
  });
});
