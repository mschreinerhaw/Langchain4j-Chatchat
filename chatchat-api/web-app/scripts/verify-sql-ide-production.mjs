import { chromium, request } from 'playwright-core';
import { mkdir, access } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = process.env.SQL_STUDIO_DEPLOY_BASE;
const username = process.env.SQL_STUDIO_DEPLOY_USER;
const password = process.env.SQL_STUDIO_DEPLOY_PASSWORD;
assert.ok(base && username && password, 'Base URL and credentials must be supplied through environment variables');
let executablePath;
for (const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try { await access(candidate); executablePath = candidate; break; } catch {}
}
assert.ok(executablePath, 'Local Chromium required');
const client = await request.newContext();
let browser;
try {
  const login = await client.post(`${base}/api/v1/enterprise/auth/login`, { data: { username, password } });
  assert.equal(login.status(), 200, 'Admin login HTTP status');
  const payload = await login.json();
  assert.equal(payload.code, 200, 'Admin login result');
  const session = payload.data;
  assert.ok(session?.token && session?.user, 'Admin session must include token and user');
  const projects = await client.get(`${base}/api/v1/data-science/sql/projects`, { headers: { Authorization: `Bearer ${session.token}` } });
  assert.equal(projects.status(), 200, 'Authorized SQL project list');
  assert.equal((await projects.json()).code, 200);
  const headers = { Authorization: `Bearer ${session.token}` };
  const library = await client.get(`${base}/api/v1/data-science/sql/library-files?kind=MAPPING`, { headers });
  assert.equal(library.status(), 200, 'Authorized document-library choices');
  const libraryChoices = (await library.json()).data;
  assert.ok(Array.isArray(libraryChoices));
  const testName = `__sql_deploy_smoke_${Date.now()}`;
  const created = await client.post(`${base}/api/v1/data-science/sql/projects`, { headers, data: { name: testName, dialect: 'spark' } });
  assert.equal(created.status(), 200, 'Create temporary SQL project');
  const testProjectId = (await created.json()).data.id;
  try {
    const saved = await client.put(`${base}/api/v1/data-science/sql/projects/${testProjectId}/files`, {
      headers, data: { path: 'sql/smoke.sql', kind: 'SQL', content: 'SELECT 1;', expectedVersion: 0 }
    });
    assert.equal(saved.status(), 200, 'Create versioned SQL file');
    const testFileId = (await saved.json()).data.id;
    const stale = await client.delete(`${base}/api/v1/data-science/sql/projects/${testProjectId}/files/${testFileId}?expectedVersion=2`, { headers });
    assert.equal(stale.status(), 409, 'Reject stale file deletion');
    const deleted = await client.delete(`${base}/api/v1/data-science/sql/projects/${testProjectId}/files/${testFileId}?expectedVersion=1`, { headers });
    assert.equal(deleted.status(), 200, 'Delete own test file');
    const mapping = libraryChoices.find(item => /\.(md|txt|csv)$/i.test(item.fileName || ''));
    if (mapping) {
      const imported = await client.post(`${base}/api/v1/data-science/sql/projects/${testProjectId}/library-files`, {
        headers, data: { kind: 'MAPPING', documentIds: [mapping.docId] }
      });
      assert.equal(imported.status(), 200, `Import authorized Mapping: ${await imported.text()}`);
      assert.equal((await imported.json()).data.length, 1);
    }
  } finally {
    const deletedProject = await client.delete(`${base}/api/v1/data-science/sql/projects/${testProjectId}`, { headers });
    assert.equal(deletedProject.status(), 200, 'Delete own temporary project');
  }
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify(value)), session);
  await page.goto(`${base}/#/w/data-science/sqlStudio`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.locator('.sql-ide-shell').waitFor({ timeout: 30000 });
  assert.equal(await page.locator('.sql-activity-bar').count(), 1);
  assert.equal(await page.locator('.sql-ide-titlebar .sql-activity-bar').count(), 1);
  assert.equal(await page.locator('.sql-ide-main').count(), 1);
  assert.equal(await page.locator('.sql-assistant-panel').count(), 1);
  await page.getByRole('button', { name: '切换底部面板' }).click();
  await page.locator('.sql-bottom-panel').waitFor();
  await mkdir('../../target/sql-studio-ide-deploy-audit', { recursive: true });
  await page.screenshot({ path: '../../target/sql-studio-ide-deploy-audit/sql-studio-real.png', fullPage: true });
  await page.goto(`${base}/#/w/data-science/dataScienceDevelop`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.locator('.ds-develop-pane').waitFor({ timeout: 30000 });
  await page.goto(`${base}/#/w/zhice/chat`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.locator('.chat-view').waitFor({ timeout: 30000 });
  assert.deepEqual(errors, [], `Browser errors: ${errors.join(' | ')}`);
  console.log('PASS: real admin login, authorized document library, SQL project and versioned file create/delete, deployed IDE panels, Python Studio route, Zhice route, no browser page errors.');
} finally {
  await browser?.close();
  await client.dispose();
}
