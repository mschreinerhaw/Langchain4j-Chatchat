import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";

const base = process.env.CHATCHAT_STAGE_URL || "http://192.168.195.221:18100";
const out = process.env.CHATCHAT_BROWSER_OUTPUT || "target/tool-categories-live";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", headless: true, args: ["--no-proxy-server"] });
const checks = {}, errors = [], ownCategories = new Set();
let api, context, page, logical;
try {
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: "zh-CN" });
  const credentials = JSON.parse(readFileSync("target/workspace-stage-auth-private.json", "utf8"));
  const login = await (await context.request.post(base + "/api/v1/enterprise/auth/login", { data: credentials })).json();
  assert.ok(login.data?.token);
  await context.addInitScript(session => localStorage.setItem("chatchat.auth.session", JSON.stringify(session)), login.data);
  api = async (path, body, method = body ? "POST" : "GET", expected = 200) => {
    const response = await context.request.fetch(base + "/api/v1" + path, { method, headers: { Authorization: "Bearer " + login.data.token, "X-Workspace-Id": "mcp-publication" }, ...(body ? { data: body } : {}) });
    const value = await response.json(); assert.equal(response.status(), expected, path + ": " + JSON.stringify(value));
    if (expected === 200) assert.equal(value.code, 200, path);
    return value.data;
  };
  const before = await api("/tool-publications"), beforeCategories = await api("/tool-categories");
  const stamp = Date.now().toString(36), sourceName = "客户分析 " + stamp, renamed = "客户洞察 " + stamp, targetName = "经营分析 " + stamp;
  page = await context.newPage(); page.setDefaultTimeout(45000); page.on("pageerror", error => errors.push(error.message));
  const categoryReady = () => page.locator('.tool-categories[aria-busy="false"]').waitFor();
  async function showCategories() { await page.goto(base + "/#/w/mcp-publication/toolCategories"); await categoryReady(); }
  async function searchCategory(name) { await page.getByPlaceholder("输入分类名称").fill(name); }
  const modal = () => page.getByRole("dialog");
  async function submitCategory(method, name = "保存") {
    const response = page.waitForResponse(r => r.url().includes("/api/v1/tool-categories") && r.request().method() === method);
    await modal().getByRole("button", { name, exact: true }).click();
    const result = await response; assert.equal(result.status(), 200, await result.text());
    await page.locator("dialog").waitFor({ state: "detached" }); await categoryReady();
    return (await result.json()).data;
  }
  await showCategories();
  assert.ok(await page.locator(".sidebar").getByText("分类管理", { exact: true }).count() || await page.getByText("分类管理", { exact: true }).count());
  await page.locator(".permission-heading-actions").getByRole("button", { name: "新建分类", exact: true }).click();
  await modal().getByLabel("分类名称", { exact: true }).fill(sourceName);
  const source = await submitCategory("POST"); ownCategories.add(source.id);
  const target = await api("/tool-categories", { name: targetName }); ownCategories.add(target.id);
  checks.separateMenuAndCreateDialog = true;
  await searchCategory(sourceName);
  await page.getByRole("button", { name: "删除 " + sourceName, exact: true }).click();
  await modal().getByRole("button", { name: "取消", exact: true }).click();
  assert.ok((await api("/tool-categories")).some(row => row.id === source.id));
  checks.cancelDoesNotDelete = true;
  await page.locator(".permission-heading-actions").getByRole("button", { name: "新建分类", exact: true }).click();
  await modal().getByLabel("分类名称", { exact: true }).fill(sourceName);
  await modal().getByRole("button", { name: "保存", exact: true }).click();
  assert.match(await modal().getByRole("alert").innerText(), /已存在/);
  await page.keyboard.press("Escape"); await page.locator("dialog").waitFor({ state: "detached" });
  checks.duplicateFeedbackAndKeyboardCancel = true;

  const seed = before.find(row => row.state === "RUNNING" && row.kind === "CONTROLLED_TEMPLATE"); assert.ok(seed);
  const definition = { ...JSON.parse(seed.definitionJson), domainCode: "category_accept_" + stamp, businessCategory: sourceName, enabled: false };
  delete definition.chineseAlias;
  const payload = { title: "分类体验验收 " + stamp, kind: "CONTROLLED_TEMPLATE", description: "临时验收工具，验证结束后删除。", definition, inputSchema: { type: "object" }, outputSchema: { type: "object" } };
  const first = await api("/tool-publications", payload); logical = first.logicalId;
  await api(`/tool-publications/${first.id}/validate`, {}, "POST");
  await api(`/tool-publications/${first.id}/test`, { input: { limit: 1 } });
  await api(`/tool-publications/${first.id}/publish`, {}, "POST");
  const second = await api("/tool-publications", { ...payload, logicalId: logical });
  const family = async () => (await api("/tool-publications")).filter(row => row.logicalId === logical);
  const snapshots = (await family()).map(({ businessCategory, businessCategoryId, ...row }) => row);
  await showCategories(); await searchCategory(sourceName);
  assert.match(await page.locator(".category-table tbody").innerText(), /1 个工具/);
  await page.getByRole("button", { name: "重命名 " + sourceName, exact: true }).click();
  await modal().getByLabel("分类名称", { exact: true }).fill(renamed); await submitCategory("PUT");
  const renamedCategory = (await api("/tool-categories")).find(row => row.id === source.id);
  assert.equal(renamedCategory.toolCount, 1); assert.ok((await family()).every(row => row.businessCategory === renamed));
  assert.deepEqual((await family()).map(({ businessCategory, businessCategoryId, ...row }) => row), snapshots);
  checks.renameAppliesAcrossVersionsWithoutChangingSnapshots = true;
  await api(`/tool-categories/${source.id}`, { name: sourceName, expectedVersion: source.version }, "PUT", 409);
  await searchCategory(renamed);
  await page.getByRole("button", { name: "删除 " + renamed, exact: true }).click();
  assert.match(await modal().innerText(), /1 个工具/);
  await modal().getByLabel("关联工具的新分类").selectOption(target.id);
  await page.screenshot({ path: out + "/delete-transfer-dialog.png" });
  await submitCategory("DELETE", "转移工具并删除"); ownCategories.delete(source.id);
  assert.ok((await family()).every(row => row.businessCategory === targetName));
  assert.deepEqual((await family()).map(({ businessCategory, businessCategoryId, ...row }) => row), snapshots);
  checks.deleteTransfersWholeToolAndRetainsHistory = true;

  await page.goto(base + "/#/w/mcp-publication/toolPublications");
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  await page.getByPlaceholder("搜索名称或说明").fill(payload.title);
  await page.locator(".permission-master-item").filter({ hasText: payload.title }).click();
  assert.equal(await page.locator("#current-tool-category").inputValue(), target.id);
  await page.locator("#current-tool-category").selectOption("");
  const assignmentResponse = page.waitForResponse(r => r.url().includes("/tool-categories/assignments/") && r.request().method() === "PUT");
  await page.getByRole("button", { name: "保存分类", exact: true }).click(); assert.equal((await assignmentResponse).status(), 200);
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  assert.ok((await family()).every(row => row.businessCategory === ""));
  await page.locator("#current-tool-category").selectOption(target.id);
  const reassign = page.waitForResponse(r => r.url().includes("/tool-categories/assignments/") && r.request().method() === "PUT");
  await page.getByRole("button", { name: "保存分类", exact: true }).click(); assert.equal((await reassign).status(), 200);
  await page.locator('.tool-publications[aria-busy="false"]').waitFor();
  checks.existingPublishedToolCanBeClassifiedWithoutNewVersion = true;
  await page.getByRole("button", { name: "管理分类", exact: true }).click(); await categoryReady();
  assert.match(page.url(), /toolCategories/);

  await showCategories(); await searchCategory(targetName);
  await page.getByRole("button", { name: "删除 " + targetName, exact: true }).click();
  const currentTarget = (await api("/tool-categories")).find(row => row.id === target.id);
  await api(`/tool-categories/${target.id}`, { name: targetName + " 更新", expectedVersion: currentTarget.version }, "PUT");
  const stale = page.waitForResponse(r => r.request().method() === "DELETE" && r.url().includes(target.id));
  await modal().getByRole("button", { name: "转移工具并删除", exact: true }).click(); assert.equal((await stale).status(), 409);
  await modal().getByRole("alert").waitFor(); assert.ok(await modal().isVisible());
  await modal().getByRole("button", { name: "刷新分类信息", exact: true }).click();
  await page.waitForFunction(() => !document.querySelector(".category-dialog-error"));
  await submitCategory("DELETE", "转移工具并删除"); ownCategories.delete(target.id);
  assert.ok((await family()).every(row => row.businessCategory === ""));
  checks.staleConfirmationRetainedAndRefreshAllowsRetry = true;
  checks.deleteToUnclassifiedKeepsToolRunning = (await family()).some(row => row.id === first.id && row.state === "RUNNING");

  await page.goto(base + "/#/w/zhice/mcp"); await page.locator(".mcp-tool-card").first().waitFor();
  await page.getByPlaceholder("搜索工具名称或用途").fill(payload.title);
  await page.waitForFunction(title => [...document.querySelectorAll(".mcp-tool-card")].some(card => card.textContent.includes(title)), payload.title);
  assert.match(await page.locator(".mcp-tool-card").filter({ hasText: payload.title }).innerText(), /未分类/);
  checks.publishedCardReflectsCurrentClassification = true;
  await page.screenshot({ path: out + "/published-tool-category.png" });

  for (let i = 0; i < 12; i++) { const row = await api("/tool-categories", { name: `业务分类验收 ${stamp} ${String(i).padStart(2, "0")}` }); ownCategories.add(row.id); }
  await showCategories(); await searchCategory(`业务分类验收 ${stamp}`);
  assert.equal(await page.locator(".category-table tbody tr").count(), 10);
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  assert.equal(await page.locator(".category-table tbody tr").count(), 2);
  await page.screenshot({ path: out + "/categories-pagination.png" });
  await page.getByPlaceholder("输入分类名称").fill(`业务分类验收 ${stamp} 00`);
  assert.equal(await page.locator(".category-table tbody tr").count(), 1);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: out + "/categories-mobile.png" });
  checks.searchPaginationAndSmallScreen = true;
  assert.deepEqual((await api("/tool-publications")).filter(row => row.logicalId !== logical), before);
  assert.deepEqual((await api("/tool-categories")).filter(row => !ownCategories.has(row.id)), beforeCategories);
  assert.deepEqual(errors, []);
  writeFileSync(out + "/acceptance.json", JSON.stringify({ checks, consoleErrors: errors }, null, 2));
  console.log(JSON.stringify(checks));
} finally {
  if (api) {
    if (logical) {
      let rows = (await api("/tool-publications")).filter(row => row.logicalId === logical);
      for (const row of rows.filter(row => row.state === "RUNNING")) await api(`/tool-publications/${row.id}/offline`, {}, "POST");
      rows = (await api("/tool-publications")).filter(row => row.logicalId === logical);
      const stopped = rows.find(row => row.state === "OFFLINE");
      if (stopped) await api(`/tool-publications/${stopped.id}`, { expectedVersions: Object.fromEntries(rows.map(row => [row.id, row.version])) }, "DELETE");
    }
    for (const row of (await api("/tool-categories")).filter(row => ownCategories.has(row.id))) await api(`/tool-categories/${row.id}`, { expectedVersion: row.version, expectedToolCount: row.toolCount, targetCategoryId: null }, "DELETE");
    writeFileSync(out + "/cleanup.json", JSON.stringify({ temporaryToolRemaining: (await api("/tool-publications")).some(row => row.logicalId === logical), temporaryCategoriesRemaining: (await api("/tool-categories")).filter(row => ownCategories.has(row.id)).length }, null, 2));
  }
  await browser.close();
}
