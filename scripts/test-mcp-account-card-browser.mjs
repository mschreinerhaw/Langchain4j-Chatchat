import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const url = process.env.CHATCHAT_MCP_ADMIN_URL || 'http://192.168.195.221:18101/admin/';
const output = process.env.CHATCHAT_BROWSER_OUTPUT || 'target/mcp-account-card-browser';
assert.ok(process.env.CHATCHAT_TEST_PASSWORD || process.env.CHATCHAT_STAGE_SESSION_FILE, 'Password or private session is required');
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
const errors = [], checks = {};
page.on('pageerror', error => errors.push(error.message));
page.setDefaultTimeout(30000);
try {
  if (process.env.CHATCHAT_STAGE_SESSION_FILE) {
    const session = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
    await page.addInitScript(value => {
      sessionStorage.setItem('chatchat.mcp.admin.token', value.token);
      sessionStorage.setItem('chatchat.mcp.admin.user', value.username);
      sessionStorage.setItem('chatchat.mcp.admin.expiresAt', String(value.expiresAt));
      sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt', String(Date.now()));
    }, session);
  }
  await page.goto(url);
  if (!process.env.CHATCHAT_STAGE_SESSION_FILE) {
    await page.getByPlaceholder('请输入用户名').fill(process.env.CHATCHAT_TEST_USERNAME || 'admin');
    await page.getByPlaceholder('请输入密码').fill(process.env.CHATCHAT_TEST_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
  }
  const card = page.locator('.sidebar-account-card');
  const summary = page.locator('.sidebar-account-summary');
  await card.waitFor();
  await page.getByRole('menuitem', { name: '用户管理', exact: true }).click();
  assert.equal(await card.getAttribute('open'), null);
  assert.equal(await card.getByRole('button', { name: '修改密码', exact: true }).isVisible(), false);
  assert.equal(await card.getByRole('button', { name: '退出登录', exact: true }).isVisible(), false);
  assert.ok((await summary.textContent()).includes('当前用户'));
  assert.ok((await summary.textContent()).includes(process.env.CHATCHAT_TEST_USERNAME || 'admin'));
  checks.collapsedAccountIdentity = true;
  const menu = page.locator('.sidebar-menu');
  for (const [width, height] of [[1366, 768], [1440, 900], [1920, 1080]]) {
    await page.setViewportSize({ width, height });
    assert.equal(await menu.evaluate(element => element.scrollHeight > element.clientHeight + 1), false, 'Unnecessary menu overflow at ' + width + 'x' + height);
  }
  checks.commonDesktopMenusFitWithoutScrolling = true;
  await page.setViewportSize({ width: 1280, height: 600 });
  await menu.getByText('数据能力中心', { exact: true }).click();
  await menu.getByRole('menuitem', { name: '关系库查询', exact: true }).waitFor();
  assert.equal(await menu.evaluate(element => element.scrollHeight > element.clientHeight + 1), true);
  const scrollStyle = await menu.evaluate(element => ({ width: getComputedStyle(element).scrollbarWidth, color: getComputedStyle(element).scrollbarColor }));
  assert.equal(scrollStyle.width, 'thin');
  assert.notEqual(scrollStyle.color, 'auto');
  await menu.hover();
  await page.mouse.wheel(0, 1600);
  await menu.getByRole('menuitem', { name: 'License 授权', exact: true }).scrollIntoViewIfNeeded();
  assert.ok(await menu.evaluate(element => element.scrollTop > 0));
  const shortCard = await card.boundingBox();
  assert.ok(shortCard.y + shortCard.height <= 601);
  await page.screenshot({ path: output + '/overflow-scrollbar.png' });
  await menu.getByText('数据能力中心', { exact: true }).click();
  checks.shortExpandedMenuScrollsWithTheme = scrollStyle;
  await page.setViewportSize({ width: 1440, height: 900 });
  await menu.evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path: output + '/collapsed.png' });
  await summary.focus();
  await page.keyboard.press('Enter');
  await card.getByRole('button', { name: '修改密码', exact: true }).waitFor();
  await page.keyboard.press('Escape');
  assert.equal(await card.getAttribute('open'), null);
  assert.equal(await summary.evaluate(element => document.activeElement === element), true);
  checks.keyboardToggleAndEscape = true;
  for (const [width, height] of [[1366, 768], [1440, 900], [1920, 1080], [1280, 600], [390, 844]]) {
    await page.setViewportSize({ width, height });
    await summary.click();
    assert.equal(await card.getByRole('button', { name: '修改密码', exact: true }).isVisible(), true);
    assert.equal(await card.getByRole('button', { name: '退出登录', exact: true }).isVisible(), true);
    const box = await card.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= width + 1);
    if (width > 900) assert.ok(box.y >= 0 && box.y + box.height <= height + 1);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    await page.screenshot({ path: output + '/expanded-' + width + '.png' });
    await summary.click();
  }
  checks.desktopShortAndMobileLayouts = true;
  await page.setViewportSize({ width: 1440, height: 900 });
  await summary.click();
  await card.getByRole('button', { name: '修改密码', exact: true }).click();
  await page.getByRole('dialog', { name: '修改我的密码', exact: true }).waitFor();
  assert.equal(await card.getAttribute('open'), null);
  await page.getByRole('dialog', { name: '修改我的密码', exact: true }).getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('dialog', { name: '修改我的密码', exact: true }).waitFor({ state: 'hidden' });
  checks.passwordDialogPreserved = true;
  if (!process.env.CHATCHAT_SKIP_LOGOUT) {
    await summary.click();
    await card.getByRole('button', { name: '退出登录', exact: true }).click();
    await page.getByRole('button', { name: '登录', exact: true }).waitFor();
    assert.equal(await page.evaluate(() => sessionStorage.getItem('chatchat.mcp.admin.token')), null);
    checks.logoutPreserved = true;
  }
  assert.deepEqual(errors, []);
  writeFileSync(output + '/acceptance.json', JSON.stringify({ status: 'PASSED', checks, errors }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', checks }));
} catch (error) {
  await page.screenshot({ path: output + '/failure.png' }).catch(() => {});
  writeFileSync(output + '/failure.json', JSON.stringify({ status: 'FAILED', error: String(error), checks, errors }, null, 2));
  throw error;
} finally {
  await browser.close();
}
