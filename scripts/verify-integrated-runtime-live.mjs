/** Read-only acceptance against authenticated APIs and the exact full-build frontend assets. */
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const origin = process.env.VERIFY_ORIGIN;
const output = resolve(process.env.VERIFY_OUTPUT || 'target/integrated-release/verification');
const artifacts = JSON.parse(await readFile(process.env.VERIFY_ARTIFACTS || 'target/integrated-release/source-artifacts.json', 'utf8'));
await mkdir(output, { recursive: true });
const report = { sourceCommit: artifacts.sourceCommit, status: 'RUNNING', apis: [], assets: [], pages: [], pageErrors: [], failedRequests: [] };
let browser;
try {
  const response = await fetch(origin + '/api/v1/enterprise/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.VERIFY_USER, password: process.env.VERIFY_PASSWORD })
  });
  assert.equal(response.status, 200);
  const auth = (await response.json()).data;
  async function api(path) {
    const result = await fetch(origin + '/api/v1' + path, { headers: { Authorization: `Bearer ${auth.token}`, 'X-Workspace-Id': 'data-science' } });
    const value = await result.json();
    report.apis.push({ path: path.split('?')[0], status: result.status, code: value.code });
    assert.equal(result.status, 200, path);
    assert.equal(value.code, 200, path);
    return value.data;
  }
  const projects = await api('/data-science/sql/projects');
  const pythonHistory = await api('/data-science/python/assist/history');
  const spaces = await api('/data-science/explorations');
  const tasks = await api('/agent/tasks/page?tenantId=' + auth.user.tenantId + '&page=1&pageSize=10');
  report.counts = { sqlProjects: projects.length, pythonHistory: Array.isArray(pythonHistory) ? pythonHistory.length : pythonHistory.items?.length, explorations: spaces.length, tasks: tasks.total };
  assert.ok(tasks.total > 0);
  for (const prefix of ['index.html', 'assets/AgentWorkshopView-', 'assets/DataExplorationView-', 'assets/SqlStudioView-', 'assets/DataScienceView-']) {
    const entry = Object.keys(artifacts.apiStaticFiles).find(name => name.startsWith('BOOT-INF/classes/static/' + prefix) && (prefix === 'index.html' || name.endsWith('.js')));
    assert.ok(entry, prefix);
    const path = entry.replace('BOOT-INF/classes/static', '');
    const asset = await fetch(origin + path);
    assert.equal(asset.status, 200);
    const hash = createHash('sha256').update(Buffer.from(await asset.arrayBuffer())).digest('hex');
    assert.equal(hash, artifacts.apiStaticFiles[entry], 'Deployed asset differs from full source build: ' + path);
    report.assets.push({ path, sha256: hash });
  }
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--no-proxy-server'] });
  const context = await browser.newContext({ viewport: { width: 1600, height: 1050 } });
  await context.addInitScript(session => localStorage.setItem('chatchat.auth.session', JSON.stringify(session)), auth);
  const page = await context.newPage();
  page.on('pageerror', error => report.pageErrors.push(error.message));
  page.on('response', result => {
    if (result.url().includes('/api/v1/') && result.status() >= 400)
      report.failedRequests.push({ path: new URL(result.url()).pathname, status: result.status() });
  });
  await page.goto(origin + '/index.html#/agents', { waitUntil: 'domcontentloaded' });
  await page.locator('.agent-search-field input').fill('金融文档分析助手');
  const card = page.locator('.agent-card').filter({ hasText: '金融文档分析助手' }).first();
  await card.waitFor({ timeout: 60000 });
  await card.getByRole('button', { name: '设置', exact: true }).click();
  const resources = page.locator('section[aria-label="可查阅的资料"]');
  const checkbox = resources.locator('input[value="knowledge_documents"]');
  await checkbox.waitFor();
  const appearance = await checkbox.evaluate(el => ({ fontSize: getComputedStyle(el.closest('label')).fontSize, fontWeight: getComputedStyle(el.closest('label')).fontWeight, width: el.getBoundingClientRect().width }));
  assert.deepEqual(appearance, { fontSize: '12px', fontWeight: '400', width: 14 });
  await checkbox.uncheck();
  assert.equal(await resources.locator('.agent-resource-settings').count(), 0);
  await checkbox.check();
  assert.ok(await resources.locator('.agent-resource-documents').isVisible());
  assert.ok(await resources.locator('select').isVisible());
  await resources.scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve(output, 'agent-settings.png') });
  report.pages.push({ page: 'Agent settings', conditionalResourceFlow: true, appearance });

  await page.goto(origin + '/index.html#/w/data-science/sqlStudio', { waitUntil: 'domcontentloaded' });
  await page.locator('.sql-studio').waitFor({ timeout: 60000 });
  await page.getByRole('button', { name: '历史会话', exact: true }).waitFor({ timeout: 30000 });
  const sqlHistory = page.getByRole('button', { name: '历史会话', exact: true });
  if (await sqlHistory.isEnabled()) {
    await sqlHistory.click();
    await page.locator('dialog.studio-history-dialog[open]').waitFor();
    await page.getByRole('button', { name: '关闭历史会话', exact: true }).click();
  }
  await page.screenshot({ path: resolve(output, 'sql-studio.png') });
  report.pages.push({ page: 'SQL Studio', historyControlPresent: true });

  await page.goto(origin + '/index.html#/w/data-science/dataScienceDevelop', { waitUntil: 'domcontentloaded' });
  await page.locator('.ds-page').waitFor({ timeout: 60000 });
  const pythonButton = page.getByRole('button', { name: 'Python 历史会话', exact: true });
  await pythonButton.waitFor({ timeout: 30000 });
  await pythonButton.click();
  await page.locator('dialog.studio-history-dialog[open]').waitFor();
  await page.screenshot({ path: resolve(output, 'python-history.png') });
  await page.getByRole('button', { name: '关闭历史会话', exact: true }).click();
  report.pages.push({ page: 'Python Studio', persistedHistoryDialogOpened: true });

  const firstSpace = Array.isArray(spaces) ? spaces[0] : spaces.items?.[0];
  await page.goto(origin + '/index.html#/w/data-science/dataExploration' + (firstSpace?.id ? '?exploration=' + encodeURIComponent(firstSpace.id) : ''), { waitUntil: 'domcontentloaded' });
  await page.locator('.exploration-workspace').waitFor({ timeout: 60000 });
  await page.locator('.exploration-toolbar').waitFor();
  await page.waitForFunction(() => !document.querySelector('.exploration-loading'), null, { timeout: 60000 });
  assert.equal(await page.locator('.exploration-feedback.has-error').count(), 0);
  const restoredNodes = await page.locator('.chain-select[data-node-id]').count();
  await page.screenshot({ path: resolve(output, 'data-exploration.png') });
  report.pages.push({ page: 'Data exploration', workspaceAndCanvasPresent: true, restorationFinished: true, restoredNodes });

  await page.goto(origin + '/index.html#/w/zhice/tasks', { waitUntil: 'domcontentloaded' });
  await page.locator('.runtime-view .task-table > button').first().waitFor({ timeout: 60000 });
  await page.waitForTimeout(6000);
  assert.equal(await page.locator('.runtime-error').count(), 0);
  await page.screenshot({ path: resolve(output, 'tasks.png') });
  report.pages.push({ page: 'Task runtime', populatedAndPolling: true });
  assert.deepEqual(report.pageErrors, []);
  assert.deepEqual(report.failedRequests, []);
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
