import MarkdownIt from "markdown-it";
import { createApp } from "vue";
import "../src/styles/base.css";
import "../src/styles/pages/chat-assistant.css";
import { enhanceResultTables } from "../src/js/utils/resultTableEnhancer.js";
import { normalizeArtifactHtml } from "../src/js/utils/artifactHtmlNormalizer.js";
import { stripInternalDocumentRefs } from "../src/js/utils/internalDocumentRefs.js";
import { normalizeMarkdownTables } from "../src/js/utils/markdownTableNormalizer.js";
import VisualizationRenderer from "../src/components/VisualizationRenderer.vue";

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true });
const render = (source) => markdown.render(source);
const renderReport = (source) => enhanceResultTables(render(normalizeMarkdownTables(stripInternalDocumentRefs(source))));

const holdings = [
  "### 持仓明细：宽表与前导零",
  "",
  "返回 3 条持仓，最新市值合计 **45,554.00**，当日盈亏合计 **+822.61**。",
  "| 证券代码 | 证券名称 | 数量 | 最新市值 | 当日盈亏 | 累计盈亏 | 成本价 | 摊薄成本价 |",
  "| 000155 | 川能动力 | 800 | 9,808.00 | 0.00 | +1,033.50 | 11.8154 | 10.5236 |",
  "| 600693 | 东百集团 | 2600 | 24,544.00 | +749.61 | +5,229.59 | 8.9391 | 6.9277 |",
  "| 002389 | 航天彩虹 | 600 | 11,202.00 | +73.00 | 0.00 | 18.5483 | 18.5483 |"
].join("\n");

const servers = [
  "### 服务器配置：长说明与横向滚动",
  "",
  "| 节点类型 | CPU | 内存 | OS盘大小 | 数据分区磁盘 | 数量 | 最小数据分区盘容量 | 说明 |",
  "|---|---:|---:|---:|---|---:|---|---|",
  "| 任务调度服务器节点（虚拟机） | 2×12cores | 64GB | 500GB | 无 | 3 | / | 分布式任务调度服务与 ZooKeeper，提交任务到集群 |",
  "| 中间件服务器（虚拟机含主备） | 24cores | 32GB | 300GB | 无 | 2 | / | STUDIO、Nginx，主备两套切换 |"
].join("\n");

const multi = [
  "### 多数据集",
  "",
  "#### 生产环境", "", "| 节点 | 数量 |", "|---|---:|", "| API | 3 |", "| 调度器 | 2 |", "",
  "#### 测试环境", "", "| 节点 | 数量 |", "|---|---:|", "| API | 1 |", "| 调度器 | 1 |"
].join("\n");

const legacyHtml = "<h3>旧 HTML：原生表格</h3><table><thead><tr><th>字段</th><th>数值</th></tr></thead>"
  + "<tbody><tr><td>RQ</td><td>20260731</td></tr><tr><td>KHH</td><td>070200046604</td></tr>"
  + "<tr><td>ZZC</td><td>847,174.25</td></tr></tbody></table>";

const brokenHtml = "<h3>旧 HTML：未解析管道表格</h3><p>| 证券代码 | 证券名称 | 数量 |<br>"
  + "|---|---|---:|<br>| 000609 | ST中迪 | 2300 |<br>| 002389 | 航天彩虹 | 600 |</p>";

const historicalFields = [
  "### 历史记录：缺失分隔行的两列表",
  "来自模板 `livedata_cx_mncg_khzc_r` 的返回记录：",
  "| 返回字段 | 返回值 |",
  "| RQ | 20260731 |",
  "| KHH | 070200046604 |",
  "| ZZC | 847174.25 |",
  "| ZQSZ | 846262.20 |",
  "| ZJYE | 912.05 |",
  "| DRYK | 42263.81 |"
].join("\n");

const professionalReport = [
  "# 客户资产分析报告",
  "",
  "## 一、核心结论",
  "报告正文保留 **重要结论**、层级标题、列表、引用和代码片段。",
  "> 风险提示与证据链是辅助信息，不抢占正文视觉层级。",
  "",
  "- 资产数据已完成一致性核验",
  "- 内部定位符 doc://20260804_5eee01fd#chunk=0 与 records[1…2] 不应展示",
  "",
  "```text",
  "| 管道代码示例 | 保持原样 |",
  "| A | B |",
  "```"
].join("\n");

