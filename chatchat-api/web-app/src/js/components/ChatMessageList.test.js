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
