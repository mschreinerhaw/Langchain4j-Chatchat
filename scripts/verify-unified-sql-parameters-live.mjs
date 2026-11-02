import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const origin = 'http://192.168.195.221:18101', out = 'target/unified-sql-parameters';
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
let fixture, context, headers;
try {
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const auth = (await (await context.request.post(origin + '/api/v1/admin/auth/login', { data: { username: 'admin', password: process.env.E2E_MCP_PASSWORD } })).json()).data;
  assert.ok(auth?.token);
  headers = { Authorization: 'Bearer ' + auth.token };
  async function api(route, data) {
    const r = data === undefined ? await context.request.get(origin + '/api/v1' + route, { headers }) : await context.request.post(origin + '/api/v1' + route, { headers, data });
    const j = await r.json();
    assert.equal(j.code, 200, route + ': ' + j.message);
    return j.data;
  }
  const studio = (await api('/database-query/studio-publications')).find(r => r.title === '仓库面积明细查询' && r.enabled);
  assert.ok(studio);
  const manualRows = await api('/database-query');
  const legacyRow = manualRows.find(r => (r.sqlSteps || []).some(step => /:[A-Za-z_]\w*/.test(step.sqlContent || '')));
  const base = { datasourceId: studio.datasourceId, sqlSteps: [], inputSchema: {}, maxRows: 3, timeoutSeconds: 10 };
  async function draft(sql, params) {
    const value = await api('/database-query/test', { ...base, sql, params });
    assert.equal(value.success, true, value.errorMessage);
    return value.data.rows;
  }
  const get = (record, key) => record[Object.keys(record).find(name => name.toLowerCase() === key)];
  const label = "literal' OR '1'='1";
  const canonical = await draft('SELECT {{probe}} AS probe_value, {{label}} AS label_value, {{nil}} AS null_value', { probe: 0, label, nil: null });
  assert.equal(get(canonical[0], 'probe_value'), 0);
  assert.equal(get(canonical[0], 'label_value'), label);
  assert.equal(get(canonical[0], 'null_value'), null);
  assert.equal(get((await draft('SELECT :probe AS probe_value', { probe: 0 }))[0], 'probe_value'), 0);
  assert.equal(get((await draft('SELECT 1 AS probe_value WHERE 1 IN ({{ids}})', { ids: [1, 2] }))[0], 'probe_value'), 1);
  assert.equal(get((await draft('SELECT {{today}} AS provided_date', { today: '20260102' }))[0], 'provided_date'), '20260102');
  const builtin = manualRows.find(r => r.toolName === 'sample_bond_yield_curve_latest');
  const builtinSaved = await api('/database-query/' + builtin.id + '/test', {});
  assert.equal(builtinSaved.success, true);
  const curve = get(builtinSaved.data.rows[0], 'curve_name');
  const financial = await api('/database-query/test', { ...base, datasourceId: builtin.datasourceId,
    sql: 'SELECT curve_name FROM bond_yield_curve_daily WHERE curve_name = {{curve_name}}', params: { curve_name: curve } });
  assert.equal(financial.success, true, financial.errorMessage);
  assert.ok(financial.data.rowCount > 0);
  const categories = await api('/business-categories');
  const title = '参数写法临时验收 ' + Date.now();
  fixture = await api('/database-query', {
    toolName: 'verify_parameter_syntax_' + Date.now(), title, datasourceId: studio.datasourceId,
    categoryId: categories.find(r => r.code === 'data_validation').id, domain: 'finance',
    description: '临时只读参数绑定验收，结束后删除。', businessScope: '仅验证参数格式与类型。', implementationSteps: '绑定参数并执行只读 SELECT。',
    sqlTemplate: 'SELECT {{probe}} AS probe_value, {{label}} AS label_value', sqlSteps: [],
    inputSchema: { type: 'object', properties: { probe: { type: 'integer' }, label: { type: 'string' } }, required: ['probe', 'label'] },
    maxRows: 3, timeoutSeconds: 10, enabled: false,
  });
  assert.ok(fixture?.id);
  assert.equal(fixture.sqlTemplate, 'SELECT {{probe}} AS probe_value, {{label}} AS label_value');
  const saved = await api('/database-query/' + fixture.id + '/test', { probe: 0, label });
  assert.equal(saved.success, true, saved.errorMessage);
  await context.addInitScript(a => {
    sessionStorage.setItem('chatchat.mcp.admin.token', a.token);
    sessionStorage.setItem('chatchat.mcp.admin.user', a.username);
    sessionStorage.setItem('chatchat.mcp.admin.expiresAt', String(a.expiresAt));
    sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt', String(Date.now()));
  }, auth);
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(origin + '/admin/index.html');
  await page.getByText('查询能力模板', { exact: true }).click();
  await page.getByRole('menuitem', { name: '关系库查询', exact: true }).click();
  const search = page.getByPlaceholder('搜索模板名称、描述或数据源');
  const form = page.getByRole('dialog').filter({ has: page.locator('.entity-form') });
  async function open(row) {
    await search.fill(row.title);
    await page.locator('.catalog-table .el-table__row').filter({ hasText: row.title }).first().getByRole('button', { name: '编辑', exact: true }).click();
    await form.locator('.database-sql-editor .monaco-editor').waitFor();
  }
  if (legacyRow) {
    await open(legacyRow);
    assert.ok((await form.locator('.view-lines').innerText()).includes('{{'));
    await page.screenshot({ path: out + '/legacy-manual-canonical.png' });
    await form.getByRole('button', { name: '取消', exact: true }).click();
  }
  await open(fixture);
  assert.ok((await form.locator('.view-lines').innerText()).includes('{{probe}}'));
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  const popover = page.locator('.el-popover:visible').filter({ has: page.locator('.database-simple-params') });
  await popover.getByRole('textbox', { name: 'probe 测试值', exact: true }).fill('0');
  await popover.getByRole('textbox', { name: 'label 测试值', exact: true }).fill(label);
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  let pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/database-query/test');
  pending.catch(() => {});
  await form.getByRole('button', { name: '执行查询', exact: true }).click();
  let response = await pending, value = await response.json();
  assert.ok(response.request().postDataJSON().sql.includes('{{probe}}'));
  assert.equal(value.data.success, true, value.data.errorMessage);
  await form.locator('.database-preview-result').waitFor();
  await form.locator('.database-preview-summary').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: out + '/manual-result.png' });
  await form.getByRole('button', { name: '取消', exact: true }).click();
  await open(studio);
  assert.ok((await form.locator('.view-lines').innerText()).includes('{{min_area}}'));
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  await popover.getByRole('textbox', { name: 'min_area 测试值', exact: true }).fill('0');
  await form.getByRole('button', { name: /SQL 参数/ }).click();
  pending = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/studio-publications/' + studio.templateId + '/test'));
  pending.catch(() => {});
  await form.getByRole('button', { name: '执行查询', exact: true }).click();
  value = await (await pending).json();
  assert.equal(value.data.testEvidence.success, true, value.data.testEvidence.diagnostic);
  await form.locator('.database-preview-result').waitFor();
  await form.locator('.database-preview-summary').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: out + '/studio-result.png' });
  assert.deepEqual(errors, []);
  const report = { standard: '{{name}}', legacyNamedParametersSupported: true, legacyManualDisplayConverted: Boolean(legacyRow),
    nullZeroArraysAndLiteralTextVerified: true, suppliedDatePreserved: true, builtinQuerySuccess: true,
    manualCanonicalSqlSavedAndExecuted: true, manualEditorSuccess: true, studioEditorSuccess: true, browserErrors: errors };
  fs.writeFileSync(out + '/live-verification.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  if (fixture?.id) {
    const response = await context.request.delete(origin + '/api/v1/database-query/' + encodeURIComponent(fixture.id), { headers });
    assert.equal((await response.json()).code, 200, 'Remove only the temporary verification template');
  }
  await browser.close();
}
