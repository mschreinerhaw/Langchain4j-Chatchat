/** Real UI creation/publication of a collection containing generated SQL Studio templates. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const {
  E2E_ORIGIN: origin,
  E2E_AUTH_FILE: authFile,
  E2E_FIXTURE: fixtureFile,
  E2E_OUTPUT: out,
} = process.env;
assert.ok(origin && authFile && fixtureFile && out);
const auth = JSON.parse(fs.readFileSync(authFile)),
  f = JSON.parse(fs.readFileSync(fixtureFile));
fs.mkdirSync(out, { recursive: true });
assert.ok(!fs.existsSync(path.join(out, "published.json")), "Preserve actual publication evidence");
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
const browser = await chromium.launch({
  executablePath:
    process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
});
try {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  await context.addInitScript(
    (a) => localStorage.setItem("chatchat.auth.session", JSON.stringify(a)),
    auth
  );
  const page = await context.newPage();
  await page.goto(origin + "/index.html#/w/mcp-publication/toolPublications");
  await page
    .locator(".permission-page-heading")
    .getByRole("button", { name: "新建 MCP 工具", exact: true })
    .click();
  await page.getByPlaceholder("输入业务名称").fill(f.title);
  await page.getByPlaceholder("说明工具用途与适用场景").fill(f.description);
  await page.getByPlaceholder("例如 sales_summary，发布后保持稳定").fill(f.domainCode);
  await page.getByLabel("执行能力", { exact: true }).selectOption("database_query_template_query");
  await page.getByLabel("使用角色", { exact: true }).selectOption(f.roleId);
  const filter = page.getByPlaceholder("按模板名称筛选");
  await filter.fill("仓库");
  await page.locator(".publication-template-option").first().waitFor();
  await page
    .locator(".publication-fields")
    .filter({ hasText: "可查询的子模板集合" })
    .locator('p[role="status"]')
    .waitFor({ state: "hidden" });
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "available-studio-templates.png"),
    fullPage: true,
  });
  for (const item of f.templates) {
    await filter.fill(item.title);
    await page
      .locator(".publication-template-option input")
      .filter({ visible: true })
      .first()
      .waitFor();
    const checkbox = page.locator(".publication-template-option input").filter({ visible: true });
    assert.equal(await checkbox.count(), 1);
    assert.equal(await checkbox.getAttribute("value"), item.key);
    await checkbox.check();
  }
  await filter.fill("仓库");
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "selected-template-collection.png"),
    fullPage: true,
  });
  async function clickAction(buttonName, action, id) {
    const wait = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        new URL(r.url()).pathname ===
          "/api/v1/tool-publications" + (id ? "/" + id + "/" + action : ""),
      { timeout: 90000 }
    );
    await page.getByRole("button", { name: buttonName, exact: true }).click();
    const response = await wait,
      j = await response.json();
    save(action || "draft", {
      httpStatus: response.status(),
      request: response.request().postDataJSON(),
      response: j,
    });
    assert.ok(response.ok() && j.code === 200, j.message);
    await page
      .locator(".tool-publications")
      .getAttribute("aria-busy")
      .then(async () => {
        await page.waitForFunction(
          () => document.querySelector(".tool-publications")?.getAttribute("aria-busy") === "false"
        );
      });
    return j.data;
  }
  let release = await clickAction("保存新草稿", "draft");
  assert.equal(release.state, "DRAFT");
  await page.getByRole("button", { name: "校验定义", exact: true }).waitFor();
  release = await clickAction("校验定义", "validate", release.id);
  assert.equal(release.state, "VALIDATED");
  await page
    .getByLabel("测试输入", { exact: true })
    .fill(JSON.stringify({ query: "仓库分布与面积", limit: 20 }));
  release = await clickAction("运行查询测试", "test", release.id);
  assert.equal(release.state, "TESTED");
  save("tested-release", release);
  release = await clickAction("发布工具", "publish", release.id);
  assert.equal(release.state, "RUNNING");
  save("published", release);
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "published-collection.png"),
    fullPage: true,
  });
  save("verdict", {
    status: "PASS",
    scope: "REAL_UI_CREATE_VALIDATE_DISCOVERY_TEST_PUBLISH; SQL_EXECUTION_REQUIRES_AGENT_EVIDENCE",
    releaseId: release.id,
    toolName: f.domainCode + "_template_query",
    selectedTemplates: f.templates.map((t) => t.key),
  });
  console.log(JSON.stringify({ status: "PASS", releaseId: release.id }));
} finally {
  await browser.close();
}
