import { chromium } from '../../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

const base = process.env.MONITOR_BASE_URL ?? 'http://192.168.195.221:18100';
const token = process.env.MONITOR_VERIFY_TOKEN;
assert.ok(token, 'MONITOR_VERIFY_TOKEN is required');
const destination = '../target/coding-agent-brand-20261020';
await mkdir(destination, { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.CHATCHAT_BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const context = await browser.newContext({ viewport: { width: 1520, height: 1080 }, locale: 'zh-CN' });
const page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
try {
  const response = await page.goto(base + '/monitor/');
  assert.equal(response.status(), 200);
  assert.equal(await page.title(), 'coding-agent · 运维控制台');
  assert.doesNotMatch(await page.content(), /cline/i);
  await page.screenshot({ path: destination + '/login.png', fullPage: true });
  assert.equal((await context.request.get(base + '/v1/monitor/node')).status(), 401);
  await page.locator('#token').fill(token);
  await page.locator('#login-form button').click();
  await page.locator('#console').waitFor({ state: 'visible' });
  assert.equal(await page.locator('.brand').innerText(), 'C\ncoding-agent');
  assert.doesNotMatch(await page.locator('#node-strip').innerText(), /Node v|构建|linux|win32/i);
  assert.doesNotMatch(await page.locator('body').innerText(), /cline|Runtime OS/i);
  assert.equal(await page.locator('#token').inputValue(), '');
  await page.screenshot({ path: destination + '/desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: destination + '/mobile.png', fullPage: true });
  await page.locator('[data-view="api"]').click();
  assert.ok((await page.locator('#api-panel').innerText()).includes('/v1/monitor/tasks'));
  await page.locator('#logout-mobile').click();
  await page.locator('#login').waitFor({ state: 'visible' });
  assert.equal((await context.request.get(base + '/v1/monitor/node')).status(), 401);
  assert.deepEqual(errors, []);
  const report = { status: 'PASSED', url: base + '/monitor/',
    checks: ['product branding', 'login', 'technical version hiding', 'read-only authentication',
      'desktop rendering', 'mobile overflow', 'API discovery', 'mobile logout'], errors };
  await writeFile(destination + '/live-browser.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  await browser.close();
}
