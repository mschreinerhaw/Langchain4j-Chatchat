import assert from 'node:assert/strict';
import { X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { request } from 'node:https';

const trust = readFileSync(process.env.GATEWAY_TEST_CA ?? 'local/self-signed.crt');
const upstreamTrust = readFileSync('local/tls/self-upstream.crt', 'utf8');
const leaf = new X509Certificate(trust);
assert.ok(leaf.verify(leaf.publicKey));
assert.equal(leaf.ca, false);

function call(port, path, options = {}) {
  const payload = options.body === undefined ? undefined : JSON.stringify(options.body);
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, path, ca: options.ca ?? trust, servername: options.servername,
      agent: false, signal: AbortSignal.timeout(8000), method: options.method ?? 'GET',
      headers: { ...(payload ? { 'content-type': 'application/json' } : {}), ...options.headers },
    }, response => {
      const fingerprint = response.socket.getPeerCertificate().fingerprint256;
      const parts = [];
      response.on('data', chunk => parts.push(chunk));
      response.once('error', reject);
      response.once('end', () => resolve({ status: response.statusCode, headers: response.headers,
        text: Buffer.concat(parts).toString(), fingerprint }));
    });
    req.once('error', reject);
    req.end(payload);
  });
}
assert.equal((await call(8443, '/ready')).fingerprint, leaf.fingerprint256);
await assert.rejects(call(8443, '/ready', { ca: [] }), { code: 'DEPTH_ZERO_SELF_SIGNED_CERT' });
await assert.rejects(call(8443, '/ready', { servername: 'wrong.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
const login = await call(18081, '/admin/login', { method: 'POST', body: { password: 'fixture-admin-password' } });
assert.equal(login.status, 200);
const cookie = login.headers['set-cookie'][0].split(';')[0];
const manage = (path, options = {}) => call(18081, '/admin/' + path, { ...options, headers: { cookie } });
const exported = await manage('tls/ca');
assert.match(exported.headers['content-disposition'], /gateway-server.crt/);
assert.equal(new X509Certificate(exported.text).fingerprint256, leaf.fingerprint256);
if (process.env.SELF_SIGNED_RENEW === '1') {
  const change = await manage('tls/self-signed', { method: 'POST', body: { names: ['localhost', '127.0.0.1', 'llm-gateway'] } });
  assert.equal(change.status, 200, change.text);
  assert.doesNotMatch(change.text, /PRIVATE KEY/);
  const next = JSON.parse(change.text).certificate;
  await assert.rejects(call(8443, '/ready'), { code: 'DEPTH_ZERO_SELF_SIGNED_CERT' });
  const result = await call(8443, '/ready', { ca: next });
  assert.equal(result.fingerprint, new X509Certificate(next).fingerprint256);
  assert.notEqual(result.fingerprint, leaf.fingerprint256);
  console.log('Verified self-signed rotation and trust replacement');
} else {
  const current = JSON.parse((await manage('config')).text);
  const http = { id: 'http', name: 'HTTP fixture', origin: 'http://mock:19090', apiKey: 'fixture-upstream-credential' };
  const secure = { ...http, id: 'https', name: 'Self-signed upstream', origin: 'https://mock-tls:19443', ca: upstreamTrust };
  const save = await manage('config', { method: 'PUT', body: { version: current.version, config: {
    services: [http, secure], models: [{ alias: 'sdk-model', service: 'https', upstream: '/models/Qwen中文' }],
  } } });
  assert.equal(save.status, 200, save.text);
  const chat = await call(8443, '/v1/chat/completions', { method: 'POST',
    headers: { authorization: 'Bearer fixture-gateway-credential' },
    body: { model: 'sdk-model', messages: [{ role: 'user', content: 'hello' }] } });
  assert.equal(chat.status, 200, chat.text);
  assert.equal(JSON.parse(chat.text).model, 'sdk-model');
  const lab = await manage('lab', { method: 'POST', body: { alias: 'sdk-model',
    body: { model: 'sdk-model', messages: [{ role: 'user', content: 'hello' }] } } });
  assert.match(lab.text, /"verified":true/);
  assert.match(lab.text, /"stage":"result".*"status":200/);
  console.log('Verified self-signed gateway and upstream, TLS trust and alias routing');
}
