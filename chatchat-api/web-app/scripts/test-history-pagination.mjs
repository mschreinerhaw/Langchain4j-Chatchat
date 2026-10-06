import { chromium } from "playwright-core";
import { createServer } from "vite";
import { access } from "node:fs/promises";
import assert from "node:assert/strict";

const liveUrl = process.env.HISTORY_TEST_URL;
let server, browser;
try {
  let executablePath;
  for (const candidate of [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "/usr/bin/chromium"].filter(Boolean)) {
    try { await access(candidate); executablePath = candidate; break; } catch {}
  }
  assert(executablePath, "Chromium is required");
  browser = await chromium.launch({ executablePath, headless: true });
  if (!liveUrl) {
    server = await createServer({ server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
    await server.listen();
  }
  const url = liveUrl || `http://127.0.0.1:${server.httpServer.address().port}/tests/history-pagination.html`;
  for (const viewport of [{ width: 1366, height: 900 }, { width: 390, height: 650 }]) {
    const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
    page.on("pageerror", error => console.error(error.message));
    page.on("response", response => {
      const path = new URL(response.url()).pathname;
      if (path.startsWith("/assets/") && response.status() >= 400) console.error("Asset failure", response.status(), path);
    });
    if (liveUrl) {
      const response = await page.request.post(`${liveUrl}/api/v1/enterprise/auth/login`, { data: {
        username: process.env.HISTORY_TEST_USERNAME, password: process.env.HISTORY_TEST_PASSWORD
      } });
      const payload = await response.json();
      assert.equal(payload.code, 200, "Live login failed");
      await page.addInitScript(session => localStorage.setItem("chatchat.auth.session", JSON.stringify(session)), payload.data);
      await page.route("**/summaries/page?**", async route => {
        const response = await route.fetch();
        await new Promise(resolve => setTimeout(resolve, 700));
        await route.fulfill({ response });
      });
    }
    await page.goto(url, { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "管理历史记录" }).click();
    const dialog = page.getByRole("dialog", { name: "历史记录" });
    await page.waitForFunction(() => document.querySelector(".history-manager-content")?.getAttribute("aria-busy") === "false");
    await page.setViewportSize(viewport);
    assert.equal(await dialog.locator(".history-manager-item").count(), 10);
    const before = await dialog.boundingBox();
    const first = await dialog.locator(".history-manager-item-copy strong").first().textContent();
    const nodes = await dialog.locator(".history-manager-item").elementHandles();
    await dialog.getByRole("button", { name: "下一页", exact: true }).click();
    await page.waitForFunction(() => document.querySelector(".history-manager-content")?.getAttribute("aria-busy") === "true");
    assert.equal(await dialog.locator(".history-manager-item").count(), 10, "Rows disappeared while loading");
    assert.equal(await nodes[0].evaluate(node => node.isConnected), true, "The old page was unmounted");
    assert.equal(await dialog.locator(".history-manager-item-copy strong").first().textContent(), first);
    const loading = await dialog.boundingBox();
    assert(Math.abs(before.y - loading.y) <= 1 && Math.abs(before.height - loading.height) <= 1, "Dialog jumped during loading");
    assert(await dialog.getByRole("button", { name: "下一页", exact: true }).isDisabled());
    assert(await dialog.locator(".history-manager-item input").first().isDisabled());
    await page.waitForFunction(() => document.querySelector(".history-manager-content")?.getAttribute("aria-busy") === "false");
    assert((await dialog.locator(".app-pagination strong").textContent()).includes("第 2 /"));
    if (!liveUrl) {
      await dialog.getByRole("button", { name: "下一页", exact: true }).click();
      await page.waitForFunction(() => document.querySelector(".history-manager-content")?.getAttribute("aria-busy") === "false"
        && document.querySelector(".app-pagination strong")?.textContent.includes("第 3 /"));
      assert.equal(await dialog.locator(".history-manager-item").count(), 1);
    }
    const after = await dialog.boundingBox();
    assert(Math.abs(before.y - after.y) <= 1 && Math.abs(before.height - after.height) <= 1, "Dialog jumped after pagination");
    assert(after.x >= 0 && after.x + after.width <= viewport.width, "Dialog exceeds the viewport");
    await dialog.getByRole("button", { name: "上一页", exact: true }).click();
    await page.waitForFunction(() => document.querySelector(".history-manager-content")?.getAttribute("aria-busy") === "false");
    assert.equal(await dialog.locator(".history-manager-item").count(), 10);
    await page.close();
    console.log(`PASS history pagination ${liveUrl ? "deployed" : "fixture"} ${viewport.width}x${viewport.height}`);
  }
} finally {
  await browser?.close();
  await server?.close();
}
