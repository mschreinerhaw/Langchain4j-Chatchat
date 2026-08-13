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
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 }, deviceScaleFactor: 1 });
  await page.goto(`http://127.0.0.1:${port}/tests/ui-artifact-regression.html`, { waitUntil: "networkidle" });
  await page.locator('[data-case="panel"] canvas').waitFor({ state: "visible" });

  assert(await page.locator(".regression-case").count() === 16, "回归夹具数量不完整");
  assert(await page.locator(".query-result-table-card:not(.query-result-multi-dataset-card) table").count() === 8, "所有可恢复输入都应形成可用表格");
  assert(await page.locator(".query-result-chart-button").count() === 9, "单表及多表可视化入口数量不正确");
  assert(await page.locator("text=|---|---|---:|").count() === 0, "旧管道表格仍显示为原始文本");
  assert(await page.locator("text=doc://20260804_5eee01fd").count() === 0, "内部文档定位符仍显示在报告中");
  assert(await page.locator("text=records[1…2]").count() === 0, "内部记录范围仍显示在报告中");
  assert(await page.locator('[data-case="professional-report"] pre code').textContent().then((text) => text.includes("| A | B |")), "代码块中的管道文本被错误转换");

  const leadingZero = await page.locator('[data-case="markdown-holdings"] .query-result-chart-button').evaluate((button) => {
    const payload = JSON.parse(decodeURIComponent(button.dataset.resultChartPayload));
    return payload.rows[0]["证券代码"];
  });
  assert(leadingZero === "000155", "证券代码前导零在可视化数据中丢失");

  const historicalAccount = await page.locator('[data-case="historical-fields"] .query-result-chart-button').evaluate((button) => {
    const payload = JSON.parse(decodeURIComponent(button.dataset.resultChartPayload));
    return payload.rows.find((row) => row["返回字段"] === "KHH")?.["返回值"];
  });
  assert(historicalAccount === "070200046604", "历史两列表未恢复或账号前导零丢失");

  const wideLayout = await page.locator('[data-case="markdown-server"] .query-result-table-card').evaluate((card) => ({
    scrollable: card.scrollWidth > card.clientWidth,
    tableWidth: card.querySelector("table").getBoundingClientRect().width,
    cardWidth: card.getBoundingClientRect().width,
    headerWhiteSpace: getComputedStyle(card.querySelector("th")).whiteSpace,
    cardBounds: card.getBoundingClientRect().toJSON(),
    buttonBounds: card.querySelector(".query-result-chart-button").getBoundingClientRect().toJSON()
  }));
  assert(wideLayout.scrollable || wideLayout.tableWidth <= wideLayout.cardWidth + 1, "桌面宽表既未适配也无法滚动");
  assert(wideLayout.headerWhiteSpace === "nowrap", "宽表标题仍会被逐字挤压");
  assert(wideLayout.buttonBounds.width > 0, "图形分析按钮不可见");
  assert(wideLayout.buttonBounds.right <= wideLayout.cardBounds.right, "图形分析按钮被宽表推到了可视区域之外");

  const evidence = page.locator('[data-case="supporting-evidence"]');
  assert(await evidence.locator("details[open]").count() === 0, "辅助证据默认应折叠");
  const evidenceFont = await evidence.locator(".artifact-evidence").evaluate((element) => ({
    color: getComputedStyle(element).color,
    size: Number.parseFloat(getComputedStyle(element).fontSize)
  }));
  assert(evidenceFont.color === "rgb(102, 112, 133)", "辅助证据未使用弱化灰色");
  assert(evidenceFont.size <= 12, "辅助证据字号没有弱化");
  await evidence.locator(".artifact-evidence > summary").click();
  assert(await evidence.locator(".artifact-evidence[open]").count() === 1, "证据链无法展开查看");

  const trendChart = page.locator('[data-case="semantic-trend-chart"]');
  assert(await trendChart.locator("canvas").count() === 1, "金融趋势图画布未渲染");
  assert(await trendChart.locator(".visualization-trend-legend").count() === 1, "涨跌颜色图例未显示");
  const trendColors = await trendChart.locator(".visualization-trend-legend").evaluate((legend) => ({
    up: getComputedStyle(legend.querySelector(".up i")).backgroundColor,
    down: getComputedStyle(legend.querySelector(".down i")).backgroundColor,
    neutral: getComputedStyle(legend.querySelector(".neutral i")).backgroundColor
  }));
  assert(trendColors.up === "rgb(229, 72, 77)", "上涨颜色不是金融红");
  assert(trendColors.down === "rgb(22, 163, 106)", "下跌颜色不是金融绿");
  assert(trendColors.neutral === "rgb(152, 162, 179)", "中性颜色不正确");

  for (const chartCase of ["semantic-trend-chart", "bar-chart", "pie-chart", "scatter-chart", "panel"]) {
    assert(await page.locator(`[data-case="${chartCase}"] canvas`).count() >= 1, `${chartCase} 图表未渲染`);
  }
  assert(await page.locator('[data-case="metrics"] .visualization-metrics article').count() === 3, "指标卡未完整显示");
  assert(await page.locator('[data-case="panel"] .visualization-panel-block').count() === 2, "组合面板区块未完整显示");

  const planDag = page.locator('[data-case="interactive-plan-dag"]');
  await planDag.locator(".vue-flow__node").first().waitFor({ state: "visible" });
  assert(await planDag.locator(".vue-flow__node").count() === 4, "计划图节点未完整渲染");
  assert(await planDag.locator(".vue-flow__edge").count() === 3, "计划图依赖边未完整渲染");
  assert(await planDag.locator(".vue-flow__controls").count() === 1, "计划图缩放控制器未显示");
  assert(await planDag.locator(".vue-flow__minimap").count() === 1, "计划图缩略导航未显示");
  const draggableNode = planDag.locator(".vue-flow__node").first();
  const nodeBeforeDrag = await draggableNode.boundingBox();
  await draggableNode.hover();
  await page.mouse.down();
  await page.mouse.move(nodeBeforeDrag.x + nodeBeforeDrag.width / 2 + 85, nodeBeforeDrag.y + nodeBeforeDrag.height / 2 + 42, { steps: 8 });
  await page.mouse.up();
  const nodeAfterDrag = await draggableNode.boundingBox();
  assert(Math.abs(nodeAfterDrag.x - nodeBeforeDrag.x) > 35 || Math.abs(nodeAfterDrag.y - nodeBeforeDrag.y) > 25, "计划图节点无法拖动");
  await planDag.getByRole("button", { name: "自动布局" }).click();
  await page.waitForTimeout(300);

  const trendTabs = trendChart.locator(".visualization-tabs");
  await trendTabs.getByRole("button", { name: "表格" }).click();
  assert(await trendChart.locator(".visualization-table tbody tr").count() === 6, "图表切换为表格后数据行丢失");
  await trendTabs.getByRole("button", { name: "原始数据" }).click();
  assert((await trendChart.locator(".visualization-raw").textContent()).includes('"当日盈亏"'), "原始数据视图内容丢失");
  await trendTabs.getByRole("button", { name: "图表" }).click();
  await trendChart.locator("canvas").waitFor({ state: "visible" });

  const outputDirectory = path.resolve("test-results");
  await mkdir(outputDirectory, { recursive: true });
  await planDag.screenshot({ path: path.join(outputDirectory, "plan-dag-interactive-desktop.png") });
  await page.screenshot({ path: path.join(outputDirectory, "ui-artifact-regression-desktop.png"), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  const mobileLayout = await page.evaluate(() => ({
    viewport: window.innerWidth,
    pageWidth: document.documentElement.scrollWidth,
    casesOutsideViewport: [...document.querySelectorAll(".regression-case")]
      .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1).length,
    invisibleChartButtons: [...document.querySelectorAll(".query-result-chart-button")]
      .filter((button) => {
        const bounds = button.getBoundingClientRect();
        return bounds.width <= 0 || bounds.height <= 0;
      }).length
  }));
  assert(mobileLayout.pageWidth <= mobileLayout.viewport + 1, "手机端发生整页横向溢出");
  assert(mobileLayout.casesOutsideViewport === 0, "手机端报告卡片超出可视区域");
  assert(mobileLayout.invisibleChartButtons === 0, "手机端图形分析入口不可见");

  const mobileWideTable = await page.locator('[data-case="markdown-server"] .query-result-table-card').evaluate((card) => ({
    scrollable: card.scrollWidth > card.clientWidth,
    buttonRight: card.querySelector(".query-result-chart-button").getBoundingClientRect().right,
    cardRight: card.getBoundingClientRect().right
  }));
  assert(mobileWideTable.scrollable, "手机端宽表没有保留独立横向滚动");
  assert(mobileWideTable.buttonRight <= mobileWideTable.cardRight + 1, "手机端图形分析入口被推出表格卡片");
  await planDag.screenshot({ path: path.join(outputDirectory, "plan-dag-interactive-mobile.png") });
  await page.screenshot({ path: path.join(outputDirectory, "ui-artifact-regression-mobile.png"), fullPage: true });

  process.stdout.write("UI artifact visual regression passed: desktop + mobile screenshots\n");
} finally {
  await browser?.close();
  await server.close();
}
