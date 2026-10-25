import { createServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { createHash, createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createCodingRun } from './runner.js';
import { createRuntimeModel } from './runtime-model.js';
import { verifyClinePackages } from './sdk-packages.js';
import { MonitorStore, createMonitorHandler } from './monitor.ts';

const MAX_BODY = 1024 * 1024;

function equalSecret(actual, expected) {
  const a = Buffer.from(actual ?? '');
  const b = Buffer.from(expected ?? '');
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b);
}

async function body(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY) throw Object.assign(new Error('Request body exceeds limit'), { status: 413 });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw Object.assign(new Error('Invalid JSON'), { status: 400 }); }
}

function response(reply, status, value) {
  const bytes = Buffer.from(JSON.stringify(value));
  reply.writeHead(status, { 'content-type': 'application/json; charset=utf-8',
    'content-length': bytes.length, 'cache-control': 'no-store' });
  reply.end(bytes);
}

function taskKey(contract) {
  return [contract.scope.tenantId, contract.scope.userId, contract.scope.runId, contract.taskId].join('\0');
}

function boundRun(runs, value, allowNewerFence = false) {
  if (!value?.scope?.tenantId || !value?.scope?.userId || !value?.scope?.runId
      || !value?.taskId || !value?.contractHash || !Number.isSafeInteger(value?.fencingToken))
    return null;
  const run = runs.get(taskKey(value));
  return run && run.contractHash === value.contractHash
    && (run.fencingToken === value.fencingToken
      || allowNewerFence && value.fencingToken > run.fencingToken) ? run : null;
}

// Provider inference can outlive fetch's fixed header timeout. The authorized task
// deadline and abort signal own this request; redirects remain forbidden.
function modelRequest(url, options) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const request = (target.protocol === 'https:' ? httpsRequest : httpRequest)(target, options, response => {
      const chunks = []; let size = 0;
      response.on('error', reject);
      response.on('data', chunk => {
        size += chunk.length;
        if (size > 8 * MAX_BODY) {
          response.destroy(new Error('Runtime model response exceeds limit'));
          return;
        }
        chunks.push(chunk);
      });
      response.on('end', () => resolve({
        ok: response.statusCode >= 200 && response.statusCode < 300,
        status: response.statusCode,
        json: async () => JSON.parse(Buffer.concat(chunks).toString('utf8')),
      }));
    });
    request.on('error', reject);
    request.end(options.body);
  });
}

export function httpGateway(callbackUrl, secret, binding, { timeoutMs = 90000 } = {}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 300000)
    throw new Error('Invalid Runtime callback timeout');
  const callback = async (action, payload, signal) => {
    const path = `/internal/coding/cline/${action}`;
    const timestamp = String(Math.floor(Date.now() / 1000));
    const nonce = randomUUID();
    const bytes = JSON.stringify({ ...binding, ...payload });
    const digest = createHash('sha256').update(bytes).digest('hex');
    const signature = createHmac('sha256', secret)
      .update(`v2\nPOST\n${path}\n${timestamp}\n${nonce}\n${digest}`).digest('hex');
    const deadline = Date.parse(payload.contract?.budget?.deadlineAt ?? '');
    const reconciliation = ['get-python', 'cancel-python', 'event', 'finish', 'settle-model'].includes(action);
    const remaining = !reconciliation && Number.isFinite(deadline)
      ? action === 'model' ? deadline - Date.now() : Math.min(timeoutMs, deadline - Date.now()) : timeoutMs;
    if (remaining <= 0) throw new Error('Runtime Coding deadline reached before callback dispatch');
    const timeout = AbortSignal.timeout(remaining);
    const transport = action === 'model' ? modelRequest : fetch;
    const answer = await transport(`${callbackUrl}${path}`, {
      method: 'POST', headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json',
        'x-chatchat-timestamp': timestamp, 'x-chatchat-nonce': nonce,
        'x-chatchat-signature': signature, 'x-chatchat-signature-version': '2' },
      body: bytes, signal: signal ? AbortSignal.any([signal, timeout]) : timeout, redirect: 'error',
    });
    const data = await answer.json().catch(() => ({}));
    if (!answer.ok) throw new Error(data.message ?? `Runtime callback rejected ${action}: ${answer.status}`);
    return data;
  };
  return {
    reserveModel: (contract, model) => callback('reserve-model', { contract, model }),
    model: (contract, request, signal) => callback('model', { contract, request }, signal),
    settleModel: (contract, model) => callback('settle-model', { contract, model }),
    preparePython: (contract, execution, signal) => callback('prepare-python', { contract, execution }, signal),
    executePython: (contract, execution, signal) => callback('execute-python', { contract, execution }, signal),
    getPython: (contract, operationId, signal) => callback('get-python', { contract, operationId }, signal),
    cancelPython: (contract, operationId) => callback('cancel-python', { contract, operationId }),
    readMetadata: (contract, reference, signal) => callback('read-metadata', { contract, reference }, signal),
    readInputContext: (contract, signal) => callback('input-context', { contract }, signal),
    readArtifact: (contract, reference, signal) => callback('read-artifact', { contract, reference }, signal),
    readWorkspace: (contract, operationId, signal) => callback('workspace-read', { contract, operationId }, signal),
    proposeWorkspace: (contract, operationId, proposal, signal) => callback('workspace-propose', { contract, operationId, proposal }, signal),
    event: (event) => callback('event', { event }),
    finish: (contract, outcome) => callback('finish', { contract, outcome }),
    checkpoint: (contract, checkpoint) => callback('checkpoint', { contract, checkpoint }),
    requestData: (contract, requirement, signal) => callback('data-request', { contract, requirement }, signal),
    validateSql: (contract, validation, signal) => callback('validate-sql', { contract, validation }, signal),
  };
}

