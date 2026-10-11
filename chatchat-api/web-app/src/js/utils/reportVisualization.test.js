// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";
import MarkdownIt from "markdown-it";
import { splitReportVisualizations } from "./reportVisualization.js";
import { reportVisualizationLimits } from './visualizationCapabilities.js';
import ReportMarkdown from "../../components/ReportMarkdown.vue";
import ChatMessageList from "../../components/ChatMessageList.vue";
import VisualizationRenderer from "../components/VisualizationRenderer.js";

vi.mock("../../components/VisualizationRenderer.vue", () => ({ default: {
  props: ["spec"], setup: (props) => () => h("div", { class: "test-chart" }, JSON.stringify(props.spec))
} }));
let app;
afterEach(() => { app?.unmount(); document.body.innerHTML = ""; });
const spec = {
  schemaVersion: "visualization_spec.v2", type: "chart", chartType: "bar", title: "返回样本", scope: "仅对应返回记录",
  dataset: { sourceRef: "returned:1", xKey: "category", series: [{ name: "amount", yKey: "amount" }],
    rows: [{ category: "A", amount: -3 }, { category: "B", amount: 20 }] }
};
const fence = (value = spec) => '```json\n' + JSON.stringify({ visualizationSpec: value }) + '\n```';
const report = () => '# 报告\n\n结论段落。\n\n' + fence() + '\n\n后续行动。';
const render = (text) => new MarkdownIt({ html: false }).render(text);

