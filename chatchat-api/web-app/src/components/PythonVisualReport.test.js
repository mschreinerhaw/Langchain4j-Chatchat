// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { it, expect, afterEach, vi } from "vitest";
import PythonVisualReport from "./PythonVisualReport.vue";
vi.mock("./PythonReportChart.vue", () => ({
  default: {
    props: ["chart"],
    emits: ["status"],
    template:
      "<div class=\"chart-stub\" @click=\"$emit('status',{id:chart.id,error:'render failed'})\">{{chart.title}}</div>",
  },
}));
let app, host;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
it("shows structured analysis without an additional reference or full text body", async () => {
  host = document.createElement("div");
  document.body.append(host);
  app = createApp({
    render: () =>
      h(PythonVisualReport, {
        report: {
          status: "SUCCESS",
          title: "顶点软件行情分析",
          sections: [{ title: "融资买入结论", narrative: "按实际融资数据判断异常买入。" }],
          markdown: "duplicate-reference-body",
        },
        fallbackMarkdown: "duplicate-fallback-body",
      }),
  });
  app.mount(host);
  await nextTick();
  expect(host.textContent).toContain("融资买入结论");
  expect(host.textContent).toContain("按实际融资数据判断异常买入。");
  for (const text of [
    "完整分析正文",
    "参考正文",
    "duplicate-reference-body",
    "duplicate-fallback-body",
  ])
    expect(host.textContent).not.toContain(text);
  expect(host.querySelector(".report-full-text")).toBeNull();
});
it("preserves exact calculated values, displays partial failures and retains valid text", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const props = reactive({ report: null, fallbackMarkdown: "Existing text answer" });
  app = createApp({ render: () => h(PythonVisualReport, props) });
  app.mount(host);
  await nextTick();
  expect(host.textContent).toContain("正在整理");
  props.report = {
    schemaVersion: "python_visual_report.v1",
    status: "PARTIAL",
    title: "Actual analysis",
    summary: "Computed summary",
    metrics: [{ id: "total", label: "Total", value: "9007199254740993.01", unit: "units" }],
    tables: [
      { id: "table", title: "Computed rows", columns: ["value"], rows: [["9007199254740993.01"]] },
    ],
    charts: [{ id: "chart", title: "Actual PNG" }],
    validation: { warnings: ["Plotly dependency missing"] },
  };
  await nextTick();
  expect(host.querySelector("dd").textContent).toContain("9007199254740993.01");
  expect(host.querySelector("td").textContent).toBe("9007199254740993.01");
  expect(host.textContent).toContain("部分完成");
  expect(host.textContent).toContain("Existing text answer");
  expect(host.textContent).not.toContain("Plotly dependency missing");
  props.report = {
    status: "FAILED",
    title: "Failed",
    validation: { warnings: ["Python execution failed"] },
  };
  await nextTick();
  expect(host.textContent).toContain("暂不可用");
  expect(host.textContent).not.toContain("Python execution failed");
  expect(host.textContent).toContain("Existing text answer");
  props.report = { status: "NO_DATA", title: "Empty", markdown: "No records were available" };
  await nextTick();
  expect(host.textContent).toContain("没有可分析数据");
});
it("marks a delivery partial when a chart cannot render without rewriting immutable execution facts", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const report = {
    status: "SUCCESS",
    title: "Verified calculation",
    charts: [{ id: "chart", title: "Chart" }],
  };
  app = createApp({ render: () => h(PythonVisualReport, { report }) });
  app.mount(host);
  await nextTick();
  host.querySelector(".chart-stub").click();
  await nextTick();
  expect(host.querySelector(".python-visual-report").dataset.status).toBe("PARTIAL");
  expect(host.textContent).toContain("部分完成");
  expect(report.status).toBe("SUCCESS");
});

it("keeps internal execution metadata out of the report while retaining business limitations", async () => {
  host = document.createElement("div");
  document.body.append(host);
  app = createApp({
    render: () =>
      h(PythonVisualReport, {
        report: {
          status: "SUCCESS",
          title: "业务分析",
          metrics: [
            { id: "a", label: "合计", value: "10", calculation: "internal-python-expression" },
          ],
          execution: {
            taskId: "private-task",
            codeSha256: "private-hash",
            codeArtifact: { fileId: "private-code" },
          },
          validation: {
            warnings: ["internal-error"],
            checks: [{ name: "internal-check", detail: "private-detail" }],
          },
          limitations: ["样本仅覆盖本季度"],
        },
      }),
  });
  app.mount(host);
  await nextTick();
  for (const text of [
    "private-task",
    "private-hash",
    "private-code",
    "internal-python-expression",
    "internal-error",
    "internal-check",
    "private-detail",
  ])
    expect(host.textContent).not.toContain(text);
  expect(host.textContent).toContain("样本仅覆盖本季度");
  expect(host.querySelector(".report-code")).toBeNull();
});
