import { open } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { Readable } from 'node:stream';

const [alias, reportPath, profile = 'cline'] = process.argv.slice(2);
const endpoint = process.env.TEST_GATEWAY_ADMIN;
const password = process.env.TEST_GATEWAY_ADMIN_PASSWORD;
if (!alias || !reportPath || !endpoint || !password) {
  throw new Error('Set TEST_GATEWAY_ADMIN and TEST_GATEWAY_ADMIN_PASSWORD and pass: alias report.json [profile]');
}
const origin = new URL(endpoint).origin;
const headers = { 'content-type':'application/json' };
const login = await fetch(origin + '/admin/login', { method:'POST', headers, body:JSON.stringify({ password }),
  redirect:'error', signal:AbortSignal.timeout(15_000) });
if (!login.ok) throw new Error('Gateway login returned HTTP ' + login.status);
const cookie = login.headers.get('set-cookie')?.split(';')[0];
if (!cookie) throw new Error('Gateway did not establish an administrator session');
const auth = { ...headers, cookie };
try {
  const response = await fetch(origin + '/admin/check', { method:'POST', headers:auth,
    body:JSON.stringify({ alias, profile }), redirect:'error', signal:AbortSignal.timeout(900_000) });
  if (!response.ok || !response.body) throw new Error('Gateway checks returned HTTP ' + response.status);
  const findings = [];
  const reader = createInterface({ input:Readable.fromWeb(response.body) });
  for await (const line of reader) {
    if (!line) continue;
    const { value } = JSON.parse(line);
    if (typeof value?.test !== 'string' || typeof value.status !== 'string') throw new Error('Incomplete Gateway diagnostic response');
    const { test, status, reason, httpStatus, suggestion } = value;
    findings.push({ test, status, reason, httpStatus, suggestion });
  }
  if (!findings.some(item => item.test === 'Cline 完整任务')) throw new Error('Gateway diagnostics stopped before completion');
  const overview = await fetch(origin + '/admin/overview', { headers:{ cookie }, redirect:'error', signal:AbortSignal.timeout(15_000) });
  if (!overview.ok) throw new Error('Gateway overview returned HTTP ' + overview.status);
  const revision = (await overview.json()).checks.find(row => row.model === alias && row.profile === profile)?.version;
  const file = await open(reportPath, 'wx', 0o600);
  try {
    await file.writeFile(JSON.stringify({ alias, profile, revision, recordedAt:new Date().toISOString(), findings }, null, 2) + '\n');
  } finally { await file.close(); }
  console.log(JSON.stringify({ alias, profile, revision, failedChecks:findings.filter(item => item.status === 'FAIL').length }));
  if (findings.some(item => item.status === 'FAIL')) process.exitCode = 2;
} finally {
  await fetch(origin + '/admin/logout', { method:'POST', headers:{ cookie }, redirect:'error',
    signal:AbortSignal.timeout(5000) }).catch(() => {});
}
