// @vitest-environment jsdom

import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import { collapseRecordCoverageEvidenceHtml } from "./recordCoverageEvidence.js";

const markdown = new MarkdownIt({ breaks: true });

function root(source) {
  const html = collapseRecordCoverageEvidenceHtml(markdown.render(source));
  return new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;
}

describe("record coverage evidence", () => {
  it("collapses the complete-record appendix without hiding the main answer", () => {
    const element = root([
      "## 分析结论",
      "这是用户优先阅读的结论。",
      "## 全量记录覆盖分析",
      "- records[1..50]：覆盖证据",
      "覆盖校验：50/50（完整）"
    ].join("\n\n"));

    const details = element.querySelector("details.record-coverage-evidence");
    expect(element.querySelector("h2")?.textContent).toBe("分析结论");
    expect(details).not.toBeNull();
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.querySelector("summary")?.textContent).toContain("证据 · 全量记录覆盖分析");
    expect(details.querySelector(".record-coverage-evidence-body")?.textContent).toContain("覆盖校验：50/50");
  });

  it("keeps later peer sections outside the collapsed evidence", () => {
    const element = root([
      "## 全量记录覆盖分析",
      "### 分批 1",
      "证据内容",
      "## 后续建议",
      "单独展示"
    ].join("\n\n"));

    const details = element.querySelector("details.record-coverage-evidence");
    expect(details.querySelector("h3")?.textContent).toBe("分批 1");
    expect(details.textContent).not.toContain("后续建议");
    expect(element.querySelector(":scope > h2")?.textContent).toBe("后续建议");
  });
});
