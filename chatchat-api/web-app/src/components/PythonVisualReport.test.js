// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { it, expect, afterEach, vi } from "vitest";
import PythonVisualReport from "./PythonVisualReport.vue";
import { reviewArchivedVisualReport } from "../js/utils/pythonVisualReport.js";
vi.mock("../js/utils/pythonVisualReport.js", () => ({ reviewArchivedVisualReport: vi.fn() }));
vi.mock("./PythonReportChart.vue", () => ({
  default: {
    props: ["chart"],
    emits: ["status"],
    template:
      "<div class=\"chart-stub\" @click=\"$emit('status',{id:chart.id,error:'render failed'})\">{{chart.title}}</div>",
  },
}));
let app, host;
it('does not let a late archived review replace an in-place final report update',async()=>{
 let resolve;reviewArchivedVisualReport.mockReturnValueOnce(new Promise(done=>resolve=done));
 const props=reactive({report:{status:'PARTIAL',layoutValidationStatus:'FAILED',title:'Old archived report',execution:{resultDatasetRef:'result',codeArtifact:{fileId:'source'}}}});
 host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(PythonVisualReport,props)});app.mount(host);await nextTick();
 props.report.title='Final streamed report';props.report.status='SUCCESS';props.report.layoutValidationStatus='PASSED';await nextTick();
 resolve({status:'PARTIAL',title:'Stale reviewed report'});await nextTick();await nextTick();
 expect(host.textContent).toContain('Final streamed report');expect(host.textContent).not.toContain('Stale reviewed report');
});
it("renders archived Markdown narratives as semantic HTML while preserving report values", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const report = {
    status: "SUCCESS", summary: "**Verified** snapshot", limitations: ["Use `read-only` evidence."],
    sections: [{ title: "Overview", narrative: "## Engine status\n\n**Snapshot**: 2026-10-24 21:24:35\n\n- **Hit rate**: 99.92%\n- Exact value: 9007199254740993.01\n\n| Metric | Value |\n|---|---|\n| Rate | 99.92% |\n\n```sql\nSELECT 1;\n```" }],
  };
  const original = JSON.stringify(report);
  app = createApp({ render: () => h(PythonVisualReport, { report }) });
  app.mount(host);
  await nextTick();
  const narrative = host.querySelector(".report-narrative");
  expect(narrative.querySelector("h2").textContent).toBe("Engine status");
  expect(narrative.querySelector("strong").textContent).toBe("Snapshot");
  expect(narrative.querySelectorAll("li")).toHaveLength(2);
  expect(narrative.querySelector("table td").textContent).toBe("Rate");
  expect(narrative.querySelector("pre code").textContent).toContain("SELECT 1;");
  expect(narrative.textContent).toContain("9007199254740993.01");
  expect(narrative.textContent).not.toMatch(/## Engine|\*\*Snapshot\*\*/);
  expect(host.querySelector(".report-summary strong").textContent).toBe("Verified");
  expect(host.querySelector(".report-notice code").textContent).toBe("read-only");
  expect(JSON.stringify(report)).toBe(original);
});
it("escapes executable HTML and rejects unsafe Markdown links in narratives", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const narrative = '<script>window.reportInjected=true</script>\n\n<img src=x onerror="alert(1)">\n\n[unsafe](javascript:alert(1))\n\n[safe](https://example.com/report)';
  app = createApp({ render: () => h(PythonVisualReport, { report: { status: "SUCCESS", sections: [{ title: "Note", narrative }] } }) });
  app.mount(host);
  await nextTick();
  expect(host.querySelector("script,img,iframe")).toBeNull();
  expect(host.querySelector('a[href^="javascript:"]')).toBeNull();
  expect(host.querySelector(".report-narrative a").getAttribute("rel")).toBe("noopener noreferrer");
  expect(host.querySelector(".report-narrative a").getAttribute("target")).toBe("_blank");
});
it("renders a whole Markdown source wrapper while keeping ordinary code examples literal", async () => {
  host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(PythonVisualReport, { report: { status: "SUCCESS", sections: [
    { title: "Wrapped", narrative: "```markdown\n## Visible heading\n\n**Visible emphasis**\n```" },
    { title: "Code", narrative: "Example:\n\n```python\n# literal heading\n```" },
  ] } }) });
  app.mount(host);
  await nextTick();
  expect(host.querySelector(".report-narrative h2").textContent).toBe("Visible heading");
  expect(host.querySelector(".report-narrative strong").textContent).toBe("Visible emphasis");
  expect(host.querySelector(".report-narrative pre code").textContent).toContain("# literal heading");
});
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.clearAllMocks();
});
it("hides duplicate chart prose and full text while retaining historical charts and business findings", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const report = { status: "SUCCESS", markdown: "Duplicate body", sections: [
    { title: "图表分析", narrative: "Duplicate chart prose", chartIds: ["chart"] },
    { title: "业务结论", narrative: "Business findings" },
  ], charts: [{ id: "chart", title: "Retained chart" }] };
  app = createApp({ render: () => h(PythonVisualReport, { report }) });
  app.mount(host);
  await nextTick();
  expect(host.querySelectorAll(".chart-stub")).toHaveLength(1);
  expect(host.textContent).toContain("Business findings");
  for (const text of ["图表分析", "Duplicate chart prose", "Duplicate body"])
    expect(host.textContent).not.toContain(text);
  expect(report.markdown).toBe("Duplicate body");
});
it("reviews a legacy rejected report without mutating its evidence or revealing internal validation", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const report = { status: "FAILED", title: "Legacy failure", execution: { resultDatasetRef: "result", codeArtifact: { fileId: "source" } } };
  reviewArchivedVisualReport.mockResolvedValue({ schemaVersion: "python_visual_report.v1", status: "PARTIAL", title: "Recovered analysis",
    sections: [{ title: "Findings", narrative: "Retained business finding" }],
    validation: { checks: [{ status: "UNRESOLVED", name: "internal-check" }] },
  });
  app = createApp({ render: () => h(PythonVisualReport, { report }) });
  app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("Recovered analysis"));
  expect(host.textContent).toContain("Retained business finding");
  expect(host.querySelector(".python-visual-report").dataset.status).toBe("PARTIAL");
  expect(host.textContent).not.toContain("internal-check");
  expect(report.status).toBe("FAILED");
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
it("preserves exact calculated values and partial status without duplicate full text", async () => {
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
  expect(host.textContent).not.toContain("Existing text answer");
  expect(host.textContent).not.toContain("Plotly dependency missing");
  props.report = {
    status: "FAILED",
    title: "Failed",
    validation: { warnings: ["Python execution failed"] },
  };
  await nextTick();
  expect(host.textContent).toContain("暂不可用");
  expect(host.textContent).not.toContain("Python execution failed");
  expect(host.textContent).not.toContain("Existing text answer");
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
  expect(host.querySelector('.report-notice')).toBeNull();
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
