import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { isIP } from 'node:net';
import { checkServerIdentity } from 'node:tls';
import { GatewayError } from './config.js';

export async function requestGateway(options: {
  port: number; host?: string; ca?: string; path: string; bearer: string; ticket: string;
  body: object; signal: AbortSignal;
}) {
  const { port, host, ca, path, bearer, ticket, body, signal } = options;
  return new Promise<number>((resolve, reject) => {
    const data = JSON.stringify(body);
    const request = (host ? httpsRequest : httpRequest)({
      host: '127.0.0.1', port, path, method: 'POST', agent: false, signal,
      ...(host ? { servername: isIP(host) ? '' : host, ca, rejectUnauthorized: true,
        checkServerIdentity: (_name: string, cert: Parameters<typeof checkServerIdentity>[1]) => checkServerIdentity(host, cert) } : {}),
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
