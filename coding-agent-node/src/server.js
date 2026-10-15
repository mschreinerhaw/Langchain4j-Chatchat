import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { createCodingRun } from './runner.js';
import { createRuntimeModel } from './runtime-model.js';

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

function boundRun(runs, value) {
  if (!value?.scope?.tenantId || !value?.scope?.userId || !value?.scope?.runId
      || !value?.taskId || !value?.contractHash || !Number.isSafeInteger(value?.fencingToken))
    return null;
  const run = runs.get(taskKey(value));
  return run && run.contractHash === value.contractHash
    && run.fencingToken === value.fencingToken ? run : null;
}

function httpGateway(callbackUrl, secret, binding) {
  const callback = async (action, payload, signal) => {
    const answer = await fetch(`${callbackUrl}/internal/coding/cline/${action}`, {
      method: 'POST', headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json' },
      body: JSON.stringify({ ...binding, ...payload }), signal,
    });
    const data = await answer.json().catch(() => ({}));
    if (!answer.ok) throw new Error(data.message ?? `Runtime callback rejected ${action}: ${answer.status}`);
    return data;
  };
  return {
    reserveModel: (contract, model) => callback('reserve-model', { contract, model }),
    model: (contract, request, signal) => callback('model', { contract, request }, signal),
    settleModel: (contract, model) => callback('settle-model', { contract, model }),
    executePython: (contract, execution, signal) => callback('execute-python', { contract, execution }, signal),
    cancelPython: (contract, operationId) => callback('cancel-python', { contract, operationId }),
    readMetadata: (contract, reference, signal) => callback('read-metadata', { contract, reference }, signal),
    readArtifact: (contract, reference, signal) => callback('read-artifact', { contract, reference }, signal),
    event: (event) => callback('event', { event }),
    finish: (contract, outcome) => callback('finish', { contract, outcome }),
  };
}

/** Node owns only transient SDK state; Runtime's A2 snapshot remains authoritative. */
export function createCodingServer({ secret, callbackUrl, modelFactory, gatewayFactory = httpGateway }) {
  if (typeof secret !== 'string' || secret.length < 32) throw new Error('A 32-character shared secret is required');
  if (!callbackUrl || !/^https?:\/\/((127\.0\.0\.1)|(localhost))(?:\:\d+)?\/?$/.test(callbackUrl))
    throw new Error('D1 PoC callback must be an explicit loopback HTTP endpoint');
  const runs = new Map();
  const server = createServer(async (request, reply) => {
    try {
      const authorization = request.headers.authorization ?? '';
      if (!equalSecret(authorization.startsWith('Bearer ') ? authorization.slice(7) : '', secret))
        return response(reply, 401, { message: 'Unauthorized' });
      const url = new URL(request.url ?? '/', 'http://localhost');
      if (request.method === 'POST' && url.pathname === '/v1/runs') {
        const value = await body(request);
        if (!value.contract || typeof value.contractHash !== 'string' || !Number.isSafeInteger(value.fencingToken)
            || value.fencingToken < 1) return response(reply, 400, { message: 'Invalid Runtime task binding' });
        const key = taskKey(value.contract);
        const old = runs.get(key);
        if (old) {
          if (old.contractHash !== value.contractHash || old.fencingToken !== value.fencingToken)
            return response(reply, 409, { message: 'Task identity or fencing token conflicts' });
          return response(reply, 202, view(old));
        }
        const run = { contractHash: value.contractHash, fencingToken: value.fencingToken,
          contract: value.contract, state: 'RUNNING', result: null, error: null, session: null };
        const binding = { scope: value.contract.scope, taskId: value.contract.taskId,
          contractHash: value.contractHash, fencingToken: value.fencingToken };
        const gateway = gatewayFactory(callbackUrl, secret, binding);
        run.session = createCodingRun(value.contract, gateway,
          modelFactory ? { model: modelFactory(value.contract), modelTokenReservation: value.modelTokenReservation }
            : { model: createRuntimeModel(gateway, value.contract),
              modelTokenReservation: value.modelTokenReservation });
        runs.set(key, run);
        void run.session.run().then(result => {
          run.result = result;
          run.state = result.status === 'completed' ? 'COMPLETED'
            : result.status === 'aborted' ? 'CANCELLED' : 'FAILED';
        }).catch(error => { run.error = String(error?.message ?? error).slice(0, 512); run.state = 'FAILED'; });
        return response(reply, 202, view(run));
      }
      if (request.method === 'POST' && url.pathname === '/v1/runs/get') {
        const value = await body(request);
        const run = boundRun(runs, value);
        return run ? response(reply, 200, view(run)) : response(reply, 404,
          { message: 'Bound session not found; reconcile Runtime task before dispatch' });
      }
      if (request.method === 'POST' && url.pathname === '/v1/runs/cancel') {
        const value = await body(request);
        const run = boundRun(runs, value);
        if (!run) return response(reply, 404, { message: 'Bound session not found; reconcile Runtime task and Sandbox' });
        run.session.abort('Runtime cancellation');
        return response(reply, 202, view(run));
      }
      return response(reply, 404, { message: 'Unknown route' });
    } catch (error) {
      return response(reply, error.status ?? 500, { message: String(error.message ?? error).slice(0, 512) });
    }
  });
  return server;
}

function view(run) {
  return { status: run.state, contractHash: run.contractHash, fencingToken: run.fencingToken,
    result: run.result, error: run.error };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const secret = process.env.RUNTIME_CODING_SHARED_SECRET;
  const callbackUrl = process.env.RUNTIME_CODING_CALLBACK_URL;
  const host = process.env.RUNTIME_CODING_HOST ?? '127.0.0.1';
  if (!['127.0.0.1', 'localhost'].includes(host)) throw new Error('D1 PoC Node service must bind loopback');
  const port = Number(process.env.RUNTIME_CODING_PORT ?? 8791);
  createCodingServer({ secret, callbackUrl }).listen(port, host);
}
