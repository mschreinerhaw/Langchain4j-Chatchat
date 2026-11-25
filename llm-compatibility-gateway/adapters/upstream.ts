import { Agent, request } from 'undici';
import { GatewayError, type Service } from '../server/config.js';
import { upstreamEndpoint } from '../protocol/origin.js';

export interface Adapter {
  endpoint(service: Service, operation: 'chat' | 'models'): URL;
}

const compatible: Adapter = {
  endpoint: (service, operation) => upstreamEndpoint(service.origin, operation === 'chat' ? service.chatPath : service.modelsPath),
};

// Each profile currently uses its OpenAI-compatible interface.
export const adapters: Record<Service['framework'], Adapter> = {
  openai: compatible, vllm: compatible, sglang: compatible, ollama: compatible,
  xinference: compatible, 'lm-studio': compatible, 'llama.cpp': compatible, localai: compatible, custom: compatible,
};

export function openUpstream(service: Service, operation: 'chat' | 'models', signal: AbortSignal,
  headers: Record<string, string>, payload?: unknown, timeout = { firstMs: service.firstMs, idleMs: service.idleMs }) {
  const url = adapters[service.framework].endpoint(service, operation);
  const dispatcher = new Agent({ connect: { timeout: service.connectMs, ca: service.ca, rejectUnauthorized: true } });
  const authorized = { ...headers };
  if (service.apiKey) authorized[service.authHeader] = service.authPrefix + service.apiKey;
  const firstController = new AbortController();
  const expire = () => firstController.abort(new GatewayError(504, 'first_packet_timeout', '上游首包超时'));
  const timer = timeout.firstMs > 0 ? setTimeout(expire, timeout.firstMs).unref() : undefined;
  const stopFirstTimer = () => clearTimeout(timer);
  const response = request(url, {
    method: operation === 'chat' ? 'POST' : 'GET', body: payload === undefined ? undefined : JSON.stringify(payload),
    headers: authorized, dispatcher, signal: timer ? AbortSignal.any([signal, firstController.signal]) : signal,
    headersTimeout: timeout.firstMs, bodyTimeout: timeout.idleMs,
  });
  // The trace writer can wait for downstream backpressure before awaiting this promise.
  void response.catch(() => {});
  return { response, url, stopFirstTimer, close: async () => { stopFirstTimer(); await dispatcher.destroy(); } };
}
