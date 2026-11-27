import { GatewayError } from '../server/config.js';

export function runtimeTrace(headers: Record<string, unknown>) {
  const trace: Record<string, string> = {};
  for (const field of ['task', 'session', 'request', 'attempt']) {
    const value = headers['x-runtime-' + field + '-id'];
    if (value === undefined) continue;
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value))
      throw new GatewayError(400, 'invalid_runtime_trace', 'Runtime trace identifier is invalid');
    trace[field + 'Id'] = value;
  }
  return trace;
}
