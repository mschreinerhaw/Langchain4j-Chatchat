import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { readFile, mkdir, access } from 'node:fs/promises';
import assert from 'node:assert/strict';

// Real Vue/Monaco in Chromium; HTTP fixtures only, no model or production access.
const manifests = {};
for (const id of ['zhice', 'data-science']) manifests[id] = JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`, 'utf8'));
let executablePath;
for (const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try { await access(candidate); executablePath = candidate; break; } catch {}
}
assert.ok(executablePath, 'Local Chromium required');
const server = await createServer({ server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1560, height: 980 } });
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.stack); });
  page.on('dialog', dialog => dialog.accept());
  const user = { id: 'sql-browser-user', username: 'admin', tenantId: 'sql-browser-tenant', permissionCodes: [] };
  await page.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify({ token: 'fixture-token', user: value })), user);
  let projects = [{ id: 'project', name: 'Spark customer ETL', dialect: 'spark', sessionId: 'session', tenantId: user.tenantId, ownerId: user.id }];
  const filesByProject = { project: [{ id: 'a', projectId: 'project', path: 'sql/a.sql', kind: 'SQL', content: 'SELECT 1;', version: 1, editable: true }] };
  const library = [{ docId: 'mapping-1', fileName: 'customer.csv', title: 'Customer Mapping', version: 1, latestVersion: true }];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    let data = [];
    if (path.endsWith('/auth/me')) data = user;
    else if (path.endsWith('/workspaces') || path.endsWith('/workspaces/catalog')) data = Object.values(manifests).map(m => ({ ...m, defaultWorkspace: m.workspaceId === 'zhice' }));
    else if (path.endsWith('/workspaces/preferences')) data = { lastWorkspaceId: 'data-science' };
    else if (path.endsWith('/manifest')) { const manifest = manifests[path.split('/').at(-2)]; data = { manifest, menus: [{ id: 'work', title: 'Workspace', type: 'directory', children: manifest.pages.map(item => ({ id: item.menuCode, title: item.id, type: 'menu', children: [] })) }] }; }
    else if (path === '/api/v1/data-science/sql/projects') {
      if (method === 'POST') { const body = request.postDataJSON(); data = { id: 'new-project', name: body.name, dialect: body.dialect, sessionId: 'new-session', tenantId: user.tenantId, ownerId: user.id }; projects.unshift(data); filesByProject[data.id] = []; }
      else data = projects;
    }
    else if (path.endsWith('/sql/reference-files')) data = Object.values(filesByProject).flat().filter(file => ['SQL', 'REFERENCE'].includes(file.kind));
    else if (path.endsWith('/sql/library-files')) data = library.filter(item => url.searchParams.get('kind') === 'MAPPING' || item.fileName.endsWith('.sql'));
    else if (/\/sql\/projects\/[^/]+$/.test(path) && method === 'DELETE') { const id = path.split('/').at(-1); projects = projects.filter(item => item.id !== id); delete filesByProject[id]; data = true; }
    else if (/\/sql\/projects\/[^/]+\/files$/.test(path)) {
      const id = path.split('/').at(-2), files = filesByProject[id];
      if (method === 'PUT') {
        const body = request.postDataJSON(); const current = files.find(file => file.path === body.path);
        if (current) { assert.equal(body.expectedVersion, current.version); Object.assign(current, { content: body.content, version: current.version + 1 }); data = current; }
        else { assert.equal(body.expectedVersion, 0); data = { id: `file-${files.length}`, projectId: id, path: body.path, kind: body.kind, content: body.content, version: 1, editable: true }; files.push(data); }
      } else data = files;
    }
    else if (/\/sql\/projects\/[^/]+\/library-files$/.test(path) && method === 'POST') {
      const id = path.split('/').at(-2), { kind, documentIds } = request.postDataJSON();
      assert.deepEqual(documentIds, ['mapping-1']);
      data = [{ id: 'mapped', projectId: id, path: 'mapping/customer.csv', kind, content: 'source,target\na,b', version: 1, editable: true }];
      filesByProject[id].push(...data);
    }
    else if (/\/sql\/projects\/[^/]+\/files\/[^/]+$/.test(path) && method === 'DELETE') {
      const id = path.split('/').at(-3), fileId = path.split('/').at(-1), files = filesByProject[id];
      const file = files.find(item => item.id === fileId); assert.equal(Number(url.searchParams.get('expectedVersion')), file.version);
      filesByProject[id] = files.filter(item => item.id !== fileId); data = true;
    }
    else if (path.endsWith('/project/generations') || path.endsWith('/new-project/generations')) data = [];
    await route.fulfill({ json: { code: 200, message: 'Success', data } });
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/w/data-science/sqlStudio`, { waitUntil: 'networkidle' });
  await page.locator('.sql-ide-shell').waitFor();
  assert.equal(await page.locator('.sql-ide-titlebar .sql-activity-bar').count(), 1, 'panel buttons belong in top bar');
  await page.locator('.sql-tree-scroll').click({ button: 'right', position: { x: 180, y: 120 } });
  await page.getByRole('menuitem', { name: '新建 SQL 文件…' }).click();
  await page.getByLabel('新文件路径').fill('sql/new.sql');
  await page.getByRole('button', { name: '创建并保存' }).click();
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').waitFor();
  await page.getByRole('button', { name: '从文档库选 Mapping' }).click();
  await page.locator('.sql-library-list input').check();
  await page.getByRole('button', { name: '关联所选文件' }).click();
  await page.locator('.sql-tree-file button[title="mapping/customer.csv"]').waitFor();
  await page.locator('.sql-chat-settings summary').click();
  await page.getByRole('button', { name: '模型', exact: true }).click();
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent / Skills' }).count(), 0);
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: '模型' }).count(), 1);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent / Skills' }).count(), 1);
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').click({ button: 'right' });
  await page.getByRole('menuitem', { name: '删除文件…' }).click();
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').waitFor({ state: 'detached' });
  await page.getByRole('button', { name: /新建工程/ }).first().click();
  await page.locator('.sql-dialog input').fill('Delete me');
  await page.getByRole('button', { name: '创建工程' }).click();
  await page.locator('.sql-studio-bar select[aria-label="SQL 工程"]').selectOption('new-project');
  await page.getByRole('button', { name: '删除工程' }).click();
  await page.waitForFunction(() => !document.querySelector('.sql-studio-bar select[aria-label="SQL 工程"] option[value="new-project"]'));
  assert.equal(projects.length, 1);
  await mkdir('../../target/sql-studio-ide-audit', { recursive: true });
  await page.screenshot({ path: '../../target/sql-studio-ide-audit/sql-studio-ide.png', fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS: top panel buttons, context-menu file creation/deletion, authorized library selection, Agent/model exclusivity, project deletion (HTTP fixtures only).');
} finally { await browser?.close(); await server.close(); }
