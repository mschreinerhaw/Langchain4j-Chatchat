import { GatewayError } from '../server/config.js';

export function runtimeRequest(headers: Record<string, unknown>, disconnect: AbortSignal) {
  const trace: Record<string, string> = {};
  for (const field of ['task', 'session', 'request', 'attempt']) {
    const value = headers['x-runtime-' + field + '-id'];
    if (value === undefined) continue;
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value))
      throw new GatewayError(400, 'invalid_runtime_trace', 'Runtime trace identifier is invalid');
    trace[field + 'Id'] = value;
  }
  const value = headers['x-runtime-deadline-ms'];
  if (value === undefined) return { trace, signal: disconnect, close() {} };
  const deadline = typeof value === 'string' && /^\d{1,16}$/.test(value) ? Number(value) : NaN;
  if (!Number.isSafeInteger(deadline))
    throw new GatewayError(400, 'invalid_runtime_deadline', 'Runtime deadline must be Unix epoch milliseconds');
  const timeout = new AbortController();
  const signal = AbortSignal.any([disconnect, timeout.signal]);
  let timer: NodeJS.Timeout | undefined;
  const schedule = () => {
    const wait = deadline - Date.now();
    if (wait <= 0) timeout.abort(new GatewayError(504, 'runtime_deadline_exceeded', 'Runtime request deadline elapsed'));
    else timer = setTimeout(schedule, Math.min(wait, 2_147_483_647)).unref();
  };
  schedule();
  return { trace, signal, close: () => clearTimeout(timer) };
}
