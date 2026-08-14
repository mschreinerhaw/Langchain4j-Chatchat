// @vitest-environment jsdom

import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import ChatMessageList from "./ChatMessageList.js";

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
