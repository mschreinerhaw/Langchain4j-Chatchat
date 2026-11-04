/** Authenticated, read-only deployment acceptance. Credentials are supplied through environment variables. */
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const origin = process.env.TASK_VERIFY_ORIGIN;
assert.ok(origin && process.env.TASK_VERIFY_USER && process.env.TASK_VERIFY_PASSWORD);
const output = resolve(process.env.TASK_VERIFY_OUTPUT || '../../target/task-page-fix/verification');
await mkdir(output, { recursive: true });
const report = { origin, api: [], browser: {}, status: 'RUNNING' };
let browser;
try {
  const login = await fetch(origin + '/api/v1/enterprise/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.TASK_VERIFY_USER, password: process.env.TASK_VERIFY_PASSWORD })
  });
  assert.equal(login.status, 200);
  const payload = await login.json();
  assert.equal(payload.code, 200);
  const session = payload.data;
  const tenant = session.user.tenantId;
  const suffix = `tenantId=${encodeURIComponent(tenant)}`;
  const headers = { Authorization: `Bearer ${session.token}` };
  async function api(path, expected = 200, authenticated = true) {
    const response = await fetch(origin + '/api/v1' + path, { headers: authenticated ? headers : {} });
    const body = await response.json();
    report.api.push({ path: path.split('?')[0], status: response.status, code: body.code });
    assert.equal(response.status, expected, path);
    assert.equal(body.code, expected, path);
    return body.data;
  }
  await api(`/agent/tasks/page?${suffix}`, 401, false);
  const first = await api(`/agent/tasks/page?${suffix}&page=1&pageSize=10`);
  assert.equal(first.items.length, 10);
  assert.ok(first.total > 0);
  assert.equal(first.totalPages, Math.ceil(first.total / 10));
  report.browser.totalTasks = first.total;
  report.browser.restrictedRowsOnFirstPage = first.items.filter(task => task.contentRestricted).length;
  assert.ok(report.browser.restrictedRowsOnFirstPage > 0, 'The reported failing history must be covered');
  const second = await api(`/agent/tasks/page?${suffix}&page=2&pageSize=10`);
  assert.equal(second.page, 2);
  assert.equal(second.total, first.total);
  assert.equal(first.items.some(task => second.items.some(other => other.taskId === task.taskId)), false);
  const success = await api(`/agent/tasks/page?${suffix}&status=SUCCESS&page=1&pageSize=10`);
  assert.ok(success.items.every(task => task.status === 'SUCCESS'));
  const normal = first.items.find(task => !task.contentRestricted);
  if (normal) await api(`/agent/tasks/${encodeURIComponent(normal.taskId)}?${suffix}`);
  const restricted = first.items.find(task => task.contentRestricted);
  const search = await api(`/agent/tasks/page?${suffix}&keyword=${encodeURIComponent(restricted.taskId)}&page=1&pageSize=10`);
  assert.equal(search.total, 1);
  assert.equal(search.items[0].taskId, restricted.taskId);
  assert.deepEqual(restricted.datasets, []);
  assert.equal(restricted.feedbackComment, undefined);
  assert.equal(JSON.stringify(restricted).includes('runtime-evidence:knowledge:'), false);
  await api(`/agent/tasks/${encodeURIComponent(restricted.taskId)}?${suffix}`, 403);
  await api(`/agent/tasks?${suffix}&page=1&pageSize=10`);
  const summary = await api(`/agent/tasks/runtime?${suffix}&latestLimit=10`);
  assert.equal(summary.totalTasks, first.total);

  browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
    args: ['--no-proxy-server']
  });
  const context = await browser.newContext({ viewport: { width: 1614, height: 998 } });
  await context.addInitScript(auth => localStorage.setItem('chatchat.auth.session', JSON.stringify(auth)), session);
  const page = await context.newPage();
  const errors = [], failures = [], requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.url().includes('/api/v1/') && response.status() >= 400)
      failures.push({ path: new URL(response.url()).pathname, status: response.status() });
  });
  page.on('request', request => requests.push(new URL(request.url()).pathname));
  await page.goto(origin + '/index.html#/tasks', { waitUntil: 'domcontentloaded' });
  await page.locator('.runtime-view .task-table > button').first().waitFor({ timeout: 60000 });
  assert.equal(await page.locator('.runtime-error').count(), 0);
  assert.equal(await page.locator('.runtime-view .task-table > button').count(), 10);
  await page.waitForFunction(() => !document.querySelector('.runtime-actions button')?.disabled);
  await page.locator('.runtime-view').screenshot({ path: resolve(output, 'tasks-restored.png') });
  // Open a protected row and allow more than two refresh intervals to elapse.
  const before = requests.length;
  await page.locator('.task-table > button').nth(first.items.indexOf(restricted)).click();
  await page.waitForTimeout(12000);
  assert.equal(await page.locator('.runtime-error').count(), 0);
  assert.equal(requests.slice(before).some(path => path.includes(restricted.taskId)), false);
  assert.deepEqual(errors, []);
  assert.deepEqual(failures, []);
  report.browser.pageErrors = errors;
  report.browser.failedApiRequests = failures;
  report.browser.polls = requests.filter(path => path === '/api/v1/agent/tasks/page').length;
  assert.ok(report.browser.polls >= 3);
  report.browser.restrictedDetailsNotRequested = true;
  report.browser.url = page.url();
  await page.screenshot({ path: resolve(output, 'restricted-task.png'), fullPage: true });
  report.status = 'PASSED';
} catch (error) {
  report.status = 'FAILED';
  report.error = error.message;
  throw error;
} finally {
  await browser?.close();
  await writeFile(resolve(output, 'verification.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