/** Node owns only transient SDK state; Runtime's A2 snapshot remains authoritative. */
export function createCodingServer({ secret, callbackUrl, modelFactory, gatewayFactory = httpGateway,
  allowedCallbackHosts = ['127.0.0.1', 'localhost'], maxSessions = 256, sessionRetentionMs = 900000,
  callbackTimeoutMs = 90000, monitorToken, monitorDirectory, monitorSecureCookie = false, sdkPackages }) {
  if (!Number.isSafeInteger(maxSessions) || maxSessions < 1 || maxSessions > 4096
      || !Number.isSafeInteger(sessionRetentionMs) || sessionRetentionMs < 0)
    throw new Error('Invalid Coding session resource limits');
  if (!Number.isSafeInteger(callbackTimeoutMs) || callbackTimeoutMs < 1 || callbackTimeoutMs > 300000)
    throw new Error('Invalid Runtime callback timeout');
  if (typeof secret !== 'string' || secret.length < 32) throw new Error('A 32-character shared secret is required');
  let endpoint;
  try { endpoint = new URL(callbackUrl); }
  catch { throw new Error('Runtime callback URL is invalid'); }
  if (endpoint.protocol !== 'http:' || endpoint.username || endpoint.password || endpoint.pathname !== '/'
      || endpoint.search || endpoint.hash || !allowedCallbackHosts.includes(endpoint.hostname))
    throw new Error('Runtime callback URL must use an explicitly allowed internal host');
  const runs = new Map();
  let draining = false;
  if (monitorToken && equalSecret(monitorToken, secret))
    throw new Error('Monitor token must be separate from the Runtime dispatch secret');
  const monitor = new MonitorStore({ directory: monitorDirectory, maxTasks: Math.max(256, maxSessions), secrets: [secret, monitorToken] });
  const handleMonitor = createMonitorHandler({ token: monitorToken, store: monitor, secureCookie: monitorSecureCookie,
    snapshot: () => monitor.node(draining, [...runs.values()].filter(run => run.state === 'RUNNING').length,
      maxSessions, callbackTimeoutMs) });
  const server = createServer(async (request, reply) => {
    try {
      if (await handleMonitor(request, reply)) return;
      const authorization = request.headers.authorization ?? '';
      if (!equalSecret(authorization.startsWith('Bearer ') ? authorization.slice(7) : '', secret))
        return response(reply, 401, { message: 'Unauthorized' });
      const url = new URL(request.url ?? '/', 'http://localhost');
      if (request.method === 'POST' && url.pathname === '/v1/input-capabilities') {
        await body(request);
        return response(reply, 200, { schemaVersion: 'runtime_os.coding.input.v1', formats: ['PARQUET', 'CSV'],
          analysisProfiles: ['runtime-coding:analysis-fast-path:v1'] });
      }
      if (request.method === 'GET' && url.pathname === '/healthz')
        return response(reply, 200, { status: 'UP', sdk: '@cline/agents@0.0.93',
          packages: sdkPackages, runtime: process.version, build: process.env.RUNTIME_CODING_BUILD ?? 'development' });
      if (request.method === 'GET' && url.pathname === '/readyz')
        return response(reply, draining ? 503 : 200, { status: draining ? 'DRAINING' : 'READY',
          activeSessions: [...runs.values()].filter(run => run.state === 'RUNNING').length });
      if (request.method === 'POST' && url.pathname === '/v1/runs') {
        if (draining) return response(reply, 503, { code: 'WORKER_DRAINING' });
        const value = await body(request);
        if (value.contract?.schemaVersion !== 'runtime_os.coding.v1'
            && !(value.contract?.schemaVersion === 'runtime_os.coding.v3'
              && value.contract?.outputContract?.resultFormat === 'JSON_LINES')
            && !(value.contract?.schemaVersion === 'runtime_os.coding.v2'
              && value.contract?.outputContract?.resultFormat === 'WORKSPACE_FILES'))
          return response(reply, 422, { code: 'VERSION_UNSUPPORTED', message: 'Coding protocol version is unsupported' });
        if (!value.contract || typeof value.contractHash !== 'string' || !Number.isSafeInteger(value.fencingToken)
            || value.fencingToken < 1) return response(reply, 400, { message: 'Invalid Runtime task binding' });
        const key = taskKey(value.contract);
        const cutoff = Date.now() - sessionRetentionMs;
        for (const [identity, session] of runs)
          if (identity !== key && session.state !== 'RUNNING' && session.finishedAt <= cutoff) runs.delete(identity);
        const old = runs.get(key);
        if (old) {
          if (old.contractHash !== value.contractHash || value.fencingToken < old.fencingToken)
            return response(reply, 409, { message: 'Task identity or fencing token conflicts' });
          if (old.fencingToken === value.fencingToken) return response(reply, 202, view(old));
          if (old.state === 'RUNNING') {
            old.session.abort('Runtime fencing takeover');
            return response(reply, 409, { message: 'Previous fenced session is stopping; reconcile before dispatch' });
          }
        }
        if (!old && runs.size >= maxSessions)
          return response(reply, 503, { code: 'CAPACITY_UNAVAILABLE', message: 'Coding worker session capacity reached' });
        const run = { contractHash: value.contractHash, fencingToken: value.fencingToken,
          contract: value.contract, state: 'RUNNING', result: null, error: null, session: null };
        const binding = { scope: value.contract.scope, taskId: value.contract.taskId,
          contractHash: value.contractHash, fencingToken: value.fencingToken };
        const gateway = gatewayFactory(callbackUrl, secret, binding, { timeoutMs: callbackTimeoutMs });
        run.recoveryMode = value.checkpoint ? 'CHECKPOINT_RESTORED_NEW_SDK' : 'NEW_SDK';
        let monitorId = null;
        run.session = createCodingRun(value.contract, gateway, {
          model: modelFactory ? modelFactory(value.contract)
            : createRuntimeModel(gateway, value.contract, value.checkpoint?.modelOrdinal ?? 0),
          modelTokenReservation: value.modelTokenReservation, checkpoint: value.checkpoint,
          contractHash: value.contractHash,
          attemptId: `${value.contract.taskId}:fence-${value.fencingToken}`,
          observe: event => monitor.event(monitorId, event),
          observeCode: code => monitor.code(monitorId, code),
        });
        monitorId = monitor.start(value.contract, value.fencingToken, run.recoveryMode);
        runs.set(key, run);
        run.pending = run.session.run().then(result => {
          run.finishedAt = Date.now();
          run.result = result;
          run.error = result.error ?? null;
          run.state = result.status === 'completed' ? 'COMPLETED'
            : result.status === 'aborted' ? 'CANCELLED' : 'FAILED';
          monitor.finish(monitorId, run.state, run.error);
        }).catch(error => { run.finishedAt = Date.now(); run.error = String(error?.message ?? error).slice(0, 512); run.state = 'FAILED';
          monitor.finish(monitorId, run.state, run.error); });
        return response(reply, 202, view(run));
      }
      if (request.method === 'POST' && url.pathname === '/v1/runs/get') {
        const value = await body(request);
        const run = boundRun(runs, value, true);
        return run ? response(reply, 200, view(run)) : response(reply, 404,
          { message: 'Bound session not found; reconcile Runtime task before dispatch' });
      }
      if (request.method === 'POST' && url.pathname === '/v1/runs/cancel') {
        const value = await body(request);
        const run = boundRun(runs, value, true);
        if (!run) return response(reply, 404, { message: 'Bound session not found; reconcile Runtime task and Sandbox' });
        if (run.state === 'RUNNING') run.session.abort('Runtime cancellation');
        return response(reply, 202, view(run));
      }
      return response(reply, 404, { message: 'Unknown route' });
    } catch (error) {
      return response(reply, error.status ?? 500, { message: String(error.message ?? error).slice(0, 512) });
    }
  });
  server.shutdown = async (graceMs = 10000) => {
    draining = true;
    for (const run of runs.values()) if (run.state === 'RUNNING') run.session.abort('Worker shutting down; reconcile Runtime checkpoint');
    let timer;
    try {
      await Promise.race([Promise.allSettled([...runs.values()].map(run => run.pending)),
        new Promise(resolve => { timer = setTimeout(resolve, graceMs); })]);
    } finally { clearTimeout(timer); }
    await monitor.flush();
    server.closeIdleConnections();
    await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
  };
  return server;
}

