// Runtime's existing signed callback protocol, copied without the legacy Worker or SDK.
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { createHash, createHmac, randomUUID } from 'node:crypto';
const MAX_BODY=1024*1024;
function modelRequest(url, options) {
  return new Promise((resolve, reject) => {
    const started = performance.now();
    let callbackConnectMs = null, callbackHeadersMs = null, callbackRequestWriteMs = null;
    const timing = () => ({callbackConnectMs,callbackHeadersMs,callbackRequestWriteMs,
      callbackHttpDurationMs:Math.round(performance.now()-started)});
    const target = new URL(url);
    const request = (target.protocol === 'https:' ? httpsRequest : httpRequest)(target, options, response => {
      callbackHeadersMs = Math.round(performance.now() - started);
      const chunks = []; let size = 0;
      response.on('error', error => reject(Object.assign(error, {
        errorCategory:'CALLBACK_RESPONSE_INTERRUPTED',callbackTiming:timing()})));
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
        callbackTiming: { callbackConnectMs, callbackHeadersMs,callbackRequestWriteMs,
          callbackBodyMs: Math.round(performance.now() - started) - callbackHeadersMs,
          callbackHttpDurationMs: Math.round(performance.now() - started) },
        json: async () => JSON.parse(Buffer.concat(chunks).toString('utf8')),
      }));
    });
    request.once('finish', () => {callbackRequestWriteMs=Math.round(performance.now()-started);});
    request.on('socket', socket => {
      if (socket.connecting) socket.once(target.protocol === 'https:' ? 'secureConnect' : 'connect',
        () => { callbackConnectMs = Math.round(performance.now() - started); });
    });
    request.on('error', error => {
      error.errorCategory = options.signal?.aborted
        ? options.signal.reason?.name === 'TimeoutError' ? 'CALLBACK_DEADLINE' : 'REQUEST_ABORTED'
        : ['ECONNREFUSED', 'ENOTFOUND', 'EAI_AGAIN'].includes(error.code) ? 'CALLBACK_CONNECTION_ERROR'
        : 'CALLBACK_TRANSPORT_ERROR';
      error.callbackTiming=timing();reject(error);
    });
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
    const answer = await transport(new URL(path,callbackUrl).href, {
      method: 'POST', headers: { authorization: `Bearer ${secret}`, 'content-type': 'application/json', accept: 'application/json',
        'x-chatchat-timestamp': timestamp, 'x-chatchat-nonce': nonce,
        'x-chatchat-signature': signature, 'x-chatchat-signature-version': '2' },
      body: bytes, signal: signal ? AbortSignal.any([signal, timeout]) : timeout, redirect: 'error',
    });
    const processingStarted=performance.now();
    const data = await answer.json().catch(() => {
      if (action === 'model') throw Object.assign(new Error('Runtime model callback returned invalid JSON'),
        { errorCategory: 'CALLBACK_RESPONSE_INVALID',callbackTiming:answer.callbackTiming });
      return {};
    });
    if (!answer.ok) throw Object.assign(new Error(data.message ?? `Runtime callback rejected ${action}: ${answer.status}`),
      { errorCategory: 'CALLBACK_HTTP_REJECTED',callbackTiming:answer.callbackTiming });
    if (action === 'model' && answer.callbackTiming) data.callbackTiming = {...answer.callbackTiming,
      callbackResponseProcessingMs:Math.round(performance.now()-processingStarted)};
    return data;
  };
  return {
    atomicModelAttempts: true,
    monitorPython: (operationId, kind='code') => callback('monitor-python', { ...(operationId?{operationId}:{}),kind }),
    observePython: operationId => callback('monitor-python', {operationId,kind:'operation'}),
    budget: (contract, contextTokensEstimate, signal) => callback('budget', { contract, contextTokensEstimate }, signal),
    reserveModel: (contract, model) => callback('reserve-model', { contract, model }),
    preflight: signal => callback('preflight', {}, signal),
    model: (contract, request, signal) => callback('model', { contract, request }, signal),
    settleModel: (contract, model) => callback('settle-model', { contract, model }),
    preparePython: (contract, execution, signal) => callback('prepare-python', { contract, execution }, signal),
    executePython: (contract, execution, signal) => callback('execute-python', { contract, execution }, signal),
    getPython: (contract, operationId, signal) => callback('get-python', { contract, operationId }, signal),
    cancelPython: (contract, operationId) => callback('cancel-python', { contract, operationId }),
    readMetadata: (contract, reference, signal) => callback('read-metadata', { contract, reference }, signal),
    readSkill: (contract, skillId, path, signal) => callback('read-skill', { contract, skillId, path }, signal),
    readInputContext: (contract, signal) => callback('input-context', { contract }, signal),
    nativeInputFile: (contract, fileId, offset, signal) => callback('native-input-file', {contract,fileId,offset},signal),
    readInput: (contract, read, signal) => callback('input-read', { contract, read }, signal),
    submitReport: (contract, submission, signal) => callback('submit-report', { contract, submission }, signal),
    knowledge: (contract, tool, args, toolCallId, signal) => callback('knowledge', { contract, tool, arguments: args, toolCallId }, signal),
    readArtifact: (contract, reference, signal) => callback('read-artifact', { contract, reference }, signal),
    readWorkspace: (contract, operationId, signal) => callback('workspace-read', { contract, operationId }, signal),
    proposeWorkspace: (contract, operationId, proposal, signal) => callback('workspace-propose', { contract, operationId, proposal }, signal),
    queryWorkspaceMetadata: (contract, operationId, query, signal) => callback('workspace-metadata', { contract, operationId, query }, signal),
    validateWorkspace: (contract, operationId, validation, signal) => callback('workspace-validate', { contract, operationId, validation }, signal),
    event: (event) => callback('event', { event }),
    finish: (contract, outcome) => callback('finish', { contract, outcome }),
    checkpoint: (contract, checkpoint) => callback('checkpoint', { contract, checkpoint }),
    requestData: (contract, requirement, signal) => callback('data-request', { contract, requirement }, signal),
    validateSql: (contract, validation, signal) => callback('validate-sql', { contract, validation }, signal),
  };
}
