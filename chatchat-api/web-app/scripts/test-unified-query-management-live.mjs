/** Real management UI; only this session's acceptance template metadata is changed then restored. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const {
  E2E_MCP_ORIGIN: origin,
  E2E_MCP_AUTH_FILE: authFile,
  E2E_AUTH_FILE: runtimeAuthFile,
  E2E_OUTPUT: out,
} = process.env;
assert.ok(origin && authFile && runtimeAuthFile && out);
fs.mkdirSync(out, { recursive: true });
assert.ok(!fs.existsSync(path.join(out, "verdict.json")));
const auth = JSON.parse(fs.readFileSync(authFile)),
  runtime = JSON.parse(fs.readFileSync(runtimeAuthFile));
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
async function api(route, body) {
  const r = await fetch(origin + "/api/v1" + route, {
    method: body ? "POST" : "GET",
    headers: { Authorization: "Bearer " + auth.token, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json();
  assert.ok(r.ok && j.code === 200, route + ": " + j.message);
  return j.data;
}
const rows = await api("/database-query/studio-publications"),
  studio = rows.find((r) => r.title === "仓库地区分布与面积汇总" && r.enabled),
  area = rows.find((r) => r.title === "仓库面积明细查询" && r.enabled),
  manual = (await api("/database-query")).find(
    (r) =>
      r.title &&
      r.enabled &&
      r.businessScope &&
      r.description &&
      r.implementationSteps &&
      r.domain &&
      r.sqlSteps?.length
  );
assert.ok(studio && area && manual, "Require existing acceptance Studio and valid manual fixtures");
const original = await api("/database-query/studio-publications/" + studio.templateId);
save("studio-before", original);
let changed = false;
const runtimeRoute =
  "/data-science/sql/projects/" +
  original.source.projectId +
  "/files/" +
  original.source.fileId +
  "/template-publications";
async function runtimeHistory() {
  const r = await fetch("http://192.168.195.221:18100/api/v1" + runtimeRoute, {
    headers: { Authorization: "Bearer " + runtime.token, "X-Workspace-Id": "data-science" },
  });
  const j = await r.json();
  assert.equal(j.code, 200, j.message);
  return j.data;
}
async function boundRevisions() {
  const r = await fetch(
    "http://192.168.195.221:18100/api/v1/tool-publications/capabilities/templates",
    { headers: { Authorization: "Bearer " + runtime.token, "X-Workspace-Id": "mcp-publication" } }
  );
  const j = await r.json();
  assert.equal(j.code, 200, j.message);
  return j.data
    .filter((t) => t.templateKey === "sql_datasource:" + original.code)
    .map((t) => ({ key: t.key, revision: t.revision }))
    .sort((a, b) => a.key.localeCompare(b.key));
}
const boundBefore = await boundRevisions();
assert.ok(boundBefore.length);
save("bound-revisions-before", boundBefore);
const browser = await chromium.launch({
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
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
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(origin + "/admin/index.html");
  await page.getByText("查询能力模板", { exact: true }).click();
  await page.getByRole("menuitem", { name: "关系库查询", exact: true }).click();
  const search = page.getByPlaceholder("搜索模板名称、描述或数据源");
  await search.waitFor();
  const form = page.getByRole("dialog").filter({ has: page.locator(".entity-form") });
  const params = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("button", { name: "发送请求", exact: true }) });
  const field = (label) =>
    form.locator(".el-form-item").filter({
      has: page.locator(".el-form-item__label").filter({ hasText: new RegExp("^" + label + "$") }),
    });
  async function open(row) {
    await search.fill(row.title);
    const tableRow = page
      .locator(".catalog-table .el-table__row")
      .filter({ hasText: row.title })
      .first();
    await tableRow.getByRole("button", { name: "编辑", exact: true }).click();
    await form.waitFor();
    await field("显示名称").locator("input").waitFor();
    return tableRow;
  }
  const shared = [];
  for (const [source, row] of [
    ["manual", manual],
    ["studio", studio],
  ]) {
    await open(row);
    const title = await form.locator(".modal-panel-header-title").innerText();
    const labels = await form.locator(".el-form-item__label").allTextContents();
    for (const label of ["模板来源", "显示名称", "工具描述", "能力分类", "数据源资产"])
      assert.ok(
        labels.some((s) => s.trim() === label),
        label
      );
    shared.push({ source, title, width: Math.round((await form.boundingBox()).width), labels });
    await page.screenshot({ path: path.join(out, source + "-shared-form.png") });
    if (source === "manual") {
      await form.getByRole("button", { name: "测试调用", exact: true }).click();
      await params.waitFor();
      await page.screenshot({ path: path.join(out, "manual-shared-parameters.png") });
      await params.getByRole("button", { name: "取消", exact: true }).click();
    }
    await form.getByRole("button", { name: "取消", exact: true }).click();
  }
  assert.equal(shared[0].title, shared[1].title);
  assert.equal(shared[0].width, shared[1].width);
  save("shared-form-comparison", shared);
  await open(area);
  await form.getByRole("button", { name: "测试调用", exact: true }).click();
  await params.waitFor();
  await params.locator('input[role="spinbutton"]').fill("294242");
  await page.screenshot({ path: path.join(out, "studio-shared-parameters.png") });
  const tested = page.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      new URL(r.url()).pathname.endsWith("/studio-publications/" + area.templateId + "/test")
  );
  await params.getByRole("button", { name: "发送请求", exact: true }).click();
  const tr = await tested,
    tj = await tr.json();
  assert.equal(tj.code, 200, tj.message);
  assert.equal(tj.data.testEvidence.success, true);
  assert.equal(tj.data.testEvidence.parameters.min_area, 294242);
  assert.equal(tj.data.testEvidence.rowCount, 3);
  save("studio-real-query", { request: tr.request().postDataJSON(), response: tj.data });
  await form
    .locator(".database-preview-summary")
    .getByText("合计返回 3 行", { exact: true })
    .waitFor();
  await page.screenshot({ path: path.join(out, "studio-shared-query-result.png") });
  await form.getByRole("button", { name: "取消", exact: true }).click();
  async function editDescription(text, suffix) {
    await open(studio);
    await field("工具描述").locator("textarea").fill(text);
    const pending = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        new URL(r.url()).pathname.endsWith(
          "/studio-publications/" + studio.templateId + "/metadata"
        )
    );
    await form.getByRole("button", { name: "保存", exact: true }).click();
    await params.waitFor();
    await params.getByRole("button", { name: "发送请求", exact: true }).click();
    const response = await pending,
      j = await response.json();
    save("metadata-" + suffix, {
      request: response.request().postDataJSON(),
      httpStatus: response.status(),
      response: j,
    });
    assert.equal(j.code, 200, j.message);
    await form.waitFor({ state: "hidden" });
    return j.data;
  }
  const description = original.description + "（统一页面同步验收，随后恢复）";
  changed = true;
  await editDescription(description, "changed");
  const during = await api("/database-query/studio-publications/" + studio.templateId);
  assert.equal(during.description, description);
  const rh = await runtimeHistory();
  assert.equal(rh.find((r) => r.id === studio.templateId).description, description);
  save("runtime-history-during-update", rh);
  await editDescription(original.description, "restored");
  changed = false;
  const restored = await api("/database-query/studio-publications/" + studio.templateId);
  save("studio-restored", restored);
  assert.equal(restored.description, original.description);
  for (const key of [
    "code",
    "sqlTemplate",
    "parameterSchema",
    "datasourceId",
    "title",
    "capabilityCategory",
  ])
    assert.deepEqual(restored[key], original[key], key);
  assert.ok(restored.enabled);
  save("runtime-history-after-restore", await runtimeHistory());
  const boundAfter = await boundRevisions();
  save("bound-revisions-after", boundAfter);
  assert.deepEqual(
    boundAfter,
    boundBefore,
    "Restoring business metadata must restore the existing bound execution revision"
  );
  assert.deepEqual(errors, []);
  save("verdict", {
    status: "PASS",
    scope:
      "REAL_UI_SHARED_MANUAL_STUDIO_FORM_AND_PARAMETER_DIALOG; real parameterized 248 TPCDS query; metadata changed through UI and synchronized to Studio then restored",
    sharedTitle: shared[0].title,
    sharedWidth: shared[0].width,
    queryRows: 3,
    metadataSynchronized: true,
    originalBusinessInformationRestored: true,
    manualRecordUnmodified: true,
    browserErrors: errors,
    capturedAt: new Date().toISOString(),
  });
  console.log({ status: "PASS", rows: 3, metadataRestored: true });
} finally {
  if (changed) {
    const latest = await api("/database-query/studio-publications/" + studio.templateId);
    await api("/database-query/studio-publications/" + studio.templateId + "/metadata", {
      expectedVersion: latest.expectedVersion,
      title: original.title,
      description: original.description,
      category: original.capabilityCategory,
      parameters: {},
      testId: "restore-" + Date.now(),
      expectedActiveReleaseId: latest.publicationId,
    });
    save("failure-restoration", { status: "RESTORED_ORIGINAL_DESCRIPTION" });
  }
  await browser.close();
}
