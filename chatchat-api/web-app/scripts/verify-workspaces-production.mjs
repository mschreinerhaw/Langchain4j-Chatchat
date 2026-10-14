import { chromium, request } from 'playwright-core';
import { access, mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = process.env.WORKSPACE_DEPLOY_BASE;
const password = process.env.WORKSPACE_DEPLOY_PASSWORD;
const mcp = process.env.WORKSPACE_MCP_BASE;
const mcpPassword = process.env.WORKSPACE_MCP_PASSWORD;
assert.ok(base && password && mcp && mcpPassword, 'Supply deployment URLs and passwords through the environment');
const output = process.env.WORKSPACE_AUDIT_DIR || '../../target/workspace-management-deploy';
await mkdir(output, { recursive: true });
const http = await request.newContext();
const results = {};
let browser, headers;
async function api(path, options = {}) {
  const response = await http.fetch(`${base}${path}`, { headers, ...options });
  assert.equal(response.status(), 200, `${options.method || 'GET'} ${path}`);
  const body = await response.json();
  assert.equal(body.code, 200, `${path}: ${body.message}`);
  return body.data;
}
try {
  const session = await api('/api/v1/enterprise/auth/login', { method: 'POST', data: { username: 'admin', password } });
  headers = { Authorization: `Bearer ${session.token}` };
  results.health = await api('/api/v1/health');
  const available = await api('/api/v1/workspaces');
  assert.equal(available.find(w => w.workspaceId === 'zhice').defaultWorkspace, true);
  const loaded = await api('/api/v1/workspaces/zhice/manifest');
  assert.ok(loaded.manifest.pages.some(p => p.id === 'workspaces'));
  assert.ok(loaded.menus.find(m => m.id === 'platform').children.some(m => m.id === 'workspaces' && m.title === '工作空间'));
  results.menu = 'platform/workspaces';
  const unauthenticated = await http.get(`${base}/api/v1/workspaces/preferences`);
  assert.equal(unauthenticated.status(), 401);
  const mcpLogin = await http.post(`${mcp}/api/v1/admin/auth/login`, { data: { username: 'admin', password: mcpPassword } });
  assert.equal(mcpLogin.status(), 200);
  const mcpSession = (await mcpLogin.json()).data;
  const mcpMe = await http.get(`${mcp}/api/v1/admin/auth/me`, { headers: { Authorization: `Bearer ${mcpSession.token}` } });
  assert.equal((await mcpMe.json()).data.authenticated, true);
  results.mcpLogin = 'PASS';
  let executablePath;
  for (const path of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
    try { await access(path); executablePath = path; break; } catch {}
  }
  assert.ok(executablePath, 'Local Chromium required');
  browser = await chromium.launch({ executablePath, headless: true, args: ['--no-proxy-server'] });
  const errors = [];
  async function loginPage(workspace) {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
    // Capture the characters actually drawn by the client-side captcha for this UI smoke test.
    await context.addInitScript(() => {
      const draw = CanvasRenderingContext2D.prototype.fillText;
      CanvasRenderingContext2D.prototype.fillText = function (text, ...args) {
        if (this.canvas.width === 216 && this.canvas.height === 84 && String(text).length === 1) {
          this.canvas.dataset.renderedCaptcha = ((this.canvas.dataset.renderedCaptcha || '') + text).slice(-4);
        }
        return draw.call(this, text, ...args);
      };
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder('请输入企业账号').fill('admin');
    await page.getByPlaceholder('请输入登录密码').fill(password);
    const code = await page.locator('.captcha-code canvas').getAttribute('data-rendered-captcha');
    assert.equal(code?.length, 4);
    await page.getByPlaceholder('输入验证码').fill(code);
    await page.locator('.login-submit').click();
    await page.waitForURL(new RegExp(`/#/w/${workspace}/`), { timeout: 45000 });
    assert.equal(await page.locator('.workspace-selection').count(), 0);
    return { page, context };
  }
  // The requested platform and administrator defaults remain zhice after all tests.
  await api('/api/v1/workspaces/preferences', { method: 'PUT', data: { defaultWorkspaceId: 'zhice' } });
  const first = await loginPage('zhice');
  const group = first.page.getByRole('button', { name: '平台管理', exact: true });
  if (await group.getAttribute('aria-expanded') === 'false') await group.click();
  await first.page.getByRole('button', { name: '工作空间', exact: true }).click();
  await first.page.waitForURL('**/#/w/zhice/workspaces');
  const search = first.page.getByRole('searchbox', { name: '搜索工作空间' });
  await search.fill('sql');
  assert.equal(await first.page.locator('.workspace-card').count(), 1);
  await first.page.getByRole('button', { name: /^智能协作/ }).click();
  await first.page.getByText('没有找到匹配的空间', { exact: true }).waitFor();
  await first.page.getByRole('button', { name: '清除筛选', exact: true }).click();
  await first.page.getByRole('button', { name: /^数据开发/ }).click();
  assert.equal(await first.page.locator('.workspace-card').count(), 1);
  await first.page.getByRole('button', { name: /^全部空间/ }).click();
  await first.page.getByRole('combobox', { name: '每页显示空间数' }).selectOption('12');
  assert.equal(await first.page.locator('.workspace-card').count(), available.length);
  assert.equal(await first.page.getByRole('button', { name: '下一页', exact: true }).isDisabled(), true);
  results.directorySearchCategoryAndPagination = 'PASS';
  const science = first.page.locator('.workspace-card').filter({ has: first.page.getByRole('heading', { name: '数据科学', exact: true }) });
  await science.getByRole('button', { name: '设为我的默认空间' }).click();
  await science.getByText('默认登录空间', { exact: true }).waitFor();
  assert.equal((await api('/api/v1/workspaces/preferences')).defaultWorkspaceId, 'data-science');
  await first.context.close();
  const second = await loginPage('data-science');
  await second.context.close();
  results.savedDefaultNewLogin = 'PASS';
  await api('/api/v1/workspaces/preferences', { method: 'PUT', data: { defaultWorkspaceId: 'zhice', lastWorkspaceId: 'zhice' } });
  const final = await loginPage('zhice');
  await final.page.goto(`${base}/#/workspaces`);
  await final.page.getByRole('heading', { name: '工作空间', exact: true }).waitFor();
  await final.page.reload();
  await final.page.waitForURL('**/#/w/zhice/workspaces');
  const zhice = final.page.locator('.workspace-card').filter({ has: final.page.getByRole('heading', { name: '灵动智策', exact: true }) });
  await zhice.getByText('默认登录空间', { exact: true }).waitFor();
  const finalGroup = final.page.getByRole('button', { name: '平台管理', exact: true });
  if (await finalGroup.getAttribute('aria-expanded') === 'false') await finalGroup.click();
  await final.page.screenshot({ path: `${output}/production-workspaces.png`, fullPage: true });
  results.preference = await api('/api/v1/workspaces/preferences');
  assert.equal(results.preference.defaultWorkspaceId, 'zhice');
  assert.deepEqual(errors, []);
  results.browserErrors = errors;
  results.realLogin = 'PASS';
  results.reloadAndLegacyLink = 'PASS';
  await writeFile(`${output}/verification.json`, JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results, null, 2));
} finally {
  if (headers) await api('/api/v1/workspaces/preferences', { method: 'PUT', data: { defaultWorkspaceId: 'zhice', lastWorkspaceId: 'zhice' } });
  await browser?.close();
  await http.dispose();
}
