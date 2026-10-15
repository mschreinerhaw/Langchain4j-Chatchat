import { connect } from 'node:net';
import assert from 'node:assert/strict';

const callback = process.env.RUNTIME_CODING_CALLBACK_URL;
assert.ok(callback, 'Runtime callback URL is required');
const unsigned = await fetch(`${callback}/internal/coding/cline/model`, {
  method: 'POST', body: '{}', signal: AbortSignal.timeout(5000),
});
assert.equal(unsigned.status, 401, 'Callback must reach Java authentication');
const general = await fetch(`${callback}/api/v1/admin/auth/login`, {
  method: 'POST', body: '{}', signal: AbortSignal.timeout(5000),
});
assert.equal(general.status, 404, 'Gateway must reject general API paths');
const denied = [
  ['172.30.91.1', 8080], ['172.30.91.1', 8090], ['172.30.91.1', 18080],
  ['192.168.195.221', 8080], ['192.168.195.221', 8090],
  ['192.168.195.221', 3306], ['192.168.195.221', 5432],
  ['192.168.195.221', 9200], ['1.1.1.1', 443],
];
for (const [host, port] of denied) {
  const reachable = await new Promise(resolve => {
    const socket = connect({ host, port });
    const finish = value => { socket.destroy(); resolve(value); };
    socket.setTimeout(1500, () => finish(false));
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
  });
  assert.equal(reachable, false, `Forbidden network destination is reachable: ${host}:${port}`);
  console.log(`DENIED ${host}:${port}`);
}
console.log('PASS callback authentication, path isolation and network egress');