describe("inline report visualizations", () => {
  it('does not guess report graphics from Markdown tables', () => {
    const source = '## Comparison\n\n| Period | Value |\n|---|---:|\n| **A** | 12 |\n| B | 3 |';
    expect(splitReportVisualizations(source)).toEqual([{ type: 'markdown', content: source }]);
  });
  it('renders only Runtime verified ReportBlocks with stable preference slots', () => {
    const block = { id: 'comparison', schemaVersion: 'report_block.v1', type: 'chart', chartType: 'bar',
      datasetRef: 'returned:1', conclusion: 'B exceeds A', validationStatus: 'VERIFIED_SOURCE_DATA',
      visualizationSpec: { ...spec, validationStatus: 'VERIFIED_SOURCE_DATA' } };
    const source = 'Conclusion\n\n```json\n' + JSON.stringify({ reportBlock: block }) + '\n```';
    expect(splitReportVisualizations(source)[1]).toMatchObject({ type: 'visualization', slot: 'block:comparison', block });
    block.validationStatus = 'REQUESTED';
    const unaudited = '```json\n' + JSON.stringify({ reportBlock: block }) + '\n```';
    expect(splitReportVisualizations(unaudited)[0].type).toBe('visualization-summary');
  });
  it('does not infer charts from identifiers, missing measures or qualitative conclusions', () => {
    for (const table of ['| 对象 | id |\n|---|---|\n| A | 1 |\n| B | 2 |',
      '| 对象 | 数量 |\n|---|---|\n| A | 1 |\n| B | - |',
      '| 维度 | 结论 |\n|---|---|\n| A | 改善 |\n| B | 稳定 |']) {
      expect(splitReportVisualizations('## 结论\n\n' + table).filter(p => p.type === 'visualization')).toHaveLength(0);
    }
  });
  it("preserves compiled ranking orientation and verified units", () => {
    const compiled = { ...spec, orientation: "horizontal", validationStatus: "VERIFIED_SOURCE_DATA",
      dataset: { ...spec.dataset, series: [{ name: "amount", yKey: "amount", unit: "CNY" }] } };
    const parts = splitReportVisualizations(fence(compiled));
    expect(parts[0].spec.orientation).toBe("horizontal");
    expect(parts[0].spec.dataset.series[0].unit).toBe("CNY");
  });
  it("does not compile unaudited model intent in the browser", () => {
    const text = '```json:visualization\n{"intent":"ranking","datasetRef":"returned:1","x":"category","y":"amount"}\n```';
    expect(splitReportVisualizations(text)).toEqual([{ type: "markdown", content: text }]);
  });
  it('presents direct table declarations as readable summaries without inventing source rows', async () => {
    const declaration = { id: 'table:603383_recent_quotes', type: 'table', chartType: 'table',
      datasetRef: 'mcp_chatchat_mcp_server_web_search', title: '近期关键行情快照',
      conclusion: '成交额显著放大', reason: '根据行情快照对比', encoding: { columns: ['trade_date', 'close'] } };
    for (const language of ['text', 'json', 'json:report-block', '']) {
      const source = `前文\n\n\`\`\`${language}\n${JSON.stringify(declaration)}\n\`\`\`\n\n后文`;
      const parts = splitReportVisualizations(source);
      expect(parts.map(part => part.type)).toEqual(['markdown', 'visualization-summary', 'markdown']);
      expect(parts[1].block.title).toBe(declaration.title);
      expect(JSON.stringify(parts)).not.toContain('datasetRef');
    }
    expect(splitReportVisualizations(JSON.stringify(declaration))[0].type).toBe('visualization-summary');
    const root = document.createElement('div'); document.body.append(root);
    app = createApp(ReportMarkdown, { content: '```text\n' + JSON.stringify(declaration) + '\n```', renderMarkdown: render });
    app.mount(root); await nextTick();
    expect(root.textContent).toContain(declaration.title);
    expect(root.textContent).toContain(declaration.conclusion);
    expect(root.textContent).toContain('缺少可用的已校验数据');
    expect(root.textContent).not.toContain('datasetRef');
    expect(root.querySelector('pre')).toBeNull();
  });
  it('does not leak declarations beyond the inline chart count limit', () => {
    const source = Array.from({ length: reportVisualizationLimits.maxBlocks + 2 }, () => fence()).join('\n\n');
    const parts = splitReportVisualizations(source);
    expect(parts.filter(part => part.type === 'visualization')).toHaveLength(reportVisualizationLimits.maxBlocks);
    expect(parts.filter(part => part.type === 'visualization-summary')).toHaveLength(2);
    expect(parts.filter(part => part.type === 'markdown').some(part => part.content.includes('visualizationSpec'))).toBe(false);
  });
  it.each([
    ['warehouse:stock', 'dataset:warehouse', '库存变化', 'table'],
    ['service:latency', 'dataset:service', '服务延迟', 'line'],
    ['experiment:result', 'dataset:experiment', '实验结果', 'scatter'],
    ['custom:result', 'arbitrary:source', '自定义分析', 'future-renderer']
  ])('handles declarations using their protocol rather than business identity: %s', (id, datasetRef, title, chartType) => {
    const declaration = { id, datasetRef, title, chartType, conclusion: `${title}结论`, reason: '模型自主选择展示' };
    const parts = splitReportVisualizations('```text\n' + JSON.stringify(declaration) + '\n```');
    expect(parts).toHaveLength(1);
    expect(parts[0]).toMatchObject({ type: 'visualization-summary', block: { title, conclusion: declaration.conclusion } });
    expect(parts[0]).not.toHaveProperty('spec');
  });
  it("preserves document order and parses multiple chart types", () => {
    const parts = splitReportVisualizations(report());
    expect(parts.map((part) => part.type)).toEqual(["markdown", "visualization", "markdown"]);
    expect(parts[1].spec.dataset.rows[0].amount).toBe(-3);
    for (const chartType of ["line", "pie", "scatter", "kpi"]) {
      expect(splitReportVisualizations(fence({ ...spec, chartType }))[0].type).toBe("visualization");
    }
  });
  it("keeps malformed JSON, incomplete fences and nested code examples visible", () => {
    for (const source of ['```json\n{"visualizationSpec":', '````markdown\n' + fence() + '\n````',
      '```json\n{"hello":42}\n```', fence().replace(/```$/, "")]) {
      expect(splitReportVisualizations(source)).toEqual([{ type: "markdown", content: source }]);
    }
  });
  it("renders prose and charts together without exposing the JSON", async () => {
    const root = document.createElement("div"); document.body.append(root);
    app = createApp(ReportMarkdown, { content: report(), renderMarkdown: render });
    app.mount(root); await nextTick();
    expect(root.querySelectorAll(".test-chart")).toHaveLength(1);
    expect(root.textContent).toContain("结论段落。");
    expect(root.textContent).toContain("后续行动。");
    expect(root.querySelector("pre")).toBeNull();
  });
  it("uses the inline path in the actual chat view", async () => {
    const root = document.createElement("div"); document.body.append(root);
    app = createApp(ChatMessageList, { messages: [{ id: "report-1", role: "assistant", content: report() }] });
    app.mount(root); await nextTick();
    expect(root.querySelectorAll(".report-inline-visualization")).toHaveLength(1);
    expect(root.textContent.match(/后续行动。/g)).toHaveLength(1);
  });
  it("honors explicit KPI values and labels even with original source rows", () => {
    const normalized = VisualizationRenderer.computed.normalizedSpec.call({ spec: { ...spec, type: "metric", chartType: "kpi",
      metrics: [{ label: "可用资金", value: 42 }] } });
    expect(normalized.metrics).toEqual([{ label: "可用资金", value: 42, unit: "" }]);
  });
});
