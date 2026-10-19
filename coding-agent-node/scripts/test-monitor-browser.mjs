import { chromium } from '../../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { startFixture } from '../fixtures/monitor-server.mjs';

const fixture = await startFixture();
const browser = await chromium.launch({ executablePath: process.env.CHATCHAT_BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'] });
const context = await browser.newContext({ viewport: { width: 1520, height: 1080 }, locale: 'zh-CN' });
const page = await context.newPage(), errors = [];
async function until(predicate) {
  for (let i = 0; i < 150; i++) { if (await predicate()) return; await new Promise(resolve => setTimeout(resolve, 100)); }
  throw new Error('Browser condition timed out');
}
page.on('pageerror', error => errors.push(error.message));
try {
  await page.goto(fixture.base + '/monitor/');
  await page.locator('#token').fill('incorrect-token-with-at-least-32-characters');
  await page.locator('#login-form button').click();
  await until(async () => (await page.locator('#login-error').innerText()).includes('Invalid'));
  await page.locator('#token').fill(fixture.monitorToken); await page.locator('#login-form button').click();
  await page.locator('#console').waitFor({ state: 'visible' });
  await page.locator('.task-row').first().waitFor();
  await page.locator('[data-tab="codes"]').click();
  await until(async () => (await page.locator('.code-block').innerText()).includes('import json'));
  assert.equal(await page.locator('.code-block').innerText(), fixture.source);
  assert.equal(await page.evaluate(() => window.pwned), undefined);
  assert.equal(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('monitor-secret')), false);
  await page.locator('[data-tab="models"]').click();
  await page.locator('.model-card').first().waitFor();
  assert.ok((await page.locator('#detail-content').innerText()).includes('actual-model'));
  await page.locator('[data-tab="skills"]').click();
  assert.ok((await page.locator('#detail-content').innerText()).includes('指标汇总'));
  await page.locator('[data-tab="events"]').click(); await page.locator('.timeline li').first().waitFor();
  await page.locator('.timeline summary').first().click();
  assert.ok((await page.locator('.timeline pre').first().innerText()).includes('cursor'));
  await context.setOffline(true); await page.locator('#refresh').click();
  await page.locator('#error-banner').waitFor({ state: 'visible' });
  assert.ok(await page.locator('.task-row').count());
  await context.setOffline(false); await page.locator('#refresh').click();
  await until(() => page.locator('#error-banner').isHidden());
  await page.locator('#search').fill('no-matching-task');
  await until(async () => await page.locator('.task-row').count() === 0);
  await page.locator('#search').fill(''); await page.locator('.task-row').first().waitFor();
  await page.locator('[data-tab="overview"]').click();
  await mkdir('../target/coding-node-monitor', { recursive: true });
  await page.screenshot({ path: '../target/coding-node-monitor/desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: '../target/coding-node-monitor/mobile.png', fullPage: true });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  assert.equal(overflow, false);
  await page.locator('[data-view="api"]').click();
  assert.ok((await page.locator('#api-panel').innerText()).includes('/v1/monitor/tasks'));
  // Mobile has no logout in the compact navigation; verify session deletion at desktop width.
  await page.setViewportSize({ width: 1520, height: 1080 }); await page.locator('#logout').click();
  await page.locator('#login').waitFor({ state: 'visible' });
  const unauthenticated = await context.request.get(fixture.base + '/v1/monitor/node'); assert.equal(unauthenticated.status(), 401);
  assert.deepEqual(errors, []);
  const result = { status: 'PASSED', source: 'Local real Cline SDK loop with deterministic gateway fixture',
    checks: ['login', 'read-only session', 'code source/hash', 'XSS text rendering', 'models', 'skills', 'events', 'offline recovery', 'search', 'mobile overflow', 'API discovery', 'logout'], errors };
  await writeFile('../target/coding-node-monitor/browser.json', JSON.stringify(result, null, 2)); console.log(JSON.stringify(result));
} finally { await browser.close(); await fixture.server.shutdown(); }
