/** Authenticated production history verification. No HTTP fixtures or generation requests. */
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const base = process.env.STUDIO_VERIFY_BASE || 'http://192.168.195.221:18100';
const session = JSON.parse(readFileSync(process.env.STUDIO_VERIFY_AUTH, 'utf8')).data;
const project = process.env.STUDIO_VERIFY_PROJECT;
const output = process.env.STUDIO_VERIFY_OUT;
assert.ok(project && output, 'Set the existing acceptance project and private output directory');
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.STUDIO_VERIFY_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
await context.addInitScript(auth => localStorage.setItem('chatchat.auth.session', JSON.stringify(auth)), session);
const page = await context.newPage();
const errors = [], submissions = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => {
  if (request.method() === 'POST' && /\/generations(?:$|\/.*\/coding(?:\/resume)?$)/.test(new URL(request.url()).pathname))
    submissions.push(new URL(request.url()).pathname);
});
try {
  await page.goto(base + '/#/w/data-science/sqlStudio');
  const selector = page.getByRole('combobox', { name: 'SQL 工程', exact: true });
  await selector.waitFor();
  await page.waitForFunction(id => [...document.querySelectorAll('select[aria-label="SQL 工程"] option')]
    .some(option => option.value === id), project);
  await selector.selectOption(project);
  const assistant = page.getByRole('button', { name: '切换 AI 工程助手', exact: true });
  if (await assistant.getAttribute('aria-pressed') !== 'true') await assistant.click();
  const delivery = page.locator('details[aria-label="SQL 交付依据"]');
  await delivery.waitFor({ state: 'visible', timeout: 60000 });
  await delivery.locator('summary').click();
  assert.match(await delivery.innerText(), /hive/i);
  assert.match(await delivery.innerText(), /授权字段/);
  assert.match(await delivery.innerText(), /未配置并授权目标数据库试执行/);
  const text = await page.locator('.sql-assistant-panel').innerText();
  assert.doesNotMatch(text, /\bCline\b|\bRuntime\b/i);
  assert.deepEqual(submissions, []);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: output + '/delivery.png', fullPage: true });
  const result = { status: 'PASSED', mockedRequests: false, projectId: project,
    persistedHistoryLoaded: true, deliveryEvidenceVisible: true, targetDialect: 'hive',
    sourceFieldsVisible: true, executionSkipReasonVisible: true,
    frameworkNamesAbsent: true, generationRequests: submissions.length, pageErrors: errors };
  writeFileSync(output + '/verification.json', JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} catch (error) {
  await page.screenshot({ path: output + '/failure.png', fullPage: true });
  writeFileSync(output + '/failure.json', JSON.stringify({ error: error.message, errors, submissions }, null, 2));
  throw error;
} finally {
  await browser.close();
}
