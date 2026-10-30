// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";
import ReportRenderer from "./ReportRenderer.vue";
import { reportLayoutCases } from "../js/utils/fixtures/report-layout-cases";
vi.mock("./PythonReportChart.vue", () => ({
  default: {
    props: ["chart"],
    template: '<figure class="verified-chart-stub">{{chart.title}}</figure>',
  },
}));
let app, host;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
it.each(reportLayoutCases)(
  "renders $id using only its actual component references",
  async ({ report }) => {
    host = document.createElement("div");
    document.body.append(host);
    app = createApp({ render: () => h(ReportRenderer, { report }) });
    app.mount(host);
    await nextTick();
    const order = [...host.querySelectorAll("[data-block-type]")].map(
      (node) => node.dataset.blockType
    );
    expect(order.slice(0, report.layout.blocks.length)).toEqual(
      report.layout.blocks.map((block) => block.type)
    );
    expect(host.querySelectorAll(".python-report-metrics")).toHaveLength(report.metrics.length);
    expect(host.querySelectorAll(".verified-chart-stub")).toHaveLength(report.charts.length);
    expect(host.textContent).toContain(report.limitations[0]);
    expect(host.textContent).toContain(report.dataSources[0]);
  }
);
it("never executes model HTML, layout scripts or remote Markdown images", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const report = {
    ...reportLayoutCases[5].report,
    summary: "<script>window.compromised=true</script>\n\n![leak](https://example.invalid/secret)",
    layout: {
      schemaVersion: "report_layout.v1",
      blocks: [{ id: "x", type: "script", code: "alert(1)" }],
    },
  };
  app = createApp({ render: () => h(ReportRenderer, { report }) });
  app.mount(host);
  await nextTick();
  expect(host.querySelector("script,img,iframe")).toBeNull();
  expect(window.compromised).toBeUndefined();
  expect(host.textContent).not.toContain("排版描述未通过校验");
  expect(host.textContent).toContain('数据范围');
});
