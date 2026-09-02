// @vitest-environment jsdom

import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import ChatMessageList from "./ChatMessageList.js";
import { renderArtifactHtml, renderArtifactMarkdownHtml } from "../ui-artifact/registry.js";

const markdown = new MarkdownIt({ breaks: true });
const methods = ChatMessageList.methods;
const context = Object.fromEntries(
  Object.entries(methods).map(([name, method]) => [name, method])
);

function render(source) {
  const html = methods.collapseToolEvidenceHtml.call(context, markdown.render(source));
  return new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;
}

describe("tool execution evidence", () => {
  it("recognizes supporting datasets as evidence attachments", () => {
    expect(methods.isSupportingDatasetVisualization({
      presentationChannel: "supporting_dataset",
      ui: { role: "evidence_attachment", defaultCollapsed: true }
    })).toBe(true);
    expect(methods.isSupportingDatasetVisualization({
      type: "table",
      ui: { defaultView: "table" }
    })).toBe(false);
  });

  it("hides numbered web citation markers in dynamic report answers", () => {
    const markdownHtml = renderArtifactMarkdownHtml(
      "关注行业数据 [网页7][网页8]。\n\n风险提示 [网页3]。"
    );
    const richHtml = renderArtifactHtml(
      "<p>关注行业数据 [网页7]，并观察后续变化 [网页8]。</p>"
    );

    expect(markdownHtml).toContain("关注行业数据");
    expect(richHtml).toContain("关注行业数据");
    expect(markdownHtml).not.toMatch(/\[(?:网页|網頁)\s*\d+\]/);
    expect(richHtml).not.toMatch(/\[(?:网页|網頁)\s*\d+\]/);
  });

  it("hides decoded and full-width web markers when restoring history", () => {
    const html = methods.renderMarkdown.call(
      context,
      "历史结论 &#91;网页7&#93;，补充来源 ［网页\u200B８］。",
      { role: "assistant", sources: [], traces: [] }
    );
    const contractHtml = methods.renderMarkdown.call(
      context,
      "历史结论 &#91;网页7&#93;。",
      {
        role: "assistant",
        sources: [],
        traces: [],
        uiResponse: {
          contractVersion: "ui_response_v1",
          answer: "历史结论 &#91;网页7&#93;。",
          citations: []
        }
      }
    );

    expect(html).toContain("历史结论");
    expect(html).not.toContain("网页7");
    expect(html).not.toContain("网页\u200B８");
    expect(html).not.toContain("&#91;");
    expect(contractHtml).not.toContain("网页7");
    expect(contractHtml).not.toContain("&#91;");
  });

  it("repairs uneven Markdown tables inside structured answer blocks", () => {
    const report = [
      "## 当前持仓明细",
      "返回 2 条当前持仓。",
      "| 证券代码 (ZQDM) | 证券名称 (ZQMC) | 证券数量 (ZQSL) | 证券市值 (ZXSZ) | 当日盈亏 (DRYK) | 累计实现盈亏 (LJYK) |",
      "|---|---:|---:|---:|---:|",
      "| 600693 | 东百集团 | 2600 | 24544.00 | 749.61 | 5229.59 |",
      "| 000155 | 川能动力 | 800 | 9808.00 | 0.00 | 1033.50 |"
    ].join("\n");

    const html = methods.renderMarkdown.call(context, report, {
      role: "assistant",
      sources: [],
      traces: [],
      uiResponse: {
        contractVersion: "ui_response_v1",
        answerBlocks: [{ type: "markdown", text: report }],
        citations: []
      }
    });
    const root = new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;

    expect(root.querySelectorAll("table")).toHaveLength(1);
    expect(root.querySelectorAll("thead th")).toHaveLength(6);
    expect(root.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(root.querySelector(".query-result-table-toolbar")?.textContent).toContain("2 行 / 6 列");
    expect(root.textContent).not.toContain("|---|");
  });

  it("keeps tool execution evidence collapsed by default", () => {
    const element = render([
      "## 回答结论",
      "主要回答内容。",
      "## 工具执行证据",
      "- `document_search` [检索] success 证据类型: DOCUMENT duration ms: 20 summary: 找到文档"
    ].join("\n\n"));

    const details = element.querySelector("details.tool-evidence-details");
    expect(details).not.toBeNull();
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.querySelector("summary")?.textContent).toContain("证据 · 工具执行证据");
    expect(details.querySelector("summary small")?.textContent).toBe("1 条");
    expect(details.querySelector(".tool-evidence-body")?.textContent).toContain("document_search");
  });

  it("keeps nested evidence headings inside and later peer sections outside", () => {
    const element = render([
      "## 工具调用证据",
      "### 调用摘要",
      "普通证据文本。",
      "## 后续建议",
      "这部分不应收起。"
    ].join("\n\n"));

    const details = element.querySelector("details.tool-evidence-details");
    expect(details.textContent).toContain("调用摘要");
    expect(details.textContent).not.toContain("后续建议");
    expect(element.querySelector(":scope > h2")?.textContent).toBe("后续建议");
  });
});
