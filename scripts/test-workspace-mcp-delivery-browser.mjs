import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
const base = process.env.CHATCHAT_STAGE_URL || "http://127.0.0.1:5174";
const upstream = "http://192.168.195.221:18100";
const output = process.env.CHATCHAT_BROWSER_OUTPUT || "target/workspace-mcp-local";
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  locale: "zh-CN",
});
const page = await context.newPage();
page.setDefaultTimeout(30000);
const errors = [],
  checks = {};
page.on("pageerror", (e) => errors.push(e.message));
try {
  const credentials = JSON.parse(
    readFileSync(
      process.env.CHATCHAT_CREDENTIAL_FILE || "target/workspace-stage-auth-private.json",
      "utf8"
    )
  );
  const r = await context.request.post(upstream + "/api/v1/enterprise/auth/login", {
    data: credentials,
  });
  const login = await r.json();
  assert.ok(login.data?.token, "Login failed");
  await context.addInitScript(
    (s) => localStorage.setItem("chatchat.auth.session", JSON.stringify(s)),
    login.data
  );
  if (base !== upstream)
    await page.route("**/api/**", async (route) => {
      const u = new URL(route.request().url());
      const response = await route.fetch({ url: upstream + u.pathname + u.search });
      await route.fulfill({ response });
    });
  await page.goto(base + "/#/w/zhice/workspaces");
  await page.locator(".workspace-settings-card").first().waitFor();
  const cards = page.locator(".workspace-settings-card");
  checks.realWorkspaceCount = await cards.count();
  assert.ok(checks.realWorkspaceCount >= 4);
  assert.equal(await page.locator(".workspace-settings table").count(), 0);
  checks.cardColumns = await page
    .locator(".workspace-settings-grid")
    .evaluate((e) => getComputedStyle(e).gridTemplateColumns);
  assert.equal(checks.cardColumns.split(" ").length, 3);
  await page.getByPlaceholder("搜索工作空间名称、说明或标识").fill("不存在的空间");
  await page.getByText("没有匹配的工作空间，请调整关键词或状态。").waitFor();
  await page.getByPlaceholder("搜索工作空间名称、说明或标识").fill("");
  await cards.first().waitFor();
  await page.screenshot({ path: output + "/workspaces-desktop.png", fullPage: true });
  const real = (
    await (
      await context.request.get(upstream + "/api/v1/enterprise/workspaces", {
        headers: { Authorization: "Bearer " + login.data.token },
      })
    ).json()
  ).data;
  await page.route("**/api/v1/enterprise/workspaces", async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    await route.fulfill({
      json: {
        code: 200,
        data: Array.from({ length: 14 }, (_, i) => ({
          ...real[0],
          manifest: { ...real[0].manifest, workspaceId: "preview-" + i, name: "分页验证 " + i },
          status: i === 13 ? "disabled" : "enabled",
        })),
      },
    });
  });
  await page.locator(".workspace-refresh").click();
  await page.getByText("共 14 个空间，找到 14 个").waitFor();
  assert.equal(await cards.count(), 6);
  await page.locator(".workspace-catalog").getByRole("button", { name: "下一页" }).click();
  await page.getByText("第 2 / 3 页").waitFor();
  await page.locator(".workspace-catalog").getByRole("button", { name: "下一页" }).click();
  await page.getByText("第 3 / 3 页").waitFor();
  assert.equal(await cards.count(), 2);
  assert.equal(await cards.last().getByRole("button", { name: "进入工作空间" }).isDisabled(), true);
  checks.paginationAndDisabledEntry = true;
  await page.unroute("**/api/v1/enterprise/workspaces");
  await page.locator(".workspace-refresh").click();
  await page.getByText("第 1 / 1 页").waitFor();
  const target = cards.filter({
    has: page.getByRole("heading", { name: "数据车间", exact: true }),
  });
  await target.getByRole("button", { name: "进入工作空间" }).click();
  await page.waitForURL("**/#/w/data-science/**");
  checks.enterWorkspaceWorks = true;
  await page.goto(base + "/#/w/zhice/mcp");
  await page.locator(".mcp-tool-card").first().waitFor();
  const heading = page.locator(".mcp-page-heading");
  const refresh = heading.getByRole("button", { name: "刷新", exact: true });
  const hb = await page.locator(".mcp-tools").boundingBox(),
    rb = await refresh.boundingBox();
  assert.ok(Math.abs(hb.x + hb.width - rb.x - rb.width) < 3);
  checks.refreshAlignedRight = true;
  assert.equal(await page.locator(".mcp-operations").evaluate((e) => e.open), false);
  assert.equal(
    await page.locator(".mcp-operations").evaluate((e) => e.nextElementSibling.className),
    "mcp-tools"
  );
  checks.operationsAtTopCollapsed = true;
  await page.screenshot({ path: output + "/mcp-desktop.png", fullPage: true });
  await page.locator(".mcp-operations summary").click();
  const sync = page.getByRole("button", { name: "同步连接", exact: true });
  assert.equal(await sync.evaluate((e) => getComputedStyle(e).display), "inline-flex");
  await page.locator(".mcp-operations summary").click();
  await page.getByRole("button", { name: "查看用途" }).first().click();
  await page.getByRole("dialog", { name: "工具用途" }).waitFor();
  await page.getByText("调用信息（高级）", { exact: true }).click();
  assert.equal(
    await page.locator(".mcp-tool-detail-panel").evaluate((e) => e.scrollWidth <= e.clientWidth),
    true
  );
  assert.ok(
    ["inline-flex", "flex"].includes(
      await page.locator(".mcp-detail-close").evaluate((e) => getComputedStyle(e).display)
    )
  );
  await page.screenshot({ path: output + "/mcp-detail.png" });
  await page.keyboard.press("Escape");
  assert.equal(await page.getByRole("dialog", { name: "工具用途" }).count(), 0);
  checks.detailStylesAndEscape = true;
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "查看用途" }).first().click();
  await page.getByText("调用信息（高级）", { exact: true }).click();
  assert.equal(
    await page.locator(".mcp-tool-detail-panel").evaluate((e) => e.scrollWidth <= e.clientWidth),
    true
  );
  assert.equal(
    await page.locator(".mcp-detail-close").evaluate((e) => getComputedStyle(e).whiteSpace),
    "nowrap"
  );
  assert.equal(
    await page.locator(".mcp-call-details pre").evaluate((e) => e.scrollWidth <= e.clientWidth),
    true
  );
  assert.ok(
    await page
      .locator(".mcp-call-details")
      .evaluate((e) => e.getBoundingClientRect().right <= window.innerWidth)
  );
  await page.screenshot({ path: output + "/mcp-mobile.png" });
  await page.keyboard.press("Escape");
  await page.goto(base + "/#/w/zhice/workspaces");
  await cards.first().waitFor();
  assert.equal(
    (
      await page
        .locator(".workspace-settings-grid")
        .evaluate((e) => getComputedStyle(e).gridTemplateColumns)
    ).split(" ").length,
    1
  );
  checks.mobileSingleColumn = true;
  await page.screenshot({ path: output + "/workspaces-mobile.png", fullPage: true });
  assert.deepEqual(errors, []);
  writeFileSync(
    output + "/acceptance.json",
    JSON.stringify({ status: "PASSED", checks, errors }, null, 2)
  );
  console.log(JSON.stringify({ status: "PASSED", checks }));
} catch (e) {
  await page.screenshot({ path: output + "/failure.png", fullPage: true }).catch(() => {});
  writeFileSync(
    output + "/failure.json",
    JSON.stringify({ error: String(e), checks, errors }, null, 2)
  );
  throw e;
} finally {
  await browser.close();
}
