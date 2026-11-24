import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';

const admin = 'http://127.0.0.1:18081';
const cline = 'http://127.0.0.1:18329';
const alias = 'gateway-cline-fixture';
const json = { 'content-type': 'application/json' };
async function call(origin, path, options = {}) {
  const response = await fetch(origin + path, {
    method: options.method ?? 'GET', headers: { ...json, ...options.headers },
    body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: AbortSignal.timeout(5000),
  });
  const body = await response.json();
  const allowed = Array.isArray(options.status) ? options.status : [options.status ?? 200];
  assert.ok(allowed.includes(response.status), 'HTTP ' + response.status + ' ' + JSON.stringify(body));
  return { body, headers: response.headers };
}
const login = await call(admin, '/admin/login', { method: 'POST', body: { password: 'fixture-admin-password' } });
const auth = { cookie: login.headers.get('set-cookie').split(';')[0] };
const { body: initial } = await call(admin, '/admin/overview', { headers: auth });
const prior = new Set(initial.metrics.map(row => row.id));
const { body: snapshot } = await call(admin, '/admin/config', { headers: auth });
await call(admin, '/admin/config', { method: 'PUT', headers: auth, body: { version: snapshot.version, config: { ...snapshot.config, models: [
  ...snapshot.config.models.filter(model => model.alias !== alias),
  { alias, upstream: '/models/ClineFixture', service: 'fixture' },
] } } });
const clineAuth = { authorization: 'Bearer fixture-cline-operator-token' };
const agent = (path, options = {}) => call(cline, path, { ...options, headers: { ...clineAuth, ...options.headers } });
const { body: { sessionId } } = await agent('/v1/sessions', { method: 'POST', status: 201, body: { workspaceRef: 'fixture' } });
const route = '/v1/sessions/' + sessionId;
async function waitFor(states) {
  for (let attempt = 0; attempt < 90; attempt++) {
    const { body } = await agent(route + '/status');
    if (states.includes(body.status) || body.status === 'failed') return body;
    if (body.status === 'awaiting_permission') {
      for (const permission of body.pendingPermissions) {
        const kind = permission.tool ?? permission.toolName ?? permission.kind;
        if (kind !== 'attempt_completion') continue;
        await agent('/v1/permissions/' + (permission.id ?? permission.permissionId) + '/respond',
          { method: 'POST', body: { decision: 'approve', reason: 'Mock completion' } });
      }
    }
    await delay(500);
  }
  return (await agent(route + '/status')).body;
}
const idle = await waitFor(['idle']);
assert.equal(idle.status, 'idle');
await agent(route + '/messages', { method: 'POST', status: 202, headers: { 'idempotency-key': 'gateway-cline-smoke' },
  body: { text: 'Reply with Gateway fixture completed and finish the task. Do not change files.' } });
const result = await waitFor(['turn_completed', 'awaiting_input']);
const { body: { events } } = await agent(route + '/events', { headers: { accept: 'application/json' } });
const { body: overview } = await call(admin, '/admin/overview', { headers: auth });
const proxied = overview.metrics.filter(row => !prior.has(row.id) && row.model === alias && row.status === 200 && row.stream);
console.log(JSON.stringify({ state: result.status, events: [...new Set(events.map(event => event.eventType))],
  proxiedRequests: proxied.length, completionObserved: JSON.stringify(events).includes('Gateway fixture completed'),
  modelErrors: events.filter(event => event.eventType === 'model.error' || event.eventType === 'task.failed')
    .map(event => event.payload.error?.message ?? event.payload.message) }, null, 2));
assert.ok(proxied.length > 0, 'Expected an actual Cline SSE request through the gateway');
assert.ok(JSON.stringify(events).includes('Gateway fixture completed'));
assert.equal(result.status, 'turn_completed');
await agent(route + '/cancel', { method: 'POST', status: [200, 202] });
assert.equal((await waitFor(['cancelled'])).status, 'cancelled');