function view(run) {
  return { status: run.state, contractHash: run.contractHash, fencingToken: run.fencingToken,
    result: run.result, error: run.error, recoveryMode: run.recoveryMode };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const sdkPackages = await verifyClinePackages();
  const secret = process.env.RUNTIME_CODING_SHARED_SECRET_FILE
    ? readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE, 'utf8').trim()
    : process.env.RUNTIME_CODING_SHARED_SECRET;
  const callbackUrl = process.env.RUNTIME_CODING_CALLBACK_URL;
  const host = process.env.RUNTIME_CODING_HOST ?? '127.0.0.1';
  if (!['127.0.0.1', 'localhost', '0.0.0.0'].includes(host))
    throw new Error('Node service bind address is invalid');
  const port = Number(process.env.RUNTIME_CODING_PORT ?? 8791);
  const allowedCallbackHosts = (process.env.RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS ?? '127.0.0.1,localhost')
    .split(',').map(value => value.trim()).filter(Boolean);
  const callbackTimeoutMs = Number(process.env.RUNTIME_CODING_CALLBACK_TIMEOUT_MS ?? 90000);
  const monitorToken = process.env.RUNTIME_CODING_MONITOR_TOKEN_FILE
    ? readFileSync(process.env.RUNTIME_CODING_MONITOR_TOKEN_FILE, 'utf8').trim()
    : process.env.RUNTIME_CODING_MONITOR_TOKEN;
  const level = process.env.RUNTIME_CODING_LOG_LEVEL ?? 'info';
  if (!['silent', 'error', 'warn', 'info'].includes(level)) throw new Error('Invalid worker log level');
  const log = event => { if (level === 'info') process.stdout.write(JSON.stringify({
    timestamp: new Date().toISOString(), component: 'NODE_CLINE', event,
    build: process.env.RUNTIME_CODING_BUILD ?? 'development', port }) + '\n'); };
  const server = createCodingServer({ secret, callbackUrl, allowedCallbackHosts, callbackTimeoutMs,
    sdkPackages,
    monitorToken, monitorDirectory: process.env.RUNTIME_CODING_MONITOR_DIRECTORY,
    monitorSecureCookie: process.env.RUNTIME_CODING_MONITOR_SECURE_COOKIE === 'true' });
  server.listen(port, host, () => log('WORKER_READY'));
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
    log('WORKER_DRAINING');
    void server.shutdown().then(() => log('WORKER_STOPPED')).catch(() => { process.exitCode = 1; });
  });
}
