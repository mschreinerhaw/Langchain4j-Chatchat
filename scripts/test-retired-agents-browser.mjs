import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const session = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const output = process.env.CHATCHAT_BROWSER_OUTPUT;
const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
const checks = {}, errors = [], retiredRequests = [];
try {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 }, locale: 'zh-CN' });
  await context.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify(value)), session);
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    if (/\/api\/v1\/(agent\/analysis|enterprise\/agent-registry)/.test(request.url())) retiredRequests.push(request.url());
  });
  await page.goto(base + '/#/w/zhice/chat');
  await page.locator('.chat-view').waitFor();
  assert.equal(await page.getByText('联合分析', { exact: true }).count(), 0);
  checks.chatAndNavigation = true;
  await page.screenshot({ path: output + '/chat.png' });
  const workshopLoaded = page.waitForResponse(response => response.url().includes('/api/v1/agents/workshop?') && response.request().method() === 'GET');
  await page.goto(base + '/#/w/zhice/agents');
  await page.locator('.agent-workshop-view').waitFor();
  const workshopResponse = await workshopLoaded;
  assert.equal(workshopResponse.status(), 200);
  const workshop = (await workshopResponse.json()).data;
  assert.ok(workshop.agents.length > 0);
  assert.ok(workshop.models.length > 0);
  await page.getByRole('button', { name: '刷新', exact: true }).waitFor();
  await page.getByRole('button', { name: '新增Agent', exact: true }).waitFor();
  assert.equal(await page.getByText('接入专有分析 Agent', { exact: true }).count(), 0);
  assert.equal(await page.locator('.remote-agent-card, .remote-agent-dialog').count(), 0);
  await page.getByRole('button', { name: '新增Agent', exact: true }).click();
  await page.locator('form.agent-dialog').waitFor();
  assert.equal(await page.getByText('接入专有分析 Agent', { exact: true }).count(), 0);
  checks.localEditorWithoutRemoteRegistration = true;
  await page.screenshot({ path: output + '/local-editor.png' });
  await page.goto(base + '/#/w/zhice/domainAnalysis');
  await page.waitForTimeout(1500);
  assert.equal(await page.locator('.domain-intelligence-view').count(), 0);
  assert.equal(await page.getByText('联合分析', { exact: true }).count(), 0);
  checks.retiredRouteCannotLoad = true;
  for (const route of ['/agent/analysis/providers', '/enterprise/agent-registry']) {
    const response = await context.request.get(base + '/api/v1' + route, { headers: { Authorization: 'Bearer ' + session.token } });
    assert.equal(response.status(), 404, route);
  }
  checks.retiredAuthenticatedApiRemoved = true;
  assert.deepEqual(retiredRequests, []);
  assert.deepEqual(errors, []);
  checks.noRetiredRequestsOrPageErrors = true;
  writeFileSync(output + '/acceptance.json', JSON.stringify({ status: 'PASSED', checks }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', checks }));
} finally {
  await browser.close();
}
