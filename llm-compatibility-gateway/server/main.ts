import { timingSafeEqual, createHash, randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdirSync } from 'node:fs';
import { createServer as createHttp, type Server as HttpServer, type ServerResponse } from 'node:http';
import { createServer as createHttps, type Server as HttpsServer } from 'node:https';
import { isIP } from 'node:net';
import { TLSSocket, type TlsOptions } from 'node:tls';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Fastify, { type FastifyReply, type FastifyRequest } from 'fastify';
import staticFiles from '@fastify/static';
import { z } from 'zod';
import { settings, limits, Store, GatewayError, withProfile, profileSchema, type Config } from './config.js';
import { proxy, translate, prepare, redact, outgoingHeaders, inFlight, upstreamModels } from './proxy.js';
import { diagnose } from './diagnostics.js';
import { isObject, applyRules, type JsonObject } from '../protocol/rules.js';
import { profileNames, type Profile } from '../protocol/profiles.js';
import { CertificateStore, importSchema, namesSchema } from './tls.js';
import { probeGateway, requestGateway } from './transport.js';
import { checkIntegrationResponse, integrationBody } from './integration.js';
import { trafficRanges, type TrafficRange } from './traffic.js';
import { runtimeRequest } from '../protocol/runtime.js';

const settingsValue = settings();
const version = '1.2.0-rc.1';
const dataDir = process.env.DATA_DIR ?? '/app/data';
mkdirSync(dataDir, { recursive: true, mode: 0o700 });
const store = new Store(resolve(dataDir, 'gateway.sqlite'), settingsValue.key, settingsValue.origins);
const certificates = process.env.GATEWAY_HTTP_ONLY === 'true' ? undefined
  : new CertificateStore(process.env.TLS_DATA_DIR ?? '/app/certs', settingsValue.key);
const readPort = (key: string, fallback: number) => z.coerce.number().int().min(1).max(65535).parse(process.env[key] || fallback);
const httpPort = readPort('HTTP_TEST_PORT', 18080);
const httpEnabled = !certificates || process.env.ENABLE_HTTP_TEST === 'true';
const httpPublicPort = httpEnabled ? readPort('HTTP_PUBLIC_PORT', httpPort) : undefined;
const apiPort = certificates ? readPort('HTTPS_PORT', 8443) : httpPort;
const adminPort = readPort('ADMIN_PORT', 18081);
const apiPublicPort = readPort(certificates ? 'HTTPS_PUBLIC_PORT' : 'HTTP_PUBLIC_PORT', apiPort);
const gatewayUrl = () => {
  const name = certificates?.primaryName;
  const host = name && isIP(name) === 6 ? '[' + name + ']' : name;
  const port = certificates?.publicPort ?? apiPublicPort;
  return host ? 'https://' + host + (port === 443 ? '' : ':' + port) + '/v1' : undefined;
};
const options = { bodyLimit: limits().requestBytes, logger: false, genReqId: () => randomUUID(), requestTimeout: 30_000 };
const serverFactory: typeof options & { serverFactory?: import('fastify').FastifyServerFactory<HttpServer> } = certificates
  ? { ...options, serverFactory: handler => createHttps(certificates.options, handler) as unknown as HttpServer } : options;
const api = Fastify(serverFactory);
const admin = Fastify(serverFactory);
const sessions = new Map<string, number>();
type LabTrace = (stage: string, value: unknown) => Promise<void>;
const labRequests = new Map<string, { identity: string; profile: Profile; headers?: JsonObject; trace: LabTrace }>();
let loginFailures = 0;
let loginWindow = 0;
let checking = false;
let integrationChecks = 0;
const suppliedSecrets = [settingsValue.password, ...settingsValue.clients.map(client => client.key), settingsValue.key.toString('hex')];
const digest = (value: string) => createHash('sha256').update(value).digest();
const equal = (a: string, b: string) => timingSafeEqual(digest(a), digest(b));
const errorBody = (error: GatewayError) => ({ error: { message: error.message, code: error.code,
  type: error.status >= 500 ? 'server_error' : 'invalid_request_error', param: null } });
