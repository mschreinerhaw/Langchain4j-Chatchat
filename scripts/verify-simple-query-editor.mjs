import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const origin = process.env.E2E_MCP_ORIGIN || 'http://127.0.0.1:5189';
const output = process.env.E2E_OUTPUT || 'target/simple-query-editor-check';
fs.mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'],
});
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const login = await context.request.post(origin + '/api/v1/admin/auth/login', {
    data: { username: 'admin', password: process.env.E2E_ADMIN_PASSWORD },
  });
  const session = (await login.json()).data;
  assert.ok(session?.token, 'Login succeeds');
  await context.addInitScript(a => {
    for (const key of ['token', 'username', 'expiresAt']) {
      sessionStorage.setItem('chatchat.mcp.admin.' + (key === 'username' ? 'user' : key), String(a[key]));
    }
    sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt', String(Date.now()));
  }, session);
  const headers = { Authorization: 'Bearer ' + session.token };
  const get = async route => {
    const response = await context.request.get(origin + '/api/v1' + route, { headers });
    const result = await response.json();
    assert.equal(result.code, 200, result.message);
    return result.data;
  };
  const studio = (await get('/database-query/studio-publications')).find(row => row.title === '仓库面积明细查询' && row.enabled);
  assert.ok(studio, 'Existing warehouse acceptance query');
  const original = await get('/database-query/studio-publications/' + studio.templateId);
  const manual = (await get('/database-query')).find(row => row.enabled && row.title && row.sqlTemplate);
  assert.ok(manual);
  const errors = [];
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', message => {
    if (message.type() === 'error' && /worker|monaco|module script/i.test(message.text())) errors.push(message.text());
  });
  page.on('requestfailed', request => {
    if (/\.(?:js|css)(?:\?|$)/.test(request.url())) errors.push('Editor resource failed: ' + request.url());
  });
  await page.goto(origin + '/admin/index.html');
  await page.getByText('查询能力模板', { exact: true }).click();
  await page.getByRole('menuitem', { name: '关系库查询', exact: true }).click();
  const search = page.getByPlaceholder('搜索模板名称、描述或数据源');
  await search.waitFor();
  const form = page.getByRole('dialog').filter({ has: page.locator('.entity-form') });
  async function open(row) {
    await search.fill(row.title);
    await page.locator('.catalog-table .el-table__row').filter({ hasText: row.title }).first().getByRole('button', { name: '编辑', exact: true }).click();
    await form.locator('.database-sql-editor .monaco-editor').waitFor();
    assert.equal(await form.locator('.database-flow-workspace').count(), 0);
    assert.equal(await form.getByRole('button', { name: '测试调用', exact: true }).count(), 0);
    const sections = await form.locator('.form-section-header strong').allTextContents();
    assert.equal(sections[0], '基础信息');
    assert.equal(sections.at(-1), 'SQL 查询');
    if (row === manual) assert.equal(sections[1], '运行策略');
  }
  await open(manual);
  const manualSql = form.getByRole('textbox', { name: 'SQL 语句', exact: true });
  assert.equal(await manualSql.getAttribute('readonly'), null);
  await manualSql.focus();
  await page.keyboard.press('Control+A');
  await page.keyboard.insertText('SELECT :probe AS result');
  await page.waitForTimeout(250);
  const tokenColors = await form.locator('.view-lines .view-line span[class^="mtk"]').evaluateAll(nodes => [...new Set(nodes.map(node => getComputedStyle(node).color))]);
  assert.ok(tokenColors.length > 1, 'SQL keywords and identifiers have different token colors');
  await page.keyboard.press('End');
  await page.keyboard.insertText('\nSEL');
  await page.keyboard.press('Control+Space');
  await form.locator('.suggest-widget.visible').waitFor();
  assert.ok((await form.locator('.suggest-widget.visible').innerText()).includes('SELECT'), 'SQL keyword completion');
  await page.keyboard.press('Escape');
  await page.keyboard.press('Control+A');
  await page.keyboard.insertText('select :probe as result');
  await page.keyboard.press('Shift+Alt+F');
  await page.waitForTimeout(250);
  assert.ok((await form.locator('.view-lines').innerText()).includes('SELECT'), 'SQL formatting uppercases keywords');
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  const popover = page.locator('.el-popover:visible').filter({ has: page.locator('.database-simple-params') });
  await popover.getByRole('textbox', { name: 'probe 测试值', exact: true }).fill('7');
  await page.screenshot({ path: path.join(output, 'manual-query-editor.png') });
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  await form.getByRole('button', { name: '取消', exact: true }).click();
  await open(studio);
  const sql = form.getByRole('textbox', { name: 'SQL 语句', exact: true });
  const sqlBefore = await form.locator('.view-lines').innerText();
  await sql.focus();
  await page.keyboard.press('Control+A');
  await page.keyboard.insertText('SELECT 999');
  assert.equal(await form.locator('.view-lines').innerText(), sqlBefore, 'Studio SQL stays read-only');
  await page.keyboard.press('ArrowRight');
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  await popover.getByRole('textbox', { name: 'min_area 测试值', exact: true }).fill('294242');
  assert.ok(await popover.getByRole('combobox', { name: '参数类型', exact: true }).isDisabled());
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(output, 'studio-query-parameters.png') });
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  const pending = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/studio-publications/' + studio.templateId + '/test'));
  await form.getByRole('button', { name: '执行查询', exact: true }).click();
  const response = await pending;
  const result = await response.json();
  assert.equal(result.code, 200, result.message);
  assert.equal(result.data.testEvidence.success, true);
  assert.equal(result.data.testEvidence.parameters.min_area, 294242);
  assert.equal(result.data.testEvidence.rowCount, 3);
  await form.locator('.database-preview-result').waitFor();
  await page.screenshot({ path: path.join(output, 'studio-query-result.png') });
  if (process.env.E2E_VERIFY_SAVE === '1') {
    const saved = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/studio-publications/' + studio.templateId + '/metadata'));
    await form.getByRole('button', { name: '保存', exact: true }).click();
    const savedResult = await (await saved).json();
    assert.equal(savedResult.code, 200, savedResult.message);
    await form.waitFor({ state: 'hidden' });
    const after = await get('/database-query/studio-publications/' + studio.templateId);
    assert.equal(after.sqlTemplate, original.sqlTemplate);
    assert.deepEqual(after.parameterSchema, original.parameterSchema);
    assert.equal(after.title, original.title);
  }
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ origin, manualEditor: true, studioEditor: true, settingsAboveSql: true, syntaxHighlighting: true, keywordCompletion: true, sqlFormatting: true, studioSchemaPreserved: true, parameter: 294242, returnedRows: 3, saveVerified: process.env.E2E_VERIFY_SAVE === '1', browserErrors: errors }, null, 2));
  console.log('PASS: settings above SQL, Monaco highlighting/completion/formatting, manual/Studio editors, real SQL execution (3 rows), browser error checks' + (process.env.E2E_VERIFY_SAVE === '1' ? ', metadata save preserves source SQL/schema' : ''));
} finally { await browser.close(); }
