import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const admin = process.env.TEST_ADMIN ?? 'https://127.0.0.1:18481';
const cline = process.env.TEST_CLINE ?? 'http://127.0.0.1:18329';
const upstream = process.env.TEST_FIXTURE ?? 'http://127.0.0.1:19090';
const alias = 'gateway-cline-fixture';
const model = '/models/ClineCancel';

async function call(base, path, { method = 'GET', headers = {}, body, status = [200] } = {}) {
  const response = await fetch(base + path, { method, headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10_000) });
  assert.ok(status.includes(response.status), method + ' ' + path + ' HTTP ' + response.status);
  return { data: await response.json(), headers: response.headers };
}
async function waitFor(callback) {
  for (let i = 0; i < 80; i++) {
    const result = await callback();
    if (result) return result;
    await delay(250);
  }
  throw Error('Expected event did not arrive');
}
const login = await call(admin, '/admin/login', { method: 'POST', body: { password: 'fixture-admin-password' } });
const cookie = { cookie: login.headers.get('set-cookie').split(';')[0] };
const configuration = () => call(admin, '/admin/config', { headers: cookie }).then(response => response.data);
const publish = (version, config) => call(admin, '/admin/config', { method: 'PUT', headers: cookie, body: { version, config } });
const state = await configuration();
const previous = state.config.models.find(item => item.alias === alias);
assert.ok(previous, 'Run the successful SDK smoke before switching the alias to the cancellation fixture');
await publish(state.version, { ...state.config, models: state.config.models.map(item => item.alias === alias ? { ...item, upstream: model } : item) });
const agent = (path, options) => call(cline, path, { ...options,
  headers: { authorization: 'Bearer fixture-cline-operator-token', ...options?.headers } }).then(response => response.data);
const fixture = () => call(upstream, '/fixture/metrics').then(response => response.data);
const overview = () => call(admin, '/admin/overview', { headers: cookie }).then(response => response.data);
try {
  const before = await fixture();
  const { sessionId } = await agent('/v1/sessions', { method: 'POST', status: [201], body: { workspaceRef: 'fixture' } });
  const path = '/v1/sessions/' + sessionId;
  await waitFor(async () => (await agent(path + '/status')).status === 'idle');
  const requestId = randomUUID();
  await agent(path + '/messages', { method: 'POST', status: [202], headers: { 'idempotency-key': requestId },
    body: { text: 'Continue with the supplied fixture analysis.' } });
  await waitFor(async () => (await fixture()).clineRequests.slice(before.clineRequests.length).some(row => row.model === model));
  await agent(path + '/cancel', { method: 'POST', status: [200, 202] });
  await waitFor(async () => (await agent(path + '/status')).status === 'cancelled');
  await waitFor(async () => (await fixture()).cancellations > before.cancellations);
  const receipt = await waitFor(async () => (await overview()).metrics.find(row =>
    row.upstreamModel === model && row.status === 499 && row.clientDisconnected));
  const events = (await agent(path + '/events', { headers: { accept: 'application/json' } })).events;
  console.log(JSON.stringify({ sessionId, requestId, gatewayRequestId: receipt.id, status: 'cancelled',
    gatewayStatus: receipt.status, gatewayError: receipt.error, upstreamDisconnected: true,
    events: [...new Set(events.map(event => event.eventType))] }, null, 2));
} finally {
  const latest = await configuration();
  await publish(latest.version, { ...latest.config, models: latest.config.models.map(item => item.alias === alias ? previous : item) });
}