const invalid = (message: string) => new GatewayError(400, 'invalid_request', message);
function localError(error: unknown) {
  if (error instanceof z.ZodError) return invalid(error.issues.map(issue => issue.path.join('.') + ' ' + issue.message).join(' · ').slice(0, 1000));
  if (error instanceof GatewayError) return error;
  if (isObject(error) && typeof error.statusCode === 'number' && error.statusCode < 500) {
    return new GatewayError(error.statusCode, 'invalid_request', 'HTTP 请求无效或超过大小限制');
  }
  return new GatewayError(500, 'internal_error', '网关内部错误');
}

for (const app of [api, admin]) {
  app.addHook('onRequest', async (req, reply) => {
    reply.header('x-request-id', req.id).header('cache-control', 'no-store').header('x-content-type-options', 'nosniff');
  });
  app.setErrorHandler((error, _req, reply) => {
    const failure = localError(error);
    reply.code(failure.status).send(errorBody(failure));
  });
}
api.addHook('onRequest', async (req, reply) => {
  if (['/health', '/ready'].includes(req.routeOptions.url ?? '')) return;
  const token = req.headers.authorization?.match(/^Bearer (.+)$/i)?.[1] ?? '';
  if (token.length <= 8192) {
    const client = settingsValue.clients.find(item => equal(item.key, token));
    if (client) { identities.set(req, client); return; }
  }
  reply.header('www-authenticate', 'Bearer');
  throw new GatewayError(401, 'invalid_api_key', '网关 API Key 无效');
});
type Client = (typeof settingsValue.clients)[number];
const identities = new WeakMap<FastifyRequest, Client>();
const forwardingRequests = new WeakSet<FastifyRequest>();
api.addHook('onResponse', async (req, reply) => {
  if (req.method !== 'POST' || reply.statusCode < 400 || forwardingRequests.has(req)) return;
  const route = req.routeOptions.url;
  const endpoint = route && ['/v1/chat/completions', '/v1/responses', '/v1/embeddings', '/v1/messages', '/v1/messages/count_tokens'].includes(route)
    ? route : 'unmatched';
  try {
    store.record(req.id, { endpoint, status: reply.statusCode, phase: 'gateway_ingress',
      client: identities.get(req)?.id, revision: store.snapshot.version });
  } catch { process.stderr.write('Failed to record request metadata\n'); }
});
let draining = false;
const downstreamRequests = new Map<AbortController, ServerResponse>();
function availableModels(config: Config, client: Client) {
  const services = new Set(config.services.filter(service => service.enabled).map(service => service.id));
  return config.models.filter(model => model.enabled && services.has(model.service)
    && (client.models.includes('*') || client.models.includes(model.alias)));
}
function visible(config: Config, req: FastifyRequest) {
  return availableModels(config, identities.get(req)!);
}
const modelInfo = (id: string) => ({ id, object: 'model', created: 0, owned_by: 'internal' });
api.get('/health', async () => ({ status: 'ok', version }));
api.get('/ready', async (_req, reply) => draining ? reply.code(503).send({ status: 'draining' })
  : { status: 'ready', configuration: store.snapshot.version });
api.get('/v1/models', async req => ({ object: 'list', data: visible(store.snapshot.config, req).map(model => modelInfo(model.alias)) }));
api.get('/v1/models/*', async req => {
  const alias = (req.params as { '*': string })['*'];
  if (!visible(store.snapshot.config, req).some(model => model.alias === alias)) {
    throw new GatewayError(404, 'model_not_found', '模型不存在或未授权');
  }
  return modelInfo(alias);
});
const unsupported = () => { throw new GatewayError(501, 'unsupported_endpoint', '此版本不支持该 API'); };
for (const path of ['/v1/responses', '/v1/embeddings', '/v1/messages', '/v1/messages/count_tokens']) api.post(path, unsupported);
api.setNotFoundHandler((_req, reply) => reply.code(404).send(errorBody(new GatewayError(404, 'not_found', 'API 路径不存在'))));

