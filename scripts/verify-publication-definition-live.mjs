import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const origin = process.env.E2E_ORIGIN || 'http://127.0.0.1:5190';
const output = process.env.E2E_OUTPUT || 'target/publication-visual-definition/local';
fs.mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
try {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  const auth = (await (await context.request.post(origin + '/api/v1/enterprise/auth/login', { data: { username: 'admin', password: process.env.E2E_ADMIN_PASSWORD } })).json()).data;
  assert.ok(auth?.token);
  const headers = { Authorization: 'Bearer ' + auth.token, 'X-Workspace-Id': 'mcp-publication' };
  async function api(route) {
    const result = await (await context.request.get(origin + '/api/v1' + route, { headers })).json();
    assert.equal(result.code, 200, result.message);
    return result.data;
  }
  const rows = await api('/tool-publications');
  const running = rows.filter(row => row.state === 'RUNNING');
  const families = new Set(), verified = [], writes = [], errors = [];
  await context.addInitScript(a => localStorage.setItem('chatchat.auth.session', JSON.stringify(a)), auth);
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', request => {
    if (request.url().includes('/tool-publications') && !['GET', 'HEAD'].includes(request.method())) writes.push(request.url());
  });
  await page.goto(origin + '/index.html#/w/mcp-publication/toolPublications');
  await page.locator('.tool-publications .permission-master-list').waitFor();
  for (const row of running) {
    const definition = JSON.parse(row.definitionJson);
    if (families.has(definition.parentToolName) && row.title !== '本地采集金融数据') continue;
    families.add(definition.parentToolName);
    await page.getByPlaceholder('搜索名称或说明').fill(row.title);
    await page.locator('.permission-master-item').filter({ hasText: row.title }).first().click();
    await page.getByRole('tab', { name: '工具定义', exact: true }).click();
    const panel = page.locator('.publication-definition');
    await panel.waitFor();
    await page.waitForFunction(() => !document.querySelector('.publication-definition-notice')?.textContent.includes('正在读取'));
    const expected = [...new Set(definition.templateKeys || [])];
    assert.equal(await panel.locator('.publication-definition-template').count(), expected.length);
    assert.equal(await page.locator('#publication-panel-definition pre').count(), 0);
    const text = await panel.innerText();
    assert.ok(text.includes('执行能力') && text.includes('使用对象') && text.includes('调用输入') && text.includes('返回内容'));
    assert.ok(!text.includes('"parentToolName"') && !text.includes('"templateKeys"') && !text.includes('"properties"'));
    const catalog = await api('/tool-publications/catalog/templates?roleId=' + encodeURIComponent(definition.roleId) + '&parentToolName=' + encodeURIComponent(definition.parentToolName));
    for (const key of expected) {
      const item = catalog.find(item => item.key === key);
      if (item) assert.ok(text.includes(item.title), item.title);
    }
    verified.push({ title: row.title, capability: definition.parentToolName, templates: expected.length });
    if (row.title === '本地采集金融数据') {
      await panel.scrollIntoViewIfNeeded();
      await page.screenshot({ path: output + '/financial-definition.png' });
      const parameters = panel.locator('.publication-template-parameters').first();
      if (await parameters.count()) {
        await parameters.locator('summary').click();
        await parameters.scrollIntoViewIfNeeded();
        await page.screenshot({ path: output + '/template-parameters.png' });
      }
      await page.setViewportSize({ width: 1024, height: 1000 });
      await panel.scrollIntoViewIfNeeded();
      const overflow = await panel.evaluate(element => element.scrollWidth > element.clientWidth + 2);
      assert.equal(overflow, false, 'Definition fits its available width');
      await page.screenshot({ path: output + '/narrow-definition.png' });
      await page.setViewportSize({ width: 1600, height: 1100 });
    }
  }
  const after = await api('/tool-publications');
  const snapshots = list => list.map(row => ({ id: row.id, definition: row.definitionJson, input: row.inputSchemaJson, output: row.outputSchemaJson, state: row.state })).sort((a, b) => a.id.localeCompare(b.id));
  assert.deepEqual(snapshots(after), snapshots(rows));
  assert.deepEqual(writes, []);
  assert.deepEqual(errors, []);
  fs.writeFileSync(output + '/verification.json', JSON.stringify({ origin, verified, publicationSnapshotsUnchanged: true, writeRequests: writes, browserErrors: errors }, null, 2));
  console.log(JSON.stringify({ verified, publicationSnapshotsUnchanged: true, browserErrors: errors }));
} finally { await browser.close(); }
