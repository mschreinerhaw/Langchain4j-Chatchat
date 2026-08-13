import MarkdownIt from "markdown-it";
import { createApp } from "vue";
import "../src/styles/base.css";
import "../src/styles/pages/chat-assistant.css";
import { enhanceResultTables } from "../src/js/utils/resultTableEnhancer.js";
import { normalizeArtifactHtml } from "../src/js/utils/artifactHtmlNormalizer.js";
import VisualizationRenderer from "../src/components/VisualizationRenderer.vue";

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true });
const render = (source) => markdown.render(source);

const holdings = [
  "### 持仓明细：宽表与前导零",
  "",
  "返回 3 条持仓，最新市值合计 **45,554.00**，当日盈亏合计 **+822.61**。",
  "| 证券代码 | 证券名称 | 数量 | 最新市值 | 当日盈亏 | 累计盈亏 | 成本价 | 摊薄成本价 |",
  "|---|---|---:|---:|---:|---:|---:|---:|",
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

const cases = [
  ["markdown-holdings", "Markdown 宽表", enhanceResultTables(render(holdings))],
  ["markdown-server", "Markdown 配置表", enhanceResultTables(render(servers))],
  ["markdown-multi", "Markdown 多表", enhanceResultTables(render(multi))],
  ["legacy-native", "旧 Artifact 原生 HTML", normalizeArtifactHtml(legacyHtml, render)],
  ["legacy-pipe", "旧 Artifact 管道文本", normalizeArtifactHtml(brokenHtml, render)]
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
  <section class="regression-case" data-case="semantic-trend-chart">
    <div class="regression-case-label">金融语义趋势图</div>
    <div id="trend-chart"></div>
  </section>
`;

createApp(VisualizationRenderer, {
  spec: {
    version: "v1",
    type: "chart",
    chartType: "line",
    title: "近六期当日盈亏走势",
    dataset: {
      columns: ["日期", "当日盈亏"],
      xKey: "日期",
      series: [{ name: "当日盈亏", yKey: "当日盈亏" }],
      rows: [
        { 日期: "08-01", 当日盈亏: -320 },
        { 日期: "08-02", 当日盈亏: -80 },
        { 日期: "08-03", 当日盈亏: 0 },
        { 日期: "08-04", 当日盈亏: 180 },
        { 日期: "08-05", 当日盈亏: 420 },
        { 日期: "08-06", 当日盈亏: -120 }
      ]
    },
    ui: { defaultView: "chart" }
  }
}).mount("#trend-chart");

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
`;
document.head.append(style);
