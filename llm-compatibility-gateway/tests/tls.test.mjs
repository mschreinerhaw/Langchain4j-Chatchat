import { test } from 'node:test';
import assert from 'node:assert/strict';
import { request, createServer } from 'node:https';
import { request as httpRequest } from 'node:http';
import { readFileSync } from 'node:fs';
import { once } from 'node:events';
import { X509Certificate } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

const dir = process.env.TLS_FIXTURE_DIR ?? 'local/tls';
const read = name => readFileSync(dir + '/' + name);
const ca = read('bootstrap/ca.crt');
const names = ['localhost', '127.0.0.1', 'llm-gateway'];
const server = process.env.TEST_TLS_HOST ?? '127.0.0.1';
const api = Number(process.env.TEST_TLS_PORT ?? 8443);
const admin = Number(process.env.TEST_ADMIN_PORT ?? 18081);
const credentials = { authorization: 'Bearer fixture-gateway-credential' };
const service = { id: 'http', name: 'HTTP', origin: 'http://mock:19090', apiKey: 'fixture-upstream-credential' };
const secure = { ...service, id: 'https', name: 'HTTPS', origin: 'https://mock-tls:19443', ca: ca.toString() };
const basic = { upstream: '/models/Qwen中文', service: 'http', enabled: true };
const requestRules = [{ stage: 'request', op: 'rename', path: 'max_completion_tokens', to: 'max_tokens' },
  { stage: 'response', op: 'rename', path: 'fixture', to: 'debug' },
  { stage: 'sse', op: 'rename', path: 'choices.0.delta.reasoning_content', to: 'choices.0.delta.reasoning' }];
const profileRules = { langchain4j: [{ stage: 'headers', op: 'set', path: 'x-test', value: 'java-fixture-profile' }] };
const config = { services: [service, secure, { ...secure, id: 'untrusted', name: 'Untrusted', ca: undefined }],
  models: [{ ...basic, alias: 'sdk-model', profileRules, rules: requestRules }, { ...basic, alias: 'secure-model', service: 'https' },
    { ...basic, alias: 'untrusted-model', service: 'untrusted' }, { ...basic, alias: 'other' }] };

function call(port, path, options = {}) {
  return new Promise((resolve, reject) => {
    const body = options.body === undefined ? undefined : JSON.stringify(options.body);
    const req = request({ host: server, port, path, ca: Object.hasOwn(options, 'ca') ? options.ca : ca,
      minVersion: options.min, maxVersion: options.max, servername: options.servername, agent: false,
      method: options.method ?? 'GET', signal: AbortSignal.timeout(10000),
      headers: { ...(body ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } : {}), ...options.headers },
    }, res => {
      const tls = res.socket.getProtocol();
      const fingerprint = res.socket.getPeerCertificate().fingerprint256;
      const parts = [];
      res.on('data', chunk => parts.push(chunk));
      res.once('error', reject);
      res.once('end', () => {
        const raw = Buffer.concat(parts).toString();
        const json = () => JSON.parse(raw);
        resolve({ status: res.statusCode, raw, json, headers: res.headers, tls, fingerprint });
      });
    });
    req.once('error', reject);
    req.end(body);
  });
}
let cookie;
let version;
const manage = (path, method = 'GET', body) => call(admin, '/admin/' + path, { method, body, headers: { cookie } });
const chat = (model, options = {}, headers = credentials) => call(api, '/v1/chat/completions', { method: 'POST', headers,
  body: { model, messages: [{ role: 'user', content: '你好' }], ...options } });
const leafFingerprint = name => new X509Certificate(read(name)).fingerprint256;
const getStatus = async () => (await manage('tls')).json();
const redirectProbe = async options => await assert.rejects(call(api, '/ready', options),
  error => ['UNABLE_TO_VERIFY_LEAF_SIGNATURE', 'UNABLE_TO_GET_ISSUER_CERT_LOCALLY', 'SELF_SIGNED_CERT_IN_CHAIN',
    'ERR_TLS_CERT_ALTNAME_INVALID', 'CERT_HAS_EXPIRED', 'ERR_SSL_TLSV1_ALERT_PROTOCOL_VERSION'].includes(error.code));

