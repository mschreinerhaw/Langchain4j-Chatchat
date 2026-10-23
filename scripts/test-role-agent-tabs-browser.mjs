import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
const output = process.env.CHATCHAT_BROWSER_OUTPUT || 'target/role-agent-tabs-browser';
assert.ok(process.env.CHATCHAT_TEST_PASSWORD, 'CHATCHAT_TEST_PASSWORD is required');
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.CHATCHAT_BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, locale: 'zh-CN' });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
page.setDefaultTimeout(60000);
const checks = {};
try {
  const response = await context.request.post(base + '/api/v1/enterprise/auth/login', {
    data: { username: process.env.CHATCHAT_TEST_USERNAME || 'admin', password: process.env.CHATCHAT_TEST_PASSWORD },
  });
  assert.equal(response.status(), 200);
  const login = await response.json();
  assert.ok(login.data?.token, 'Login did not return a session');
  await context.addInitScript(session => localStorage.setItem('chatchat.auth.session', JSON.stringify(session)), login.data);
  checks.authenticatedLogin = true;
  await page.goto(base + '/#/w/permissions/systemRoles');
  await page.locator('.permission-master-item').first().waitFor();
  await page.locator('.permission-master-detail[aria-busy="false"]').waitFor();
  const businessRole = page.locator('.permission-master-item').filter({ hasText: '业务管理员' });
  if (await businessRole.count()) await businessRole.first().click();
  const tabs = page.locator('.permission-category-tabs');
  await tabs.getByRole('button', { name: 'Agent', exact: true }).waitFor();
  assert.equal(await tabs.getByRole('button', { name: '领域技能', exact: true }).count(), 1);
  assert.equal(await tabs.getByRole('button', { name: 'Agent', exact: true }).count(), 1);
  for (const label of ['领域技能', 'Agent']) {
    await tabs.getByRole('button', { name: label, exact: true }).click();
    await page.locator('.permission-pagination select').selectOption('50');
    await page.waitForFunction(expected => {
      const cells = [...document.querySelectorAll('.permission-table tbody tr td:nth-child(3)')];
      return cells.length > 0 && cells.every(cell => cell.textContent.startsWith(expected));
    }, label);
    const names = await page.locator('.permission-table tbody tr td:nth-child(2)').allTextContents();
    checks[label] = { count: names.length, names };
    await page.screenshot({ path: output + '/' + (label === 'Agent' ? 'agents' : 'skills') + '.png', fullPage: true });
    await page.locator('.permission-table tbody tr').first().getByRole('button', { name: '管理授权', exact: true }).click();
    await page.locator('.permission-resource-editor').waitFor();
    assert.ok((await page.locator('.permission-resource-editor').textContent()).includes(label));
    await page.getByRole('button', { name: '← 返回角色授权列表', exact: true }).click();
  }
  assert.ok(checks.Agent.names.includes('LiveBOS 报表开发助手'));
  assert.ok(!checks['领域技能'].names.includes('LiveBOS 报表开发助手'));
  assert.equal(checks.Agent.names.some(name => checks['领域技能'].names.includes(name)), false);
  checks.separateListsAndDetails = true;
  for (const width of [1366, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1080 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    assert.equal(await tabs.getByRole('button', { name: 'Agent', exact: true }).isVisible(), true);
  }
  checks.desktopWidths = [1366, 1440, 1920];
  await page.reload();
  await tabs.getByRole('button', { name: 'Agent', exact: true }).waitFor();
  checks.reloadPreservesNewTab = true;
  await page.goto(base + '/#/w/permissions/systemResources');
  await page.locator('.permission-master-detail[aria-busy="false"]').waitFor();
  await page.getByLabel('资源类型', { exact: true }).waitFor();
  await page.getByLabel('资源类型', { exact: true }).selectOption('agent');
  await page.locator('.permission-master-item').first().waitFor();
  assert.ok((await page.locator('.permission-master-item').first().textContent()).includes('Agent'));
  checks.resourcePageAgentFilter = true;
  assert.deepEqual(errors, []);
  writeFileSync(output + '/acceptance.json', JSON.stringify({ status: 'PASSED', checks }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', checks }));
} catch (error) {
  await page.screenshot({ path: output + '/failure.png' }).catch(() => {});
  writeFileSync(output + '/failure.json', JSON.stringify({ status: 'FAILED', error: String(error), checks, errors }, null, 2));
  throw error;
} finally {
  await browser.close();
}
