import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { isIP } from 'node:net';
import { checkServerIdentity } from 'node:tls';
import { GatewayError } from './config.js';

function tlsOptions(host?: string, ca?: string) {
  return host ? { servername: isIP(host) ? '' : host, ca, rejectUnauthorized: true,
    checkServerIdentity: (_name: string, cert: Parameters<typeof checkServerIdentity>[1]) => checkServerIdentity(host, cert) } : {};
}

export async function requestGateway(options: {
  port: number; host?: string; ca?: string; path: string; bearer: string; ticket: string;
  body: object; signal: AbortSignal;
}) {
  const { port, host, ca, path, bearer, ticket, body, signal } = options;
  return new Promise<number>((resolve, reject) => {
    const data = JSON.stringify(body);
    const request = (host ? httpsRequest : httpRequest)({
      host: '127.0.0.1', port, path, method: 'POST', agent: false, signal,
      ...tlsOptions(host, ca),
      headers: { authorization: 'Bearer ' + bearer, 'content-type': 'application/json',
        'content-length': Buffer.byteLength(data), 'x-gateway-lab-ticket': ticket },
    }, response => {
      // The API streams its sanitized diagnostic trace separately. Drain production SSE for backpressure.
      response.resume();
      response.once('end', () => resolve(response.statusCode ?? 502));
      response.once('error', reject);
    });
    request.once('error', () => reject(new GatewayError(502, 'gateway_transport_failed',
      host ? '网关 HTTPS 信任链、名称或连接验证失败' : '本地 HTTP 连接失败')));
    request.end(data);
  });
}

export function probeGateway(options: {
  port: number; host?: string; ca?: string; path: string; bearer?: string; ticket?: string; body?: object; signal: AbortSignal;
}) {
  const { port, host, ca, path, bearer, ticket, body, signal } = options;
  const method = body ? 'POST' : 'GET';
  const data = body ? JSON.stringify(body) : undefined;
  const start = performance.now();
  return new Promise<{ status: number; body: string; mediaType: string; elapsedMs: number; truncated: boolean;
    tls?: { verified: boolean; version: string | null } }>((resolve, reject) => {
    const request = (host ? httpsRequest : httpRequest)({
      host: '127.0.0.1', port, path, method, agent: false, signal, ...tlsOptions(host, ca),
      headers: { ...(bearer ? { authorization: 'Bearer ' + bearer } : {}),
        ...(ticket ? { 'x-gateway-lab-ticket': ticket } : {}),
        ...(data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {}) },
    }, response => {
      const socket = response.socket as import('node:tls').TLSSocket;
      const tls = host ? { verified: socket.authorized, version: socket.getProtocol() } : undefined;
      const chunks: Buffer[] = [];
      const limit = 512_000;
      let bytes = 0;
      response.on('data', (part: Buffer) => {
        if (bytes < limit) chunks.push(part.subarray(0, limit - bytes));
        bytes += part.length;
      });
      response.once('error', reject);
      response.once('end', () => resolve({ status: response.statusCode ?? 502, body: Buffer.concat(chunks).toString('utf8'),
        mediaType: String(response.headers['content-type'] ?? ''), elapsedMs: Math.round(performance.now() - start), truncated: bytes > limit, tls }));
    });
    request.once('error', reject);
    request.end(data);
  });
}
