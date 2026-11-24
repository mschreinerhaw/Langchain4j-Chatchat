import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { isIP } from 'node:net';
import { checkServerIdentity } from 'node:tls';
import { CertificateStore } from './tls.js';

const key = process.env.CONFIG_ENCRYPTION_KEY ?? '';
const tls = process.env.GATEWAY_HTTP_ONLY === 'true' ? undefined
  : new CertificateStore(process.env.TLS_DATA_DIR ?? '/app/certs', Buffer.from(key, 'hex'));
const port = Number(tls ? process.env.HTTPS_PORT || 8443 : process.env.HTTP_TEST_PORT || 18080);
function check(port: number, path: string) {
  const name = tls?.primaryName;
  return new Promise<void>((resolve, reject) => {
    const request = (name ? httpsRequest : httpRequest)({
      host: '127.0.0.1', port, path, signal: AbortSignal.timeout(3000),
      ...(name ? { servername: isIP(name) ? '' : name, ca: tls?.ca, rejectUnauthorized: true,
        checkServerIdentity: (_host: string, cert: Parameters<typeof checkServerIdentity>[1]) => checkServerIdentity(name, cert) } : {}),
    }, response => {
      response.resume();
      if (response.statusCode === 200) resolve();
      else reject(new Error('Unexpected health status'));
    });
    request.once('error', reject);
    request.end();
  });
}

try { await Promise.all([check(port, '/ready'), check(Number(process.env.ADMIN_PORT || 18081), '/')]); }
catch { process.exitCode = 1; }
