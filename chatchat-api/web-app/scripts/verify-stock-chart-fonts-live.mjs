// One real deployed stock analysis. No API fixtures or replacement market data.
import { chromium } from "playwright-core";
import { readFile, writeFile, mkdir, access } from "node:fs/promises";
import { resolve, basename } from "node:path";
import assert from "node:assert/strict";

const [authFile, base, output, existingConversationId] = process.argv.slice(2);
if (!output) throw new Error("AUTH_FILE BASE_URL OUTPUT required");
const auth = JSON.parse(await readFile(authFile, "utf8"));
const stockNames = (process.env.STOCK_NAMES || "九阳股份,雪龙集团,兴业股份")
  .split(",")
  .filter(Boolean);
const analysisQuery =
  process.env.STOCK_ANALYSIS_QUERY ||
  "九阳股份、雪龙集团与兴业股份三只股票，从当前市场行情层面进行一次分析，生成图文报告。请明确实际行情日期、可用数据范围和缺失项。";
await mkdir(output, { recursive: true });
let executablePath;
for (const path of [
  process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/chromium",
].filter(Boolean)) {
  try {
    await access(path);
    executablePath = path;
    break;
  } catch {}
}
const browser = await chromium.launch({ executablePath, headless: true });
const errors = [],
  failures = [],
  submissions = [];
