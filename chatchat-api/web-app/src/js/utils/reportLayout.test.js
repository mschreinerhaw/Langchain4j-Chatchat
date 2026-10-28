import { describe, it, expect } from "vitest";
import { reportLayout, checkedReportLayout, reportPresentation } from "./reportLayout";
import { reportLayoutCases } from "./fixtures/report-layout-cases";

describe("the existing report protocol supports content-driven layouts", () => {
  it('binds each component to the same controlled calculation inputs',()=>{
    for(const {report,input,checks,figure} of reportLayoutCases){
      expect(report.dataSources).toEqual([input.reference]);expect(checks.length).toBeGreaterThan(0);
      for(const item of [...report.metrics,...report.tables,...report.charts])expect(item.dataRefs).toEqual(report.dataSources);
      if(report.tables.length)expect(report.tables[0].rows.map(row=>row[1])).toEqual(input.values);
      if(figure)expect(figure.data[0].y||figure.data[0].values).toEqual(input.values.map(Number));
    }
    expect(reportLayoutCases[1].report.metrics[0].value).toBe('50.00');
    expect(reportLayoutCases[2].report.metrics[0].value).toBe('-3.75');
  });
  it("defaults to auto and preserves the authored order for six distinct business requirements", () => {
    expect(reportPresentation().strategy).toBe("auto");
    const layouts = reportLayoutCases.map(({ report }) => reportLayout(report));
    expect(
      new Set(layouts.map((value) => value.blocks.map((block) => block.type).join(","))).size
    ).toBe(6);
    for (let i = 0; i < layouts.length; i++) {
      expect(layouts[i].warning).toBe("");
      expect(
        layouts[i].blocks
          .slice(0, reportLayoutCases[i].report.layout.blocks.length)
          .map((block) => block.type)
      ).toEqual(reportLayoutCases[i].report.layout.blocks.map((block) => block.type));
      expect(layouts[i].blocks.filter((block) => block.type === "metric").length).toBeLessThan(4);
    }
  });
  it("does not invent a graphic or a metric when the report contains no measured data", () => {
    for (const { report } of reportLayoutCases.slice(4))
      expect(
        reportLayout(report).blocks.some((block) =>
          ["metric", "chart", "table"].includes(block.type)
        )
      ).toBe(false);
  });
  it("restores the same exact values and order after serialization without mutating the original", () => {
    for (const { report } of reportLayoutCases) {
      const stored = JSON.stringify(report),
        first = reportLayout(report),
        restored = reportLayout(JSON.parse(stored));
      expect(restored).toEqual(first);
      expect(JSON.stringify(report)).toBe(stored);
    }
    expect(reportLayoutCases[3].report.metrics[0].value).toBe("9007199254741000.02");
  });
  it("rejects executable components, unknown data references and duplicate placements", () => {
    const report = reportLayoutCases[0].report;
    for (const blocks of [
      [{ id: "x", type: "Html", html: "<script>alert(1)</script>" }],
      [{ id: "x", type: "chart", ref: "other-report" }],
      [
        { id: "x", type: "summary" },
        { id: "y", type: "summary" },
      ],
    ]) {
      const layout = { schemaVersion: "report_layout.v1", blocks };
      expect(() => checkedReportLayout(layout, report)).toThrow();
      const fallback = reportLayout({ ...report, layout });
      expect(fallback.warning).toBeTruthy();
      expect(fallback.blocks.some((block) => block.type === "limitations")).toBe(true);
    }
  });
  it("honors templates and visualization switches while keeping limits and sources", () => {
    const report = reportLayoutCases[0].report;
    const result = reportLayout({
      ...report,
      presentation: { strategy: "template", template: "evidence-first", autoCharts: false },
    });
    expect(result.blocks[0].type).toBe("table");
    expect(result.blocks.some((block) => block.type === "chart")).toBe(false);
    expect(result.blocks.some((block) => block.type === "limitations")).toBe(true);
    expect(result.blocks.some((block) => block.type === "sources")).toBe(true);
  });
});