function downstream(reply: FastifyReply) {
  const abort = new AbortController();
  const raw = reply.raw;
  const disconnected = new GatewayError(499, 'client_disconnected', 'Client connection closed');
  const closed = () => {
    downstreamRequests.delete(abort);
    if (!raw.writableFinished) abort.abort(disconnected);
  };
  raw.once('close', closed);
  if (raw.destroyed) closed();
  else downstreamRequests.set(abort, raw);
  let started = false;
  const start = (contentType: string, status = 200) => {
    if (started) return;
    abort.signal.throwIfAborted();
    reply.hijack();
    raw.writeHead(status, { 'content-type': contentType, 'cache-control': 'no-store, no-transform',
      'x-accel-buffering': 'no', 'x-request-id': String(reply.getHeader('x-request-id') ?? ''),
      'x-content-type-options': 'nosniff' });
    started = true;
  };
  const write = async (value: string, signal = abort.signal) => {
    signal.throwIfAborted();
    if (!raw.write(value)) await once(raw, 'drain', { signal });
  };
  return { abort, start, write, get started() { return started; }, end: () => raw.end() };
}

api.post('/v1/chat/completions', async (req, reply) => {
  if (draining) throw new GatewayError(503, 'gateway_draining', 'Gateway is draining');
  if (!isObject(req.body) || typeof req.body.model !== 'string') throw invalid('model 必须是字符串');
  const snapshot = store.snapshot;
  const identity = identities.get(req)!;
  const ticket = req.headers['x-gateway-lab-ticket'];
  const candidate = req.raw.socket.remoteAddress === '127.0.0.1' && typeof ticket === 'string' ? labRequests.get(ticket) : undefined;
  const lab = candidate?.identity === identity.id ? candidate : undefined;
  if (lab && typeof ticket === 'string') labRequests.delete(ticket);
  const profile = lab?.profile ?? identity.profile;
  const body = req.body;
  const client = downstream(reply);
  const start = performance.now();
  const model = visible(snapshot.config, req).find(item => item.alias === body.model);
  const service = snapshot.config.services.find(item => item.id === model?.service);
  forwardingRequests.add(req);
  const metrics: JsonObject = { client: identity.id, profile, model: model?.alias, upstreamModel: model?.upstream,
    upstreamOrigin: service?.origin, stream: body.stream === true, revision: snapshot.version };
  const mode = lab ? 'diagnostic' : identity.mode;
  metrics.mode = mode;
  let runtime: ReturnType<typeof runtimeRequest> | undefined;
  try {
    if (mode === 'runtime_managed') {
      runtime = runtimeRequest(req.headers, client.abort.signal);
      Object.assign(metrics, runtime.trace);
      runtime.signal.throwIfAborted();
    }
    if (!model || !service) throw new GatewayError(404, 'model_not_found', '模型不存在或未授权');
    if (lab) {
      const socket = req.raw.socket;
      await lab.trace('client', redact(body, [...suppliedSecrets, service.apiKey ?? '']));
      await lab.trace('transport', { protocol: socket instanceof TLSSocket ? socket.getProtocol() : 'HTTP',
        verified: socket instanceof TLSSocket, authenticated: true, profile });
    }
    const result = await proxy({ body, model: withProfile(model, profile), service,
      signal: runtime?.signal ?? client.abort.signal, mode, metrics,
      trace: lab ? async (stage, value) => { if (stage !== 'client') await lab.trace(stage, value); } : undefined,
      headers: lab?.headers, secrets: suppliedSecrets,
      writer: { start: status => client.start('text/event-stream; charset=utf-8', status),
        write: value => client.write(value, runtime?.signal) } });
    metrics.status = result.status;
    await lab?.trace('compatibility', { status: 'PASS', profile, format: body.stream ? 'SSE' : 'JSON',
      nativeToolRequest: Array.isArray(body.tools) && body.tools.length > 0 });
    if (client.started) client.end();
    else if (!client.abort.signal.aborted) return reply.code(result.status).send(result.data);
  } catch (error) {
    const failure = translate(error);
    metrics.status = failure.status;
    metrics.error = failure.code;
    if (!client.abort.signal.aborted) {
      try { await lab?.trace('compatibility', { status: 'FAIL', profile, httpStatus: failure.status, ...errorBody(failure) }); }
      catch { /* The admin client disconnected while receiving the trace. */ }
      if (!client.started) {
        if (failure.code === 'concurrency_limit') reply.header('retry-after', '1');
        reply.header('x-gateway-error-code', failure.code);
        return reply.code(failure.status).send(errorBody(failure));
      }
      try { await client.write('data: ' + JSON.stringify(errorBody(failure)) + '\n\n'); }
      catch { /* Client disconnected while reporting the stream error. */ }
      client.end();
    }
  } finally {
    runtime?.close();
    try { store.record(req.id, { ...metrics, phase: 'gateway_forwarding', elapsedMs: Math.round(performance.now() - start),
      clientDisconnected: client.abort.signal.reason?.code === 'client_disconnected' }); }
    catch { process.stderr.write('Failed to record request metadata\n'); }
  }
});

