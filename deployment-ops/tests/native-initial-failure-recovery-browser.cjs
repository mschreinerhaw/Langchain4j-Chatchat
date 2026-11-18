// Actual initial-installation failure recovery through Vue; no Docker mocks.
const fs = require('fs'), assert = require('assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE);
(async () => {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  const base = process.env.OPS_QA_URL, out = process.env.OPS_TEST_ARTIFACTS;
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH, headless: true, args: ['--no-proxy-server'] });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } }), checks = [];
  async function get(path) { const r = await page.request.get(base + '/api' + path); assert.equal(r.status(), 200); return r.json(); }
  async function inventory() { return (await get('/inventory')).flatMap(n => n.data?.containers || []); }
  try {
    await page.goto(base + '/#/login');
    await page.getByLabel('账号', { exact: true }).fill('admin');
    await page.getByLabel('密码', { exact: true }).fill(input.password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL('**/#/overview');
    const route = '/nodes/1/installations/' + input.task, original = await get(route);
    assert.equal(original.status, 'failed'); assert.equal(original.environment, input.environment); assert(!original.active && !original.previousRun);
    const before = await inventory(), project = 'ops-env-' + input.environment;
    const own = before.filter(c => c.Labels?.['com.docker.compose.project'] === project);
    assert(own.some(c => c.State === 'running'), 'Expected real partial business components');
    const denied = await page.request.post(base + '/api' + route + '/resolve', { data: {}, headers: { 'X-Ops-Request': '1' } });
    assert.equal(denied.status(), 400); checks.push('ending a failure with running components is rejected');
    await page.goto(base + '/#/tasks/deployment/1/' + input.task);
    await page.getByRole('button', { name: '停止残留组件', exact: true }).click();
    await page.getByLabel('输入环境名称确认', { exact: true }).fill(input.environment);
    await page.screenshot({ path: out + '/stop-confirmation.png', fullPage: true });
    const submitted = page.waitForResponse(r => r.url().endsWith('/' + input.task + '/quiesce') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '确认停止残留组件', exact: true }).click();
    assert.equal((await submitted).status(), 200);
    let stopped;
    for (let attempt = 0; attempt < 180; attempt++) {
      stopped = await get(route);
      if (stopped.partialStoppedAt && stopped.status === 'failed') break;
      assert(['stopping', 'queued', 'running'].includes(stopped.status), JSON.stringify({ status: stopped.status, message: stopped.message }));
      await page.waitForTimeout(1000);
    }
    assert(stopped.partialStoppedAt); assert.equal(stopped.failure, original.failure); assert.equal(stopped.failedInstallerLog, original.installerLog);
    checks.push('confirmed application stop preserves original failure and installer log');
    const after = await inventory();
    for (const c of own) { const current = after.find(a => a.Id === c.Id); assert(current); assert.equal(current.State, 'exited'); assert.deepEqual(current.Mounts, c.Mounts); }
    for (const c of before.filter(c => c.Labels?.['com.docker.compose.project'] !== project)) assert(after.some(a => a.Id === c.Id && a.State === c.State));
    checks.push('only the failed project stopped; container IDs, mounts and unrelated containers retained');
    await page.reload(); await page.getByRole('button', { name: '结束失败任务', exact: true }).click();
    await page.getByLabel('输入环境名称确认', { exact: true }).fill(input.environment);
    const resolved = page.waitForResponse(r => r.url().endsWith('/' + input.task + '/resolve') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '确认结束失败任务', exact: true }).click(); assert.equal((await resolved).status(), 200);
    const final = await get(route); assert.equal(final.status, 'resolved'); assert.equal(final.failure, original.failure); assert.equal(final.failedInstallerLog, original.installerLog);
    checks.push('explicit GUI resolution permits a corrected release without deleting application data');
    await page.screenshot({ path: out + '/failure-resolved.png', fullPage: true });
    fs.writeFileSync(out + '/result.json', JSON.stringify({ passed: true, checks, task: input.task, retainedComponents: own.length }, null, 2));
    console.log(JSON.stringify({ passed: true, checks }));
  } catch (error) {
    await page.screenshot({ path: out + '/failure.png', fullPage: true });
    fs.writeFileSync(out + '/result.json', JSON.stringify({ passed: false, checks, error: error.stack }, null, 2)); throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
