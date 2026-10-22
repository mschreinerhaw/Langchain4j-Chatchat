import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const { admin } = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
const output = process.env.CHATCHAT_BROWSER_OUTPUT;
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
const checks = {}, errors = [];
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  await context.addInitScript(session => localStorage.setItem('chatchat.auth.session', JSON.stringify(session)), admin);
  const page = await context.newPage();
  page.setDefaultTimeout(60000);
  page.on('pageerror', error => errors.push(error.message));
  async function assertManagementLayout() {
    await page.locator('.tool-publications[aria-busy="false"]').waitFor();
    await page.locator('.workspace-management').waitFor();
    assert.equal(await page.locator('.recent-block,.history-manager-trigger,.assistant-right,.floating-new-conversation').count(), 0);
    assert.equal(await page.locator('.assistant-sidebar').getByText('最近对话', { exact: true }).count(), 0);
    await page.locator('.user-account-trigger').waitFor();
  }
  await page.goto(base + '/#/w/mcp-publication/toolPublications');
  await assertManagementLayout(); checks.directOpenHasNoConversationHistory = true;
  await page.reload(); await assertManagementLayout(); checks.reloadHasNoConversationHistory = true;
  await page.locator('.user-account-trigger').click();
  await page.locator('[data-workspace-id="zhice"]').click();
  await page.waitForURL(/#\/w\/zhice\//);
  await page.locator('.recent-block').waitFor(); checks.chatWorkspaceRetainsHistory = true;
  await page.locator('.user-account-trigger').click();
  await page.locator('[data-workspace-id="mcp-publication"]').click();
  await page.waitForURL(/#\/w\/mcp-publication\//);
  await assertManagementLayout(); checks.switchFromChatHasNoConversationHistory = true;
  for (const width of [1366, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await assertManagementLayout();
    await page.screenshot({ path: output + '/publication-no-history-' + width + '.png' });
  }
  checks.threeDesktopWidths = true;
  assert.deepEqual(errors, []); checks.noPageErrors = true;
} finally {
  writeFileSync(output + '/report.json', JSON.stringify({ checks, errors }, null, 2));
  await browser.close();
}
console.log(JSON.stringify(checks));
