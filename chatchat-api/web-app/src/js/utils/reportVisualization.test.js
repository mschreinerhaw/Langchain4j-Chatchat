// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";
import MarkdownIt from "markdown-it";
import { splitReportVisualizations } from "./reportVisualization.js";
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
    expect(splitReportVisualizations(unaudited)).toEqual([{ type: 'markdown', content: unaudited }]);
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
