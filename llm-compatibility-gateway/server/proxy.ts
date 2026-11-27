import { performance } from 'node:perf_hooks';
import { openUpstream } from '../adapters/upstream.js';
import { applyRules, isObject, headerName, type JsonObject } from '../protocol/rules.js';
import { eventText, readEvents, protocolError } from '../protocol/sse.js';
import { litellmRequest, litellmUsageBoundary } from '../protocol/litellm.js';
import { modelErrorFields } from './upstream-error-log.js';
import { protocolRequest, ResponseProtocol } from '../protocol/compat.js';
import type { Protocol } from '../protocol/compat-schema.js';
import { GatewayError, limits, diagnosticTimeouts, type Model, type Service } from './config.js';

export const active = new Map<string, number>();
let totalActive = 0;
export const inFlight = () => totalActive;
export type Trace = (stage: 'client' | 'upstream_request' | 'upstream_response' | 'gateway', value: unknown) => Promise<void>;
type StreamWriter = { start: (status: number) => void; write: (value: string) => Promise<void> };

export function processingMode(model: Model, protocol: Protocol) {
  return model.processingMode ?? (model.rules.length || Object.keys(protocol).length ? 'CUSTOM' : 'DIRECT');
}

export function prepare(body: JsonObject, model: Model, direct = false, protocol: Protocol = model.protocol ?? {}): JsonObject {
  if (!Array.isArray(body.messages) || !body.messages.length || (body.stream !== undefined && typeof body.stream !== 'boolean')
    || (body.tools !== undefined && !Array.isArray(body.tools))) {
    throw new GatewayError(400, 'invalid_request', 'messages 必须为非空数组，stream 必须为布尔，tools 必须为数组');
  }
  if (!direct && Array.isArray(body.tools) && body.tools.length && model.capabilities.tools === 'unsupported') {
    throw new GatewayError(400, 'tools_unsupported', '此模型映射标记为不支持 Native Tool Calling');
  }
  if (!direct && body.stream === true && model.capabilities.streaming === 'unsupported')
    throw new GatewayError(422, 'streaming_unsupported', '此模型映射标记为不支持 SSE 流式响应');
  if (!direct && body.parallel_tool_calls === true && model.capabilities.parallelTools === 'unsupported')
    throw new GatewayError(422, 'parallel_tools_unsupported', '此模型映射标记为不支持并行工具调用');
  if (!direct && model.capabilities.vision === 'unsupported' && body.messages.some(message =>
    Array.isArray(message?.content) && message.content.some((part: JsonObject) => part?.type === 'image_url')))
    throw new GatewayError(422, 'vision_unsupported', '此模型映射标记为不支持图片输入');
  try {
    const transparent = direct || processingMode(model, protocol) === 'DIRECT';
    const mapped = { ...(transparent || !model.rules.length ? body : applyRules(body, model.rules, 'request')), model: model.upstream };
    return transparent ? mapped : protocolRequest(mapped, protocol);
  } catch (error) {
    if (error instanceof GatewayError) throw error;
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
    if (!parsed.success || parsed.data.startsWith('x-runtime-') || (service.framework === 'litellm' && parsed.data.startsWith('x-litellm-'))
      || ['authorization', 'accept', 'content-type', service.authHeader, 'x-api-key'].includes(parsed.data)
      || typeof value !== 'string' || !/^[\x20-\x7e]*$/.test(value)) {
      throw new GatewayError(400, 'invalid_header', '请求头配置包含无效、保留或认证字段');
    }
    headers[parsed.data] = value;
  }
  if (service.framework === 'litellm') headers['x-litellm-num-retries'] = '0';
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

function adapt(raw: JsonObject, model: Model, stage: 'response' | 'sse', direct: boolean,
  response: ResponseProtocol, eventName?: string) {
  if (raw.error != null) throw new GatewayError(502, 'upstream_error_event', '上游返回了错误对象');
  let data = raw;
  if (!direct) {
    try { data = applyRules(raw, model.rules, stage); }
    catch { throw protocolError('响应字段无法无损应用当前兼容规则'); }
    if (data.model !== undefined && typeof data.model !== 'string') throw protocolError('上游 model 字段无效');
    if (data.model !== undefined) data.model = model.alias;
  }
  response.normalize(data, stage === 'sse', eventName);
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
  onUpstreamError?: (record: JsonObject) => Promise<void>;
  metrics: JsonObject; mode?: 'ordinary' | 'runtime_managed' | 'diagnostic'; probe?: boolean;
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
  let failureBody: unknown;
  try {
    await report('client', body);
    const configured = { ...service.protocol, ...model.protocol };
    const viaFramework = service.framework === 'litellm';
    const hideUsage = viaFramework && options.mode === 'runtime_managed';
    const engine = viaFramework ? 'FRAMEWORK' : direct ? 'DIRECT' : processingMode(model, configured);
    metrics.processingMode = engine;
    const protocol = engine === 'DIRECT' ? {} : configured;
    const activeModel = engine === 'DIRECT' ? { ...model, rules: [] } : model;
    const payload = prepare(body, model, direct, protocol);
    if (viaFramework) litellmRequest(payload);
    const response = new ResponseProtocol(protocol, payload);
    metrics.usageStatus = 'UNKNOWN';
    const stream = payload.stream === true;
    const requestHeaders = outgoingHeaders(service, activeModel, stream, direct, headers);
    const timeout = options.probe ? diagnosticTimeouts() : undefined;
    upstream = openUpstream(service, 'chat', signal, requestHeaders, payload, timeout);
    options.onForward?.(payload, model.upstream, upstream.url.origin, upstream.url.pathname);
    await report('upstream_request', { url: upstream.url.href, headers: { ...requestHeaders,
      ...(service.apiKey ? { [service.authHeader]: '[REDACTED]' } : {}) }, body: payload });
    const result = await upstream.response;
    const status = result.statusCode;
    metrics.upstreamStatus = status;
    if (typeof result.headers['x-request-id'] === 'string'
      && /^[A-Za-z0-9_.:-]{1,128}$/.test(result.headers['x-request-id']))
      metrics.upstreamRequestId = result.headers['x-request-id'];
    if (status < 200 || status >= 300) {
      if (trace || options.onUpstreamError) {
        const timer = AbortSignal.timeout(1000);
        const raw = await Promise.race([
          readText(result.body, 24_000, upstream.stopFirstTimer).catch(() => ''),
          new Promise<string>(resolve => timer.addEventListener('abort', () => resolve(''), { once:true })),
        ]);
        try { failureBody = JSON.parse(raw); } catch { /* Only structured fields go to disk. */ }
        await report('upstream_response', { status, body: failureBody ?? raw });
      }
      if (status >= 300 && status < 400) throw protocolError('上游重定向已拒绝');
      throw new GatewayError(status >= 400 && status <= 599 ? status : 502, 'upstream_http_error', `上游返回 HTTP ${status}`);
    }
    if (!stream) {
      const rawText = await readText(result.body, bounds.responseBytes, upstream.stopFirstTimer);
      if (trace) await report('upstream_response', { status, body: rawText });
      const raw = parseObject(rawText);
      if (raw.error != null) failureBody = raw;
      const data = adapt(raw, activeModel, 'response', direct, response);
      response.assertFinished();
      recordUsage(metrics, data.usage, viaFramework);
      if (hideUsage) litellmUsageBoundary(data);
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
    let completed = false;
    let lastUsageFrame: string | undefined;
    const send = async (event: string) => {
      if (!started) { writer.start(status); started = true; }
      await writer.write(event);
    };
    for await (const event of readEvents(result.body, bounds.eventBytes, bounds.streamBytes)) {
      upstream.stopFirstTimer();
      const eventName = event.other.filter(line => line.startsWith('event:')).at(-1)?.slice(6).trim();
      const terminal = Boolean(eventName && eventName === protocol.doneEvent);
      if (eventName === 'error') {
        try { failureBody = JSON.parse(event.data ?? ''); } catch { /* Skip unstructured text. */ }
        throw new GatewayError(502, 'upstream_error_event', '上游发送了 SSE 错误事件');
      }
      if (event.data === undefined && !terminal) { await send(eventText(event)); continue; }
      await report('upstream_response', { status, event: event.data, fields: event.other });
      if (terminal || event.data?.trim() === '[DONE]') {
        if (unfinished.size || (terminal && !completed)) throw protocolError('上游在 choice 完成前发送了 SSE 结束标记');
        response.assertFinished();
        if (terminal && event.data && event.data.trim() !== '[DONE]') {
          const marker = parseObject(event.data);
          if (marker.error != null) failureBody = marker;
          if (marker.error != null || (Array.isArray(marker.choices) && marker.choices.length))
            throw protocolError('上游结束事件包含错误或尚未处理的输出');
          const usage = response.normalize(marker, true, eventName);
          if (!viaFramework && isObject(usage.usage)) {
            recordUsage(metrics, usage.usage);
            await send(eventText({ other:[], data:JSON.stringify({ choices:[], usage:usage.usage }) }));
          }
        }
        await report('gateway', { status, event: '[DONE]' });
        await send(eventText({ other:terminal ? event.other.filter(line => !line.startsWith('event:')) : event.other, data:'[DONE]' }));
        done = true;
        break;
      }
      const raw = parseObject(event.data!);
      if (raw.error != null) failureBody = raw;
      const data = adapt(raw, activeModel, 'sse', direct, response, eventName);
      recordUsage(metrics, data.usage, viaFramework);
      if (hideUsage) {
        litellmUsageBoundary(data);
        if (!(data.choices as JsonObject[]).length) continue;
      }
      const choices = data.choices as JsonObject[];
      for (const choice of choices) {
        const delta = choice.delta as JsonObject;
        if (typeof choice.index === 'number') {
          if (typeof choice.finish_reason === 'string' && choice.finish_reason.length) {
            unfinished.delete(choice.index);
            completed = true;
          } else unfinished.add(choice.index);
        }
        if (metrics.firstTokenMs === undefined && [delta.content, delta.reasoning, delta.reasoning_content, delta.tool_calls]
          .some(value => (typeof value === 'string' || Array.isArray(value)) && value.length)) {
          metrics.firstTokenMs = Math.round(performance.now() - start);
        }
      }
      if ((protocol.usage || protocol.usageEvent) && !choices.length && isObject(data.usage)) {
        const fingerprint = JSON.stringify(data.usage);
        if (fingerprint === lastUsageFrame) continue;
        lastUsageFrame = fingerprint;
      }
      await report('gateway', { status, event: data, fields: event.other });
      const other = eventName && eventName === protocol.usageEvent ? event.other.filter(line => !line.startsWith('event:')) : event.other;
      await send(eventText({ other, data: JSON.stringify(data) }));
    }
    if (!done) throw protocolError('上游在 SSE 结束标记前断开');
    return { status, data: undefined };
  } catch (error) {
    if (signal.aborted) throw signal.reason;
    if (upstream && options.onUpstreamError) {
      const failure = translate(error);
      await options.onUpstreamError({
        ...Object.fromEntries(['taskId', 'sessionId', 'attemptId', 'runtimeRequestId', 'requestId', 'upstreamRequestId',
          'upstreamStatus', 'processingMode', 'mode'].filter(key => metrics[key] !== undefined).map(key => [key, metrics[key]])),
        model: model.alias, upstreamModel: model.upstream, upstreamOrigin: upstream.url.origin,
        code: failure.code, status: failure.status,
        upstreamError: modelErrorFields(redact(failureBody, secrets)),
      });
    }
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

function recordUsage(metrics: JsonObject, usage: unknown, viaFramework = false) {
  if (isObject(usage)) {
    const numeric = Object.fromEntries(Object.entries(usage).filter(([key, value]) =>
      ['prompt_tokens', 'completion_tokens', 'total_tokens'].includes(key) && typeof value === 'number' && Number.isSafeInteger(value) && value >= 0));
    if (Object.keys(numeric).length) {
      const merged = { ...(isObject(metrics.usage) ? metrics.usage : {}), ...numeric };
      metrics.usage = merged;
      metrics.usageSource = viaFramework ? 'FRAMEWORK_UNVERIFIED' : 'UPSTREAM';
      metrics.usageStatus = viaFramework ? 'UNVERIFIED'
        : merged.total_tokens != null || (merged.prompt_tokens != null && merged.completion_tokens != null) ? 'COMPLETE' : 'PARTIAL';
    }
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
