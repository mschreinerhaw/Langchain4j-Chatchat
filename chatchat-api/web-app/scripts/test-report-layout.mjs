import { createServer } from "vite";
import { chromium } from "playwright-core";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const output = resolve("../../target/report-layout-browser");
await mkdir(output, { recursive: true });
const server = await createServer({ server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
const errors = [],
  external = [],
  cases = [];
let browser;
try {
  await server.listen();
  const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
  browser = await chromium.launch({
    executablePath:
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ||
      "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    headless: true,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (
      !request.url().startsWith(origin) &&
      !request.url().startsWith("blob:") &&
      !request.url().startsWith("data:")
    )
      external.push(request.url());
  });
  await page.route("**/api/v1/interactions/user-assets/fixture-*/content", async (route) => {
    const id = route.request().url().split("/").at(-2).slice("fixture-".length);
    const figure = await page.evaluate(
      (id) => window.layoutCases.find((entry) => entry.id === id).figure,
      id
    );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(figure),
    });
  });
  await page.goto(origin + "/tests/report-layout.html");
  await page.waitForFunction(() => window.layoutCases);
  await page.getByRole("switch", { name: "图文报告", exact: true }).click();
  await page.locator(".report-layout-settings summary").click();
  await page.getByLabel("报告排版策略").selectOption("concise");
  await page.getByRole("button", { name: "发送问题" }).click();
  assert.equal(
    (await page.evaluate(() => window.layoutSends[0])).reportPresentation.strategy,
    "concise"
  );
  await page.reload();
  await page.waitForFunction(() => window.layoutCases);
  await page.getByRole("switch", { name: "图文报告", exact: true }).click();
  await page.locator(".report-layout-settings summary").click();
  assert.equal(await page.getByLabel("报告排版策略").inputValue(), "concise");
  await page.locator(".report-layout-settings summary").click();
  for (const index of [0, 1, 2, 3, 4, 5]) {
    const started = performance.now();
    const test = await page.evaluate((index) => {
      const entry = window.layoutCases[index];
      window.layoutState.report = entry.report;
      return {
        id: entry.id,
        expected: entry.report.layout.blocks.map((block) => block.type),
        charts: entry.report.charts.length,
        metrics: entry.report.metrics.length,
        expectedY:entry.figure?.data[0].y || entry.figure?.data[0].values,
      };
    }, index);
    const report = page.locator(".report-renderer");
    await report.getByRole("heading", { level: 2 }).waitFor();
    const order = await report
      .locator("[data-block-type]")
      .evaluateAll((nodes) => nodes.map((node) => node.dataset.blockType));
    assert.deepEqual(order.slice(0, test.expected.length), test.expected);
    assert.equal(await report.locator(".python-report-metrics").count(), test.metrics);
    assert.equal(await report.locator(".python-report-chart").count(), test.charts);
    for (const chart of await report.locator(".python-report-chart").all()) {
      await chart.scrollIntoViewIfNeeded();
      await chart.locator("iframe[data-pdf-image]").waitFor({ timeout: 30000 });
      const frame=await (await chart.locator('iframe').elementHandle()).contentFrame();
      assert.deepEqual(await frame.evaluate(()=>document.getElementById('plot').data[0].y || document.getElementById('plot').data[0].values),test.expectedY);
    }
    await report.screenshot({ path: resolve(output, test.id + "-desktop.png") });
    const before = JSON.stringify(order);
    await page.evaluate(() => {
      window.layoutState.report = JSON.parse(JSON.stringify(window.layoutState.report));
    });
    assert.equal(
      JSON.stringify(
        await report
          .locator("[data-block-type]")
          .evaluateAll((nodes) => nodes.map((node) => node.dataset.blockType))
      ),
      before
    );
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await report.screenshot({ path: resolve(output, test.id + "-mobile.png") });
    await page.setViewportSize({ width: 1440, height: 1000 });
    cases.push({ ...test, order, durationMs: Math.round(performance.now() - started) });
  }
  await page.setViewportSize({width:390,height:844});
  await page.locator('.report-layout-settings summary').click();
  await page.getByLabel('报告排版策略').selectOption('template');
  await page.getByLabel('报告模板').selectOption('evidence-first');
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  assert.equal(await page.locator('.report-renderer').getAttribute('data-layout-strategy'),'auto');
  await page.screenshot({path:resolve(output,'settings-mobile.png'),fullPage:true});
  await page.locator('.report-layout-settings summary').click();
  await page.setViewportSize({width:1440,height:1000});
  await page.evaluate(() => {
    const base = window.layoutCases[5].report;
    window.layoutState.report = {
      ...base,
      sections: Array.from({ length: 5 }, (_, i) => ({
        id: "s" + i,
        title: "章节 " + (i + 1),
        narrative: "受控样本的章节内容。".repeat(20),
      })),
      layout: undefined,
    };
  });
  await page.locator(".report-contents").waitFor();
  const hash = new URL(page.url()).hash;
  await page.locator(".report-contents a").last().click();
  assert.equal(new URL(page.url()).hash, hash);
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  const result = {
    scope: "CONTROLLED_BUSINESS_FIXTURES_WITH_REAL_VUE_AND_PLOTLY; NOT_PROVIDER_OR_CLINE_E2E",
    cases,
    preferencesPersisted: true,
    futurePreferencesDoNotChangeArchivedLayout:true,
    mobileSettingsNoOverflow:true,
    historyLayoutStable: true,
    chapterNavigationPreservesRoute: true,
    mobileNoOverflow: true,
    errors,
    external,
  };
  await writeFile(resolve(output, "acceptance.json"), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally {
  await browser?.close();
  await server.close();
}