test('HTTPS gateway contract', async t => {
  await t.test('TLS 1.2 and 1.3, IP and DNS SAN, trust-chain rejection, authentication', async () => {
    for (const protocol of ['TLSv1.2', 'TLSv1.3']) {
      const result = await call(api, '/ready', { min: protocol, max: protocol, servername: 'localhost' });
      assert.equal(result.status, 200);
      assert.equal(result.tls, protocol);
    }
    assert.equal((await call(api, '/ready')).status, 200);
    await redirectProbe({ ca: undefined });
    await redirectProbe({ ca: read('wrong-root.crt') });
    await redirectProbe({ servername: 'wrong.invalid' });
    assert.equal((await call(api, '/v1/models')).status, 401);
    assert.equal((await call(admin, '/admin/tls')).status, 401);
    const login = await call(admin, '/admin/login', { method: 'POST', body: { password: 'fixture-admin-password' } });
    assert.equal(login.status, 200);
    assert.match(login.headers['set-cookie'][0], /; Secure/);
    cookie = login.headers['set-cookie'][0].split(';')[0];
    const status = await getStatus();
    assert.equal(status.enabled, true);
    assert.equal(status.listener, 8445);
    assert.equal(status.apiBaseUrl, 'https://localhost:8443/v1');
    assert.equal(status.httpPublicPort, process.env.TEST_HTTP_PORT ? Number(process.env.TEST_HTTP_PORT) : undefined);
    assert.ok(status.clients.every(client => !Object.hasOwn(client, 'key')));
    assert.equal((await manage('tls/ca')).raw, ca.toString());
  });
  await t.test('HTTPS to HTTP and HTTPS, verified upstream CA, models and scoped client profiles', async () => {
    version = (await manage('config')).json().version;
    const publish = await manage('config', 'PUT', { version, config });
    assert.equal(publish.status, 200, publish.raw);
    version = publish.json().version;
    const keyRequest = { clientId: 'java', alias: 'sdk-model' };
    const unauthenticated = await call(admin, '/admin/integration/key', { method: 'POST', body: keyRequest });
    assert.equal(unauthenticated.status, 401);
    const key = await manage('integration/key', 'POST', keyRequest);
    assert.equal(key.status, 200);
    assert.equal(key.headers['cache-control'], 'no-store');
    assert.ok(key.json().apiKey === 'fixture-java-model-credential');
    assert.equal((await manage('integration/key', 'POST', { ...keyRequest, alias: 'secure-model' })).status, 404);
    const list = await call(api, '/v1/models', { headers: credentials });
    assert.ok(list.json().data.some(model => model.id === 'sdk-model'));
    const normal = await chat('sdk-model', { max_completion_tokens: 17 });
    assert.equal(normal.status, 200, normal.raw);
    assert.equal(normal.json().debug.input.max_tokens, 17);
    assert.equal(normal.json().debug.marker, undefined);
    const java = { authorization: 'Bearer fixture-java-model-credential' };
    assert.equal((await chat('sdk-model', {}, java)).json().debug.marker, 'java-fixture-profile');
    assert.equal((await chat('secure-model', {}, java)).status, 404);
    const https = await chat('secure-model');
    assert.equal(https.status, 200, https.raw);
    assert.equal(https.json().model, 'secure-model');
    assert.equal((await chat('untrusted-model')).status, 502);
    if (process.env.TEST_HTTP_PORT) {
      await new Promise((resolve, reject) => {
        const req = httpRequest({ host: server, port: Number(process.env.TEST_HTTP_PORT),
          path: '/v1/chat/completions', method: 'POST', headers: { ...credentials, 'content-type': 'application/json' } }, res => {
          res.resume();
          if (res.statusCode === 200) resolve();
          else reject(new Error('HTTP to HTTPS returned ' + res.statusCode));
        });
        req.once('error', reject);
        req.end(JSON.stringify({ model: 'secure-model', messages: [{ role: 'user', content: 'hello' }] }));
      });
    }
    const sse = await chat('sdk-model', { stream: true, tools: [{ type: 'function', function: { name: 'gateway_echo' } }] });
    assert.equal(sse.status, 200, sse.raw);
    assert.match(sse.raw, /reasoning":"推理/);
    assert.match(sse.raw, /"finish_reason":"tool_calls"/);
    assert.match(sse.raw, /data: \[DONE\]/);
    const end = await chat('secure-model', { stream: true });
    assert.match(end.raw, /"model":"secure-model"/);
    assert.match(end.raw, /data: \[DONE\]/);
  });
  await t.test('peer and import reject invalid, mismatched or expired certificate; certificate activation is atomic', async () => {
    const before = (await call(api, '/ready')).fingerprint;
    const submit = async (name, key = name) => manage('tls', 'PUT', { names, certificate: read(name + '.crt').toString(),
      privateKey: read(key + '.key').toString() });
    assert.equal((await submit('wrong')).status, 400);
    assert.equal((await submit('expired')).status, 400);
    assert.equal((await submit('initial', 'wrong')).status, 400);
    const malformed = await manage('tls', 'PUT', { names, certificate: read('initial.leaf').toString(), privateKey: read('initial.key').toString() });
    assert.equal(malformed.status, 400, 'Incomplete intermediate chain must be rejected');
    assert.equal((await call(api, '/ready')).fingerprint, before);
    const expired = createServer({ cert: read('expired.crt'), key: read('expired.key') }, (_req, res) => res.end('unexpected'));
    expired.listen(0, '0.0.0.0');
    await once(expired, 'listening');
    try {
      if (server === '127.0.0.1') await assert.rejects(call(expired.address().port, '/'), { code: 'CERT_HAS_EXPIRED' });
    } finally { expired.close(); await once(expired, 'close'); }
    const p12 = await manage('tls', 'PUT', { names, p12: read('rotation.p12').toString('base64'), passphrase: 'fixture-password' });
    assert.equal(p12.status, 200, p12.raw);
    assert.equal(p12.json().warning, true);
    const after = (await call(api, '/ready')).fingerprint;
    assert.equal(after, leafFingerprint('rotation.leaf'));
    assert.equal((await call(admin, '/')).fingerprint, after);
    const csr = await manage('tls/csr', 'POST', { names });
    assert.equal(csr.status, 200, csr.raw);
    assert.match(csr.json().request, /BEGIN CERTIFICATE REQUEST/);
    assert.doesNotMatch(csr.raw, /PRIVATE KEY/);
    const badCsr = await manage('tls', 'PUT', { names, csrId: csr.json().id, certificate: read('initial.crt').toString() });
    assert.equal(badCsr.status, 400, 'CSR activation requires its corresponding public key');
    if (process.env.TEST_SIGN_CSR === '1') {
      const signed = execFileSync('docker', ['run', '--rm', '-i', '--network', 'none',
        '--mount', 'type=bind,source=' + resolve(dir) + ',target=/fixture,readonly',
        '--entrypoint', 'openssl', process.env.GATEWAY_IMAGE ?? 'llm-compatibility-gateway:1.1.1', 'x509', '-req', '-in', '/dev/stdin',
        '-CA', '/fixture/intermediate.crt', '-CAkey', '/fixture/intermediate.key', '-set_serial', '555',
        '-days', '45', '-extfile', '/fixture/initial.ext'], { input: csr.json().request, encoding: 'utf8' });
      const deployed = await manage('tls', 'PUT', { names, csrId: csr.json().id,
        certificate: signed + read('intermediate.crt').toString() });
      assert.equal(deployed.status, 200, deployed.raw);
      assert.equal((await call(api, '/ready')).fingerprint, new X509Certificate(signed).fingerprint256);
    }
    if (process.env.TLS_SDK_LEAVE_ROTATED !== '1') {
      const rotated = await submit('initial');
      assert.equal(rotated.status, 200, rotated.raw);
      assert.equal((await call(api, '/ready')).fingerprint, leafFingerprint('initial.leaf'));
    }
  });
  await t.test('real HTTPS laboratory replay and compatibility trace, direct comparison', async () => {
    const input = { alias: 'sdk-model', profile: 'langchain4j', body: { model: 'sdk-model', messages: [{ role: 'user', content: '重放' }] } };
    const via = await manage('lab', 'POST', input);
    assert.equal(via.status, 200, via.raw);
    const rows = via.raw.trim().split('\n').map(JSON.parse);
    assert.deepEqual(rows.map(item => item.stage), ['client', 'transport', 'upstream_request', 'upstream_response', 'gateway', 'compatibility', 'result']);
    assert.equal(rows[1].value.verified, true);
    assert.equal(rows[1].value.authenticated, true);
    assert.match(rows[1].value.protocol, /^TLSv1\.[23]$/);
    assert.equal(rows[2].value.headers['x-test'], 'java-fixture-profile');
    assert.equal(rows[5].value.status, 'PASS');
    assert.equal(rows[6].value.status, 200);
    assert.doesNotMatch(via.raw, /fixture-(java-model|upstream)-credential/);
    const direct = await manage('lab', 'POST', { ...input, direct: true, service: 'https', body: { ...input.body, model: '/models/Qwen中文' } });
    const raw = direct.raw.trim().split('\n').map(JSON.parse);
    assert.equal(raw.find(item => item.stage === 'gateway').value.body.model, '/models/Qwen中文');
    assert.equal(raw.at(-1).value.status, 200);
    const port = await manage('tls/endpoint', 'PATCH', { apiPublicPort: 443 });
    assert.equal(port.status, 200);
    assert.equal((await getStatus()).apiBaseUrl, 'https://localhost/v1');
    assert.equal((await manage('tls/endpoint', 'PATCH', { apiPublicPort: 8443 })).status, 200);
  });
});