const edgeValues = [
  "### 空值、百分比与涨跌状态",
  "",
  "| 名称 | 当日盈亏 | 占比 | 备注 |",
  "|---|---:|---:|---|",
  "| 产品 A | +1,020.50 | 12.5% | - |",
  "| 产品 B | -80.00 | N/A | 暂无 |",
  "| 产品 C | 0 | 0% | null |"
].join("\n");

const cases = [
  ["markdown-holdings", "历史 Markdown 宽表（缺失分隔行）", renderReport(holdings)],
  ["markdown-server", "Markdown 配置表", renderReport(servers)],
  ["markdown-multi", "Markdown 多表", renderReport(multi)],
  ["historical-fields", "历史 Markdown 两列表（缺失分隔行）", renderReport(historicalFields)],
  ["legacy-native", "旧 Artifact 原生 HTML", normalizeArtifactHtml(legacyHtml, render)],
  ["legacy-pipe", "旧 Artifact 管道文本", normalizeArtifactHtml(brokenHtml, render)],
  ["professional-report", "专业报告层级与内部标识清理", renderReport(professionalReport)],
  ["edge-values", "空值 / 异常值 / 涨跌色", renderReport(edgeValues)]
];

document.querySelector("#app").innerHTML = `
  <header class="regression-header">
    <p>UI ARTIFACT REGRESSION MATRIX</p>
    <h1>动态报告显示回归矩阵</h1>
    <span>Markdown / HTML / 宽表 / 多表 / 旧数据兼容</span>
  </header>
  ${cases.map(([id, label, html]) => `
    <section class="regression-case" data-case="${id}">
      <div class="regression-case-label">${label}</div>
      <article class="message-markdown artifact-markdown">${html}</article>
    </section>
  `).join("")}
  <section class="regression-case" data-case="supporting-evidence">
    <div class="regression-case-label">辅助证据链（默认折叠）</div>
    <article class="evidence-demo">
      <details class="artifact-notice">
        <summary><strong>证据摘要</strong><span>展开查看</span></summary>
        <div class="artifact-notice-content">这部分是灰色小字号辅助信息。</div>
      </details>
      <details class="artifact-evidence">
        <summary><h4>引用与证据</h4><span>3 条</span></summary>
        <ol><li>生产配置文档</li><li>测试配置文档</li><li>部署说明</li></ol>
      </details>
    </article>
  </section>
  <section class="regression-case" data-case="semantic-trend-chart">
    <div class="regression-case-label">金融语义趋势图</div>
    <div id="trend-chart"></div>
  </section>
  <section class="regression-case chart-gallery" data-case="bar-chart"><div class="regression-case-label">正负区间柱状图</div><div id="bar-chart"></div></section>
  <section class="regression-case chart-gallery" data-case="pie-chart"><div class="regression-case-label">分类占比饼图</div><div id="pie-chart"></div></section>
  <section class="regression-case chart-gallery" data-case="scatter-chart"><div class="regression-case-label">风险收益散点图</div><div id="scatter-chart"></div></section>
  <section class="regression-case chart-gallery" data-case="metrics"><div class="regression-case-label">关键指标卡</div><div id="metrics"></div></section>
  <section class="regression-case" data-case="panel"><div class="regression-case-label">组合分析面板</div><div id="panel"></div></section>
`;

function mountVisualization(selector, spec) {
  createApp(VisualizationRenderer, { spec }).mount(selector);
}

const trendRows = [
  { 日期: "08-01", 当日盈亏: -320 },
  { 日期: "08-02", 当日盈亏: -80 },
  { 日期: "08-03", 当日盈亏: 0 },
  { 日期: "08-04", 当日盈亏: 180 },
  { 日期: "08-05", 当日盈亏: 420 },
  { 日期: "08-06", 当日盈亏: -120 }
];

mountVisualization("#trend-chart", {
    version: "v1",
    type: "chart",
    chartType: "line",
    title: "近六期当日盈亏走势",
    dataset: {
      columns: ["日期", "当日盈亏"],
      xKey: "日期",
      series: [{ name: "当日盈亏", yKey: "当日盈亏" }],
      rows: trendRows
    },
    insight: { summary: "收益跨越零轴，红色为正收益，绿色为负收益。", trend: "先回升后回落" },
    ui: { defaultView: "chart", allowSwitch: true }
});

