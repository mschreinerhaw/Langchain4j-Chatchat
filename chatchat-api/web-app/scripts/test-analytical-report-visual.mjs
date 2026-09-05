import { access, mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import assert from 'node:assert/strict';

const candidates = [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/chromium'];
let executablePath;
for (const candidate of candidates.filter(Boolean)) {
  try { await access(candidate); executablePath = candidate; break; } catch { /* try next installed browser */ }
}
if (!executablePath) throw new Error('Chromium executable required');
const rows = [{ entity: '产品 A', value: '20.500' }, { entity: '产品 B', value: '10.125' }];
const report = { schemaVersion: 'analytical_report.v1', decisionQuestion: '测试样例：产品存量如何分布？', blocks: [{
  id: 'F1', section: 'CORE', question: '产品之间存在规模差异', observation: '样例中，产品 A 的存量高于产品 B。',
  interpretation: '这是同一观测日的存量比较，无法据此推断净流入。', implication: '后续可按规模分层观察产品表现。',
  confidence: '已核对计算结果', caveats: ['演示数据，仅用于验证报告编排。'],
  data: { title: '样本规模', metric: '30.625', metricUnit: '万份', unit: '万份', rows, scope: '两个产品的测试样本' },
  visualization: { type: 'chart', chartType: 'bar', orientation: 'horizontal', title: '产品规模比较（测试数据）',
    dataset: { columns: ['entity', 'value'], xKey: 'entity', series: [{ name: '规模', yKey: 'value', unit: '万份' }], rows } },
  presentation: { primaryPresentation: 'CHART', primaryConclusion: true, showKeyMetrics: true, showDataTable: true, validationStatus: 'VERIFIED_DATA_BOUND' },
  evidence: [{ artifactId: 'fact-1', text: '产品规模计算结果', sourceScope: '测试数据集', recordRefs: ['sample.records[1]', 'sample.records[2]'], supportingValues: ['20.500', '10.125'] }]
}] };
const manifest = { schemaVersion: 'enterprise_ui_artifact_v1', spec: { root: 'report', elements: {
  report: { type: 'Report', props: {}, children: ['insights'] },
  insights: { type: 'AnalyticalReport', props: { resourceId: 'analytical-report' }, children: [] }
} }, resources: { 'analytical-report': {} } };
const server = await createServer({ server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.route(/\/api\/v1\/ui-artifacts\/test-report(?:\/.*)?$/, route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(route.request().url().includes('/resources/') ? report : manifest)
  }));
  await mkdir('../../../.codex-build/analytical-report', { recursive: true });
  for (const [name, width] of [['desktop', 1280], ['mobile', 390]]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/tests/analytical-report.html`, { waitUntil: 'networkidle' });
    await page.locator('canvas').waitFor({ state: 'visible' });
    const option = await page.evaluate(() => window.chartOption());
    assert.equal(option.xAxis[0].type, 'value'); assert.equal(option.yAxis[0].type, 'category');
    assert.deepEqual(option.series[0].data.map(point => point.value), [20.5, 10.125]);
    assert.equal(await page.locator('.insight-data tbody tr').count(), 2);
    assert.equal(await page.locator('.insight-data tbody tr').first().textContent(), '产品 A20.500');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Report overflows viewport');
    await page.locator('.insight-evidence summary').click();
    await page.screenshot({ path: `../../../.codex-build/analytical-report/${name}.png`, fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log('Structured report visual checks passed: artifact load, horizontal chart, exact table values, evidence, desktop/mobile.');
} finally { await browser?.close(); await server.close(); }
