import { Agent, request } from 'undici';
import { GatewayError, type Service } from '../server/config.js';

export interface Adapter {
  endpoint(service: Service, operation: 'chat' | 'models'): URL;
}

const compatible: Adapter = {
  endpoint: (service, operation) => {
    const target = new URL(operation === 'chat' ? service.chatPath : service.modelsPath, service.origin);
    if (target.origin !== new URL(service.origin).origin) throw new GatewayError(400, 'upstream_not_allowed', '上游路径无效');
    return target;
  },
};

// Each profile currently uses its OpenAI-compatible interface.
export const adapters: Record<Service['framework'], Adapter> = {
  openai: compatible, vllm: compatible, sglang: compatible, ollama: compatible,
  xinference: compatible, 'lm-studio': compatible, 'llama.cpp': compatible, localai: compatible, custom: compatible,
};

export function openUpstream(service: Service, operation: 'chat' | 'models', signal: AbortSignal,
  headers: Record<string, string>, payload?: unknown) {
  const url = adapters[service.framework].endpoint(service, operation);
  const dispatcher = new Agent({ connect: { timeout: service.connectMs, ca: service.ca, rejectUnauthorized: true } });
  const authorized = { ...headers };
  if (service.apiKey) authorized[service.authHeader] = service.authPrefix + service.apiKey;
  const first = AbortSignal.timeout(service.firstMs);
  const firstController = new AbortController();
  const expire = () => firstController.abort(new GatewayError(504, 'first_packet_timeout', '上游首包超时'));
  first.addEventListener('abort', expire);
  const stopFirstTimer = () => first.removeEventListener('abort', expire);
  const response = request(url, {
    method: operation === 'chat' ? 'POST' : 'GET', body: payload === undefined ? undefined : JSON.stringify(payload),
    headers: authorized, dispatcher, signal: AbortSignal.any([signal, firstController.signal]),
    headersTimeout: service.firstMs, bodyTimeout: service.idleMs,
  });
  // The trace writer can wait for downstream backpressure before awaiting this promise.
  void response.catch(() => {});
  return { response, url, stopFirstTimer, close: async () => { stopFirstTimer(); await dispatcher.destroy(); } };
}
