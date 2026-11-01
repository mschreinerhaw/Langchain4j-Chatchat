/** Live SQL Studio UI publication, metadata synchronization and offline republish. Only isolated fixtures. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const {
  E2E_ORIGIN: origin,
  E2E_AUTH_FILE: authFile,
  E2E_MCP_ORIGIN: mcpOrigin,
  E2E_MCP_AUTH_FILE: mcpAuthFile,
  E2E_FIXTURE: fixtureFile,
  E2E_OUTPUT: out,
} = process.env;
assert.ok(origin && authFile && mcpOrigin && mcpAuthFile && fixtureFile && out);
const auth = JSON.parse(fs.readFileSync(authFile)),
  mcpAuth = JSON.parse(fs.readFileSync(mcpAuthFile)),
  fixture = JSON.parse(fs.readFileSync(fixtureFile));
fs.mkdirSync(out, { recursive: true });
assert.ok(
  !fs.existsSync(path.join(out, "initial-publication.json")),
  "Do not overwrite actual publication evidence"
);
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
async function api(route, method = "GET", body) {
  const r = await fetch(origin + "/api/v1" + route, {
      method,
      headers: {
        Authorization: "Bearer " + auth.token,
        "X-Workspace-Id": "data-science",
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    j = await r.json();
  assert.ok(r.ok && j.code === 200, route + ": " + j.message);
  return j.data;
}
async function mcp(route) {
  const r = await fetch(mcpOrigin + "/api/v1" + route, {
      headers: { Authorization: "Bearer " + mcpAuth.token },
    }),
    j = await r.json();
  assert.ok(r.ok && j.code === 200, route + ": " + j.message);
  return j.data;
}
const categories = await api("/data-science/sql/template-publications/categories"),
  maintained = (await mcp("/business-categories")).filter((c) => c.enabled);
assert.deepEqual(
  categories.map((c) => [c.id, c.code, c.name]),
  maintained.map((c) => [c.id, c.code, c.name])
);
save("categories-authoritative", categories);
const changedCategory =
  categories.find((c) => c.code === "data_validation") ??
  categories.find((c) => c.code !== fixture.category.code);
assert.ok(changedCategory);
const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  }),
  records = [],
  pending = [];
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(
    (a) => localStorage.setItem("chatchat.auth.session", JSON.stringify(a)),
    auth
  );
  const page = await context.newPage();
  page.on("response", (r) => {
    const u = new URL(r.url());
    if (u.pathname.includes("/template-publications") && r.request().method() !== "GET")
      pending.push(
        (async () => {
          records.push({
            path: u.pathname,
            method: r.request().method(),
            request: r.request().postDataJSON(),
            httpStatus: r.status(),
            response: await r.json(),
          });
        })()
      );
  });
  await page.goto(origin + "/index.html#/w/data-science/sqlStudio");
  await page.getByLabel("SQL 工程", { exact: true }).selectOption(fixture.project.id);
  await page.locator(".sql-tree-file").getByTitle(fixture.file.path, { exact: true }).click();
  const open = async () => {
    await page.getByRole("button", { name: "模板管理与发布", exact: true }).click();
    await page.getByRole("dialog", { name: "发布 MCP SQL 模板" }).waitFor();
    await page.locator(".sql-publication-body select").first().waitFor();
  };
  await open();
  let panel = page.getByRole("dialog", { name: "发布 MCP SQL 模板" });
  await panel.locator(".sql-publication-footer .sql-publication-primary").waitFor();
  await panel.getByLabel("模板业务分类").selectOption(fixture.category.code);
  const technical = panel.locator('[aria-label="发布技术详情"]');
  assert.equal(await technical.getAttribute("open"), null);
  const footerBox = await panel.locator(".sql-publication-footer").boundingBox();
  assert.ok(footerBox.y + footerBox.height <= 1000);
  await page.screenshot({ path: path.join(out, "studio-before-publish.png") });
  await technical.locator("summary").first().click();
  const typography = await technical
    .locator("li")
    .first()
    .evaluate((e) => ({
      fontSize: getComputedStyle(e).fontSize,
      color: getComputedStyle(e).color,
    }));
  assert.equal(typography.fontSize, "12px");
  await page.screenshot({ path: path.join(out, "studio-technical-expanded.png") });
  await technical.locator("summary").first().click();
  const publish = async (action) => {
    await panel.locator(".sql-publication-review input").check();
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        new URL(r.url()).pathname.endsWith(
          "/template-publications/" + fixture.draft.id + "/" + action
        ),
      { timeout: 60000 }
    );
    await panel.locator(".sql-publication-primary").click();
    const r = await response,
      j = await r.json();
    save(action + "-response", j);
    assert.equal(r.status(), 200, j.message);
    assert.equal(j.code, 200, j.message);
    await panel
      .locator(".sql-publication-primary")
      .getByText("处理中…")
      .waitFor({ state: "hidden" })
      .catch(() => {});
    await page.waitForFunction(
      () =>
        !document.querySelector(".sql-publication-primary")?.disabled ||
        !document
          .querySelector('.sql-publication-panel [aria-live="polite"]')
          ?.textContent?.includes("正在"),
      { timeout: 30000 }
    );
    return j.data;
  };
  const initial = await publish("publish");
  save("initial-publication", initial);
  assert.equal(initial.publication.state, "RUNNING");
  assert.equal(initial.testEvidence.success, true);
  assert.ok(initial.testEvidence.preview.rows.length);
  await page.screenshot({ path: path.join(out, "studio-published.png") });
  const updatedTitle = fixture.draft.title + " · 信息同步",
    updatedDescription =
      "在 SQL Studio 修改名称、说明和业务分类后同步到 MCP Server，查询逻辑保持不变。";
  await panel.getByLabel("名称", { exact: true }).fill(updatedTitle);
  await panel.getByLabel("描述", { exact: true }).fill(updatedDescription);
  await panel.getByLabel("模板业务分类").selectOption(changedCategory.code);
  const updated = await publish("metadata");
  save("updated-publication", updated);
  assert.equal(updated.publication.toolName, initial.publication.toolName);
  assert.ok(updated.publication.version > initial.publication.version);
  assert.equal(updated.code, initial.code);
  assert.equal(updated.sqlTemplate, initial.sqlTemplate);
  assert.deepEqual(updated.parameterSchema, initial.parameterSchema);
  assert.equal(updated.datasourceId, initial.datasourceId);
  assert.equal(updated.category, changedCategory.code);
  assert.ok(updated.releaseHistory.some((h) => h.sqlSnapshot.title === initial.title));
  const catalog = (await mcp("/database-query/studio-publications")).find(
    (r) => r.templateId === fixture.draft.id
  );
  assert.ok(catalog);
  assert.equal(catalog.title, updatedTitle);
  assert.equal(catalog.description, updatedDescription);
  assert.equal(catalog.capabilityCategory, changedCategory.code);
  save("mcp-catalog-updated", catalog);
  const tools = await api("/mcp/tools"),
    tool = tools.find((t) => t.localToolName?.endsWith(updated.publication.toolName));
  assert.ok(tool);
  assert.ok(tool.description.includes(updatedDescription));
  save("runtime-tool-updated", {
    localToolName: tool.localToolName,
    description: tool.description,
  });
  await page.screenshot({ path: path.join(out, "studio-updated.png") });
  await api(
    "/tool-publications/capabilities/publications/" + updated.publication.id + "/offline",
    "POST"
  );
  save(
    "offline-history",
    await api(
      "/data-science/sql/projects/" +
        fixture.project.id +
        "/files/" +
        fixture.file.id +
        "/template-publications"
    )
  );
  await panel.getByRole("button", { name: "关闭发布模板" }).click();
  await open();
  panel = page.getByRole("dialog", { name: "发布 MCP SQL 模板" });
  await panel.locator(".sql-publication-primary").getByText("发布模板", { exact: true }).waitFor();
  await page.screenshot({ path: path.join(out, "studio-offline-publish-button.png") });
  const restored = await publish("rollback");
  save("restored-publication", restored);
  assert.equal(restored.publication.state, "RUNNING");
  assert.equal(restored.publication.toolName, initial.publication.toolName);
  assert.ok(restored.publication.version > updated.publication.version);
  const admin = await browser.newContext({ viewport: { width: 1870, height: 1100 } });
  await admin.addInitScript((a) => {
    sessionStorage.setItem("chatchat.mcp.admin.token", a.token);
    sessionStorage.setItem("chatchat.mcp.admin.user", a.username);
    sessionStorage.setItem("chatchat.mcp.admin.expiresAt", String(a.expiresAt));
    sessionStorage.setItem("chatchat.mcp.admin.lastActivityAt", String(Date.now()));
  }, mcpAuth);
  const mcpPage = await admin.newPage();
  await mcpPage.goto(mcpOrigin + "/admin/index.html");
  await mcpPage.getByText("查询能力模板", { exact: true }).click();
  await mcpPage.getByRole("menuitem", { name: "关系库查询", exact: true }).click();
  await mcpPage
    .getByPlaceholder("搜索名称、描述、实现步骤、工作台步骤、标签或数据源")
    .fill(updatedTitle);
  const gridRow = mcpPage.locator(".el-table__row").filter({ hasText: updatedTitle }).first();
  await gridRow.waitFor({ timeout: 60000 });
  await gridRow.getByText(changedCategory.name, { exact: true }).waitFor();
  await mcpPage.screenshot({ path: path.join(out, "mcp-synchronized.png"), fullPage: true });
  await Promise.all(pending);
  save("actual-ui-requests", records);
  save("verdict", {
    status: "PASS",
    authoritativeCategories: true,
    technicalDetailsInitiallyCollapsed: true,
    technicalTypography: typography,
    publishActionAlwaysInFooter: true,
    firstPublishActions: records.map((r) => r.path.split("/").at(-1)),
    publishedMetadataEditable: true,
    sameToolAndSqlContract: true,
    mcpCategoryName: changedCategory.name,
    offlineRepublish: true,
    stableToolName: restored.publication.toolName,
    releaseVersions: [
      initial.publication.version,
      updated.publication.version,
      restored.publication.version,
    ],
    scope:
      "REAL_BROWSER_UI, REAL_READ_ONLY_SQL, MCP_CATALOG_AND_RUNTIME_TOOL_METADATA; NOT_AN_AGENT_DATA_ANALYSIS_CASE",
  });
  console.log(
    JSON.stringify({
      status: "PASS",
      tool: restored.publication.toolName,
      versions: [
        initial.publication.version,
        updated.publication.version,
        restored.publication.version,
      ],
    })
  );
} catch (error) {
  await Promise.allSettled(pending);
  save("actual-ui-requests", records);
  save("failure", { status: "FAILED", message: error.message });
  throw error;
} finally {
  await browser.close();
}
