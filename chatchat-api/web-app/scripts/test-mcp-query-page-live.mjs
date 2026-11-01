/** Read-only real-browser regression for manual and SQL Studio query templates. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const origin = process.env.E2E_MCP_ORIGIN,
  authFile = process.env.E2E_MCP_AUTH_FILE,
  out = process.env.E2E_OUTPUT;
assert.ok(origin && authFile && out);
const auth = JSON.parse(fs.readFileSync(authFile, "utf8").replace(/^\uFEFF/, ""));
fs.mkdirSync(out, { recursive: true });
assert.ok(!fs.existsSync(path.join(out, "verdict.json")), "Preserve existing evidence");
async function api(route) {
  const r = await fetch(origin + "/api/v1" + route, {
      headers: { Authorization: "Bearer " + auth.token },
    }),
    j = await r.json();
  assert.ok(r.ok && j.code === 200, route + ": " + j.message);
  return j.data;
}
const manual = (await api("/database-query")).find((r) => r.title && r.enabled !== false),
  studio = (await api("/database-query/studio-publications")).find(
    (r) => r.title && r.publicationState === "RUNNING"
  );
assert.ok(manual && studio, "Require existing templates of both sources");
const browser = await chromium.launch({
  executablePath:
    process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
});
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript((a) => {
    sessionStorage.setItem("chatchat.mcp.admin.token", a.token);
    sessionStorage.setItem("chatchat.mcp.admin.user", a.username);
    sessionStorage.setItem("chatchat.mcp.admin.expiresAt", String(a.expiresAt));
    sessionStorage.setItem("chatchat.mcp.admin.lastActivityAt", String(Date.now()));
  }, auth);
  const page = await context.newPage(),
    errors = [],
    metadataRequests = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("request", (r) => {
    if (/\/database-query\/[^/]+\/metadata$/.test(new URL(r.url()).pathname))
      metadataRequests.push(r.url());
  });
  await page.goto(origin + "/admin/index.html");
  await page.getByText("查询能力模板", { exact: true }).click();
  await page.getByRole("menuitem", { name: "关系库查询", exact: true }).click();
  const search = page.getByPlaceholder("搜索模板名称、描述或数据源");
  await search.waitFor();
  const expected = ["模板名称", "业务分类", "来源", "数据源", "状态", "操作"];
  const headers = await page.locator(".catalog-table .el-table__header th").allTextContents();
  for (const h of expected)
    assert.ok(
      headers.some((x) => x.trim() === h),
      h
    );
  assert.ok(!headers.some((x) => /工具名称|最大行数|业务领域|参数摘要|发布版本/.test(x)));
  await page.locator(".catalog-table .el-table__row").first().waitFor();
  await page.locator(".catalog-table .el-loading-mask").waitFor({ state: "hidden" });
  await page.screenshot({ animations: "disabled", path: path.join(out, "query-list.png"), fullPage: true });
  const results = [];
  for (const [source, row] of [
    ["studio", studio],
    ["manual", manual],
  ]) {
    const metadataBefore = metadataRequests.length;
    await search.fill(row.title);
    const tableRow = page.locator(".el-table__row").filter({ hasText: row.title }).first();
    await tableRow.waitFor();
    await tableRow.getByRole("button", { name: "详情", exact: true }).click();
    const dialog = page.getByRole("dialog").filter({ has: page.locator(".database-query-detail") });
    await dialog.waitFor();
    const detail = dialog.locator(".database-query-detail");
    await detail.getByText(row.description || "暂无描述", { exact: true }).waitFor();
    assert.equal(await detail.locator(".el-collapse-item.is-active").count(), 0);
    assert.equal(await detail.locator("pre:visible").count(), 0);
    assert.equal(
      metadataRequests.length,
      metadataBefore,
      "Basic details must not depend on metadata"
    );
    const text = await detail.innerText();
    assert.ok(!text.includes(row.toolName));
    assert.ok(!/RUNNING|OFFLINE|参数 Schema|所属租户/.test(text));
    await page.screenshot({ animations: "disabled", path: path.join(out, source + "-detail.png") });
    const record = detail.locator(".el-collapse-item__header").filter({ hasText: "技术记录" });
    const metadataRequested =
      source === "manual"
        ? page.waitForRequest((r) =>
            /\/database-query\/[^/]+\/metadata$/.test(new URL(r.url()).pathname)
          )
        : null;
    await record.click();
    await detail.getByText(row.toolName, { exact: true }).waitFor();
    if (source === "manual") {
      await metadataRequested;
      assert.equal(metadataRequests.length, metadataBefore + 1);
      await detail.getByText("正在读取元数据…", { exact: true }).waitFor({ state: "hidden" });
    }
    const typography = await record.evaluate((e) => ({
      fontSize: getComputedStyle(e).fontSize,
      color: getComputedStyle(e).color,
    }));
    assert.equal(typography.fontSize, "12px");
    await page.screenshot({ animations: "disabled", path: path.join(out, source + "-technical.png") });
    await dialog.getByRole("button", { name: "关闭", exact: true }).click();
    await tableRow.getByRole("button", { name: "详情", exact: true }).click();
    await dialog.waitFor();
    assert.equal(await detail.locator(".el-collapse-item.is-active").count(), 0);
    await page.setViewportSize({ width: 900, height: 720 });
    const box = await dialog.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 901);
    const close = await dialog.getByRole("button", { name: "关闭", exact: true }).boundingBox();
    assert.ok(close.y + close.height <= 720);
    await page.screenshot({ animations: "disabled", path: path.join(out, source + "-compact.png") });
    await dialog.getByRole("button", { name: "关闭", exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    results.push({
      source,
      title: row.title,
      templateId: row.templateId || row.id,
      collapsedByDefault: true,
      resetAfterReopen: true,
      metadataDeferredUntilExpanded: source === "manual",
      technicalTypography: typography,
      compactLayout: "PASS",
    });
  }
  assert.deepEqual(errors, []);
  const verdict = {
    status: "PASS",
    capturedAt: new Date().toISOString(),
    scope: "READ_ONLY_REAL_BROWSER_NO_MOCKS",
    businessColumns: expected,
    templates: results,
    browserErrors: errors,
  };
  fs.writeFileSync(path.join(out, "verdict.json"), JSON.stringify(verdict, null, 2));
  console.log(JSON.stringify(verdict));
} finally {
  await browser.close();
}
