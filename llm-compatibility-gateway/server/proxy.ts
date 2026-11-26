import { performance } from 'node:perf_hooks';
import { openUpstream } from '../adapters/upstream.js';
import { applyRules, isObject, headerName, type JsonObject } from '../protocol/rules.js';
import { eventText, readEvents, protocolError } from '../protocol/sse.js';
import { GatewayError, limits, diagnosticTimeouts, type Model, type Service } from './config.js';

export const active = new Map<string, number>();
let totalActive = 0;
export const inFlight = () => totalActive;
export type Trace = (stage: 'client' | 'upstream_request' | 'upstream_response' | 'gateway', value: unknown) => Promise<void>;
type StreamWriter = { start: (status: number) => void; write: (value: string) => Promise<void> };

export function prepare(body: JsonObject, model: Model, direct = false): JsonObject {
  if (!Array.isArray(body.messages) || !body.messages.length || (body.stream !== undefined && typeof body.stream !== 'boolean')
    || (body.tools !== undefined && !Array.isArray(body.tools))) {
    throw new GatewayError(400, 'invalid_request', 'messages 必须为非空数组，stream 必须为布尔，tools 必须为数组');
  }
  if (!direct && Array.isArray(body.tools) && body.tools.length && model.capabilities.tools === 'unsupported') {
    throw new GatewayError(400, 'tools_unsupported', '此模型映射标记为不支持 Native Tool Calling');
  }
  try {
    return { ...(direct ? body : applyRules(body, model.rules, 'request')), model: model.upstream };
  } catch {
    throw new GatewayError(400, 'request_rule_error', '请求字段无法应用当前兼容规则');
  }
}

export function outgoingHeaders(service: Service, model: Model, stream: boolean, direct: boolean, extra: JsonObject = {}) {
  const headers: Record<string, string> = {
    'content-type': 'application/json', accept: stream ? 'text/event-stream' : 'application/json',
  };
  let configured: JsonObject;
  try { configured = direct ? extra : applyRules(extra, model.rules, 'headers'); }
  catch { throw new GatewayError(400, 'header_rule_error', '请求头规则执行失败'); }
  for (const [key, value] of Object.entries(configured)) {
    const parsed = headerName.safeParse(key);
    if (!parsed.success || parsed.data.startsWith('x-runtime-')
      || ['authorization', 'accept', 'content-type', service.authHeader, 'x-api-key'].includes(parsed.data)
      || typeof value !== 'string' || !/^[\x20-\x7e]*$/.test(value)) {
      throw new GatewayError(400, 'invalid_header', '请求头配置包含无效、保留或认证字段');
    }
    headers[parsed.data] = value;
  }
  return headers;
}

export function redact(value: unknown, secrets: string[]): unknown {
  if (typeof value === 'string') {
    for (const secret of secrets) if (secret) {
      for (const form of new Set([secret, JSON.stringify(secret).slice(1, -1)])) {
        value = (value as string).split(form).join('[REDACTED]');
      }
    }
    return value;
  }
  if (Array.isArray(value)) return value.map(item => redact(item, secrets));
  if (isObject(value)) return Object.fromEntries(Object.entries(value).map(([key, item]) => [
    String(redact(key, secrets)), /authorization|api[-_]?key|password|secret|cookie|token$/i.test(key)
      ? '[REDACTED]' : redact(item, secrets),
  ]));
  return value;
}

async function readText(body: AsyncIterable<Uint8Array>, maxBytes = 8_000_000, first?: () => void) {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of body) {
    if (!size) first?.();
    size += chunk.byteLength;
    if (size > maxBytes) throw protocolError('上游响应超过大小限制');
    chunks.push(Buffer.from(chunk));
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
}

function parseObject(text: string): JsonObject {
  try {
    const result: unknown = JSON.parse(text);
    if (isObject(result)) return result;
  } catch { /* Report a stable protocol error. */ }
  throw protocolError('上游返回了无效的 JSON 对象');
}

function adapt(raw: JsonObject, model: Model, stage: 'response' | 'sse', direct: boolean) {
  if (raw.error != null) throw new GatewayError(502, 'upstream_error_event', '上游返回了错误对象');
  let data = raw;
  if (!direct) {
    try { data = applyRules(raw, model.rules, stage); }
    catch { throw protocolError('响应字段无法无损应用当前兼容规则'); }
    if (data.model !== undefined && typeof data.model !== 'string') throw protocolError('上游 model 字段无效');
    if (data.model !== undefined) data.model = model.alias;
  }
  if (!Array.isArray(data.choices) || (stage === 'response' && !data.choices.length)
    || data.choices.some(choice => !isObject(choice) || !isObject(choice[stage === 'response' ? 'message' : 'delta']))) {
    throw protocolError('上游 choices、message 或 delta 结构不符合 Chat Completions');
  }
  return data;
}

export function translate(error: unknown) {
  if (error instanceof GatewayError) return error;
  const code = isObject(error) && typeof error.code === 'string' ? error.code : '';
  if (code.includes('TIMEOUT')) return new GatewayError(504, 'upstream_timeout', '上游连接或流式读取超时');
  return new GatewayError(502, 'upstream_failure', '上游连接、TLS 或响应读取失败');
}

