import { chromium } from "playwright-core";
import { createServer } from "vite";
import { readFile, mkdir, access } from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";

// Local browser smoke: UI fixtures only; real authorization is covered by Java integration tests.
const manifests = {};
for (const id of ["zhice", "data-science"]) manifests[id] = JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`, "utf8"));
const candidates = ["C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"];
let executablePath;
for (const candidate of candidates) { try { await access(candidate); executablePath = candidate; break; } catch {} }
assert.ok(executablePath, "A local Chromium browser is required");
const server = await createServer({ server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = []; page.on("pageerror", error => errors.push(error.message));
  const user = { id: "browser-user", username: "browser-user", tenantId: "browser-tenant", permissionCodes: [] };
  let logins = 0;
  await page.route("**/api/v1/**", async route => {
    const url = new URL(route.request().url()); let data = [];
    if (url.pathname.endsWith("/auth/me")) data = user;
    else if (url.pathname.endsWith("/auth/login")) { logins++; data = { token: "fixture-token", user }; }
    else if (url.pathname.endsWith("/workspaces/catalog")) data = Object.values(manifests).map(manifest => ({ ...manifest, defaultWorkspace: manifest.workspaceId === "zhice" }));
    else if (url.pathname.endsWith("/workspaces")) data = Object.values(manifests).map(manifest => ({ ...manifest, defaultWorkspace: manifest.workspaceId === "zhice" }));
    else if (url.pathname.endsWith("/workspaces/preferences")) data = { lastWorkspaceId: "zhice" };
    else if (url.pathname.endsWith('/data-science/python/workbench')) data = { assets:[{id:'fixture-asset',name:'Fixture Python',status:'READY',tenantId:user.tenantId,ownerId:user.id}], folders:[],scripts:[],executions:[],dataFiles:[],systemExamples:[] };
    else if (url.pathname.endsWith("/manifest")) {
      const id = url.pathname.split("/").at(-2);
      if (!manifests[id]) { await route.fulfill({ status: 403, json: { code: 403, message: "Access denied" } }); return; }
      if (id === "data-science") await new Promise(resolve => setTimeout(resolve, 700));
      data = { manifest: manifests[id], menus: [{ id: "workspace", title: "Workspace", type: "directory", children:
        manifests[id].pages.map(item => ({ id: item.menuCode, title: item.id, type: "menu", children: [] })) }] };
    }
    await route.fulfill({ json: { code: 200, data, message: "Success" } });
  });
  const port = server.httpServer.address().port;
  await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: "networkidle" });
  await page.waitForFunction(() => document.querySelector('select[aria-label="选择工作空间"]')?.options.length === 3);
  assert.equal(await page.locator('select[aria-label="选择工作空间"]').inputValue(),"zhice");
  assert.equal(await page.locator('select[aria-label="选择工作空间"]').evaluate(select => getComputedStyle(select.parentElement).gridTemplateColumns.split(' ').length), 1);
  await page.locator('input[autocomplete="username"]').fill('browser-user');
  await page.locator('input[type="password"]').fill('fixture-password');
  const captcha=await page.evaluate(()=>document.querySelector('#app').__vue_app__._container._vnode.component.subTree.component.proxy.captchaCode);
  await page.locator('input[maxlength="4"]').fill(captcha);
  await page.locator('.login-submit').click();
  await page.waitForURL("**/#/w/zhice/chat");
  assert.equal(await page.locator('.user-account[open]').count(), 0);
  await page.locator('.user-account-trigger').click();
  assert.equal(await page.locator('.user-account[open]').count(), 1);
  assert.equal(await page.locator('.user-account-menu').evaluate(element => getComputedStyle(element).position), 'absolute');
  assert.equal(await page.locator('.workspace-switcher').evaluate(element => getComputedStyle(element).borderTopWidth), '0px');
  assert.equal(await page.locator('.user-card .workspace-switcher-option').count(), 2);
  assert.equal(await page.locator('.workspace-switcher-options').evaluate(element => getComputedStyle(element).display), 'grid');
  assert.equal(await page.locator('.workspace-switcher-option[aria-pressed="true"]').count(), 1);
  await mkdir('../../target/workspace-audit', { recursive: true });
  await page.screenshot({ path: '../../target/workspace-audit/workspace-switcher.png' });
  const composer = page.locator("textarea").first();
  await composer.waitFor({ state: "visible" }); await composer.fill("unsaved workspace draft");
  await page.locator('.workspace-switcher-option[data-workspace-id="data-science"]').click();
  await page.locator(".workspace-switcher.is-switching").waitFor({ state: "visible" });
  assert.equal(await page.locator(".workspace-switcher-state").textContent(), "切换中…");
  assert.equal(await page.locator(".assistant-app.workspace-changing").count(), 1);
  await page.waitForURL("**/#/w/data-science/dataScienceEnvironment");
  await page.locator('.user-account[open]').waitFor({ state: 'detached' });
  await page.locator(".assistant-app.workspace-changing").waitFor({ state: "detached" });
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.assistant-content')).opacity === '1');
  assert.equal(await page.locator('.recent-block').count(),0);
  assert.equal(await page.locator(".workspace-extensions").count(), 0);
  await page.evaluate(() => { location.hash = "/w/data-science/dataScienceDevelop"; });
  await page.locator(".ds-page.develop-mode").waitFor({ state: "visible" });
  const pythonInput = page.locator('.monaco-host textarea.inputarea');
  await pythonInput.waitFor({ state: 'visible' });
  await pythonInput.focus(); await pythonInput.press('Control+A'); await pythonInput.pressSequentially('def foo():'); await pythonInput.press('Enter');
  await pythonInput.pressSequentially('if True:'); await pythonInput.press('Escape'); await pythonInput.press('Enter'); await pythonInput.pressSequentially('pass');
  const pythonSource = await page.locator('.ds-page').evaluate(element => element.__vueParentComponent?.proxy?.form?.sourceCode || '');
  assert.match(pythonSource, /def foo\(\):\n {4}if True:\n {8}pass/);
  await pythonInput.press('Home'); await pythonInput.press('Shift+Tab');
  assert.match(await page.locator('.ds-page').evaluate(element => element.__vueParentComponent.proxy.form.sourceCode), /if True:\n {4}pass/);
  await pythonInput.press('Home'); await pythonInput.press('Tab');
  assert.match(await page.locator('.ds-page').evaluate(element => element.__vueParentComponent.proxy.form.sourceCode), /if True:\n {8}pass/);
  await page.locator('.editor-tools').getByRole('button',{name:'格式化'}).click();
  await page.waitForFunction(() => document.querySelector('.ds-page').__vueParentComponent.proxy.form.sourceCode.endsWith('pass\n'));
  assert.equal(await page.locator(".ds-header").count(), 0);
  assert.equal(await page.getByText("SQL Studio", { exact: false }).count(), 0);
  if (await page.locator('.user-account:not([open])').count()) await page.locator('.user-account-trigger').click();
  await page.locator('.workspace-switcher-option[data-workspace-id="zhice"]').click();
  await page.waitForURL("**/#/w/zhice/chat");
  assert.equal(await composer.inputValue(), "unsaved workspace draft");
  assert.equal(await page.locator(".workspace-card").count(),0);
  await page.evaluate(() => { location.hash = "/w/forbidden/chat"; });
  await page.locator(".workspace-error").waitFor({ state: "visible" });
  assert.equal(await page.locator('.workspace-switcher-option[aria-pressed="true"]').getAttribute('data-workspace-id'), 'zhice');
  assert.equal(logins, 1);
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("chatchat.auth.session")).token), "fixture-token");
  assert.deepEqual(errors, []);
  await mkdir("../../target/workspace-audit", { recursive: true });
  await page.screenshot({ path: "../../target/workspace-audit/workspace-shell.png", fullPage: true });
  console.log("PASS: login selection, one identity, workspace switch, Python Enter/Tab/Shift+Tab/format, draft recovery, denied space");
} finally { await browser?.close(); await server.close(); }
