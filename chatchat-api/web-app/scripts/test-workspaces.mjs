import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { access, readFile, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';

// Real application navigation; HTTP fixtures, no production or model calls.
const manifests = {};
for (const id of ['zhice', 'data-science']) {
  manifests[id] = JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`, 'utf8'));
}
let executablePath;
for (const path of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try { await access(path); executablePath = path; break; } catch {}
}
assert.ok(executablePath, 'Local Chromium required');
const server = await createServer({ server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 960 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const user = { id: 'workspace-user', username: 'admin', tenantId: 'workspace-tenant', permissionCodes: [] };
  await page.addInitScript(user => {
    if (location.protocol === 'http:') localStorage.setItem('chatchat.auth.session', JSON.stringify({ token: 'fixture-token', user }));
  }, user);
  let preference = { defaultWorkspaceId: 'zhice', lastWorkspaceId: 'data-science' };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let data = [];
    if (path.endsWith('/auth/me')) data = user;
    else if (path.endsWith('/workspaces')) data = Object.values(manifests).map(m => ({ ...m, defaultWorkspace: m.workspaceId === 'zhice' }));
    else if (path.endsWith('/workspaces/preferences')) {
      if (route.request().method() === 'PUT') preference = { ...preference, ...route.request().postDataJSON() };
      data = preference;
    } else if (path.endsWith('/manifest')) {
      const manifest = manifests[path.split('/').at(-2)];
      const menu = (id, title) => ({ id, title, type: 'menu', children: [] });
      data = { manifest, menus: manifest.workspaceId === 'zhice'
        ? [{ id: 'workspace', title: '工作台', children: [menu('chat', '智能对话')] },
           { id: 'platform', title: '平台管理', children: [menu('workspaces', '工作空间')] }]
        : [{ id: 'capability', title: '数据科学', children: [menu('dataScienceEnvironment', 'Python 环境')] }] };
    }
    await route.fulfill({ json: { code: 200, message: 'OK', data } });
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  await page.goto(base);
  await page.waitForURL('**/#/w/zhice/chat');
  assert.equal(await page.getByRole('heading', { name: '工作空间', exact: true }).count(), 0);
  await page.getByRole('button', { name: '平台管理', exact: true }).click();
  await page.getByRole('button', { name: '工作空间', exact: true }).click();
  await page.waitForURL('**/#/w/zhice/workspaces');
  await page.getByRole('heading', { name: '工作空间', exact: true }).waitFor();
  const science = page.locator('.workspace-card').filter({ has: page.getByRole('heading', { name: '数据科学', exact: true }) });
  await science.getByRole('button', { name: '设为我的默认空间' }).click();
  await science.getByText('默认登录空间', { exact: true }).waitFor();
  assert.equal(preference.defaultWorkspaceId, 'data-science');
  assert.ok(page.url().endsWith('/w/zhice/workspaces'));
  await mkdir('../../target/workspace-management-audit', { recursive: true });
  // A new login route honors the saved default despite a different last workspace.
  await page.goto('about:blank');
  await page.goto(`${base}/#/login`);
  await page.waitForURL('**/#/w/data-science/dataScienceEnvironment');
  await page.goto(`${base}/#/workspaces`);
  await page.waitForURL('**/#/w/zhice/workspaces');
  const zhice = page.locator('.workspace-card').filter({ has: page.getByRole('heading', { name: '灵动智策', exact: true }) });
  await zhice.getByRole('button', { name: '设为我的默认空间' }).click();
  await zhice.getByText('默认登录空间', { exact: true }).waitFor();
  assert.equal(preference.defaultWorkspaceId, 'zhice');
  await page.screenshot({ path: '../../target/workspace-management-audit/workspaces.png', fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS: default login, platform menu, saved preference, new login and legacy link.');
} finally {
  await browser?.close();
  await server.close();
}
