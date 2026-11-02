import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const origin = 'http://192.168.195.221:18101';
const output = 'target/builtin-financial-query-fix';
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const login = await context.request.post(origin + '/api/v1/admin/auth/login', { data: { username: 'admin', password: process.env.E2E_MCP_PASSWORD } });
  const auth = (await login.json()).data;
  assert.ok(auth?.token);
  const headers = { Authorization: 'Bearer ' + auth.token };
  async function api(route, data) {
    const response = data === undefined
      ? await context.request.get(origin + '/api/v1' + route, { headers })
      : await context.request.post(origin + '/api/v1' + route, { headers, data });
    const result = await response.json();
    assert.equal(result.code, 200, route + ': ' + result.message);
    return result.data;
  }
  const row = (await api('/database-query')).find(item => item.toolName === 'sample_bond_yield_curve_latest');
  assert.ok(row);
  const payload = { sql: row.sqlTemplate, sqlSteps: [], inputSchema: row.inputSchema || {}, params: {}, maxRows: row.maxRows, timeoutSeconds: row.timeoutSeconds, datasourceId: row.datasourceId };
  const draft = await api('/database-query/test', payload);
  assert.equal(draft.success, true, draft.errorMessage);
  assert.equal(draft.metadata.internalFinancialDatasource, true);
  assert.equal(draft.data.governedFinancialTablesOnly, true);
  assert.ok(draft.data.rowCount > 0);
  const limited = await api('/database-query/test', { ...payload, maxRows: 3 });
  assert.equal(limited.success, true, limited.errorMessage);
  assert.ok(limited.data.rowCount > 0 && limited.data.rowCount <= 3);
  const first = draft.data.rows[0];
  const curve = first[Object.keys(first).find(key => key.toLowerCase() === 'curve_name')];
  assert.ok(curve);
  const parameterized = await api('/database-query/test', {
    ...payload, sql: 'SELECT curve_name, maturity_years, yield_pct FROM bond_yield_curve_daily WHERE curve_name = :curve_name ORDER BY maturity_years',
    params: { curve_name: curve }, maxRows: 3,
  });
  assert.equal(parameterized.success, true, parameterized.errorMessage);
  assert.ok(parameterized.data.rowCount > 0);
  assert.ok(parameterized.data.rows.every(record => record[Object.keys(record).find(key => key.toLowerCase() === 'curve_name')] === curve));
  const forbidden = await api('/database-query/test', { ...payload, sql: 'SELECT COUNT(*) AS row_count FROM information_schema.tables' });
  assert.equal(forbidden.success, false, 'Internal financial queries must retain their table boundary');
  const saved = await api('/database-query/' + row.id + '/test', {});
  assert.equal(saved.success, true, saved.errorMessage);
  const external = (await api('/database-query/studio-publications')).find(item => item.title === '仓库面积明细查询' && item.enabled);
  assert.ok(external);
  const normal = await api('/database-query/test', { ...payload, datasourceId: external.datasourceId, sql: 'SELECT 1 AS probe', maxRows: 1 });
  assert.equal(normal.success, true, normal.errorMessage);
  await context.addInitScript(a => {
    sessionStorage.setItem('chatchat.mcp.admin.token', a.token);
    sessionStorage.setItem('chatchat.mcp.admin.user', a.username);
    sessionStorage.setItem('chatchat.mcp.admin.expiresAt', String(a.expiresAt));
    sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt', String(Date.now()));
  }, auth);
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/admin/index.html');
  await page.getByText('查询能力模板', { exact: true }).click();
  await page.getByRole('menuitem', { name: '关系库查询', exact: true }).click();
  await page.getByPlaceholder('搜索模板名称、描述或数据源').fill(row.title);
  await page.locator('.catalog-table .el-table__row').filter({ hasText: row.title }).first().getByRole('button', { name: '编辑', exact: true }).click();
  const form = page.getByRole('dialog').filter({ has: page.locator('.entity-form') });
  await form.locator('.database-sql-editor .monaco-editor').waitFor();
  const pending = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/database-query/test');
  pending.catch(() => {});
  await form.getByRole('button', { name: '执行查询', exact: true }).click();
  const response = await pending;
  const result = await response.json();
  assert.equal(result.code, 200, result.message);
  assert.equal(result.data.success, true, result.data.errorMessage);
  await form.locator('.database-preview-result').waitFor();
  assert.ok((await form.locator('.database-preview-summary').first().innerText()).includes('成功'));
  await form.locator('.database-preview-summary').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: output + '/builtin-query-success.png' });
  assert.deepEqual(errors, []);
  const report = { datasourceId: row.datasourceId, draftSuccess: true, draftRows: draft.data.rowCount, savedSuccess: true,
    limitVerified: limited.data.rowCount, parametersVerified: true, financialTableBoundaryRetained: true,
    externalDatasourceUnaffected: true, editorExecutionSuccess: true, browserErrors: errors };
  fs.writeFileSync(output + '/live-verification.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally { await browser.close(); }
