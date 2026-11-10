/** Read-only verification against the deployed, authenticated monitoring page. No mocked responses. */
import assert from 'node:assert/strict';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
const {chromium} = await import(new URL('../chatchat-api/web-app/node_modules/playwright-core/index.mjs', import.meta.url));
const args = Object.fromEntries(Array.from({length: (process.argv.length - 2) / 2}, (_, i) =>
  [process.argv[2 + i * 2].replace(/^--/, ''), process.argv[3 + i * 2]]));
const origin = new URL(args.origin).origin;
const auth = JSON.parse(await readFile(args.auth, 'utf8'));
const output = resolve(args.output);
await mkdir(output, {recursive: true});
async function api(path) {
  const response = await fetch(origin + '/api/v1' + path, {headers: {Authorization: 'Bearer ' + auth.token}});
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.code, 200);
  return payload.data;
}
const tenant = auth.user?.tenantId || auth.tenantId;
assert(tenant, 'Authenticated tenant is required');
const query = '?tenantId=' + encodeURIComponent(tenant);
const summary = await api('/agent/tasks/runtime' + query + '&latestLimit=1');
const experiences = await api('/agent/tasks/runtime/experiences' + query + '&limit=100');
const restricted = experiences.experiences.filter(item => item.contentRestricted).length;
const browser = await chromium.launch({executablePath: args.chrome || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true});
const evidence = {scope: 'REAL_AUTHENTICATED_MONITOR_NO_MOCKS', observedAt: new Date().toISOString(),
  pageErrors: [], apiFailures: [], experienceResponses: [], controlPosts: 0};
try {
  const context = await browser.newContext({viewport: {width: 1600, height: 1000}});
  await context.addInitScript(({origin, auth}) => {
    if (window === window.top && location.origin === origin) localStorage.setItem('chatchat.auth.session', JSON.stringify(auth));
  }, {origin, auth});
  const page = await context.newPage();
  page.on('pageerror', error => evidence.pageErrors.push(error.message));
  page.on('request', request => {
    if (request.method() === 'POST' && !request.url().includes('/auth/')) evidence.controlPosts++;
  });
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/') && response.status() >= 400) evidence.apiFailures.push({path, status: response.status()});
    if (path === '/api/v1/agent/tasks/runtime/experiences') evidence.experienceResponses.push(response.status());
  });
  await page.goto(origin + '/index.html#/w/zhice/tasks', {waitUntil: 'domcontentloaded'});
  await page.locator('.runtime-view').waitFor({timeout: 60000});
  await page.waitForFunction(total => document.querySelector('.runtime-metrics article strong')?.textContent.trim() === String(total), summary.totalTasks);
  if (summary.totalTasks > 0) await page.locator('.task-table > button').first().waitFor();
  await page.screenshot({path: output + '/tasks-private.png', fullPage: true});
  // Observe several actual five-second polls, then exercise explicit refresh and browser reload.
  await page.waitForTimeout(17000);
  await page.locator('.runtime-tabs button').filter({hasText: '经验'}).click();
  if (experiences.aggregateContentRestricted) {
    await page.getByText(experiences.contentRestrictionMessage, {exact: true}).waitFor();
  }
  await page.locator('.experience-subtabs button').filter({hasText: '经验记录'}).click();
  assert.equal(await page.getByText('受限历史经验', {exact: true}).count(), restricted);
  assert.equal(await page.locator('.runtime-error').count(), 0);
  await page.screenshot({path: output + '/experiences-private.png', fullPage: true});
  const beforeRefresh = evidence.experienceResponses.length;
  await page.locator('.runtime-actions button').click();
  await page.waitForFunction(() => !document.querySelector('.runtime-actions button')?.disabled);
  await page.waitForTimeout(1000);
  assert(evidence.experienceResponses.length > beforeRefresh);
  await page.reload({waitUntil: 'domcontentloaded'});
  await page.locator('.runtime-view').waitFor();
  await page.waitForTimeout(6500);
  assert.equal(await page.locator('.runtime-metrics article strong').first().textContent(), String(summary.totalTasks));
  assert.equal(await page.locator('.runtime-error').count(), 0);
  assert(evidence.experienceResponses.length >= 5, 'Actual polling responses must be observed');
  assert(evidence.experienceResponses.every(status => status === 200));
  assert.deepEqual(evidence.apiFailures, []);
  assert.deepEqual(evidence.pageErrors, []);
  assert.equal(evidence.controlPosts, 0);
  Object.assign(evidence, {status: 'PASSED', totalTasks: summary.totalTasks,
    totalExperiences: experiences.totalExperiences, restrictedExperiences: restricted,
    aggregateContentRestricted: experiences.aggregateContentRestricted === true});
} finally {
  await writeFile(output + '/browser-public.json', JSON.stringify(evidence, null, 2));
  await browser.close();
}
console.log(JSON.stringify(evidence, null, 2));
