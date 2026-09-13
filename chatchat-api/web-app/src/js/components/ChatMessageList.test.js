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
  it("collapses completed execution steps by default and allows reopening them", () => {
    const collapseContext = {
      ...context,
      expandedExecutionMessageIds: new Set(),
      loading: false
    };
    const completed = {
      id: "message-completed",
      role: "assistant",
      status: "completed",
      streaming: false,
      content: "report",
      steps: [{ id: "step-1", status: "done", title: "完成分析" }]
    };

    expect(methods.executionStepsExpanded.call(collapseContext, completed)).toBe(false);
    methods.toggleExecutionSteps.call(collapseContext, completed);
    expect(methods.executionStepsExpanded.call(collapseContext, completed)).toBe(true);
    expect(methods.executionStepsExpanded.call(collapseContext, {
      ...completed,
      id: "message-running",
      status: "streaming",
      streaming: true,
      content: ""
    })).toBe(true);
    expect(methods.executionStepsExpanded.call(collapseContext, {
      ...completed,
      id: "message-failed",
      status: "failed"
    })).toBe(true);
  });

  it("keeps backend lifecycle observations visible without counting them as tool calls", () => {
    const message = {
      role: "assistant",
      status: "failed",
      streaming: false,
      steps: [
        { id: "runtime-start", type: "RUNTIME_STARTED", title: "后端执行中", status: "done" },
        {
          id: "planner-observation",
          type: "RUNTIME_OBSERVATION",
          title: "计划校验",
          toolName: "mcp_chatchat_mcp_server_api_template_query",
          status: "done"
        },
        { id: "runtime-failed", type: "RUNTIME_FAILED", title: "运行失败", status: "error" }
      ],
      traces: []
    };

    expect(methods.runtimeToolCalls.call(context, message)).toHaveLength(0);
    const stages = methods.runtimeProcessSteps.call(context, message);
    expect(stages.map((step) => step.id)).toEqual(["runtime-start", "runtime-failed"]);
    expect(stages[0].children).toHaveLength(1);
    expect(stages[0].children[0]).toEqual(expect.objectContaining({
      id: "planner-observation",
      title: "匹配业务模板"
    }));
  });

  it("groups child events from the same tool inside the realtime event stream", () => {
    const groupContext = { ...context, loading: false };
    const message = {
      id: "message-tool-group",
      role: "assistant",
      status: "completed",
      streaming: false,
      timestamp: 1_000,
      steps: [
        { id: "repair-1", type: "RUNTIME_OBSERVATION", title: "恢复 DAG", detail: "已恢复工作流", toolName: "dag_repair", status: "repaired", timestamp: 1_100 },
        { id: "repair-2", type: "RUNTIME_OBSERVATION", title: "校验 DAG", detail: "校验通过", toolName: "dag_repair", status: "repaired", timestamp: 1_200 }
      ]
    };

    const groups = methods.runtimeEventGroups.call(groupContext, message);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toEqual(expect.objectContaining({
      id: "tool:dag_repair",
      grouped: true,
      toolName: "dag_repair",
      active: false,
      statusText: "已修复"
    }));
    expect(groups[0].children).toHaveLength(2);
  });

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

  it("collapses raw tabular data by default without collapsing charts", () => {
    expect(methods.isCollapsibleRawDataVisualization.call(context, {
      type: "table",
      ui: { role: "raw_data", defaultCollapsed: true }
    })).toBe(true);
    expect(methods.rawDataVisualizationToggleLabel.call(context, {
      type: "table",
      dataset: { rowCount: 84, rows: [{ id: 1 }] },
      ui: { role: "raw_data", defaultCollapsed: true }
    })).toBe("查看原始数据（84 行）");
    expect(methods.isCollapsibleRawDataVisualization.call(context, {
      type: "chart",
      ui: { defaultCollapsed: true }
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