mountVisualization("#bar-chart", {
  version: "v1", type: "chart", chartType: "bar", title: "产品当日盈亏对比",
  dataset: { columns: ["产品", "当日盈亏"], xKey: "产品", series: [{ name: "当日盈亏", yKey: "当日盈亏" }], rows: [
    { 产品: "产品 A", 当日盈亏: 1200 }, { 产品: "产品 B", 当日盈亏: -680 }, { 产品: "产品 C", 当日盈亏: 0 }
  ] }, ui: { defaultView: "chart" }
});

mountVisualization("#pie-chart", {
  version: "v1", type: "chart", chartType: "pie", title: "资产类别占比",
  dataset: { columns: ["类别", "市值"], xKey: "类别", series: [{ name: "市值", yKey: "市值" }], rows: [
    { 类别: "股票", 市值: 62 }, { 类别: "基金", 市值: 25 }, { 类别: "现金", 市值: 13 }
  ] }, ui: { defaultView: "chart" }
});

mountVisualization("#scatter-chart", {
  version: "v1", type: "chart", chartType: "scatter", title: "风险收益分布",
  dataset: { columns: ["波动率", "累计收益"], xKey: "波动率", series: [{ name: "累计收益", yKey: "累计收益" }], rows: [
    { 波动率: 4.2, 累计收益: 8.1 }, { 波动率: 8.5, 累计收益: -2.4 }, { 波动率: 12.1, 累计收益: 15.6 }
  ] }, ui: { defaultView: "chart" }
});

mountVisualization("#metrics", {
  version: "v1", type: "metrics", title: "资产关键指标",
  metrics: [{ label: "总资产", value: "847,174.25", unit: "元" }, { label: "当日盈亏", value: "+42,263.81", unit: "元" }, { label: "持仓数量", value: 20, unit: "只" }]
});

mountVisualization("#panel", {
  version: "v2", type: "panel", title: "客户资产驾驶舱", analysisType: "组合分析", layout: "grid",
  blocks: [
    { id: "overview", type: "metrics", title: "资产概览", spec: { type: "metrics", title: "资产概览", metrics: [{ label: "净资产", value: "847,174.25", unit: "元" }] } },
    { id: "trend", type: "chart", title: "收益走势", spec: { type: "chart", chartType: "line", title: "收益走势", dataset: { columns: ["日期", "当日盈亏"], xKey: "日期", series: [{ name: "当日盈亏", yKey: "当日盈亏" }], rows: trendRows } } }
  ], insight: { summary: "指标与趋势在同一面板呈现。" }
});

const style = document.createElement("style");
style.textContent = `
  body { margin: 0; background: #eef2f7; color: #172033; }
  #app { width: min(1180px, calc(100% - 40px)); margin: 0 auto; padding: 32px 0 64px; }
  .regression-header { margin-bottom: 22px; }
  .regression-header p, .regression-header span { color: #667085; font-size: 12px; }
  .regression-header h1 { margin: 5px 0; font-size: 26px; }
  .regression-case { margin: 18px 0; padding: 22px; border: 1px solid #dbe3ef; border-radius: 14px; background: #fff; box-shadow: 0 8px 24px rgba(16, 24, 40, .05); }
  .regression-case-label { margin-bottom: 12px; color: #667085; font-size: 12px; font-weight: 700; letter-spacing: .04em; }
  .regression-case h3, .regression-case h4 { margin: 0 0 12px; color: #172033; }
  .evidence-demo { display: grid; gap: 12px; }
  .evidence-demo details { border: 1px solid #dbe3ef; border-radius: 12px; background: #f8fafc; color: #667085; font-size: 12px; }
  .evidence-demo summary { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; cursor: pointer; list-style: none; }
  .evidence-demo summary h4, .evidence-demo summary strong { margin: 0; color: #475467; font-size: 13px; }
  .evidence-demo ol, .artifact-notice-content { margin: 0; padding: 0 32px 16px; color: #667085; font-size: 12px; line-height: 1.65; }
  .chart-gallery { min-height: 430px; }
  @media (max-width: 520px) {
    #app { width: calc(100% - 20px); padding-top: 16px; }
    .regression-case { padding: 14px; border-radius: 10px; }
    .regression-header h1 { font-size: 22px; }
  }
`;
document.head.append(style);
