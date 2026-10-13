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
    else if (url.pathname.endsWith("/manifest")) {
      const id = url.pathname.split("/").at(-2);
      if (!manifests[id]) { await route.fulfill({ status: 403, json: { code: 403, message: "Access denied" } }); return; }
      data = { manifest: manifests[id], menus: [{ id: "workspace", title: "Workspace", type: "directory", children:
        manifests[id].pages.map(item => ({ id: item.menuCode, title: item.id, type: "menu", children: [] })) }] };
    }
    await route.fulfill({ json: { code: 200, data, message: "Success" } });
  });
  const port = server.httpServer.address().port;
  await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: "networkidle" });
  await page.waitForFunction(() => document.querySelector('select[aria-label="选择工作空间"]')?.options.length === 3);
  assert.equal(await page.locator('select[aria-label="选择工作空间"]').inputValue(),"zhice");
  await page.locator('input[autocomplete="username"]').fill('browser-user');
  await page.locator('input[type="password"]').fill('fixture-password');
  const captcha=await page.evaluate(()=>document.querySelector('#app').__vue_app__._container._vnode.component.subTree.component.proxy.captchaCode);
  await page.locator('input[maxlength="4"]').fill(captcha);
  await page.locator('.login-submit').click();
  await page.waitForURL("**/#/w/zhice/chat");
  assert.equal(await page.locator('.user-card #workspace-switch').count(),1);
  const composer = page.locator("textarea").first();
  await composer.waitFor({ state: "visible" }); await composer.fill("unsaved workspace draft");
  await page.locator("#workspace-switch").selectOption("data-science");
  await page.waitForURL("**/#/w/data-science/dataScienceEnvironment");
  assert.equal(await page.locator('.recent-block').count(),0);
  assert.equal(await page.locator(".workspace-extensions button").isDisabled(), true);
  assert.match(await page.locator(".workspace-extensions").textContent(), /SQL Studio/);
  await page.locator("#workspace-switch").selectOption("zhice");
  await page.waitForURL("**/#/w/zhice/chat");
  assert.equal(await composer.inputValue(), "unsaved workspace draft");
  assert.equal(await page.locator(".workspace-card").count(),0);
  await page.evaluate(() => { location.hash = "/w/forbidden/chat"; });
  await page.locator(".workspace-error").waitFor({ state: "visible" });
  assert.equal(await page.locator("#workspace-switch").inputValue(), "zhice");
  assert.equal(logins, 1);
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("chatchat.auth.session")).token), "fixture-token");
  assert.deepEqual(errors, []);
  await mkdir("../../target/workspace-audit", { recursive: true });
  await page.screenshot({ path: "../../target/workspace-audit/workspace-shell.png", fullPage: true });
  console.log("PASS: login selection, account switch, editor draft, reserved extension, denied space, one login identity");
} finally { await browser?.close(); await server.close(); }
