import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
const upstream = process.env.CHATCHAT_API_UPSTREAM || base;
const output = process.env.CHATCHAT_BROWSER_OUTPUT || 'target/publication-permission-feedback-browser';
assert.ok(process.env.CHATCHAT_TEST_PASSWORD, 'CHATCHAT_TEST_PASSWORD is required');
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const page = await context.newPage();
page.setDefaultTimeout(60000);
const errors = [], checks = {};
page.on('pageerror', error => errors.push(error.message));
if (upstream !== base) await page.route('**/api/**', async route => {
  const original = new URL(route.request().url());
  const response = await route.fetch({ url: upstream + original.pathname + original.search });
  await route.fulfill({ response });
});
async function readablePrimary(button, key) {
  await button.hover();
  const hovered = await button.evaluate(element => ({ background: getComputedStyle(element).backgroundColor, color: getComputedStyle(element).color }));
  assert.equal(hovered.background, 'rgb(36, 85, 199)', key);
  assert.equal(hovered.color, 'rgb(255, 255, 255)', key);
  await button.focus();
  assert.equal(await button.evaluate(element => getComputedStyle(element).color), 'rgb(255, 255, 255)');
  checks[key] = hovered;
}
const publicationRoute = '/#/w/mcp-publication/toolPublications';
try {
  const loginResponse = await context.request.post(upstream + '/api/v1/enterprise/auth/login', {
    data: { username: process.env.CHATCHAT_TEST_USERNAME || 'admin', password: process.env.CHATCHAT_TEST_PASSWORD },
  });
  const login = await loginResponse.json();
  assert.ok(login.data?.token);
  await context.addInitScript(session => localStorage.setItem('chatchat.auth.session', JSON.stringify(session)), login.data);
  await page.goto(base + publicationRoute);
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  assert.equal(await page.getByLabel('版本状态', { exact: true }).inputValue(), 'RUNNING');
  const items = await page.locator('.tool-publications .permission-master-item small').allTextContents();
  assert.ok(items.length > 0 && items.every(text => text.includes('运行中')));
  assert.ok((await page.locator('.publication-version-bar select option:checked').textContent()).includes('运行中'));
  checks.defaultRunningListAndSelection = items.length;
  await readablePrimary(page.getByRole('button', { name: '新建工具', exact: true }), 'createToolHover');
  await page.screenshot({ path: output + '/tool-hover.png' });
  await page.getByRole('button', { name: '新建工具', exact: true }).click();
  await page.getByRole('heading', { name: '创建工具草稿', exact: true }).waitFor();
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.goto(base + '/#/w/permissions/systemRoles');
  await page.locator('.permission-master-detail[aria-busy="false"]').waitFor();
  await readablePrimary(page.getByRole('button', { name: '新增角色', exact: true }), 'createRoleHover');
  await page.getByRole('button', { name: '新增角色', exact: true }).click();
  const dialog = page.locator('.permission-profile-dialog');
  await dialog.waitFor();
  await readablePrimary(dialog.getByRole('button', { name: '保存角色', exact: true }), 'roleDialogSaveHover');
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  const disabled = page.getByRole('button', { name: '保存权限变更', exact: true });
  assert.equal(await disabled.isDisabled(), true);
  await page.mouse.move(0, 0);
  const before = await disabled.evaluate(element => getComputedStyle(element).backgroundColor);
  await disabled.hover({ force: true });
  assert.equal(await disabled.evaluate(element => getComputedStyle(element).backgroundColor), before);
  checks.disabledButtonKeepsBackground = true;
  await page.goto(base + '/#/w/permissions/systemResources');
  await page.locator('.permission-master-detail[aria-busy="false"]').waitFor();
  await readablePrimary(page.locator('.permission-resource-editor').getByRole('button', { name: '保存授权', exact: true }), 'authorizationSaveHover');
  await page.goto(base + publicationRoute);
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  await page.goto(base + '/#/w/permissions/systemResources');
  const notice = page.getByText('授权目录已刷新', { exact: true });
  await notice.waitFor();
  await notice.waitFor({ state: 'hidden', timeout: 6000 });
  checks.successNoticeAutoDismissed = true;
  await page.screenshot({ path: output + '/authorization-after-dismissal.png' });
  await page.reload();
  await page.locator('.permission-master-detail[aria-busy="false"]').waitFor();
  await page.goto(base + publicationRoute);
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  assert.equal(await page.getByLabel('版本状态', { exact: true }).inputValue(), 'RUNNING');
  checks.defaultRunningAfterReload = true;
  assert.deepEqual(errors, []);
  writeFileSync(output + '/acceptance.json', JSON.stringify({ status: 'PASSED', checks, errors }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', checks }));
} catch (error) {
  await page.screenshot({ path: output + '/failure.png' }).catch(() => {});
  writeFileSync(output + '/failure.json', JSON.stringify({ status: 'FAILED', error: String(error), checks, errors }, null, 2));
  throw error;
} finally { await browser.close(); }
