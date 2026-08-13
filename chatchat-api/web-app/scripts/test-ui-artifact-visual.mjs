import { access, mkdir } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { chromium } from "playwright-core";
import { createServer } from "vite";

const browserCandidates = process.platform === "win32"
  ? [
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
      "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
      "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
    ]
  : [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH, "/usr/bin/google-chrome", "/usr/bin/chromium"];

async function firstBrowser() {
  for (const candidate of browserCandidates.filter(Boolean)) {
    try {
      await access(candidate);
      return candidate;
    } catch {
      // Try the next supported browser location.
    }
  }
  throw new Error("未找到 Chromium。请设置 PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH。");
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const server = await createServer({ server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
let browser;
try {
  await server.listen();
  const address = server.httpServer.address();
  const port = typeof address === "object" ? address.port : 5173;
  browser = await chromium.launch({ executablePath: await firstBrowser(), headless: true });
  const page = await browser.newPage({ viewport: { width: 760, height: 1000 }, deviceScaleFactor: 1 });
  await page.goto(`http://127.0.0.1:${port}/tests/ui-artifact-regression.html`, { waitUntil: "networkidle" });

  assert(await page.locator(".regression-case").count() === 5, "回归夹具数量不完整");
  assert(await page.locator("table").count() === 6, "所有输入都应形成可用表格");
  assert(await page.locator(".query-result-chart-button").count() === 7, "单表及多表可视化入口数量不正确");
  assert(await page.locator("text=|---|---|---:|").count() === 0, "旧管道表格仍显示为原始文本");

  const leadingZero = await page.locator('[data-case="markdown-holdings"] .query-result-chart-button').evaluate((button) => {
    const payload = JSON.parse(decodeURIComponent(button.dataset.resultChartPayload));
    return payload.rows[0]["证券代码"];
  });
  assert(leadingZero === "000155", "证券代码前导零在可视化数据中丢失");

  const wideLayout = await page.locator('[data-case="markdown-server"] .query-result-table-card').evaluate((card) => ({
    scrollable: card.scrollWidth > card.clientWidth,
    headerWhiteSpace: getComputedStyle(card.querySelector("th")).whiteSpace,
    cardBounds: card.getBoundingClientRect().toJSON(),
    buttonBounds: card.querySelector(".query-result-chart-button").getBoundingClientRect().toJSON()
  }));
  assert(wideLayout.scrollable, "宽表没有形成横向滚动区域");
  assert(wideLayout.headerWhiteSpace === "nowrap", "宽表标题仍会被逐字挤压");
  assert(wideLayout.buttonBounds.width > 0, "图形分析按钮不可见");
  assert(wideLayout.buttonBounds.right <= wideLayout.cardBounds.right, "图形分析按钮被宽表推到了可视区域之外");

  const outputDirectory = path.resolve("test-results");
  await mkdir(outputDirectory, { recursive: true });
  await page.screenshot({ path: path.join(outputDirectory, "ui-artifact-regression.png"), fullPage: true });
  process.stdout.write("UI artifact visual regression passed: test-results/ui-artifact-regression.png\n");
} finally {
  await browser?.close();
  await server.close();
}
