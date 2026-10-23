import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import assert from "node:assert/strict";
const base = process.env.CHATCHAT_STAGE_URL || "http://192.168.195.221:18100";
const out = process.env.CHATCHAT_BROWSER_OUTPUT || "docs/resource-catalog-20261023";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: "zh-CN",
  });
  const login = await (
    await context.request.post(base + "/api/v1/enterprise/auth/login", {
      data: JSON.parse(
        readFileSync(
          process.env.CHATCHAT_CREDENTIAL_FILE || "target/workspace-stage-auth-private.json",
          "utf8"
        )
      ),
    })
  ).json();
  assert.ok(login.data?.token);
  const policy = async () => {
    const response = await context.request.get(base + "/api/v1/permission-center", {
      headers: { Authorization: "Bearer " + login.data.token, "X-Workspace-Id": "permissions" },
    });
    assert.equal(response.status(), 200);
    const { grants, resourceGrants, roleAuthorizations } = (await response.json()).data;
    return { grants, resourceGrants, roleAuthorizations };
  };
  const before = await policy();
  await context.addInitScript(
    (session) => localStorage.setItem("chatchat.auth.session", JSON.stringify(session)),
    login.data
  );
  const page = await context.newPage(),
    errors = [],
    writes = [],
    checks = {};
  page.setDefaultTimeout(45000);
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (
      /\/api\/v1\/(permission-center|enterprise\/resource-grants)/.test(request.url()) &&
      !["GET", "OPTIONS"].includes(request.method())
    )
      writes.push(request.method() + " " + new URL(request.url()).pathname);
  });
  await page.goto(base + "/#/w/permissions/systemResources");
  await page
    .locator('.permission-resource-mode .permission-master-detail[aria-busy="false"]')
    .waitFor();
  const master = page.locator(".permission-master"),
    items = master.locator(".permission-master-item"),
    heading = page.locator(".permission-detail h2").first();
  assert.ok((await items.count()) <= 6);
  assert.equal(await page.getByLabel("资源类型", { exact: true }).inputValue(), "");
  assert.equal(await heading.innerText(), await items.first().locator("strong").innerText());
  assert.ok(!(await items.allTextContents()).some((text) => text.includes("业务模板")));
  checks.defaultShowsCommonResources = true;
  await page.screenshot({ path: out + "/common-resources.png" });
  await page.getByLabel("资源类型", { exact: true }).selectOption("template");
  await page.waitForFunction(() =>
    document.querySelector(".permission-detail-heading > div > p")?.textContent.includes("业务模板")
  );
  assert.equal(await items.count(), 6);
  assert.equal(await items.locator("small").count(), 0);
  const template = await items.first().locator("strong").innerText();
  assert.equal(await items.first().locator("strong").getAttribute("title"), template);
  assert.equal(
    await items
      .first()
      .locator("strong")
      .evaluate((e) => getComputedStyle(e).whiteSpace),
    "nowrap"
  );
  assert.equal(
    await master.locator(".permission-master-list").evaluate((e) => getComputedStyle(e).overflowY),
    "visible"
  );
  assert.equal(await heading.innerText(), template);
  assert.equal(
    await page.getByText("保存后生效，操作记录可在安全审计中查看。", { exact: true }).count(),
    1
  );
  await page.screenshot({ path: out + "/business-templates.png" });
  checks.sixCompactTemplateRowsWithoutInnerScrolling = true;
  await master.getByRole("button", { name: "下一页", exact: true }).click();
  assert.match(await page.getByLabel("当前资源范围", { exact: true }).innerText(), /^7–12/);
  assert.notEqual(await items.first().locator("strong").innerText(), template);
  assert.equal(await heading.innerText(), await items.first().locator("strong").innerText());
  checks.pagingKeepsDetailsAligned = true;
  await page.getByLabel("搜索资源", { exact: true }).fill(template);
  await page.waitForFunction(
    (name) => document.querySelector(".permission-detail h2")?.textContent === name,
    template
  );
  assert.match(await page.getByLabel("当前资源范围", { exact: true }).innerText(), /^1–/);
  await page.getByLabel("搜索资源", { exact: true }).fill("不存在的模板_验收_29641");
  await page.waitForFunction(() => !document.querySelector(".permission-master-item"));
  assert.equal(await page.locator(".permission-resource-editor").count(), 0);
  checks.emptySearchClearsStaleDetails = true;
  await page.getByLabel("搜索资源", { exact: true }).fill("");
  await page.getByLabel("资源类型", { exact: true }).selectOption("");
  await page.getByLabel("搜索资源", { exact: true }).fill(template);
  await page.waitForFunction(
    (name) => document.querySelector(".permission-detail h2")?.textContent === name,
    template
  );
  checks.defaultSearchStillFindsBusinessTemplates = true;
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: out + "/resource-catalog-mobile.png" });
  checks.smallScreenDoesNotOverflow = true;
  assert.deepEqual(await policy(), before);
  assert.deepEqual(writes, []);
  assert.deepEqual(errors, []);
  checks.existingAuthorizationUnchanged = true;
  writeFileSync(
    out + "/browser-verification.json",
    JSON.stringify({ checks, consoleErrors: errors, permissionWrites: writes }, null, 2)
  );
  console.log(JSON.stringify(checks));
} finally {
  await browser.close();
}
