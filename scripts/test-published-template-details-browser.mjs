import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
const base = process.env.CHATCHAT_STAGE_URL || "http://192.168.195.221:18100";
const out = "docs/published-template-details-20261023";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const checks = {},
  errors = [];
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: "zh-CN",
  });
  const login = await (
    await context.request.post(base + "/api/v1/enterprise/auth/login", {
      data: JSON.parse(readFileSync("target/workspace-stage-auth-private.json", "utf8")),
    })
  ).json();
  assert.ok(login.data?.token);
  await context.addInitScript(
    (session) => localStorage.setItem("chatchat.auth.session", JSON.stringify(session)),
    login.data
  );
  const api = async (path) => {
    const response = await context.request.get(base + "/api/v1" + path, {
      headers: { Authorization: "Bearer " + login.data.token, "X-Workspace-Id": "zhice" },
    });
    const data = await response.json();
    return { status: response.status(), ...data };
  };
  const before = await api("/mcp/tool-cards?pageSize=100&sourceType=mcp");
  assert.equal(before.code, 200);
  const cards = before.data.tools;
  assert.ok(cards.length);
  const results = [];
  for (const card of cards) {
    const result = await api(
      "/mcp/published-tool-templates?toolName=" + encodeURIComponent(card.localToolName)
    );
    assert.equal(result.code, 200, JSON.stringify(result));
    assert.ok(Array.isArray(result.data));
    assert.ok(result.data.length <= card.templateCount);
    assert.equal(new Set(result.data.map((item) => item.key)).size, result.data.length);
    for (const item of result.data) {
      assert.deepEqual(
        Object.keys(item).sort(),
        ["key", "title", "description", "category", "content"].sort()
      );
      assert.ok(item.title);
    }
    results.push({
      name: card.localToolName,
      count: result.data.length,
      withContent: result.data.filter((row) => row.content).length,
      rows: result.data,
    });
  }
  checks.allPublishedToolsReadWithoutTechnicalFields = results.map(({ rows, ...rest }) => rest);
  assert.ok(
    results.some((result) => result.count > 0),
    "Expected at least one authorized template"
  );
  const unauthorized = await api("/mcp/published-tool-templates?toolName=raw_execute");
  assert.notEqual(unauthorized.code, 200);
  checks.arbitraryToolRejected = true;
  const target = results.reduce((a, b) => (a.count > b.count ? a : b));
  const card = cards.find((card) => card.localToolName === target.name);
  const page = await context.newPage();
  page.setDefaultTimeout(45000);
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(base + "/#/w/zhice/mcp");
  const filtered = page.waitForResponse(
    (response) => response.url().includes("/mcp/tool-cards?") && response.url().includes("keyword=")
  );
  await page.getByPlaceholder("搜索工具名称或用途").fill(card.chineseAlias || card.displayName);
  await filtered;
  await page.getByRole("button", { name: "查看用途", exact: true }).first().click();
  const dialog = page.getByRole("dialog", { name: "工具详情与授权模板" });
  await dialog.locator(".mcp-authorized-template").first().waitFor();
  assert.equal(await dialog.getByText("调用信息", { exact: false }).count(), 0);
  assert.equal(await dialog.locator(".mcp-authorized-template").count(), Math.min(6, target.count));
  if (target.count > 6) {
    await dialog.getByRole("button", { name: "下一页", exact: true }).click();
    assert.equal(
      await dialog.locator(".mcp-authorized-template").count(),
      Math.min(6, target.count - 6)
    );
    checks.pagination = true;
  }
  const search = dialog.getByRole("searchbox", { name: "搜索授权模板" });
  await search.fill("__no_match_template__");
  await dialog.getByText("未找到匹配的模板，请调整搜索内容。").waitFor();
  await search.fill(target.rows[0].title);
  await dialog.locator(".mcp-authorized-template").first().waitFor();
  await dialog.locator(".mcp-authorized-template summary").first().click();
  if (target.rows[0].content)
    assert.equal(
      await dialog.locator(".mcp-template-content pre").first().textContent(),
      target.rows[0].content
    );
  checks.contentSearchAndEmptyState = true;
  await page.screenshot({ path: out + "/desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: out + "/mobile.png", fullPage: true });
  assert.ok(await dialog.evaluate((el) => el.getBoundingClientRect().width <= window.innerWidth));
  checks.mobileFits = true;
  await dialog.getByRole("button", { name: "关闭", exact: true }).click();
  await dialog.waitFor({ state: "detached" });
  checks.close = true;
  assert.deepEqual(errors, []);
  checks.noBrowserErrors = true;
  const after = await api("/mcp/tool-cards?pageSize=100&sourceType=mcp");
  assert.equal(after.data.total, before.data.total);
  checks.readOnly = true;
} finally {
  writeFileSync(out + "/acceptance.json", JSON.stringify({ checks, errors }, null, 2));
  await browser.close();
}