export async function proxy(options: {
  body: JsonObject; model: Model; service: Service; signal: AbortSignal;
  writer: StreamWriter; trace?: Trace; direct?: boolean; headers?: JsonObject; secrets?: string[];
  onForward?: (body: JsonObject, upstreamModel: string, origin: string, path: string) => void;
  metrics: JsonObject; mode?: 'ordinary' | 'runtime_managed' | 'diagnostic';
}) {
  const { body, model, service, signal, writer, trace, direct = false, headers, metrics } = options;
  const start = performance.now();
  const used = active.get(service.id) ?? 0;
  const bounds = limits();
  if (used >= service.concurrency || totalActive >= bounds.concurrency) throw new GatewayError(429, 'concurrency_limit', '达到并发限制');
  active.set(service.id, used + 1);
  totalActive++;
  let upstream: ReturnType<typeof openUpstream> | undefined;
  const secrets = [...(options.secrets ?? []), service.apiKey ?? ''];
  const report: Trace = async (stage, value) => { if (trace) await trace(stage, redact(value, secrets)); };
  try {
    await report('client', body);
    const payload = prepare(body, model, direct);
    const stream = payload.stream === true;
    const requestHeaders = outgoingHeaders(service, model, stream, direct, headers);
    const timeout = options.mode === 'runtime_managed' ? { firstMs: 0, idleMs: 0 }
      : options.mode === 'diagnostic' ? diagnosticTimeouts() : undefined;
    upstream = openUpstream(service, 'chat', signal, requestHeaders, payload, timeout);
    options.onForward?.(payload, model.upstream, upstream.url.origin, upstream.url.pathname);
    await report('upstream_request', { url: upstream.url.href, headers: { ...requestHeaders,
      ...(service.apiKey ? { [service.authHeader]: '[REDACTED]' } : {}) }, body: payload });
    const result = await upstream.response;
    const status = result.statusCode;
    metrics.upstreamStatus = status;
    if (status < 200 || status >= 300) {
      if (trace) {
        const raw = await readText(result.body, 256_000);
        let parsed: unknown = raw;
        try { parsed = JSON.parse(raw); } catch { /* Retain text in the ephemeral trace. */ }
        await report('upstream_response', { status, body: parsed });
      }
      if (status >= 300 && status < 400) throw protocolError('上游重定向已拒绝');
      throw new GatewayError(status >= 400 && status <= 599 ? status : 502, 'upstream_http_error', `上游返回 HTTP ${status}`);
    }
    if (!stream) {
      const rawText = await readText(result.body, bounds.responseBytes, upstream.stopFirstTimer);
      if (trace) await report('upstream_response', { status, body: rawText });
      const raw = parseObject(rawText);
      const data = adapt(raw, model, 'response', direct);
      recordUsage(metrics, data.usage);
      await report('gateway', { status, body: data });
      return { status, data };
    }
    if (!/^text\/event-stream(?:\s*;|$)/i.test(String(result.headers['content-type']))) {
      if (trace) await report('upstream_response', { status, body: await readText(result.body, 256_000) });
      throw protocolError('上游流式响应缺少 text/event-stream');
    }
    let started = false;
    let done = false;
    const unfinished = new Set<number>();
    const send = async (event: string) => {
      if (!started) { writer.start(status); started = true; }
      await writer.write(event);
    };
    for await (const event of readEvents(result.body, bounds.eventBytes, bounds.streamBytes)) {
      upstream.stopFirstTimer();
      if (event.data === undefined) { await send(eventText(event)); continue; }
      await report('upstream_response', { status, event: event.data, fields: event.other });
      if (event.data.trim() === '[DONE]') {
        if (unfinished.size) throw protocolError('上游在 choice 完成前发送了 SSE 结束标记');
        await report('gateway', { status, event: '[DONE]' });
        await send(eventText({ ...event, data: '[DONE]' }));
        done = true;
        break;
      }
      const raw = parseObject(event.data);
      const data = adapt(raw, model, 'sse', direct);
      const choices = data.choices as JsonObject[];
      for (const choice of choices) {
        const delta = choice.delta as JsonObject;
        if (typeof choice.index === 'number') {
          if (typeof choice.finish_reason === 'string' && choice.finish_reason.length) unfinished.delete(choice.index);
          else unfinished.add(choice.index);
        }
        if (metrics.firstTokenMs === undefined && [delta.content, delta.reasoning, delta.reasoning_content, delta.tool_calls]
          .some(value => (typeof value === 'string' || Array.isArray(value)) && value.length)) {
          metrics.firstTokenMs = Math.round(performance.now() - start);
        }
      }
      recordUsage(metrics, data.usage);
      await report('gateway', { status, event: data, fields: event.other });
      await send(eventText({ ...event, data: JSON.stringify(data) }));
    }
    if (!done) throw protocolError('上游在 SSE 结束标记前断开');
    return { status, data: undefined };
  } catch (error) {
    if (signal.aborted) throw signal.reason;
    throw error;
  } finally {
    try { await upstream?.close(); }
    finally {
      const remaining = (active.get(service.id) ?? 1) - 1;
      if (remaining) active.set(service.id, remaining);
      else active.delete(service.id);
      totalActive--;
    }
  }
}

function recordUsage(metrics: JsonObject, usage: unknown) {
  if (isObject(usage)) {
    const numeric = Object.fromEntries(Object.entries(usage).filter(([key, value]) =>
      ['prompt_tokens', 'completion_tokens', 'total_tokens'].includes(key) && typeof value === 'number'));
    if (Object.keys(numeric).length) metrics.usage = numeric;
  }
}

export async function upstreamModels(service: Service, signal: AbortSignal) {
  const connection = openUpstream(service, 'models', signal, { accept: 'application/json' }, undefined, diagnosticTimeouts());
  try {
    const result = await connection.response;
    connection.stopFirstTimer();
    const raw = await readText(result.body, 512_000);
    let data: unknown = raw;
    try { data = JSON.parse(raw); } catch { /* Preserve diagnostic text. */ }
    return { status: result.statusCode, data };
  } finally { await connection.close(); }
}
