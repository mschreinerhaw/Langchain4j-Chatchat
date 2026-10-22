import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const sessions = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const output = process.env.CHATCHAT_BROWSER_OUTPUT;
const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
const errors = [], checks = {}, contexts = [];
let created;
async function open(session) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 }, locale: 'zh-CN' });
  contexts.push(context);
  await context.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify(value)), session);
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  return { page, context };
}
const headers = { Authorization: 'Bearer ' + sessions.admin.token };
try {
  const { page, context } = await open(sessions.admin);
  await page.goto(base + '/#/w/permissions/systemRoles');
  await page.locator('.system-management-view').waitFor();
  await page.screenshot({ path: output + '/permission-roles.png', fullPage: false });
  checks.existingRoleEditorReused = true;
  await page.goto(base + '/#/w/permissions/permissionTools');
  await page.getByRole('heading', { name: '工具与数据授权', exact: true }).waitFor();
  await page.getByText('授权目录已更新', { exact: true }).waitFor();
  await page.screenshot({ path: output + '/permission-tools.png', fullPage: false });
  checks.scopedGrantWorkspaceLoaded = true;
  await page.goto(base + '/#/w/mcp-publication/toolPublications');
  await page.getByRole('heading', { name: 'MCP 工具发布', exact: true }).waitFor();
  await page.getByText('目录已更新', { exact: true }).waitFor();
  const api = async path => {
    const response = await context.request.get(base + '/api/v1' + path, { headers });
    assert.equal(response.status(), 200); return (await response.json()).data;
  };
  const roles = await api('/tool-publications/catalog/roles');
  const parents = await api('/tool-publications/catalog/parents');
  const role = roles.find(item => item.roleCode === 'SUPER_ADMIN');
  let parent;
  for (const candidate of parents) {
    const options = await api('/tool-publications/catalog/templates?roleId=' + encodeURIComponent(role.id) + '&parentToolName=' + encodeURIComponent(candidate.toolName));
    if (options.length) { parent = candidate; break; }
  }
  assert.ok(parent);
  const form = page.locator('form.management-card');
  const title = '浏览器验收工具 ' + Date.now();
  await form.getByLabel('工具名称', { exact: true }).fill(title);
  await form.getByLabel('使用说明', { exact: true }).fill('Synthetic browser acceptance; retained offline for audit.');
  await form.getByLabel('逻辑名称', { exact: true }).fill('browser_acceptance_' + Date.now());
  await form.getByLabel('执行能力', { exact: true }).selectOption(parent.toolName);
  await form.getByLabel('使用角色', { exact: true }).selectOption(role.id);
  await form.locator('fieldset input[type=checkbox]').first().waitFor();
  await form.locator('fieldset input[type=checkbox]').first().check();
  const saved = page.waitForResponse(response => response.url().endsWith('/api/v1/tool-publications') && response.request().method() === 'POST');
  await form.getByRole('button', { name: '保存新草稿', exact: true }).click();
  const saveResponse = await saved; assert.equal(saveResponse.status(), 200);
  created = (await saveResponse.json()).data;
  const row = page.locator('.release-row').filter({ has: page.getByText(title, { exact: true }) });
  await row.getByRole('button', { name: '校验', exact: true }).waitFor();
  assert.equal(await row.getByRole('button', { name: '发布', exact: true }).count(), 0);
  checks.draftCannotPublishBeforeValidationAndTest = true;
  await row.getByRole('button', { name: '校验', exact: true }).click();
  await row.getByRole('button', { name: '合约测试', exact: true }).waitFor();
  await row.getByRole('button', { name: '合约测试', exact: true }).click();
  await row.getByRole('button', { name: '发布', exact: true }).waitFor();
  await row.getByRole('button', { name: '发布', exact: true }).click();
  await row.getByRole('button', { name: '下线', exact: true }).waitFor();
  await page.screenshot({ path: output + '/tool-publication-running.png', fullPage: false });
  checks.createTestAndPublishThroughUi = true;
  await row.getByRole('button', { name: '下线', exact: true }).click();
  await row.getByRole('button', { name: '回滚到此版本', exact: true }).waitFor();
  checks.offlineThroughUi = true;
  await page.screenshot({ path: output + '/tool-publication-offline.png', fullPage: false });
  const { page: reader } = await open(sessions.reader);
  await reader.goto(base + '/#/w/mcp-publication/toolPublications');
  await reader.getByText('目录已更新', { exact: true }).waitFor();
  assert.equal(await reader.getByRole('button', { name: '保存新草稿', exact: true }).count(), 0);
  assert.equal(await reader.getByRole('button', { name: '发布', exact: true }).count(), 0);
  assert.equal(await reader.getByRole('button', { name: '下线', exact: true }).count(), 0);
  await reader.screenshot({ path: output + '/tool-publication-reader.png', fullPage: false });
  checks.readerHasNoManagementActions = true;
  assert.deepEqual(errors, []);
  const summary = { status: 'PASSED', checks, javascriptErrors: errors, temporaryToolLogicalId: created.logicalId, temporaryToolState: 'OFFLINE' };
  writeFileSync(output + '/browser-acceptance.json', JSON.stringify(summary, null, 2));
  process.stdout.write(JSON.stringify(summary) + '\n');
} finally {
  if (created && contexts[0]) {
    const response = await contexts[0].request.get(base + '/api/v1/tool-publications', { headers });
    if (response.ok()) {
      const records = (await response.json()).data || [];
      for (const row of records.filter(row => row.logicalId === created.logicalId && row.state === 'RUNNING')) {
        await contexts[0].request.post(base + '/api/v1/tool-publications/' + row.id + '/offline', { headers, data: {} });
      }
    }
  }
  for (const context of contexts) await context.close();
  await browser.close();
}
