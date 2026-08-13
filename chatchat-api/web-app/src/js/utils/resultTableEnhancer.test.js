// @vitest-environment jsdom

import { describe, expect, it } from "vitest";
import MarkdownIt from "markdown-it";
import { normalizeArtifactHtml } from "./artifactHtmlNormalizer.js";
import { enhanceResultTables } from "./resultTableEnhancer.js";
import { stripInternalDocumentRefs, stripInternalDocumentRefsFromHtml } from "./internalDocumentRefs.js";

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true });
const renderMarkdown = (source) => markdown.render(source);

function dom(html) {
  return new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;
}

function payloadFor(root, selector = ".query-result-chart-button") {
  const encoded = root.querySelector(selector)?.dataset.resultChartPayload || "";
  return encoded ? JSON.parse(decodeURIComponent(encoded)) : null;
}

describe("dynamic report table regression matrix", () => {
  it("enhances a canonical Markdown table with a chart action and accurate dimensions", () => {
    const source = [
      "## 服务器配置",
      "",
      "| 节点 | CPU | 数量 |",
      "|---|---:|---:|",
      "| 调度器 | 24 | 3 |",
      "| 微服务 | 16 | 2 |"
    ].join("\n");
    const root = dom(enhanceResultTables(renderMarkdown(source)));

    expect(root.querySelectorAll("table")).toHaveLength(1);
    expect(root.querySelector(".query-result-table-toolbar")?.textContent).toContain("2 行 / 3 列");
    expect(root.querySelector(".query-result-chart-button")?.textContent).toBe("图形分析");
    expect(payloadFor(root)).toMatchObject({
      title: "服务器配置",
      columns: ["节点", "CPU", "数量"],
      rows: [{ 节点: "调度器", CPU: 24, 数量: 3 }, { 节点: "微服务", CPU: 16, 数量: 2 }]
    });
  });

  it("preserves leading-zero business identifiers while converting real measures", () => {
    const source = [
      "| 证券代码 | 证券名称 | 数量 | 最新市值 | 当日盈亏 |",
      "|---|---|---:|---:|---:|",
      "| 000155 | 川能动力 | 800 | 9,808.00 | +1,033.50 |",
      "| 600693 | 东百集团 | 2600 | 24,544.00 | +749.61 |"
    ].join("\n");
    const payload = payloadFor(dom(enhanceResultTables(renderMarkdown(source))));

    expect(payload.rows[0]).toEqual({
      证券代码: "000155",
      证券名称: "川能动力",
      数量: 800,
      最新市值: 9808,
      当日盈亏: 1033.5
    });
    expect(payload.rows[1].证券代码).toBe(600693);
  });

  it("enhances a legacy native HTML table", () => {
    const html = "<h2>资产总览</h2><table><thead><tr><th>字段</th><th>数值</th></tr></thead>"
      + "<tbody><tr><td>ZZC</td><td>847,174.25</td></tr></tbody></table>";
    const root = dom(normalizeArtifactHtml(html, renderMarkdown));

    expect(root.querySelectorAll(".query-result-table-card")).toHaveLength(1);
    expect(payloadFor(root)).toMatchObject({ title: "资产总览", rows: [{ 字段: "ZZC", 数值: 847174.25 }] });
  });

  it("repairs pipe-table text embedded in legacy HTML before enhancing it", () => {
    const html = "<h3>深市持仓明细</h3><p>| 证券代码 | 证券名称 | 数量 |<br>"
      + "|---|---|---:|<br>| 000609 | ST中迪 | 2300 |<br>| 002389 | 航天彩虹 | 600 |</p>";
    const root = dom(normalizeArtifactHtml(html, renderMarkdown));

    expect(root.textContent).not.toContain("|---|---|---:|");
    expect(root.querySelectorAll("table")).toHaveLength(1);
    expect(payloadFor(root).rows).toEqual([
      { 证券代码: "000609", 证券名称: "ST中迪", 数量: 2300 },
      { 证券代码: "002389", 证券名称: "航天彩虹", 数量: 600 }
    ]);
  });

  it("creates one action per table plus a multi-dataset comparison action", () => {
    const source = [
      "## 生产环境", "", "| 节点 | 数量 |", "|---|---:|", "| API | 3 |", "",
      "## 测试环境", "", "| 节点 | 数量 |", "|---|---:|", "| API | 1 |"
    ].join("\n");
    const root = dom(enhanceResultTables(renderMarkdown(source)));
    const comparison = payloadFor(root, ".query-result-multi-dataset-card .query-result-chart-button");

    expect(root.querySelectorAll(".query-result-table-card:not(.query-result-multi-dataset-card)")).toHaveLength(2);
    expect(root.querySelectorAll(".query-result-chart-button")).toHaveLength(3);
    expect(comparison.title).toBe("多数据集对比");
    expect(comparison.datasets.map((dataset) => dataset.title)).toEqual(["生产环境", "测试环境"]);
  });

  it("does not fabricate visualizations for prose or malformed pipe text", () => {
    const html = "<h2>说明</h2><p>| 这不是完整表格 |<br>| 只有两行 |</p>";
    const root = dom(normalizeArtifactHtml(html, renderMarkdown));

    expect(root.querySelector("table")).toBeNull();
    expect(root.querySelector(".query-result-chart-button")).toBeNull();
  });

  it("removes unsafe HTML while retaining safe report content", () => {
    const html = "<h2 onclick=\"alert(1)\">报告</h2><script>alert(1)</script>"
      + "<a href=\"javascript:alert(1)\">bad</a><p>安全正文</p>";
    const root = dom(normalizeArtifactHtml(html, renderMarkdown));

    expect(root.querySelector("script")).toBeNull();
    expect(root.querySelector("h2")?.hasAttribute("onclick")).toBe(false);
    expect(root.querySelector("a")?.hasAttribute("href")).toBe(false);
    expect(root.textContent).toContain("安全正文");
  });

  it("removes internal doc locators from text and legacy HTML without deleting user content", () => {
    expect(stripInternalDocumentRefs("来源：doc://20260804_5eee01fd#chunk=0 配置说明"))
      .toBe("来源： 配置说明");
    const root = dom(stripInternalDocumentRefsFromHtml(
      '<p>证据 doc://20260804_5eee01fd#chunk=0 支持该结论</p>'
      + '<a href="doc://20260804_5eee01fd#chunk=0">doc://20260804_5eee01fd#chunk=0</a>'
    ));
    expect(root.textContent).not.toContain("doc://");
    expect(root.textContent).toContain("支持该结论");
    expect(root.querySelector("a")).toBeNull();
  });

  it("removes internal record-range labels while preserving surrounding business content", () => {
    for (const marker of ["records[1…2]", "records[1..2]", "records[1...2]", "record[7]", "records[3-9]"]) {
      const cleaned = stripInternalDocumentRefs(`持仓明细 ${marker} 共两条记录`);
      expect(cleaned).toBe("持仓明细 共两条记录");
    }
    expect(stripInternalDocumentRefs("records 是英文业务字段说明")).toBe("records 是英文业务字段说明");

    const root = dom(stripInternalDocumentRefsFromHtml("<p>资产数据 records[1…2] 已加载</p>"));
    expect(root.textContent).toBe("资产数据 已加载");
    expect(root.textContent).not.toContain("records[");
  });
});