let page;
try {
  page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push({ path: new URL(response.url()).pathname, status: response.status() });
  });
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      /\/(agent\/tasks|interactions\/chat|user-assets\/analyses)$/.test(
        new URL(request.url()).pathname
      )
    ) {
      submissions.push(request.postDataJSON());
      writeFile(
        resolve(output, "requests-private.json"),
        JSON.stringify(submissions, null, 2)
      ).catch(() => {});
    }
  });
  await page.addInitScript((value) => {
    if (window === window.top) localStorage.setItem("chatchat.auth.session", JSON.stringify(value));
  }, auth);
  await page.goto(base + "/#/w/zhice/chat", { waitUntil: "domcontentloaded" });
  const composer = page.locator(".prompt-card");
  await composer.locator("textarea").waitFor({ timeout: 60000 });
  const toggle = composer.getByRole("switch", { name: "图文报告" });
  if (existingConversationId) {
    const response = await page.request.get(
      base + `/api/v1/conversations/user/${auth.user.id}/summaries?limit=100`,
      { headers: { Authorization: "Bearer " + auth.token } }
    );
    assert.equal(response.status(), 200);
    const summary = (await response.json()).data.find(
      (item) => item.id === existingConversationId || item.conversationId === existingConversationId
    );
    assert.ok(summary, "Real report must exist in authenticated history");
    await page.getByPlaceholder("搜索历史会话", { exact: true }).fill(summary.title.slice(0, 24));
    const created = new Date(summary.createdAt).toISOString();
    await page
      .locator(".recent-card")
      .filter({ has: page.locator(`time[datetime="${created}"]`) })
      .click();
  } else {
    await composer.locator(".agent-picker select").selectOption("financial_data_analysis");
    await toggle.click();
    assert.equal(await toggle.getAttribute("aria-checked"), "true");
    for (const file of JSON.parse(process.env.STOCK_INPUT_FILES || "[]")) {
      const bytes = await readFile(file);
      const name = basename(file);
      await composer.locator("textarea").evaluate(
        (element, value) => {
          const data = new DataTransfer();
          data.items.add(new File([new Uint8Array(value.bytes)], value.name, { type: "text/csv" }));
          element.dispatchEvent(
            new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true })
          );
        },
        { bytes: [...bytes], name }
      );
      await composer
        .getByRole("listitem")
        .filter({ hasText: name })
        .getByText(/已保存到用户空间/)
        .waitFor({ timeout: 30000 });
    }
    await composer.locator("textarea").fill(analysisQuery);
    await composer.getByRole("button", { name: "发送问题", exact: true }).click();
    console.log("Submitted real stock analysis: " + stockNames.join(", "));
  }
  const report = page.locator(".python-visual-report").last();
  await report.waitFor({ timeout: existingConversationId ? 60000 : 1200000 });
  if (existingConversationId) console.log("Restored real stock report from authenticated history");
  const images = report.locator(".python-report-chart img");
  await report.locator(".python-report-chart").first().scrollIntoViewIfNeeded();
  await report
    .locator(".python-report-chart img, .python-report-chart iframe")
    .first()
    .waitFor({ timeout: 120000 });
  await page.waitForFunction(
    () => !document.querySelector(".prompt-card textarea")?.disabled,
    {},
    { timeout: 120000 }
  );
  const reportText = await report.innerText();
  assert.ok(
    ["SUCCESS", "PARTIAL"].includes(await report.getAttribute("data-status")),
    "Report must retain an available analysis result"
  );
  for (const name of stockNames)
    assert.ok(reportText.includes(name), name + " missing from report");
  assert.equal(await report.locator(".report-code,.report-provenance").count(), 0);
  assert.equal(await report.locator(".report-full-text").count(), 0);
  assert.ok(!reportText.includes("完整分析正文") && !reportText.includes("参考正文"));
  if (!existingConversationId)
    assert.equal(
      await toggle.getAttribute("aria-checked"),
      "true",
      "Report mode must remain enabled after the real request"
    );
  const message = report
    .locator(
      'xpath=ancestor::*[contains(concat(" ",normalize-space(@class)," ")," chat-message ")]'
    )
    .first();
  const messageText = await message.innerText();
  assert.ok(!messageText.includes("完整分析正文") && !messageText.includes("参考正文"));
  const downloaded = page.waitForEvent("download", { timeout: 120000 });
  await message.locator('button[title="导出当前回答为PDF"]').click();
  await (await downloaded).saveAs(resolve(output, "stock-report.pdf"));
  const pdf = await readFile(resolve(output, "stock-report.pdf"));
  assert.equal(pdf.subarray(0, 5).toString(), "%PDF-");
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  const { createCanvas } = await import("@napi-rs/canvas");
  const document = await pdfjs.getDocument({
    data: new Uint8Array(pdf),
    standardFontDataUrl: resolve("node_modules/pdfjs-dist/standard_fonts") + "/",
  }).promise;
  const first = await document.getPage(1),
    viewport = first.getViewport({ scale: 1.5 });
  const canvas = createCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height));
  await first.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
  await writeFile(resolve(output, "pdf-first-page.png"), canvas.toBuffer("image/png"));
  // Decode all images first so later image loads cannot shift screenshot coordinates.
  for (const image of await images.all()) await image.evaluate((element) => element.decode());
  // PDF export resolves lazy chart assets; now inspect and capture every static chart.
  for (let index = 0; index < (await images.count()); index++) {
    const image = images.nth(index);
    await image.scrollIntoViewIfNeeded();
    await image.evaluate((element) => element.decode());
    assert.ok(await image.evaluate((element) => element.naturalWidth > 0));
    await image.screenshot({ path: resolve(output, `stock-chart-${index + 1}.png`) });
  }
  const frames = report.locator(".python-report-chart iframe");
  for (const frame of await frames.all()) {
    assert.ok(
      await frame.evaluate((element) =>
        element.dataset.pdfImage?.startsWith("data:image/png;base64,")
      )
    );
  }
  await report.locator("h2").first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve(output, "desktop-report.png") });
  await page.setViewportSize({ width: 390, height: 900 });
  await report.locator("h2").first().scrollIntoViewIfNeeded();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: resolve(output, "mobile-report.png") });
  await writeFile(resolve(output, "report-text-private.txt"), reportText);
  if (existingConversationId) assert.equal(submissions.length, 0);
  else
    assert.ok(
      submissions.some((value) => (value.toolInput?.reportMode || value.reportMode) === "visual")
    );
  assert.deepEqual(errors, []);
  assert.deepEqual(failures, []);
  const proof = {
    status: "PASSED",
    realDeployment: true,
    httpFixtures: false,
    realStockAnalysis: true,
    stockNames,
    referenceBodyHidden: true,
    staticCharts: await images.count(),
    interactiveCharts: await frames.count(),
    distinctCharts: new Set(
      await report
        .locator(".python-report-chart")
        .evaluateAll((elements) => elements.map((element) => element.dataset.chartId))
    ).size,
    reportModePreserved: existingConversationId ? undefined : true,
    historyRestored: Boolean(existingConversationId),
    analysisSubmissions: submissions.length,
    pdfBytes: pdf.length,
    pdfPages: document.numPages,
    pdfIndependentlyRendered: true,
    desktopAndMobile: true,
    pageErrors: errors,
    httpFailures: failures,
  };
  await writeFile(resolve(output, "acceptance.json"), JSON.stringify(proof, null, 2));
  console.log(JSON.stringify(proof));
} catch (error) {
  await writeFile(
    resolve(output, "failure-private.json"),
    JSON.stringify(
      {
        error: error.message,
        errors,
        failures,
        body: page ? await page.locator("body").innerText() : "",
      },
      null,
      2
    )
  );
  throw error;
} finally {
  await browser.close();
}
