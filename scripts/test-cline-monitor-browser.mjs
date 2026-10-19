import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync } from 'node:fs';

const base = 'http://192.168.195.221:18100';
const session = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const taskId = process.env.CHATCHAT_STAGE_TASK_ID;
if (!taskId) throw new Error('An actual accepted task ID is required');
const output = 'docs/cline-monitor-20261019-browser';
const browser = await chromium.launch({
  executablePath: process.env.CHATCHAT_BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, timezoneId: 'Asia/Shanghai', locale: 'zh-CN' });
await context.addInitScript(({ session, taskId }) => {
  localStorage.setItem('chatchat.auth.session', JSON.stringify(session));
  sessionStorage.setItem('chatchat.runtime.selectedTaskId', taskId);
}, { session, taskId });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message.replace(/Bearer\s+\S+/g, 'Bearer [REDACTED]')));
const failedRequests = [];
page.on('response', response => {
  if (response.status() >= 400) failedRequests.push({ path: new URL(response.url()).pathname, status: response.status() });
});
try {
  await page.goto(`${base}/#/w/zhice/tasks`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('.runtime-view', { timeout: 60000 });
  const tabs = await page.locator('.runtime-tabs button').allTextContents();
  console.log(JSON.stringify({ stage: 'TASK_PAGE_LOADED', tabs }));
  await page.locator('.runtime-tabs button').filter({ hasText: /事件/ }).first().click();
  await page.waitForSelector('.coding-monitor', { timeout: 60000 });
  await page.locator('.coding-monitor > summary').click();
  console.log(JSON.stringify({ stage: 'ACTUAL_MONITOR_OPENED', taskId }));
  await page.locator('.coding-monitor pre code').waitFor({ state: 'visible', timeout: 600000 });
  const code = await page.locator('.coding-monitor pre code').innerText();
  const historyBefore = await page.locator('.coding-monitor .event-list li').allTextContents();
  await context.setOffline(true);
  await page.locator('.coding-monitor header button').click();
  await page.locator('.coding-monitor [role="alert"]').first().waitFor({ timeout: 15000 });
  const retainedWhileOffline = (await page.locator('.coding-monitor pre code').innerText()) === code;
  await context.setOffline(false);
  await page.locator('.coding-monitor header button').click();
  await page.waitForFunction(() => !document.querySelector('.coding-monitor [role="alert"]'), null, { timeout: 30000 });
  const historyAfter = await page.locator('.coding-monitor .event-list li').allTextContents();
  const recoveredPersistedHistory = historyBefore.every(item => historyAfter.includes(item));
  const toolEvent = page.locator('.coding-monitor .event-list li').filter({ hasText: 'TOOL_STARTED' }).first();
  await toolEvent.locator('.event-payload summary').click();
  const hasActualToolDetails = (await toolEvent.locator('.event-payload pre').innerText()).includes('python_execute');
  const text = await page.locator('.coding-monitor').innerText();
  const geometry = await page.locator('.coding-monitor').evaluate(node => ({
    x: node.getBoundingClientRect().x, width: node.getBoundingClientRect().width,
    viewport: window.innerWidth, scrollWidth: document.documentElement.scrollWidth,
  }));
  const report = { scope: 'Real deployed page and authenticated task API; no request mocking',
    taskId, hasActualSource: code.length > 0, codeLines: code.split('\n').length,
    hasModelSection: text.includes('实际模型调用'), hasSkillsSection: text.includes('Skills'),
    hasEventTimeline: text.includes('持久化事件时间线'), hasCodeHash: text.includes('SHA-256'),
    hasConfiguredAndLoadedSkills: text.includes('已配置') && text.includes('已加载'),
    retainedWhileOffline, recoveredPersistedHistory, hasActualToolDetails, geometry,
    errors, failedRequests, screenshot: output + '.png' };
  // The app owns a viewport scroll container; render the entire evidence panel for capture.
  await page.setViewportSize({ width: 1440, height: 3000 });
  await page.locator('.coding-monitor').screenshot({ path: output + '.png' });
  writeFileSync(output + '.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
  if (errors.length || !report.hasActualSource || !report.hasEventTimeline || !retainedWhileOffline || !recoveredPersistedHistory || !hasActualToolDetails
      || geometry.x < 0 || geometry.width > geometry.viewport) process.exitCode = 1;
} catch (error) {
  await page.screenshot({ path: output + '-failure.png', fullPage: true });
  writeFileSync(output + '.json', JSON.stringify({ scope: 'Real deployed browser', taskId, status: 'FAILED', reason: error.message, errors, failedRequests }, null, 2));
  throw error;
} finally { await browser.close(); }
