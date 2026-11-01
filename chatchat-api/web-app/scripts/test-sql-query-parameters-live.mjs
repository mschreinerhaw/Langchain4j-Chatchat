/** Real SQL Studio UI: opening/cancelling never queries; published template tests preserve the release. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const { E2E_ORIGIN: origin, E2E_AUTH_FILE: authFile, E2E_OUTPUT: out } = process.env;
assert.ok(origin && authFile && out);
fs.mkdirSync(out, { recursive: true });
assert.ok(!fs.existsSync(path.join(out, "verdict.json")));
const state = JSON.parse(
  fs.readFileSync("../../docs/studio-template-query-collection/studio/state.json")
);
const auth = JSON.parse(fs.readFileSync(authFile));
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
async function api(route) {
  const r = await fetch(origin + "/api/v1" + route, {
    headers: { Authorization: "Bearer " + auth.token, "X-Workspace-Id": "data-science" },
  });
  const j = await r.json();
  assert.ok(r.ok && j.code === 200, j.message);
  return j.data;
}
const historyRoute =
  "/data-science/sql/projects/" +
  state.project.id +
  "/files/" +
  state.detail.file.id +
  "/template-publications";
const before = await api(historyRoute);
save("history-before", before);
const browser = await chromium.launch({
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
});
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(
    (a) => localStorage.setItem("chatchat.auth.session", JSON.stringify(a)),
    auth
  );
  const page = await context.newPage();
  let queries = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname.endsWith("/test")) queries++;
  });
  await page.goto(origin + "/index.html#/w/data-science/sqlStudio");
  await page.getByLabel("SQL 工程", { exact: true }).selectOption(state.project.id);
  await page.locator(".sql-tree-file").getByTitle(state.detail.file.path, { exact: true }).click();
  await page.getByRole("button", { name: "模板管理与发布", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "发布 MCP SQL 模板" });
  await panel.getByRole("button", { name: "测试查询", exact: true }).waitFor();
  await panel.getByRole("button", { name: "测试查询", exact: true }).scrollIntoViewIfNeeded();
  assert.equal(await panel.locator('[aria-label="模板测试参数"]').count(), 0);
  await page.screenshot({ path: path.join(out, "published-query-test-button.png") });
  await panel.getByRole("button", { name: "测试查询", exact: true }).click();
  let dialog = page.getByRole("dialog", { name: "填写测试参数", exact: true });
  await dialog.waitFor();
  assert.equal(queries, 0);
  assert.equal(await dialog.getByLabel("min_area", { exact: true }).inputValue(), "0");
  await page.screenshot({ path: path.join(out, "parameter-dialog.png") });
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  assert.equal(queries, 0);
  const receipts = [];
  for (const [threshold, count] of [
    [294242, 3],
    [0, 4],
  ]) {
    await panel.getByRole("button", { name: "测试查询", exact: true }).click();
    await dialog.getByLabel("min_area", { exact: true }).fill(String(threshold));
    const pending = page.waitForResponse(
      (r) => r.request().method() === "POST" && new URL(r.url()).pathname.endsWith("/test")
    );
    await dialog.getByRole("button", { name: "执行测试", exact: true }).click();
    const response = await pending;
    const envelope = await response.json();
    assert.equal(envelope.code, 200, envelope.message);
    const row = envelope.data;
    assert.equal(row.state, "PUBLISHED");
    assert.equal(row.active, true);
    assert.equal(row.testEvidence.success, true);
    assert.equal(row.testEvidence.rowCount, count);
    assert.equal(row.testEvidence.parameters.min_area, threshold);
    const ids = row.testEvidence.preview.rows.map((r) => r.w_warehouse_sk);
    assert.deepEqual(ids, threshold ? [1, 3, 4] : [1, 2, 3, 4]);
    await dialog.waitFor({ state: "hidden" });
    await panel.getByLabel("真实查询结果预览").waitFor();
    await page.screenshot({ path: path.join(out, "query-result-" + threshold + ".png") });
    receipts.push({
      parameters: response.request().postDataJSON().parameters,
      httpStatus: response.status(),
      response: row,
    });
  }
  save("real-ui-query-receipts", receipts);
  const after = await api(historyRoute);
  save("history-after", after);
  assert.deepEqual(after, before, "Testing must not modify published history or saved evidence");
  await panel.getByRole("button", { name: "关闭发布模板", exact: true }).click();
  await page.locator(".sql-tree-file").getByTitle(state.summary.file.path, { exact: true }).click();
  await page.getByRole("button", { name: "模板管理与发布", exact: true }).click();
  await panel.getByRole("button", { name: "测试查询", exact: true }).click();
  await dialog.waitFor();
  assert.match(await dialog.innerText(), /此查询无需参数/);
  assert.equal(queries, 2);
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  save("verdict", {
    status: "PASS",
    scope:
      "REAL_UI_PUBLISHED_QUERY_PARAMETER_DIALOG_CANCEL_AND_EXECUTE; real 248 TPCDS queries; online release and persisted evidence unchanged",
    queryRequests: queries,
    cancelRequests: 0,
    thresholds: [294242, 0],
    returnedRows: [3, 4],
    publishedHistoryUnchanged: true,
    capturedAt: new Date().toISOString(),
  });
  console.log({ status: "PASS", queryRequests: queries });
} finally {
  await browser.close();
}