function session(req: FastifyRequest) {
  return req.headers.cookie?.match(/(?:^|;\s*)gateway_session=([a-f0-9]{64})(?:;|$)/)?.[1] ?? '';
}
function sessionCookie(token: string, age: number) {
  return `gateway_session=${token}; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=${age}${certificates || process.env.ADMIN_SECURE_COOKIE === 'true' ? '; Secure' : ''}`;
}

admin.addHook('onRequest', async (req, reply) => {
  reply.header('referrer-policy', 'no-referrer').header('x-frame-options', 'DENY')
    .header('content-security-policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
  if (!req.routeOptions.url?.startsWith('/admin/')) return;
  if (req.method !== 'GET' && req.headers['sec-fetch-site'] === 'cross-site') throw new GatewayError(403, 'cross_origin', '禁止跨站管理请求');
  if (req.routeOptions.url === '/admin/login') return;
  if ((sessions.get(session(req)) ?? 0) < Date.now()) throw new GatewayError(401, 'login_required', '请登录管理平台');
});
admin.post('/admin/login', async (req, reply) => {
  const password = isObject(req.body) ? req.body.password : undefined;
  if (Date.now() - loginWindow > 60_000) { loginWindow = Date.now(); loginFailures = 0; }
  if (loginFailures >= 20) throw new GatewayError(429, 'login_rate_limit', '登录尝试过多，请稍后重试');
  if (typeof password !== 'string' || password.length > 8192 || !equal(password, settingsValue.password)) {
    loginFailures++;
    throw new GatewayError(401, 'invalid_credentials', '管理员密码无效');
  }
  for (const [token, expires] of sessions) if (expires < Date.now()) sessions.delete(token);
  if (sessions.size >= 128) sessions.delete(sessions.keys().next().value!);
  sessions.delete(session(req));
  const token = randomBytes(32).toString('hex');
  sessions.set(token, Date.now() + 28_800_000);
  return reply.header('set-cookie', sessionCookie(token, 28_800)).send({ authenticated: true });
});
admin.post('/admin/logout', async (req, reply) => {
  sessions.delete(session(req));
  return reply.header('set-cookie', sessionCookie('', 0)).send({ authenticated: false });
});
const publishedConfig = () => ({ ...store.publicVersion(), allowedOrigins: [...store.allowedOrigins], apiBaseUrl: gatewayUrl() });
admin.get('/admin/config', async () => publishedConfig());
const tlsStore = () => {
  if (!certificates) throw new GatewayError(409, 'tls_disabled', '当前只启用了 HTTP 测试模式');
  return certificates;
};
admin.get('/admin/tls', async () => ({ enabled: Boolean(certificates), listener: apiPort,
  publicPort: certificates?.publicPort ?? apiPublicPort, adminPort, apiBaseUrl: gatewayUrl(), httpPublicPort, certificate: certificates?.status(),
  clients: settingsValue.clients.map(({ id, profile, models }) => ({ id, profile, models })), profiles: profileNames }));
admin.post('/admin/integration/key', async req => {
  if (!(req.raw.socket instanceof TLSSocket)) {
    throw new GatewayError(403, 'https_required', '请通过 HTTPS 管理台查看网关 API Key');
  }
  const { clientId, alias } = z.object({
    clientId: z.string().max(240), alias: z.string().max(240),
  }).parse(req.body);
  const client = settingsValue.clients.find(item => item.id === clientId);
  if (!client || !availableModels(store.snapshot.config, client).some(model => model.alias === alias)) {
    throw new GatewayError(404, 'client_model_not_found', '客户端或已授权的模型不存在');
  }
  return { apiKey: client.key };
});
admin.post('/admin/integration/test', async (req, reply) => {
  const { protocol, clientId, alias, mode } = z.object({
    protocol: z.enum(['http', 'https']), clientId: z.string().max(240), alias: z.string().max(240),
    mode: z.enum(['models', 'chat', 'sse', 'tool']),
  }).parse(req.body);
  if (protocol === 'http' && !httpEnabled || protocol === 'https' && !certificates?.primaryName) {
    throw invalid('所选协议未启用');
  }
  const client = settingsValue.clients.find(item => item.id === clientId);
  const snapshot = store.snapshot;
  const model = client && availableModels(snapshot.config, client).find(item => item.alias === alias);
  if (!client || !model) throw new GatewayError(404, 'client_model_not_found', '客户端或已授权的模型不存在');
  if (integrationChecks >= 4) throw new GatewayError(429, 'integration_busy', '在线测试并发请求过多');
  const service = snapshot.config.services.find(item => item.id === model.service)!;
  const secrets = [...suppliedSecrets, service.apiKey ?? ''];
  const tcp = { port: protocol === 'https' ? apiPort : httpPort, host: protocol === 'https' ? certificates?.primaryName : undefined,
    ca: certificates?.ca, signal: AbortSignal.any([downstream(reply).abort.signal, AbortSignal.timeout(90_000)]) };
  const checks: Array<{ name: string; passed: boolean; request: object; response?: object }> = [];
  async function run(name: string, path: string, verify: (value: Awaited<ReturnType<typeof probeGateway>>) => boolean | Promise<boolean>,
    bearer?: string, body?: object, ticket?: string) {
    const request = { method: body ? 'POST' : 'GET', path, body };
    try {
      const response = await probeGateway({ ...tcp, path, bearer, ticket, body });
      const passed = await verify(response);
      checks.push({ name, passed, request, response });
      return response;
    } catch {
      checks.push({ name, passed: false, request, response: { error: tcp.signal.aborted ? '测试已取消或超时'
        : protocol === 'https' ? 'HTTPS 证书、主机名验证或连接失败' : 'HTTP 连接失败' } });
      return undefined;
    }
  }
  integrationChecks++;
  try {
    const ready = await run(protocol === 'https' ? '连接与 TLS' : '连接', '/ready', result => result.status === 200);
    if (ready?.status === 200) {
      const listed = await run('API Key 与模型列表', '/v1/models', result => {
        if (result.status !== 200 || result.truncated) return false;
        const data = JSON.parse(result.body).data;
        return Array.isArray(data) && data.some(item => item?.id === alias);
      }, client.key);
      if (mode !== 'models' && listed && checks.at(-1)?.passed) {
        const ticket = randomUUID();
        let upstreamRequest: unknown;
        labRequests.set(ticket, { identity: client.id, profile: client.profile, trace: async (stage, value) => {
          if (stage === 'upstream_request') upstreamRequest = value;
        } });
        try {
          await run({ chat: '普通对话', sse: '流式对话', tool: '工具调用协议' }[mode],
            '/v1/chat/completions', result => checkIntegrationResponse(mode, alias, result), client.key, integrationBody(alias, mode), ticket);
        } finally { labRequests.delete(ticket); }
        const actual = isObject(upstreamRequest) && isObject(upstreamRequest.body) ? upstreamRequest.body.model : undefined;
        checks.push({ name: '实际模型映射', passed: actual === model.upstream,
          request: { publicModelId: model.alias, configuredUpstreamId: model.upstream }, response: { upstreamRequest } });
      }
    }
    return redact({ protocol, revision: snapshot.version, checks }, secrets);
  } finally { integrationChecks--; }
});
admin.get('/admin/tls/ca', async (_req, reply) => {
  const ca = tlsStore().ca;
  if (!ca) throw new GatewayError(404, 'ca_not_configured', '未配置私有 CA');
  const filename = tlsStore().status().selfSigned ? 'gateway-server.crt' : 'gateway-ca.crt';
  return reply.type('application/x-pem-file').header('content-disposition', 'attachment; filename=' + filename).send(ca);
});
admin.post('/admin/tls/csr', async req => tlsStore().csr(z.object({ names: namesSchema }).parse(req.body).names));
function applyTls(options: TlsOptions) {
  (api.server as unknown as HttpsServer).setSecureContext(options);
  (admin.server as unknown as HttpsServer).setSecureContext(options);
}
admin.put('/admin/tls', async req => tlsStore().update(importSchema.parse(req.body), applyTls));
admin.post('/admin/tls/self-signed', async req => tlsStore().renewSelfSigned(
  z.object({ names: namesSchema }).parse(req.body).names, applyTls));
admin.patch('/admin/tls/endpoint', async req => tlsStore().setPublicPort(z.object({
  apiPublicPort: z.number().int().min(1).max(65535),
}).parse(req.body).apiPublicPort));
admin.get('/admin/history', async () => store.history());
admin.get('/admin/history/:version', async req => store.publicVersion(store.getRevision(z.coerce.number().int().positive().parse((req.params as { version: string }).version))));
admin.get('/admin/backup', async (_req, reply) => reply.header('content-disposition', 'attachment; filename=gateway-config.json').send(store.publicVersion()));
const change = z.object({ version: z.number().int().positive(), config: z.unknown() });
admin.put('/admin/config', async req => {
  const body = change.parse(req.body);
  store.publish(body.version, body.config, { allowNewOrigins: true });
  return publishedConfig();
});
admin.post('/admin/validate', async req => {
  const body = change.parse(req.body);
  return store.publicVersion({ version: body.version, config: store.validate(body.config, true, true) });
});
admin.post('/admin/restore', async req => {
  const body = change.parse(req.body);
  return store.publish(body.version, body.config, { action: 'restore', allowNewOrigins: true });
});
admin.post('/admin/rollback', async req => {
  const body = z.object({ version: z.number().int().positive(), target: z.number().int().positive() }).parse(req.body);
  return store.publish(body.version, store.getRevision(body.target).config, { action: `rollback:${body.target}`, retainSecrets: false });
});
const overviewQuery = z.object({ range: z.enum(Object.keys(trafficRanges) as TrafficRange[]).default('1h') });
admin.get('/admin/overview', async req => {
  const { range } = overviewQuery.parse(req.query);
  const metrics = store.metrics();
  const checks = store.checks();
  const config = store.snapshot.config;
  const enabled = config.services.filter(service => service.enabled);
  const first = metrics.filter(row => typeof row.firstTokenMs === 'number');
  const latest = new Map<string, number>();
  for (const row of metrics) if (row.revision === store.snapshot.version && row.upstreamOrigin && !latest.has(row.upstreamOrigin)
    && !row.cancelled && row.error !== 'concurrency_limit') latest.set(row.upstreamOrigin, row.upstreamStatus ?? 502);
  return { metrics, checks, traffic: store.traffic(range), overview: {
    configured: config.services.length, published: config.models.filter(model => model.enabled && enabled.some(service => service.id === model.service)).length,
    online: enabled.filter(service => (latest.get(service.origin) ?? 0) >= 200 && (latest.get(service.origin) ?? 0) < 300).length,
    unhealthy: enabled.filter(service => (latest.get(service.origin) ?? 0) >= 400).length,
    requests: metrics.length, success: metrics.filter(row => row.status >= 200 && row.status < 300).length,
    latency: metrics.length ? Math.round(metrics.reduce((sum, row) => sum + row.elapsedMs, 0) / metrics.length) : null,
    firstToken: first.length ? Math.round(first.reduce((sum, row) => sum + row.firstTokenMs, 0) / first.length) : null,
    active: inFlight(), version, status: 'ready',
  } };
});
admin.post('/admin/preview', async req => {
  const input = z.object({ alias: z.string(), profile: profileSchema.default('generic'), body: z.record(z.string(), z.unknown()),
    config: z.unknown().optional(), stage: z.enum(['request', 'headers', 'response', 'sse']).default('request') }).parse(req.body);
  const config = input.config === undefined ? store.snapshot.config : store.validate(input.config);
  const mapping = config.models.find(item => item.alias === input.alias);
  const model = mapping && withProfile(mapping, input.profile);
  const service = config.services.find(item => item.id === model?.service);
  if (!model || !service) throw invalid('模型映射不存在');
  let result: unknown;
  try {
    if (input.stage === 'request') result = prepare(input.body, model);
    else if (input.stage === 'headers') result = outgoingHeaders(service, model, false, false, input.body);
    else result = applyRules(input.body, model.rules, input.stage);
  } catch (error) {
    throw error instanceof GatewayError ? error : invalid('预览样本无法应用当前规则');
  }
  const saved = store.snapshot.config.services.flatMap(item => item.apiKey ? [item.apiKey] : []);
  return redact(result, [...suppliedSecrets, ...saved, ...(service.apiKey ? [service.apiKey] : [])]);
});
admin.post('/admin/upstream/models', async (req, reply) => {
  const { id } = z.object({ id: z.string() }).parse(req.body);
  const service = store.snapshot.config.services.find(item => item.id === id);
  if (!service) throw invalid('上游服务不存在');
  const client = downstream(reply);
  try { return redact(await upstreamModels(service, client.abort.signal), [...suppliedSecrets, service.apiKey ?? '']); }
  catch (error) { throw translate(error); }
});
admin.post('/admin/lab', async (req, reply) => {
  const input = z.object({ alias: z.string().optional(), service: z.string().optional(), direct: z.boolean().default(false),
    profile: profileSchema.default('generic'), path: z.string().optional(), body: z.record(z.string(), z.unknown()),
    headers: z.record(z.string(), z.unknown()).optional() }).parse(req.body);
  const config = store.snapshot.config;
  const model = config.models.find(item => item.alias === input.alias);
  const service = config.services.find(item => item.id === (input.direct ? input.service ?? model?.service : model?.service));
  if (!service || (!input.direct && (!model?.enabled || !service.enabled))) throw invalid('模型或服务不可用');
  if (input.path && input.path !== (input.direct ? service.chatPath : '/v1/chat/completions')) throw invalid('请求路径须与已配置接口一致');
  const target = { ...(model ?? { alias: 'direct', service: service.id, enabled: true, rules: [], profileRules: {}, capabilities: {
    tools: 'unverified', reasoning: 'unverified', streaming: 'unverified', vision: 'unverified',
  }, sseEnd: 'require_done' }), upstream: input.direct && typeof input.body.model === 'string' ? input.body.model : model?.upstream };
  if (!target.upstream) throw invalid('直连测试需要真实模型 ID');
  const eligible = settingsValue.clients.filter(client => client.models.includes('*') || client.models.includes(model?.alias ?? ''));
  const identity = eligible.find(client => client.profile === input.profile) ?? eligible[0];
  if (!input.direct && (!identity || input.body.model !== model?.alias)) throw invalid('需要有权访问此映射的客户端且请求 model 必须与所选别名一致');
  if (!input.direct && labRequests.size >= 16) throw new GatewayError(429, 'lab_busy', '实验室并发请求过多');
  const client = downstream(reply);
  client.start('application/x-ndjson; charset=utf-8');
  const startedAt = performance.now();
  const trace = async (stage: string, value: unknown) => client.write(JSON.stringify({ stage, ms: Math.round(performance.now() - startedAt), value }) + '\n');
  try {
    if (input.direct) {
      const result = await proxy({ body: input.body, model: { ...target, upstream: target.upstream }, service, direct: true, mode: 'diagnostic',
        headers: input.headers, signal: client.abort.signal, secrets: suppliedSecrets, metrics: {}, trace,
        writer: { start() {}, async write() {} } });
      await trace('result', { status: result.status });
    } else {
      const ticket = randomUUID();
      let protocolFailure: unknown;
      labRequests.set(ticket, { identity: identity.id, profile: input.profile, headers: input.headers, trace: async (stage, value) => {
        if (stage === 'compatibility' && isObject(value) && value.status === 'FAIL') protocolFailure = value;
        await trace(stage, value);
      } });
      try {
        const status = await requestGateway({ port: apiPort, host: certificates?.primaryName, ca: certificates?.ca,
          path: '/v1/chat/completions', bearer: identity.key, ticket, body: input.body, signal: client.abort.signal });
        await trace('result', isObject(protocolFailure) ? { ...protocolFailure, status: protocolFailure.httpStatus } : { status });
      } finally { labRequests.delete(ticket); }
    }
  } catch (error) {
    if (!client.abort.signal.aborted) {
      const failure = translate(error);
      if (failure.code === 'gateway_transport_failed') await trace('transport', { protocol: certificates ? 'HTTPS' : 'HTTP',
        verified: false, authenticated: false, profile: input.profile });
      await trace('result', { status: failure.status, ...errorBody(failure) });
    }
  } finally { client.end(); }
});
admin.post('/admin/check', async (req, reply) => {
  if (checking) throw new GatewayError(429, 'check_in_progress', '已有体检任务正在运行');
  const { alias, profile } = z.object({ alias: z.string(), profile: profileSchema.default('generic') }).parse(req.body);
  const snapshot = store.snapshot;
  const model = snapshot.config.models.find(item => item.alias === alias);
  const service = snapshot.config.services.find(item => item.id === model?.service);
  if (!model || !service) throw invalid('模型映射不存在');
  checking = true;
  const client = downstream(reply);
  try {
    client.start('application/x-ndjson; charset=utf-8');
    const results = await diagnose(withProfile(model, profile), service, client.abort.signal, suppliedSecrets,
      async finding => client.write(JSON.stringify({ value: finding }) + '\n'));
    store.saveCheck(alias, snapshot.version, results, profile);
  } catch (error) {
    if (!client.abort.signal.aborted) await client.write(JSON.stringify({ value: { test: '诊断', status: 'FAIL', reason: translate(error).message } }) + '\n');
  } finally { checking = false; if (client.started) client.end(); }
});

await admin.register(staticFiles, { root: fileURLToPath(new URL('../web/', import.meta.url)), index: 'index.html', cacheControl: false });
let testServer: HttpServer | undefined;
async function shutdown() {
  if (draining) return;
  draining = true;
  const stop = new GatewayError(503, 'gateway_shutdown', 'Gateway shutdown drain deadline elapsed');
  const timer = setTimeout(() => {
    for (const [abort, response] of downstreamRequests) {
      abort.abort(stop);
      response.destroy();
    }
    for (const server of [api.server, admin.server, testServer]) server?.closeAllConnections();
  }, limits().drainMs);
  try {
    await Promise.all([api.close(), admin.close(), testServer && new Promise<void>((resolve, reject) =>
      testServer!.close(error => error ? reject(error) : resolve()))]);
  } finally { clearTimeout(timer); }
}
for (const signal of ['SIGTERM', 'SIGINT'] as const) process.on(signal, () => {
  void shutdown().catch(() => { process.stderr.write('Gateway shutdown failed\n'); process.exitCode = 1; });
});
await api.listen({ host: '0.0.0.0', port: apiPort });
await admin.listen({ host: '0.0.0.0', port: adminPort });
if (certificates && httpEnabled) {
  const listener = createHttp((request, response) => api.routing(request, response));
  testServer = listener;
  listener.requestTimeout = options.requestTimeout;
  await new Promise<void>((resolve, reject) => {
    listener.once('error', reject);
    listener.listen(httpPort, '0.0.0.0', resolve);
  });
}
process.stdout.write(`LLM Compatibility Gateway ${version} ready\n`);
