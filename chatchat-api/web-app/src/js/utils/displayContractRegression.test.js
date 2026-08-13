// @vitest-environment jsdom

import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import { normalizeArtifactHtml } from "./artifactHtmlNormalizer.js";
import { stripInternalDocumentRefs } from "./internalDocumentRefs.js";
import { normalizeMarkdownTables } from "./markdownTableNormalizer.js";
import { enhanceResultTables } from "./resultTableEnhancer.js";

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true, breaks: true });

function render(source) {
  return enhanceResultTables(markdown.render(normalizeMarkdownTables(stripInternalDocumentRefs(source))));
}

function root(html) {
  return new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;
}

function payload(element) {
  const encoded = element.querySelector(".query-result-chart-button")?.dataset.resultChartPayload;
  return encoded ? JSON.parse(decodeURIComponent(encoded)) : null;
}

describe("front-end report display contract", () => {
  it("preserves the complete professional Markdown hierarchy", () => {
    const element = root(render([
      "# 客户资产分析报告",
      "## 一、资产总览",
      "正文包含 **重点数据**、[公开说明](https://example.com/help) 与 `ZZC` 字段。",
      "> 风险提示仅作辅助信息。",
      "- 已核验资产合计",
      "- 已核验当日盈亏",
      "```text",
      "| 代码示例 | 不应成为表格 |",
      "| A | B |",
      "```"
    ].join("\n")));

    expect(element.querySelector("h1")?.textContent).toBe("客户资产分析报告");
    expect(element.querySelector("h2")?.textContent).toBe("一、资产总览");
    expect(element.querySelector("strong")?.textContent).toBe("重点数据");
    expect(element.querySelector("blockquote")?.textContent).toContain("风险提示");
    expect(element.querySelectorAll("li")).toHaveLength(2);
    expect(element.querySelector("a")?.getAttribute("href")).toBe("https://example.com/help");
    expect(element.querySelector("pre code")?.textContent).toContain("| A | B |");
    expect(element.querySelector("table")).toBeNull();
  });

  it("restores CRLF historical tables and keeps identifiers exact", () => {
    const element = root(render([
      "历史记录 records[1…2]：doc://20260804_5eee01fd#chunk=0",
      "| 返回字段 | 返回值 |",
      "| KHH | 070200046604 |",
      "| RQ | 20260731 |"
    ].join("\r\n")));

    expect(element.textContent).not.toContain("records[");
    expect(element.textContent).not.toContain("doc://");
    expect(element.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(payload(element).rows[0]).toEqual({ 返回字段: "KHH", 返回值: "070200046604" });
  });

  it("retains meaningful sources but removes an entirely empty source column", () => {
    const emptySources = root(render([
      "| 节点 | 数量 | 引用来源 |", "|---|---:|---|", "| API | 3 | / |", "| 调度器 | 2 | 暂无 |"
    ].join("\n")));
    expect([...emptySources.querySelectorAll("th")].map((cell) => cell.textContent)).toEqual(["节点", "数量"]);
    expect(payload(emptySources).columns).toEqual(["节点", "数量"]);

    const realSource = root(enhanceResultTables(
      '<table><thead><tr><th>结论</th><th>相关证据</th></tr></thead><tbody><tr><td>已核验</td>'
      + '<td><a class="web-citation-link" href="https://a.example">A</a> '
      + '<a class="web-citation-link" href="https://b.example">B</a> '
      + '<a class="web-citation-link" href="https://c.example">C</a></td></tr></tbody></table>'
    ));
    expect(realSource.querySelector("th:last-child")?.textContent).toBe("主要来源");
    expect(realSource.querySelectorAll("a.web-citation-link")).toHaveLength(3);
    expect(realSource.querySelectorAll(".source-tag-overflow-hidden")).toHaveLength(1);
    expect(realSource.querySelector(".source-tag-overflow-toggle")?.textContent).toBe("+1");
  });

  it("handles empty and malformed table-like content without fake chart actions", () => {
    const headerOnly = root(render("| 字段 | 数值 |\n|---|---:|"));
    expect(headerOnly.querySelector("table")).not.toBeNull();
    expect(headerOnly.querySelector(".query-result-chart-button")).toBeNull();

    const malformed = root(render("说明 A | B | C\n下一行只有 | 一个分隔"));
    expect(malformed.querySelector("table")).toBeNull();
    expect(malformed.querySelector(".query-result-chart-button")).toBeNull();
  });

  it("keeps null-like and percentage values readable while converting real measures", () => {
    const element = root(render([
      "| 名称 | 当日盈亏 | 占比 | 备注 |", "|---|---:|---:|---|",
      "| A | +1,020.50 | 12.5% | - |", "| B | -80 | N/A | 暂无 |", "| C | 0 | 0% | null |"
    ].join("\n")));
    const data = payload(element).rows;
    expect(data[0]).toEqual({ 名称: "A", 当日盈亏: 1020.5, 占比: "12.5%", 备注: "-" });
    expect(data[1].占比).toBe("N/A");
    expect(element.querySelectorAll("td.trend-up")).toHaveLength(1);
    expect(element.querySelectorAll("td.trend-down")).toHaveLength(1);
    expect(element.querySelectorAll("td.trend-neutral")).toHaveLength(1);
  });

  it("sanitizes all active-content containers and event attributes", () => {
    const element = root(normalizeArtifactHtml(
      '<h2 onmouseover="steal()">安全报告</h2><iframe src="https://evil.example"></iframe>'
      + '<object data="x"></object><embed src="x"><form action="/steal"><input></form>'
      + '<a href="JaVaScRiPt:steal()" onclick="steal()">不可执行链接</a><p>正文保留</p>',
      (source) => markdown.render(source)
    ));
    expect(element.querySelector("iframe, object, embed, form")).toBeNull();
    expect(element.querySelector("[onmouseover], [onclick]")).toBeNull();
    expect(element.querySelector("a")?.hasAttribute("href")).toBe(false);
    expect(element.textContent).toContain("正文保留");
  });
});
