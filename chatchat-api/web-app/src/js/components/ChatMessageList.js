import MarkdownIt from "markdown-it";
import { Check, CircleCheck, CircleX, Copy } from "@lucide/vue";
import ResponseReferences from "../../components/ResponseReferences.vue";
import chartAnalysisMixin from "./ChatMessageListChartAnalysis.js";
import {
  extractDocumentSearchPagesFromTraces,
  extractWebCitationPages,
  extractWebSearchPagesFromTraces,
  inlineWebCitationLinks
} from "../utils/webReferences.js";

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true
});
const FENCE_RE = /^(\s*)(`{3,}|~{3,})(\s*)([A-Za-z0-9_-]*)\s*$/;
const SQL_START_RE = /^\s*(CREATE|WITH|SELECT|INSERT|UPDATE|DELETE|MERGE|ALTER|DROP|TRUNCATE|SET)\b/i;
const SQL_CONTINUATION_RE = /^\s*(USING|OPTIONS\s*\(|PARTITIONED\s+BY|TBLPROPERTIES\s*\(|LOCATION\b|COMMENT\b|AS\b|FROM\b|WHERE\b|JOIN\b|LEFT\b|RIGHT\b|INNER\b|OUTER\b|ON\b|GROUP\b|ORDER\b|HAVING\b|LIMIT\b|VALUES\b|URL\b|DBTABLE\b|USER\b|PASSWORD\b|DRIVER\b|PARTITIONCOLUMN\b|LOWERBOUND\b|UPPERBOUND\b|NUMPARTITIONS\b|FETCHSIZE\b|SESSIONINITSTATEMENT\b|\)|;|,)/i;
const SECTION_BOUNDARY_RE = /^\s*(#{1,6}\s+|[-*]\s+\d+[.)]\s+|\d+[.)]\s+|[\[(].+[\])]\s*$)/;
const JSON_START_RE = /^\s*[{[]\s*$/;
const INTERNAL_DOCUMENT_REF_RE = /[\uFF08(]?\s*doc:\/\/[^\s\uFF09)\]}\>\uFF0C\u3002\uFF1B;]+[\uFF09)]?\s*[:\uFF1A]?/gi;
const EXECUTED_SQL_CONTEXT_RE = /(mcp_chatchat_mcp_server_business_query_template_search|business_query_template_search|mcp_chatchat_mcp_server_sql_query_execute|sql_query_execute|mcp_chatchat_mcp_server_sql_script_execute|sql_script_execute|\u6267\u884c\u7684?\s*SQL|\u5b9e\u9645\u6267\u884c\u8bed\u53e5|\u67e5\u8be2\u8bed\u53e5|\u5177\u4f53\u8bed\u53e5|operation\.statement|Executed\s+SQL|SQL\s+Statement)/i;

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function parseNumber(value) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const parsed = Number(String(value ?? "").replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

const defaultLinkOpen =
  markdown.renderer.rules.link_open ||
  ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options));

markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const href = token.attrGet("href") || "";
  const targetIndex = token.attrIndex("target");
  const relIndex = token.attrIndex("rel");
  const classIndex = token.attrIndex("class");

  if (env?.webCitationUrls?.has(href)) {
    const currentClass = classIndex >= 0 ? token.attrs[classIndex][1] : "";
    const nextClass = `${currentClass} web-citation-link`.trim();
    if (classIndex < 0) {
      token.attrPush(["class", nextClass]);
    } else {
      token.attrs[classIndex][1] = nextClass;
    }
  }

  if (targetIndex < 0) {
    token.attrPush(["target", "_blank"]);
  } else {
    token.attrs[targetIndex][1] = "_blank";
  }

  if (relIndex < 0) {
    token.attrPush(["rel", "noopener noreferrer"]);
  } else {
    token.attrs[relIndex][1] = "noopener noreferrer";
  }

  return defaultLinkOpen(tokens, idx, options, env, self);
};

markdown.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx];
  const language = String(token.info || "").trim().split(/\s+/)[0] || "";
  const languageLabel = language
    ? `<span class="markdown-code-language">${escapeHtml(language)}</span>`
    : "";
  const languageAttr = language ? ` class="language-${escapeHtml(language)}"` : "";
  return [
    '<div class="markdown-code-block" data-code-block>',
    '<div class="markdown-code-toolbar">',
    languageLabel,
    '<button type="button" class="markdown-code-copy" data-code-copy title="Copy code" aria-label="Copy code"></button>',
    "</div>",
    `<pre><code${languageAttr}>${escapeHtml(token.content)}</code></pre>`,
    "</div>\n"
  ].join("");
};

export default {
  name: "ChatMessageList",
  mixins: [chartAnalysisMixin],
  components: {
    Check,
    CircleCheck,
    CircleX,
    Copy,
    ResponseReferences
  },
  emits: ["feedback", "visualization-drill-down"],
  props: {
    messages: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    userId: {
      type: String,
      default: ""
    },
    activeAgent: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      copiedMessageId: "",
      copiedResetTimer: null,
      codeCopyResetTimers: new Set(),
      collapsedToolCallMessageIds: new Set(),
      expandedCompletedToolCallMessageIds: new Set(),
      reasoningModal: null,
      feedbackOptions: [
        { value: "useful", label: "\u6709\u7528" },
        { value: "adopted", label: "\u91c7\u7eb3" },
        { value: "resolved", label: "\u89e3\u51b3" },
        { value: "unresolved", label: "\u672a\u89e3\u51b3" }
      ]
    };
  },
  computed: {
    displayUserId() {
      return this.userId || "default-user";
    },
    userAvatarLabel() {
      return this.displayUserId.slice(0, 2).toUpperCase();
    },
    assistantDisplayName() {
      return this.activeAgent?.name || "\u52a9\u624b";
    },
    hasStreamingMessage() {
      return this.messages.some((message) => message.streaming || this.isExecutionRunning(message));
    }
  },
  beforeUnmount() {
    if (this.copiedResetTimer) {
      window.clearTimeout(this.copiedResetTimer);
    }
    this.codeCopyResetTimers.forEach((timer) => window.clearTimeout(timer));
    this.codeCopyResetTimers.clear();
  },
  methods: {
    toolCallMessageKey(message = {}) {
      return String(message.id || message.taskId || message.runId || "");
    },
    toolCallsExpanded(message = {}) {
      const key = this.toolCallMessageKey(message);
      if (key && this.expandedCompletedToolCallMessageIds.has(key)) {
        return true;
      }
      if (key && this.collapsedToolCallMessageIds.has(key)) {
        return false;
      }
      return this.runtimeToolStatusClass(message) === "running";
    },
    toggleToolCalls(message = {}) {
      const key = this.toolCallMessageKey(message);
      if (!key) {
        return;
      }
      if (this.toolCallsExpanded(message)) {
        this.expandedCompletedToolCallMessageIds.delete(key);
        this.collapsedToolCallMessageIds.add(key);
      } else {
        this.collapsedToolCallMessageIds.delete(key);
        if (this.runtimeToolStatusClass(message) !== "running") {
          this.expandedCompletedToolCallMessageIds.add(key);
        }
      }
    },
    shouldShowSteps(message = {}) {
      return message.role === "assistant"
        && (!!message.taskId || (Array.isArray(message.steps) && message.steps.length > 0));
    },
    messageHasRenderableContent(message = {}) {
      return !!String(message.content || "").trim() || !!this.extractUiResponse(message)?.answer;
    },
    isExecutionRunning(message = {}) {
      const status = String(message.status || "").toLowerCase();
      const runningStatus = ["running", "streaming", "processing", "executing"].includes(status);
      return message.role === "assistant"
        && (!!message.streaming || (this.loading && runningStatus) || (runningStatus && !message.content))
        && !["failed", "cancelled", "empty", "partial", "completed", "waiting"].includes(status);
    },
    visibleExecutionSteps(message = {}) {
      const steps = Array.isArray(message.steps) ? message.steps : [];
      const running = this.isExecutionRunning(message);
      const visible = running && !steps.length ? this.defaultRunningSteps(message) : steps.slice(-40);
      const normalized = visible.map((step, index) => ({
        id: step.id || `${message.id || "message"}-step-${index}`,
        title: step.title || "\u6267\u884c\u6b65\u9aa4",
        detail: step.detail || "",
        status: step.status || "pending",
        type: step.type || "",
        toolName: step.toolName || "",
        timestamp: step.timestamp || message.timestamp || Date.now(),
        latencyMs: step.latencyMs
      }));
      if (!running || normalized.some((step) => String(step.status || "").toLowerCase() === "active")) {
        return normalized;
      }
      const activeIndex = [...normalized]
        .reverse()
        .findIndex((step) => !["error", "cancelled", "empty"].includes(String(step.status || "").toLowerCase()));
      if (activeIndex < 0) {
        return normalized;
      }
      const targetIndex = normalized.length - 1 - activeIndex;
      return normalized.map((step, index) => ({
        ...step,
        status: index === targetIndex ? "active" : step.status
      }));
    },
    stepStatusClass(step = {}) {
      const status = String(step.status || "pending").toLowerCase();
      if (["done", "active", "partial", "empty", "error", "cancelled"].includes(status)) {
        return status;
      }
      return "pending";
    },
    executionTitle(message = {}) {
      if (message.status === "waiting") {
        return "\u7b49\u5f85\u6743\u9650\u786e\u8ba4";
      }
      if (message.status === "failed") {
        return "\u540e\u7aef\u6267\u884c\u672a\u5b8c\u6210";
      }
      if (message.status === "partial") {
        return "\u90e8\u5206\u7ed3\u679c";
      }
      if (message.status === "empty") {
        return "\u672a\u4ea7\u751f\u53ef\u5c55\u793a\u7ed3\u679c";
      }
      if (message.status === "cancelled") {
        return "\u5df2\u505c\u6b62\u672c\u6b21\u6267\u884c";
      }
      if (message.streaming || this.isExecutionRunning(message)) {
        const name = message.agentName || this.activeAgent?.name || "";
        return `\u6b63\u5728\u6267\u884c${name ? `: ${name}` : ""}`;
      }
      return "\u6267\u884c\u6b65\u9aa4";
    },
    canShowEvaluation(message = {}) {
      const status = String(message.status || "").toLowerCase();
      return message.role === "assistant"
        && (!!message.content || !!this.extractUiResponse(message)?.answer)
        && !!message.taskId
        && !message.streaming
        && !message.feedbackTime
        && !["waiting", "cancelled", "failed", "streaming", "running"].includes(status);
    },
    assistantName(message = {}) {
      return String(message.agentName || this.assistantDisplayName || "\u52a9\u624b").trim() || "\u52a9\u624b";
    },
    assistantAvatarLabel(message = {}) {
      const name = this.assistantName(message);
      const characters = Array.from(name);
      if (/[\u3400-\u9fff]/u.test(name)) {
        return characters.slice(0, 2).join("");
      }
      const words = name.split(/[\s_-]+/u).filter(Boolean);
      if (words.length > 1) {
        return words.slice(0, 2).map((word) => Array.from(word)[0]).join("").toUpperCase();
      }
      return characters.slice(0, 2).join("").toUpperCase() || "\u52a9\u624b";
    },
    runtimeAvatarLabel(message = {}) {
      return this.isExecutionRunning(message) || message.streaming ? "RUN" : this.assistantAvatarLabel(message);
    },
    runtimeRunId(message = {}) {
      const raw = message.taskId || message.runId || message.id || "";
      const text = String(raw || "").replace(/[^A-Za-z0-9-]/g, "");
      return text ? `Run #${text.slice(-12)}` : "Run #pending";
    },
    runtimeElapsed(message = {}) {
      const started = Number(message.startedAt || message.timestamp || Date.now());
      const running = this.isExecutionRunning(message) || message.streaming;
      const recorded = Number(message.latencyMs || (message.finishedAt ? Number(message.finishedAt) - started : 0));
      if (!running && !(recorded > 0)) {
        return "";
      }
      const elapsed = Math.max(0, running ? Date.now() - started : recorded);
      if (elapsed < 1000) {
        return "0.0s";
      }
      if (elapsed < 60_000) {
        return `${(elapsed / 1000).toFixed(1)}s`;
      }
      const minutes = Math.floor(elapsed / 60_000);
      const seconds = Math.floor((elapsed % 60_000) / 1000);
      return `${minutes}m ${seconds}s`;
    },
    runtimeStageCards(message = {}) {
      const steps = this.visibleExecutionSteps(message);
      return steps
        .filter((step) => String(step.status || "").toLowerCase() !== "pending")
        .map((step, index) => ({
          ...step,
          id: step.id || `${message.id || "message"}-runtime-step-${index}`,
          order: index
        }));
    },
    runtimeStepMatchesStage(step = {}, stage = {}) {
      const text = `${step.title || ""} ${step.detail || ""} ${step.type || ""} ${step.toolName || ""}`.toLowerCase();
      const hasAny = (terms) => terms.some((term) => text.includes(String(term).toLowerCase()));
      const key = stage.key;
      if (key === "planner") {
        return hasAny(["plan", "planner", "problem_identification", "tool_discovery"]);
      }
      if (key === "memory") {
        return hasAny(["memory", "context", "history", "summary"]);
      }
      if (key === "asset") {
        return hasAny(["asset", "metadata", "table", "database_asset", "sql_metadata"]);
      }
      if (key === "knowledge") {
        return hasAny(["knowledge", "document", "web", "search"])
          && !hasAny(["asset", "metadata", "database_asset", "sql_metadata"]);
      }
      if (key === "sql_generate") {
        return hasAny(["sql generate", "generate sql"]);
      }
      if (key === "sql_execute") {
        return hasAny(["sql execute", "execute sql", "sql_query", "query"]);
      }
      if (key === "analysis") {
        return hasAny(["python", "analysis", "calculate", "compute"]);
      }
      if (key === "answer") {
        return hasAny(["answer", "assembly", "response", "final"]);
      }
      return false;
    },
    runtimeCurrentStage(message = {}) {
      if (!this.isExecutionRunning(message) && !message.streaming) {
        return this.runtimeStatusLabel(message);
      }
      const active = this.runtimeStageCards(message)
        .find((step) => String(step.status || "").toLowerCase() === "active");
      return active?.title || (this.isExecutionRunning(message) ? "Runtime Working" : this.executionTitle(message));
    },
    runtimeProgress(message = {}) {
      if (!this.isExecutionRunning(message) && !message.streaming && !["failed", "cancelled"].includes(message.status)) {
        return 100;
      }
      const cards = this.runtimeStageCards(message);
      if (!cards.length) {
        return 0;
      }
      const weights = cards.map((step) => {
        const status = String(step.status || "").toLowerCase();
        if (["done", "partial", "empty"].includes(status)) {
          return 1;
        }
        if (status === "active") {
          return 0.55;
        }
        return 0;
      });
      const progress = weights.reduce((sum, value) => sum + value, 0) / cards.length;
      return Math.max(5, Math.min(98, Math.round(progress * 100)));
    },
    runtimeStatusLabel(message = {}) {
      if (message.status === "waiting") {
        return "等待确认";
      }
      if (message.status === "failed") {
        return "失败";
      }
      if (message.status === "cancelled") {
        return "已取消";
      }
      if (message.status === "partial") {
        return "部分完成";
      }
      return this.isExecutionRunning(message) || message.streaming ? "Run" : "完成";
    },
    runtimeStageStatusText(step = {}) {
      const status = String(step.status || "pending").toLowerCase();
      if (status === "done") {
        return "Complete";
      }
      if (status === "active") {
        return "Running";
      }
      if (status === "partial") {
        return "Partial";
      }
      if (status === "empty") {
        return "Skipped";
      }
      if (status === "error") {
        return "Error";
      }
      if (status === "cancelled") {
        return "Cancelled";
      }
      return "Waiting";
    },
    runtimeEvents(message = {}) {
      const cards = this.runtimeStageCards(message)
        .filter((step) => !["pending"].includes(String(step.status || "").toLowerCase()))
        .slice(-6);
      const base = Number(message.timestamp || Date.now());
      return cards.map((step, index) => ({
        id: `${step.id}-event`,
        time: this.formatTime(base + index * 1000),
        label: `${step.title} - ${this.runtimeStageStatusText(step)}`
      }));
    },
    runtimeToolCalls(message = {}) {
      const steps = this.visibleExecutionSteps(message)
        .filter((step) => step.toolName && ["TOOL_CALL", "TOOL_RESULT", "RUNTIME_STEP", "RUNTIME_OBSERVATION"].includes(String(step.type || "").toUpperCase()));
      const explicit = steps.filter((step) => ["TOOL_CALL", "TOOL_RESULT"].includes(String(step.type || "").toUpperCase()));
      const selected = explicit.length ? explicit : steps.filter((step) => String(step.type || "").toUpperCase().startsWith("RUNTIME_"));
      if (selected.length) {
        return selected.map((step, index) => {
          const status = !this.isExecutionRunning(message) && !message.streaming && step.status === "active"
            ? "done"
            : step.status;
          return {
            id: step.id || `${message.id || "message"}-tool-${index}`,
            name: step.toolName,
            detail: step.detail || step.title || "",
            status,
            latencyMs: step.latencyMs,
            timestamp: step.timestamp
          };
        });
      }
      return (Array.isArray(message.traces) ? message.traces : [])
        .filter((trace) => trace?.toolName || trace?.displayName)
        .map((trace, index) => ({
          id: `trace:${trace.toolName || trace.displayName}:${trace.startedAt || index}`,
          name: trace.displayName || trace.toolName,
          detail: trace.displayName && trace.toolName && trace.displayName !== trace.toolName ? trace.toolName : "",
          status: trace.success === false ? "error" : "done",
          latencyMs: trace.durationMs,
          timestamp: trace.startedAt || message.timestamp
        }));
    },
    toolCallDone(call = {}) {
      return ["done", "partial", "empty"].includes(String(call.status || "").toLowerCase());
    },
    toolCallFailed(call = {}) {
      return ["error", "failed", "cancelled"].includes(String(call.status || "").toLowerCase());
    },
    runtimeToolStatusClass(message = {}) {
      const calls = this.runtimeToolCalls(message);
      const messageStatus = String(message.status || "").toLowerCase();
      if (["failed", "cancelled"].includes(messageStatus) || calls.some((call) => this.toolCallFailed(call))) {
        return "failed";
      }
      if (
        this.isExecutionRunning(message)
        || message.streaming
        || calls.some((call) => !this.toolCallDone(call) && !this.toolCallFailed(call))
      ) {
        return "running";
      }
      return "completed";
    },
    runtimeToolStatusLabel(message = {}) {
      const status = this.runtimeToolStatusClass(message);
      if (status === "failed") {
        return "失败";
      }
      if (status === "running") {
        return "正在内部运行";
      }
      return `完成（${this.runtimeToolCalls(message).length} 项）`;
    },
    renderMarkdown(content, message = {}) {
      const prepared = this.prepareMarkdownContent(String(content ?? ""), message);
      const uiContract = this.uiRenderContract(message, prepared.content);
      if (uiContract) {
        const rendered = this.renderUiRenderContract(uiContract, new Set(prepared.citationUrls), prepared.pages);
        return this.collapseToolEvidenceHtml(this.enhanceResultTables(rendered));
      }
      const rendered = markdown.render(prepared.content, {
        webCitationUrls: new Set(prepared.citationUrls)
      });
      return this.collapseToolEvidenceHtml(this.enhanceResultTables(rendered));
    },
    enhanceResultTables(html = "") {
      if (typeof DOMParser === "undefined" || !String(html || "").includes("<table")) {
        return html;
      }
      try {
        const parser = new DOMParser();
        const doc = parser.parseFromString(`<div>${html}</div>`, "text/html");
        const root = doc.body.firstElementChild;
        root.querySelectorAll("th").forEach((cell) => {
          if (/^(?:相关证据|证据来源|引用|引用来源)$/.test(String(cell.textContent || "").trim())) {
            cell.textContent = "主要来源";
          }
        });
        root.querySelectorAll("td").forEach((cell) => {
          const sourceTags = [...cell.querySelectorAll("a.web-citation-link")];
          if (sourceTags.length <= 2) {
            return;
          }
          sourceTags.slice(2).forEach((tag) => tag.classList.add("source-tag-overflow-hidden"));
          const toggle = doc.createElement("button");
          toggle.type = "button";
          toggle.className = "source-tag-overflow-toggle";
          toggle.dataset.sourceTagsToggle = "collapsed";
          toggle.textContent = `+${sourceTags.length - 2}`;
          toggle.setAttribute("aria-label", `展开其余 ${sourceTags.length - 2} 条来源`);
          cell.append(toggle);
        });
        const tables = [...root.querySelectorAll("table")];
        const tablePayloads = tables
          .map((table, index) => ({ table, payload: this.resultTablePayload(table, index) }))
          .filter((item) => item.payload);
        let firstWrapper = null;
        tablePayloads.forEach(({ table, payload }) => {
          const wrapper = doc.createElement("section");
          wrapper.className = "query-result-table-card";
          wrapper.dataset.resultChartPayload = encodeURIComponent(JSON.stringify(payload));
          const toolbar = doc.createElement("div");
          toolbar.className = "query-result-table-toolbar";
          const summary = doc.createElement("span");
          summary.textContent = `${payload.rows.length} 行 / ${payload.columns.length} 列`;
          const button = doc.createElement("button");
          button.type = "button";
          button.className = "query-result-chart-button";
          button.dataset.resultChartPayload = encodeURIComponent(JSON.stringify(payload));
          button.textContent = "图形分析";
          toolbar.append(summary, button);
          table.parentNode.insertBefore(wrapper, table);
          wrapper.append(toolbar, table);
          firstWrapper = firstWrapper || wrapper;
        });
        if (tablePayloads.length > 1 && firstWrapper?.parentNode) {
          const multiPayload = {
            title: "多数据集对比",
            datasets: tablePayloads.map((item, index) => ({
              id: `dataset_${index + 1}`,
              ...item.payload
            }))
          };
          const multiToolbar = doc.createElement("section");
          multiToolbar.className = "query-result-table-card query-result-multi-dataset-card";
          const toolbar = doc.createElement("div");
          toolbar.className = "query-result-table-toolbar";
          const summary = doc.createElement("span");
          summary.textContent = `共 ${tablePayloads.length} 个数据集`;
          const button = doc.createElement("button");
          button.type = "button";
          button.className = "query-result-chart-button";
          button.dataset.resultChartPayload = encodeURIComponent(JSON.stringify(multiPayload));
          button.textContent = "对比数据集";
          toolbar.append(summary, button);
          multiToolbar.append(toolbar);
          firstWrapper.parentNode.insertBefore(multiToolbar, firstWrapper);
        }
        return root.innerHTML;
      } catch (error) {
        console.warn("Enhance result table failed", error);
        return html;
      }
    },
    resultTablePayload(table, index) {
      const headers = [...table.querySelectorAll("thead th")]
        .map((cell) => this.decodeHtml(cell.textContent || "").trim())
        .filter(Boolean);
      if (headers.length < 2) {
        return null;
      }
      const rows = [...table.querySelectorAll("tbody tr")]
        .map((tr) => {
          const cells = [...tr.querySelectorAll("td")];
          if (!cells.length) {
            return null;
          }
          return Object.fromEntries(headers.map((column, columnIndex) => {
            const raw = this.decodeHtml(cells[columnIndex]?.textContent || "").trim();
            const number = parseNumber(raw);
            return [column, number !== null && raw !== "" ? number : raw];
          }));
        })
        .filter(Boolean);
      if (rows.length < 1) {
        return null;
      }
      const fallbackTitle = `查询结果 ${index + 1}`;
      return {
        title: this.nearestTableTitle(table) || fallbackTitle,
        columns: headers,
        rows
      };
    },
    nearestTableTitle(table) {
      let node = table.previousElementSibling;
      let distance = 0;
      while (node && distance < 6) {
        if (/^H[1-6]$/i.test(node.tagName)) {
          return this.decodeHtml(node.textContent || "").trim();
        }
        node = node.previousElementSibling;
        distance += 1;
      }
      return "";
    },
    collapseToolEvidenceHtml(html = "") {
      let source = String(html || "");
      const headingPattern = /<h([1-4])(?:\s[^>]*)?>([\s\S]*?)<\/h\1>/gi;
      const headings = [...source.matchAll(headingPattern)];
      const targets = headings.map((match, index) => ({
        match,
        bodyEnd: headings[index + 1]?.index ?? source.length
      })).filter(({ match }) => {
        if (Number(match[1]) < 2) {
          return false;
        }
        const title = this.decodeHtml(String(match[2] || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim());
        return /工具(?:执行|调用|运行)?(?:证据|证明|链路|结果)|Tool\s+(?:execution\s+)?(?:evidence|trace|calls?)/i.test(title);
      });
      if (!targets.length) {
        return source;
      }
      [...targets].reverse().forEach(({ match: target, bodyEnd }) => {
        const bodyStart = target.index + target[0].length;
        const body = source.slice(bodyStart, bodyEnd).trim();
        if (!body) {
          return;
        }
        const count = this.extractToolEvidenceItems(body).length;
        const collapsed = [
          '<details class="tool-evidence-details">',
          `<summary><span>工具链调用</span><small>${count ? `${count} steps` : "details"}</small></summary>`,
          `<div class="tool-evidence-body">${this.formatToolEvidenceBody(body)}</div>`,
          '</details>'
        ].join("");
        source = `${source.slice(0, target.index)}${collapsed}${source.slice(bodyEnd)}`;
      });
      return source;
    },    formatToolEvidenceBody(body = "") {
      const items = this.extractToolEvidenceItems(body);
      if (!items.length) {
        return body;
      }
      return `<div class="tool-evidence-list">${items.map((item) => this.renderToolEvidenceItem(item)).join("")}</div>`;
    },
    extractToolEvidenceItems(body = "") {
      const source = String(body || "");
      const liMatches = [...source.matchAll(/<li[^>]*>([\s\S]*?)<\/li>/gi)];
      const candidates = liMatches.length ? liMatches.map((match) => match[1]) : [source];
      return candidates
        .map((candidate) => this.parseToolEvidenceItem(candidate))
        .filter(Boolean);
    },
    parseToolEvidenceItem(fragment = "") {
      const codeMatch = /<code[^>]*>([\s\S]*?)<\/code>/i.exec(fragment);
      const text = this.decodeHtml(fragment.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim());
      const toolName = this.decodeHtml(codeMatch?.[1] || "").trim() || this.firstToolEvidenceToken(text);
      const semantic = this.matchToolEvidenceText(text, /[\[\u3010]\s*([^\]\u3011]+)\s*[\]\u3011]\s*[:\uFF1A]/);
      const status = /\b(success|succeeded|ok)\b/i.test(text) || text.includes("\u6210\u529f")
        ? "success"
        : /\b(failed|failure|error)\b/i.test(text) || text.includes("\u5931\u8d25")
          ? "failed"
          : this.matchToolEvidenceText(text, /\b(success|failed|error)\b/i);
      const evidenceType = this.matchToolEvidenceText(text, /(?:\u8bc1\u636e\u7c7b\u578b|evidence\s*type)\s*[=:\uFF1A]?\s*([A-Za-z0-9_-]+)/i);
      const durationMs = this.matchToolEvidenceText(text, /(?:\u8017\u65f6\s*ms|duration\s*ms)\s*[=:\uFF1A]?\s*(\d+)/i);
      const outputKeyText = this.matchToolEvidenceText(text, /(?:\u8f93\u51fa\u952e|output\s*keys?)\s*[=:\uFF1A]\s*(.*?)(?:\s*(?:\u7b49\s*\d+\s*\u9879|summary|\u6458\u8981)\s*[=:\uFF1A]|$)/i);
      const outputKeyTotal = this.matchToolEvidenceText(text, /(?:\u8f93\u51fa\u952e|output\s*keys?)\s*[=:\uFF1A].*?\u7b49\s*(\d+)\s*\u9879/i);
      const summary = this.matchToolEvidenceText(text, /(?:\u6458\u8981|summary)\s*[=:\uFF1A]\s*(.+)$/i);
      const outputKeys = String(outputKeyText || "")
        .split(/[,\uFF0C]\s*/)
        .map((item) => item.trim())
        .filter(Boolean);
      if (!toolName && !semantic && !status && !evidenceType && !durationMs && !outputKeys.length && !summary) {
        return null;
      }
      return {
        toolName,
        semantic,
        status: status || "-",
        statusClass: /fail|error/i.test(status || "") ? "failed" : "success",
        evidenceType: evidenceType || "-",
        durationMs: durationMs || "",
        outputKeys,
        outputKeyTotal: outputKeyTotal || "",
        summary: summary || ""
      };
    },
    renderToolEvidenceItem(item = {}) {
      const duration = item.durationMs
        ? `${escapeHtml(item.durationMs)} ms${Number(item.durationMs) >= 1000 ? ` / ${(Number(item.durationMs) / 1000).toFixed(1)} s` : ""}`
        : "-";
      const outputKeys = item.outputKeys.slice(0, 14);
      const keyChips = outputKeys.length
        ? outputKeys.map((key) => `<code>${escapeHtml(key)}</code>`).join("")
        : '<span class="tool-evidence-muted">-</span>';
      const total = item.outputKeyTotal || item.outputKeys.length;
      const more = Number(total) > outputKeys.length
        ? `<span class="tool-evidence-more">${escapeHtml(total)} total</span>`
        : "";
      return [
        `<article class="tool-evidence-item ${escapeHtml(item.statusClass)}">`,
        '<header>',
        '<div>',
        `<strong><code>${escapeHtml(item.toolName || "-")}</code></strong>`,
        item.semantic ? `<small>${escapeHtml(item.semantic)}</small>` : "",
        '</div>',
        `<span class="tool-evidence-status">${escapeHtml(item.status || "-")}</span>`,
        '</header>',
        '<dl class="tool-evidence-meta">',
        `<div><dt>Evidence</dt><dd>${escapeHtml(item.evidenceType || "-")}</dd></div>`,
        `<div><dt>Duration</dt><dd>${duration}</dd></div>`,
        '</dl>',
        '<section class="tool-evidence-keys">',
        `<strong>Output keys</strong><div>${keyChips}${more}</div>`,
        '</section>',
        item.summary ? `<p class="tool-evidence-summary"><strong>Summary</strong>${escapeHtml(item.summary)}</p>` : "",
        '</article>'
      ].join("");
    },
    matchToolEvidenceText(text = "", pattern) {
      const match = pattern.exec(String(text || ""));
      return match?.[1] ? String(match[1]).trim() : "";
    },
    firstToolEvidenceToken(text = "") {
      const match = /^\s*([^\s:]+)/.exec(String(text || ""));
      return match?.[1] || "";
    },
    decodeHtml(value = "") {
      const entities = {
        amp: "&",
        lt: "<",
        gt: ">",
        quot: '"',
        "#039": "'",
        nbsp: " "
      };
      return String(value || "").replace(/&([^;]+);/g, (match, entity) => entities[entity] || match);
    },
    metadataColumnSections(message = {}) {
      const traces = Array.isArray(message.traces) ? message.traces : [];
      if (!traces.length) {
        return [];
      }
      const sections = [];
      const sectionIndex = new Map();
      traces.forEach((trace) => {
        [
          trace?.output,
          trace?.structuredContent,
          trace?.data,
          trace?.result,
          trace?.toolOutput,
          trace?.response,
          trace
        ].forEach((candidate) => {
          this.collectMetadataColumnSections(
            this.parseMetadataTracePayload(candidate),
            sections,
            sectionIndex,
            new WeakSet()
          );
        });
      });
      return sections;
    },
    parseMetadataTracePayload(value) {
      if (!value || typeof value !== "string") {
        return value;
      }
      const text = value.trim();
      if (!text || !/^[{\[]/.test(text)) {
        return value;
      }
      try {
        return JSON.parse(text);
      } catch (error) {
        return value;
      }
    },
    collectMetadataColumnSections(value, sections, sectionIndex, visited) {
      const current = this.parseMetadataTracePayload(value);
      if (typeof current === "string") {
        this.collectMetadataColumnSectionsFromText(current, sections, sectionIndex);
        return;
      }
      if (!current || typeof current !== "object") {
        return;
      }
      if (visited.has(current)) {
        return;
      }
      visited.add(current);
      if (Array.isArray(current)) {
        current.forEach((item) => this.collectMetadataColumnSections(item, sections, sectionIndex, visited));
        return;
      }
      if (this.looksLikeMetadataColumns(current.columns)) {
        this.addMetadataColumnSection(current, sections, sectionIndex);
      }
      Object.entries(current).forEach(([key, child]) => {
        if (key === "columns") {
          return;
        }
        this.collectMetadataColumnSections(child, sections, sectionIndex, visited);
      });
    },
    collectMetadataColumnSectionsFromText(text = "", sections, sectionIndex) {
      const source = String(text || "");
      const tablePattern = /###\s+Table\s+\d+\s*:\s*`([^`]+)`([\s\S]*?)(?=\n###\s+Table\s+\d+\s*:|\n##\s+|$)/gi;
      for (const match of source.matchAll(tablePattern)) {
        const qualifiedName = this.stripMetadataMarkdownCell(match[1]);
        const body = match[2] || "";
        const lines = body.split(/\r?\n/).filter((line) => /^\s*\|/.test(line));
        const headerIndex = lines.findIndex((line) => /\|\s*(?:Column|Field|字段)/i.test(line));
        if (!qualifiedName || headerIndex < 0) {
          continue;
        }
        const columns = lines.slice(headerIndex + 2)
          .map((line) => this.metadataMarkdownCells(line))
          .filter((cells) => cells.length >= 6 && /^\d+$/.test(cells[0]))
          .map((cells, index) => ({
            ordinalPosition: Number(cells[0]) - 1 || index,
            name: cells[1],
            columnType: cells[2],
            columnKey: cells[3],
            nullable: cells[4],
            comment: cells[5]
          }));
        if (columns.length) {
          this.addMetadataColumnSection({ tableName: qualifiedName, columns }, sections, sectionIndex);
        }
      }
    },
    metadataMarkdownCells(line = "") {
      const protectedPipes = String(line || "").replace(/\\\|/g, "\u0000");
      return protectedPipes
        .replace(/^\s*\|/, "")
        .replace(/\|\s*$/, "")
        .split("|")
        .map((cell) => this.stripMetadataMarkdownCell(cell.replace(/\u0000/g, "|")));
    },
    stripMetadataMarkdownCell(value = "") {
      return String(value || "")
        .trim()
        .replace(/^`+|`+$/g, "")
        .replace(/\\([|`])/g, "$1")
        .trim();
    },
    looksLikeMetadataColumns(columns) {
      if (!Array.isArray(columns) || !columns.length) {
        return false;
      }
      const first = columns.find((item) => item && typeof item === "object");
      if (!first) {
        return false;
      }
      return !!(first.name || first.columnName || first.fieldName)
        && !!(first.dataType || first.columnType || first.type || first.table || first.schema || first.ordinalPosition !== undefined);
    },
    addMetadataColumnSection(result = {}, sections, sectionIndex) {
      const rawColumns = Array.isArray(result.columns) ? result.columns : [];
      const firstColumn = rawColumns.find((item) => item && typeof item === "object") || {};
      const location = result.location && typeof result.location === "object" ? result.location : {};
      const tableName = this.metadataText(result.tableName || result.table || location.tableName || location.table || result.name || firstColumn.table);
      const schemaName = this.metadataText(result.schemaName || result.schema || location.schemaName || location.schema || firstColumn.schema);
      const databaseName = this.metadataText(result.databaseName || result.database || location.databaseName || location.database || firstColumn.database);
      const datasourceId = this.metadataText(result.datasourceId || result.assetId || location.datasourceId || location.assetId || firstColumn.datasourceId);
      const identity = [
        datasourceId,
        databaseName,
        schemaName,
        tableName || `metadata-${sections.length + 1}`
      ].join(".");
      let section = sectionIndex.get(identity);
      if (!section) {
        const titleParts = [databaseName, schemaName, tableName].filter(Boolean);
        section = {
          id: identity,
          title: titleParts.length ? titleParts.join(".") : `元数据字段组 ${sections.length + 1}`,
          comment: this.metadataText(result.comment || result.tableComment || location.tableComment || location.comment || result.description || result.summary),
          columns: [],
          columnIndex: new Set()
        };
        sectionIndex.set(identity, section);
        sections.push(section);
      }
      rawColumns
        .map((column, index) => this.normalizeMetadataColumn(column, index, section))
        .filter(Boolean)
        .forEach((column) => {
          if (section.columnIndex.has(column.id)) {
            return;
          }
          section.columnIndex.add(column.id);
          section.columns.push(column);
        });
      section.columns.sort((left, right) => left.sortOrdinal - right.sortOrdinal || left.name.localeCompare(right.name));
    },
    normalizeMetadataColumn(column = {}, index = 0, section = {}) {
      if (!column || typeof column !== "object") {
        return null;
      }
      const name = this.metadataText(column.name || column.columnName || column.fieldName || column.COLUMN_NAME);
      if (!name) {
        return null;
      }
      const ordinalValue = Number(column.ordinalPosition ?? column.position ?? column.ordinal ?? index);
      const sortOrdinal = Number.isFinite(ordinalValue) ? ordinalValue : index;
      const ordinal = sortOrdinal >= 0 ? sortOrdinal + 1 : index + 1;
      const nullable = column.nullable === true
        ? "Yes"
        : column.nullable === false
          ? "No"
          : this.metadataText(column.nullable || column.isNullable || "");
      return {
        id: `${section.id || "metadata"}:${name}`,
        name,
        type: this.metadataText(column.dataType || column.columnType || column.type || column.COLUMN_TYPE || column.DATA_TYPE),
        key: this.metadataText(column.columnKey || column.key || column.primaryKey || column.COLUMN_KEY),
        nullable: nullable || "-",
        comment: this.metadataText(column.comment || column.description || column.remark || column.remarks || column.COLUMN_COMMENT),
        ordinal,
        sortOrdinal
      };
    },
    metadataText(value) {
      if (value === null || value === undefined) {
        return "";
      }
      if (typeof value === "object") {
        return "";
      }
      return String(value).trim();
    },
    metadataTableCatalog(message = {}) {
      const traces = Array.isArray(message.traces) ? message.traces : [];
      const catalog = {
        totalMatched: 0,
        catalogTruncated: false,
        rows: []
      };
      if (!traces.length) {
        return catalog;
      }
      const seen = new Set();
      traces.forEach((trace) => {
        [
          trace?.output,
          trace?.structuredContent,
          trace?.data,
          trace?.result,
          trace?.toolOutput,
          trace?.response,
          trace
        ].forEach((candidate) => {
          this.collectMetadataTableCatalog(
            this.parseMetadataTracePayload(candidate),
            catalog,
            seen,
            new WeakSet()
          );
        });
      });
      if (!catalog.totalMatched) {
        catalog.totalMatched = catalog.rows.length;
      }
      catalog.rows = catalog.rows.map((row, index) => ({
        ...row,
        index: index + 1
      }));
      return catalog;
    },
    collectMetadataTableCatalog(value, catalog, seen, visited) {
      const current = this.parseMetadataTracePayload(value);
      if (typeof current === "string") {
        this.collectMetadataTableCatalogFromText(current, catalog, seen);
        return;
      }
      if (!current || typeof current !== "object") {
        return;
      }
      if (visited.has(current)) {
        return;
      }
      visited.add(current);
      if (Array.isArray(current)) {
        current.forEach((item) => this.collectMetadataTableCatalog(item, catalog, seen, visited));
        return;
      }
      if (Array.isArray(current.tableCatalog)) {
        const totalMatched = Number(current.totalMatched);
        if (Number.isFinite(totalMatched) && totalMatched > catalog.totalMatched) {
          catalog.totalMatched = totalMatched;
        }
        catalog.catalogTruncated = catalog.catalogTruncated || current.catalogTruncated === true;
        current.tableCatalog
          .map((item) => this.normalizeMetadataCatalogRow(item))
          .filter(Boolean)
          .forEach((row) => {
            if (seen.has(row.id)) {
              return;
            }
            seen.add(row.id);
            catalog.rows.push(row);
          });
      }
      if (current.location && Array.isArray(current.columns)) {
        const row = this.normalizeMetadataCatalogRow({
          ...current.location,
          assetId: current.assetId || current.datasourceId || current.location.assetId || current.location.datasourceId,
          score: current.score,
          tableComment: current.tableComment || current.comment || current.location.tableComment || current.location.comment
        });
        if (row && !seen.has(row.id)) {
          seen.add(row.id);
          catalog.rows.push(row);
        }
      }
      Object.entries(current).forEach(([key, child]) => {
        if (key === "tableCatalog") {
          return;
        }
        this.collectMetadataTableCatalog(child, catalog, seen, visited);
      });
    },
    collectMetadataTableCatalogFromText(text = "", catalog, seen) {
      const source = String(text || "");
      const totalMatch = /totalMatched\s*=\s*(\d+)/i.exec(source);
      if (totalMatch) {
        catalog.totalMatched = Math.max(catalog.totalMatched, Number(totalMatch[1]) || 0);
      }
      catalog.catalogTruncated = catalog.catalogTruncated || /catalogTruncated\s*=\s*true/i.test(source);
      const catalogSection = /##\s+Matched table catalog([\s\S]*?)(?=\n##\s+|$)/i.exec(source)?.[1] || "";
      const lines = catalogSection.split(/\r?\n/).filter((line) => /^\s*\|/.test(line));
      const headerIndex = lines.findIndex((line) => /\|\s*Table\s*\|/i.test(line));
      if (headerIndex >= 0) {
        lines.slice(headerIndex + 2)
          .map((line) => this.metadataMarkdownCells(line))
          .filter((cells) => cells.length >= 5 && /^\d+$/.test(cells[0]))
          .map((cells) => this.normalizeMetadataCatalogRow({
            database: cells[1],
            schema: cells[2],
            tableName: cells[3],
            tableComment: cells[4]
          }))
          .filter(Boolean)
          .forEach((row) => {
            if (!seen.has(row.id)) {
              seen.add(row.id);
              catalog.rows.push(row);
            }
          });
      }
      const detailPattern = /###\s+Table\s+\d+\s*:\s*`([^`]+)`/gi;
      for (const match of source.matchAll(detailPattern)) {
        const qualifiedName = this.stripMetadataMarkdownCell(match[1]);
        const row = this.normalizeMetadataCatalogRow({ tableName: qualifiedName });
        if (row && !seen.has(row.id)) {
          seen.add(row.id);
          catalog.rows.push(row);
        }
      }
    },
    normalizeMetadataCatalogRow(item = {}) {
      if (!item || typeof item !== "object") {
        return null;
      }
      const tableName = this.metadataText(item.tableName || item.table || item.name);
      if (!tableName) {
        return null;
      }
      const database = this.metadataText(item.database || item.databaseName);
      const schema = this.metadataText(item.schema || item.schemaName);
      const assetId = this.metadataText(item.assetId || item.datasourceId);
      return {
        id: [assetId, database, schema, tableName].join("."),
        database,
        schema,
        tableName,
        tableComment: this.metadataText(item.tableComment || item.comment || item.description),
        score: this.metadataText(item.score)
      };
    },
    extractUiResponse(message = {}) {
      const candidates = [
        message.uiResponse,
        message.metadata?.uiResponse,
        message.executionResult?.uiResponse,
        message.metadata?.executionResult?.uiResponse
      ];
      const explicit = candidates.find((candidate) => candidate && typeof candidate === "object");
      if (explicit) {
        return explicit;
      }
      const citations = message.citations || message.sources || [];
      const evidencePremises = message.evidencePremises || [];
      const hasProtocolMetadata = message.confidence !== undefined
        || (Array.isArray(citations) && citations.length > 0)
        || (Array.isArray(evidencePremises) && evidencePremises.length > 0);
      if (!hasProtocolMetadata) {
        return null;
      }
      return {
        contractVersion: "ui_response_v1",
        status: message.status || "",
        answer: message.content || "",
        citations,
        evidencePremises,
        confidence: message.confidence ?? null,
        evidenceSummary: message.evidenceSummary || ""
      };
    },
    uiRenderContract(message = {}, fallbackContent = "") {
      const uiResponse = this.extractUiResponse(message);
      if (!uiResponse) {
        return null;
      }
      const answerBlocks = this.normalizeUiAnswerBlocks(uiResponse, fallbackContent);
      const citations = this.normalizeUiCitations(uiResponse.citations || message.sources || []);
      const steps = [];
      const warnings = this.normalizeUiWarnings(uiResponse);
      const evidenceSummary = this.cleanUiProtocolText(uiResponse.evidenceSummary || "");
      const confidence = this.normalizeUiConfidence(uiResponse.confidence);
      const hasRenderableContent = answerBlocks.length || citations.length || warnings.length || evidenceSummary || confidence !== null;
      if (!hasRenderableContent) {
        return null;
      }
      return {
        contractVersion: uiResponse.uiRenderContractVersion || "ui_render_contract_v1",
        sourceContractVersion: uiResponse.contractVersion || "",
        status: String(uiResponse.status || message.status || "").trim(),
        answerBlocks,
        citations,
        steps,
        confidence,
        warnings,
        evidenceSummary
      };
    },
    normalizeUiAnswerBlocks(uiResponse = {}, fallbackContent = "") {
      const rawBlocks = Array.isArray(uiResponse.answerBlocks) ? uiResponse.answerBlocks : [];
      const blocks = rawBlocks
        .map((block) => {
          if (typeof block === "string") {
            return { type: "markdown", text: this.cleanUiProtocolText(block) };
          }
          if (!block || typeof block !== "object") {
            return null;
          }
          return {
            type: String(block.type || "markdown").trim().toLowerCase(),
            text: this.cleanUiProtocolText(block.text || block.content || block.answer || "")
          };
        })
        .filter((block) => block?.text);
      if (blocks.length) {
        return blocks;
      }
      const answer = this.cleanUiProtocolText(uiResponse.answer || fallbackContent);
      return answer ? [{ type: "markdown", text: answer }] : [];
    },
    normalizeUiCitations(citations = []) {
      if (!Array.isArray(citations)) {
        return [];
      }
      return citations
        .map((citation, index) => {
          if (!citation || typeof citation !== "object") {
            return null;
          }
          const sourceRef = String(citation.sourceRef || citation.refId || citation.docId || citation.source || "").trim();
          const rawTitle = String(citation.title || citation.name || citation.sourceTitle || citation.documentName || "").trim();
          const title = this.cleanUiCitationTitle(rawTitle || sourceRef, index);
          const text = this.cleanUiProtocolText(citation.text || citation.snippet || citation.content || citation.summary || "");
          const urlCandidate = citation.url || citation.link || citation.href || citation.sourceUrl
            || (/^https?:\/\//i.test(sourceRef) ? sourceRef : "");
          const url = String(urlCandidate || "").trim();
          const publisher = this.cleanUiProtocolText(
            citation.publisher || citation.siteName || citation.sourceName || citation.organization || ""
          );
          const publishDate = String(citation.publishDate || citation.publishedAt || citation.publish_time || "").trim();
          return { title, text, url, sourceRef, publisher, publishDate };
        })
        .filter(Boolean);
    },
    cleanUiCitationTitle(value, index = 0) {
      const text = String(value || "").trim();
      if (!text || /^doc:\/\//i.test(text) || /^\d{8}_[a-f0-9]{6,}$/i.test(text)) {
        return `\u6587\u6863 ${index + 1}`;
      }
      return this.cleanUiProtocolText(text) || `\u6587\u6863 ${index + 1}`;
    },
    normalizeUiSteps(steps = []) {
      if (!Array.isArray(steps)) {
        return [];
      }
      return steps
        .map((step, index) => {
          if (typeof step === "string") {
            const text = this.cleanUiProtocolText(step);
            return text ? { title: `\u6b65\u9aa4 ${index + 1}`, text } : null;
          }
          if (!step || typeof step !== "object") {
            return null;
          }
          const title = this.cleanUiProtocolText(step.title || step.name || step.label || `\u6b65\u9aa4 ${index + 1}`);
          const text = this.cleanUiProtocolText(step.text || step.content || step.summary || step.action || step.claim || "");
          return title || text ? { title, text } : null;
        })
        .filter(Boolean);
    },
    normalizeUiWarnings(uiResponse = {}) {
      const rawWarnings = Array.isArray(uiResponse.warnings) ? uiResponse.warnings : [];
      const warnings = rawWarnings
        .map((warning) => this.cleanUiProtocolText(warning?.message || warning?.text || warning))
        .filter(Boolean);
      const status = String(uiResponse.status || "").trim().toUpperCase();
      if (["FAILED", "PARTIAL", "EMPTY", "CONFLICTED"].includes(status) && !warnings.length) {
        warnings.push(this.uiStatusWarning(status));
      }
      return warnings.filter(Boolean);
    },
    uiStatusWarning(status) {
      if (status === "FAILED") {
        return "\u672c\u6b21\u6267\u884c\u672a\u5b8c\u6210\uff0c\u7ed3\u679c\u53ef\u80fd\u4e0d\u5b8c\u6574\u3002";
      }
      if (status === "EMPTY") {
        return "\u672a\u627e\u5230\u53ef\u76f4\u63a5\u5c55\u793a\u7684\u7ed3\u679c\u3002";
      }
      if (status === "CONFLICTED") {
        return "\u8bc1\u636e\u4e4b\u95f4\u5b58\u5728\u51b2\u7a81\uff0c\u5df2\u4fdd\u7559\u8fb9\u754c\u8bf4\u660e\u3002";
      }
      return "\u4ec5\u90e8\u5206\u8bc1\u636e\u53ef\u7528\uff0c\u7ed3\u679c\u9700\u7ed3\u5408\u4e0b\u65b9\u5f15\u7528\u6838\u5bf9\u3002";
    },
    normalizeUiConfidence(value) {
      const confidence = Number(value);
      return Number.isFinite(confidence) ? confidence : null;
    },
    cleanUiProtocolText(value) {
      return this.stripResidualUncertaintyBlocks(this.stripInternalDocumentRefs(String(value || ""))).trim();
    },
    stripResidualUncertaintyBlocks(content) {
      return String(content || "")
        .replace(/(?:^|\n)\s*#{1,6}\s*(?:\u4e0d\u786e\u5b9a\u6027\u8bf4\u660e|uncertainty)\s*\n*(?=(?:\n\s*#{1,6}\s)|$)/gi, "\n")
        .replace(/(?:^|\n)\s*(?:\u4e0d\u786e\u5b9a\u6027\u8bf4\u660e|uncertainty)\s*[:\uFF1A]?\s*(?=\n|$)/gi, "\n");
    },
    renderUiRenderContract(contract = {}, citationUrls = new Set(), pages = []) {
      const env = { webCitationUrls: citationUrls };
      const answerHtml = (contract.answerBlocks || [])
        .map((block) => {
          const text = this.cleanUiProtocolText(block?.text || "");
          if (!text) {
            return "";
          }
          const linked = inlineWebCitationLinks(text, pages);
          linked.citationUrls.forEach((url) => citationUrls.add(url));
          return `<section class="ui-render-answer-block">${markdown.render(linked.content, env)}</section>`;
        })
        .join("");
      const confidence = this.formatConfidencePercent(contract.confidence);
      const metrics = [
        confidence ? `<span>${escapeHtml("\u53ef\u4fe1\u5ea6")} <b>${escapeHtml(confidence)}</b></span>` : "",
        contract.citations?.length ? `<span>${escapeHtml("\u5f15\u7528")} <b>${escapeHtml(`${contract.citations.length} \u6761`)}</b></span>` : ""
      ].filter(Boolean).join("");
      const summary = contract.evidenceSummary
        ? `<p class="ui-render-evidence-summary">${markdown.renderInline(contract.evidenceSummary, env)}</p>`
        : "";
      const warnings = this.renderUiContractWarnings(contract.warnings || []);

      return [
        '<section class="evidence-reasoning-card ui-render-contract-card">',
        answerHtml || summary || `<p>${escapeHtml("\u6682\u65e0\u53ef\u5c55\u793a\u7ed3\u679c\u3002")}</p>`,
        metrics ? `<section class="answer-result-summary">${metrics}</section>` : "",
        warnings,
        '</section>'
      ].filter(Boolean).join("");
    },
    renderUiContractSteps(steps = []) {
      const items = steps
        .map((step) => {
          const title = this.cleanUiProtocolText(step?.title || "");
          const text = this.cleanUiProtocolText(step?.text || "");
          if (!title && !text) {
            return "";
          }
          const body = text ? `<p>${markdown.renderInline(text)}</p>` : "";
          return `<li><strong>${escapeHtml(title || "\u6b65\u9aa4")}</strong>${body}</li>`;
        })
        .filter(Boolean)
        .join("");
      if (!items) {
        return "";
      }
      return [
        '<section class="ui-render-steps">',
        `<h4>${escapeHtml("\u6267\u884c\u6b65\u9aa4")}</h4>`,
        `<ol>${items}</ol>`,
        '</section>'
      ].join("");
    },
    renderUiContractWarnings(warnings = []) {
      const items = warnings
        .map((warning) => this.cleanUiProtocolText(warning))
        .filter(Boolean)
        .map((warning) => `<li>${markdown.renderInline(warning)}</li>`)
        .join("");
      if (!items) {
        return "";
      }
      return [
        '<section class="ui-render-warnings">',
        `<h4>${escapeHtml("\u8fb9\u754c\u8bf4\u660e")}</h4>`,
        `<ul>${items}</ul>`,
        '</section>'
      ].join("");
    },
    handleVisualizationDrillDown(message, event = {}) {
      this.$emit("visualization-drill-down", {
        message,
        event
      });
    },
    defaultRunningSteps(message = {}) {
      return [
        {
          id: `${message.id || "message"}-runtime-start`,
          title: "Starting run",
          detail: "Waiting for the first execution event",
          status: "active"
        }
      ];
    },
    prepareMarkdownContent(content, message = {}) {
      const displayContent = this.stripInternalDocumentRefs(
        this.stripExecutedSqlContent(this.stripInternalProtocolBlocks(this.stripVisualizationSpecBlocks(content)))
      );
      const pages = this.webReferencePages(message);
      const normalizedContent = this.stripTrailingWebReferenceSection(
        this.stripExecutedSqlContent(this.normalizeRenderContractBlocks(displayContent)),
        pages
      );
      const linked = inlineWebCitationLinks(normalizedContent, pages);
      return { content: linked.content, citationUrls: linked.citationUrls, pages };
    },
    stripTrailingWebReferenceSection(content, pages = []) {
      const text = String(content || "");
      if (!pages.length || !text.trim()) {
        return text;
      }
      const heading = /(?:^|\n)\s{0,3}#{1,6}\s*(?:引用来源|参考来源|查询来源|来源链接|sources?|references?)\s*[:：]?\s*(?=\n|$)/ig;
      let match;
      let lastMatch = null;
      while ((match = heading.exec(text)) !== null) {
        lastMatch = match;
      }
      if (!lastMatch) {
        return text;
      }
      const trailingSection = text.slice(lastMatch.index);
      const hasLinkOrList = /https?:\/\/|(?:^|\n)\s*(?:[-*+]\s+|\d+[.)]\s+)/i.test(trailingSection);
      return hasLinkOrList ? text.slice(0, lastMatch.index).trimEnd() : text;
    },
    webReferencePages(message = {}) {
      const uiResponse = this.extractUiResponse(message) || {};
      const citations = [
        ...(Array.isArray(uiResponse.citations) ? uiResponse.citations : []),
        ...(Array.isArray(message.citations) ? message.citations : []),
        ...(Array.isArray(message.sources)
          ? message.sources.filter((source) => /^(?:https?:\/\/|web:\/\/)/i.test(String(
            source?.url || source?.link || source?.href || source?.sourceUrl || source?.sourceRef || source?.source || ""
          )))
          : [])
      ];
      const tracePages = extractWebSearchPagesFromTraces(message.traces || []);
      const citationPages = extractWebCitationPages(citations);
      const byRank = new Map();
      [...tracePages, ...citationPages].forEach((page, index) => {
        const rank = Number(page?.rank) || index + 1;
        const current = byRank.get(rank) || {};
        byRank.set(rank, {
          ...current,
          ...page,
          rank,
          title: page?.title || current.title || "",
          publisher: page?.publisher || current.publisher || "",
          url: page?.url || current.url || "",
          snippet: page?.snippet || current.snippet || "",
          publishDate: page?.publishDate || current.publishDate || "",
          accessedAt: page?.accessedAt || current.accessedAt || ""
        });
      });
      return [...byRank.values()].sort((left, right) => left.rank - right.rank);
    },
    stripExecutedSqlContent(content) {
      return String(content || "")
        .replace(/```sql\s*[\s\S]*?```/gi, (match, offset, source) => {
          const before = source.slice(Math.max(0, offset - 360), offset);
          return EXECUTED_SQL_CONTEXT_RE.test(before) ? "" : match;
        })
        .replace(/(^|\n)\s*(?:\u6267\u884c\u7684?\s*SQL|\u5b9e\u9645\u6267\u884c\u8bed\u53e5|\u67e5\u8be2\u8bed\u53e5|\u5177\u4f53\u8bed\u53e5|Executed\s+SQL|SQL\s+Statement)\s*[:\uFF1A]\s*[^\n]*(?=\n|$)/gi, "$1")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
    },
    stripInternalDocumentRefs(content) {
      return String(content || "")
        .replace(INTERNAL_DOCUMENT_REF_RE, "")
        .replace(/[ \t]*[\(\uFF08]\s*(?:confidence|missingInfo)\s*[\)\uFF09]/gi, "")
        .replace(/[ \t]*[\[\uFF3B][ \t]*[\]\uFF3D][ \t]*/g, " ")
        .replace(/[ \t]+([,.;:!?\uFF0C\u3002\uFF1B\uFF1A])/g, "$1")
        .replace(/([,.;:!?\uFF0C\u3002\uFF1B\uFF1A])[ \t]*[\[\uFF3B][ \t]*[\]\uFF3D]/g, "$1")
        .replace(/^[ \t]*[:\uFF1A][ \t]*$/gm, "")
        .replace(/[ \t]{2,}/g, " ")
        .replace(/\n[ \t]+\n/g, "\n\n")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
    },
    stripInternalProtocolBlocks(content) {
      let text = String(content || "");
      const lockedMatch = text.match(/---BEGIN_LOCKED_ANSWER---([\s\S]*?)---END_LOCKED_ANSWER---/i);
      if (lockedMatch?.[1]) {
        text = lockedMatch[1];
      }
      text = text.replace(/reasoningPayload:\s*```json\s*[\s\S]*?```/gi, "");
      text = text.replace(/```json\s*([\s\S]*?)```/gi, (match, body) => {
        return /"?(uiResponse|executionResult|reasoningTrace|trustedSql|deterministicFacts|contractVersion)"?\s*:/i.test(body || "")
          ? ""
          : match;
      });
      return text
        .replace(/Deterministic answer lock\s*\([^)]+\):[\s\S]*?lockedAnswer:\s*/i, "")
        .replace(/---BEGIN_LOCKED_ANSWER---|---END_LOCKED_ANSWER---/gi, "")
        .trim();
    },
    stripVisualizationSpecBlocks(content) {
      return String(content || "").replace(/```json\s*([\s\S]*?)```/gi, (match, body) => {
        if (/"?(visualizationSpec|dataVisualization)"?\s*:/i.test(body || "")) {
          return "";
        }
        return match;
      }).trim();
    },
    normalizeRenderContractBlocks(content) {
      const lines = String(content || "").replace(/\r\n/g, "\n").split("\n");
      const output = [];
      const pending = [];
      let inFence = false;
      let fenceMarker = "";

      const emitPendingSql = () => {
        if (!pending.length) {
          return;
        }
        if (!this.isConfidentSqlBlock(pending)) {
          output.push(...pending);
          pending.length = 0;
          return;
        }
        if (output.length && output[output.length - 1].trim()) {
          output.push("");
        }
        output.push("```sql", ...pending, "```");
        pending.length = 0;
      };

      lines.forEach((line, index) => {
        const fence = line.match(FENCE_RE);
        if (fence) {
          emitPendingSql();
          const marker = fence[2];
          const language = fence[4] || "";
          if (!inFence) {
            const nextLine = lines[index + 1] || "";
            if (!language && SQL_START_RE.test(nextLine)) {
              output.push(`${fence[1]}${marker}${fence[3]}sql`);
            } else if (!language && JSON_START_RE.test(nextLine)) {
              output.push(`${fence[1]}${marker}${fence[3]}json`);
            } else {
              output.push(line);
            }
            inFence = true;
            fenceMarker = marker;
            return;
          }
          output.push(line);
          if (marker[0] === fenceMarker[0] && marker.length >= fenceMarker.length) {
            inFence = false;
            fenceMarker = "";
          }
          return;
        }

        if (inFence) {
          output.push(line);
          return;
        }

        if (pending.length) {
          if (this.shouldContinueSqlBlock(line)) {
            pending.push(line);
            return;
          }
          emitPendingSql();
        }

        if (this.isSqlBlockStart(line)) {
          pending.push(line);
          return;
        }

        output.push(line);
      });
      emitPendingSql();
      return output.join("\n");
    },
    isSqlBlockStart(line) {
      return SQL_START_RE.test(line);
    },
    shouldContinueSqlBlock(line) {
      if (!line.trim()) {
        return false;
      }
      if (SECTION_BOUNDARY_RE.test(line) && !SQL_CONTINUATION_RE.test(line)) {
        return false;
      }
      return SQL_START_RE.test(line)
        || SQL_CONTINUATION_RE.test(line)
        || /^[\s\w."'`[\]-]+\s+'.*'[,]?\s*$/i.test(line)
        || /^[\s\w."'`[\]-]+\s+\d+[,]?\s*$/i.test(line)
        || /^\s*[),;]+\s*$/.test(line);
    },
    isConfidentSqlBlock(lines) {
      const text = lines.join("\n");
      if (/;\s*$/.test(text.trim())) {
        return true;
      }
      return lines.length > 1
        && /\b(CREATE|SELECT|INSERT|UPDATE|DELETE|MERGE|USING|OPTIONS|FROM|WHERE|JOIN)\b/i.test(text)
        && /[()]/.test(text);
    },
    parseEvidenceReasoning(content) {
      const text = String(content || "");
      const candidates = this.extractEvidenceReasoningCandidates(text);
      let sawV2Shape = false;
      for (const candidate of candidates) {
        if (!/evidence_reasoning_v2|executionSpec|executionDag|contractHash|graphViewHash/.test(candidate || "")) {
          continue;
        }
        sawV2Shape = true;
        try {
          const parsed = JSON.parse(candidate);
          const normalized = this.normalizeEvidenceReasoning(parsed);
          if (!normalized) {
            continue;
          }
          const validation = this.validateEvidenceReasoning(normalized);
          return validation.valid
            ? normalized
            : {
                fallback: true,
                errors: validation.errors,
                parsed: normalized
              };
        } catch (error) {
          // Keep scanning other candidates; a markdown answer can contain unrelated JSON first.
        }
      }
      if (sawV2Shape) {
        return { fallback: true, errors: ["v2 JSON parse failed"] };
      }
      return null;
    },
    extractEvidenceReasoningCandidates(content) {
      const text = String(content || "").trim();
      const candidates = [];
      const fencePattern = /```(?:json)?\s*([\s\S]*?)```/gi;
      let match;
      while ((match = fencePattern.exec(text)) !== null) {
        candidates.push(String(match[1] || "").trim());
      }
      const lockedMatch = text.match(/---BEGIN_LOCKED_ANSWER---([\s\S]*?)---END_LOCKED_ANSWER---/i);
      if (lockedMatch?.[1]) {
        candidates.push(...this.extractEvidenceReasoningCandidates(lockedMatch[1]));
      }
      if (text.startsWith("{") && text.endsWith("}")) {
        candidates.push(text);
      }
      const inlineJson = this.extractBalancedEvidenceJson(text);
      if (inlineJson) {
        candidates.push(inlineJson);
      }
      return [...new Set(candidates.filter(Boolean))];
    },
    extractBalancedEvidenceJson(content) {
      const text = String(content || "");
      const marker = text.indexOf('"type"');
      const typeMarker = text.indexOf("evidence_reasoning_v2");
      const anchor = marker >= 0 ? marker : typeMarker;
      if (anchor < 0) {
        return "";
      }
      const start = text.lastIndexOf("{", anchor);
      if (start < 0) {
        return "";
      }
      let depth = 0;
      let inString = false;
      let escaped = false;
      for (let index = start; index < text.length; index += 1) {
        const char = text[index];
        if (escaped) {
          escaped = false;
          continue;
        }
        if (char === "\\") {
          escaped = inString;
          continue;
        }
        if (char === '"') {
          inString = !inString;
          continue;
        }
        if (inString) {
          continue;
        }
        if (char === "{") {
          depth += 1;
        } else if (char === "}") {
          depth -= 1;
          if (depth === 0) {
            return text.slice(start, index + 1).trim();
          }
        }
      }
      return "";
    },
    normalizeEvidenceReasoning(value) {
      if (!value || typeof value !== "object") {
        return null;
      }
      const hasReasoningShape = value.type === "evidence_reasoning_v2"
        || (value.executionSpec && value.evidence && value.executionDag);
      if (!hasReasoningShape) {
        return null;
      }
      const evidence = value.evidence || {};
      const dag = value.executionDag || {};
      return {
        ...value,
        type: value.type || "evidence_reasoning_v2",
        contractHash: String(value.contractHash || "").trim(),
        graphViewHash: String(value.graphViewHash || value.graphHash || "").trim(),
        pathState: String(value.pathState || "").trim(),
        decision: String(value.decision || "").trim(),
        fromGraphOnly: value.fromGraphOnly === true,
        executable: value.executable === true,
        executionSpec: {
          type: value.executionSpec?.type || "execution_spec",
          steps: Array.isArray(value.executionSpec?.steps) ? value.executionSpec.steps : []
        },
        evidence: {
          direct: Array.isArray(evidence.direct) ? evidence.direct : [],
          supporting: Array.isArray(evidence.supporting) ? evidence.supporting : [],
          context: Array.isArray(evidence.context) ? evidence.context : []
        },
        executionDag: {
          nodes: Array.isArray(dag.nodes) ? dag.nodes : [],
          edges: Array.isArray(dag.edges) ? dag.edges : []
        },
        trustedSql: Array.isArray(value.trustedSql) ? value.trustedSql : [],
        deterministicFacts: Array.isArray(value.deterministicFacts) ? value.deterministicFacts : [],
        result: value.result && typeof value.result === "object" ? value.result : null,
        reasoningTrace: value.reasoningTrace && typeof value.reasoningTrace === "object" ? value.reasoningTrace : null
      };
    },
    validateEvidenceReasoning(reasoning = {}) {
      const errors = [];
      const steps = Array.isArray(reasoning.executionSpec?.steps) ? reasoning.executionSpec.steps : [];
      const nodes = Array.isArray(reasoning.executionDag?.nodes) ? reasoning.executionDag.nodes : [];
      const evidence = reasoning.evidence || {};
      const evidenceItems = [
        ...(Array.isArray(evidence.direct) ? evidence.direct : []),
        ...(Array.isArray(evidence.supporting) ? evidence.supporting : []),
        ...(Array.isArray(evidence.context) ? evidence.context : [])
      ];
      const nodeIds = new Set(nodes.map((node) => String(node?.id || "").trim()).filter(Boolean));
      const refs = new Set(evidenceItems.map((item) => String(item?.refId || item?.source || "").trim()).filter(Boolean));

      if (!reasoning.contractHash) {
        errors.push("missing contractHash");
      }
      if (!reasoning.graphViewHash) {
        errors.push("missing graphViewHash");
      }
      steps.forEach((step, index) => {
        const label = `step ${step?.id || index + 1}`;
        const nodeId = String(step?.nodeId || "").trim();
        const source = String(step?.source || step?.refId || "").trim();
        const confidence = Number(step?.confidence);
        if (!nodeId || !nodeIds.has(nodeId)) {
          errors.push(`${label} missing DAG node`);
        }
        if (!source || !refs.has(source)) {
          errors.push(`${label} missing evidence binding`);
        }
        if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
          errors.push(`${label} confidence out of 0..1`);
        }
      });
      return {
        valid: errors.length === 0,
        errors
      };
    },
    renderEvidenceReasoningFallback(content, citationUrls, errors = []) {
      const env = { webCitationUrls: citationUrls };
      const cleaned = this.stripEvidenceReasoningJson(content);
      const fallback = this.parseEvidenceExecutionAnswer(cleaned);
      const body = fallback
        ? this.renderEvidenceExecutionAnswer(fallback, citationUrls)
        : markdown.render(cleaned, env);
      const reason = Array.isArray(errors) && errors.length
        ? `<span>${escapeHtml(errors.slice(0, 3).join("; "))}</span>`
        : "";
      return [
        `<p class="evidence-structure-fallback">v2 structure fallback${reason}</p>`,
        body
      ].join("");
    },
    stripEvidenceReasoningJson(content) {
      const withoutFences = String(content || "").replace(/```(?:json)?\s*([\s\S]*?)```/gi, (match, body) => (
        /evidence_reasoning_v2|executionSpec|executionDag/.test(body || "") ? "" : match
      ));
      const withoutLocks = withoutFences
        .replace(/---BEGIN_LOCKED_ANSWER---/gi, "")
        .replace(/---END_LOCKED_ANSWER---/gi, "");
      const inlineJson = this.extractBalancedEvidenceJson(withoutLocks);
      const cleaned = inlineJson && /evidence_reasoning_v2|executionSpec|executionDag/.test(inlineJson)
        ? withoutLocks.replace(inlineJson, "")
        : withoutLocks;
      return cleaned.trim() || "\u7ed3\u6784\u5316\u534f\u8bae\u672a\u901a\u8fc7\u524d\u7aef\u6821\u9a8c\uff0c\u5df2\u9690\u85cf\u539f\u59cb JSON\u3002";
    },
    renderEvidenceReasoning(reasoning, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const evidence = reasoning?.evidence || {};
      const topEvidence = this.topEvidenceItems(evidence);
      const evidenceCount = this.evidenceCount(evidence);
      const confidence = this.reasoningConfidence(reasoning);
      const topEvidenceItems = topEvidence.length
        ? topEvidence.map((item, index) => this.renderTopEvidenceItem(item, env, index)).join("")
        : `<li class="empty">${escapeHtml("\u6682\u65e0\u53ef\u5c55\u793a\u8bc1\u636e")}</li>`;
      const pathState = this.reasoningPathState(reasoning);
      const decisionClass = pathState === "STRONG_PATH" ? "locked" : (pathState === "NO_PATH" ? "blocked" : "warning");
      const answer = this.reasoningResultText(reasoning);

      return [
        '<section class="evidence-reasoning-card">',
        '<header class="evidence-reasoning-header">',
        '<div>',
        `<span>${escapeHtml("\u5206\u6790\u7ed3\u679c")}</span>`,
        `<p>${markdown.renderInline(answer, env)}</p>`,
        '</div>',
        `<strong class="${decisionClass}">${escapeHtml(this.reasoningPathStateLabel(pathState))}</strong>`,
        '</header>',
        '<section class="answer-result-summary">',
        `<span>${escapeHtml("\u53ef\u4fe1\u5ea6")} <b>${escapeHtml(confidence || "\u5f85\u786e\u8ba4")}</b></span>`,
        `<span>${escapeHtml("\u4f9d\u636e\u5145\u8db3\u5ea6")} <b>${escapeHtml(this.reasoningEvidenceLevel(reasoning))}</b></span>`,
        '</section>',
        '</section>',
        '<section class="response-references compact answer-evidence-reference-group">',
        '<details class="answer-evidence-details">',
        `<summary><span>${escapeHtml("\u67e5\u770b\u8bc1\u636e\u6765\u6e90")}</span><small>${escapeHtml(`${evidenceCount} \u6761`)}</small></summary>`,
        '<div>',
        '<section class="answer-top-evidence">',
        `<ul>${topEvidenceItems}</ul>`,
        '</section>',
        '</div>',
        '</details>',
        '</section>'
      ].filter(Boolean).join("");
    },
    topEvidenceItems(evidence = {}) {
      return [
        ...(Array.isArray(evidence.direct) ? evidence.direct : []),
        ...(Array.isArray(evidence.supporting) ? evidence.supporting : []),
        ...(Array.isArray(evidence.context) ? evidence.context : [])
      ].slice(0, 3);
    },
    evidenceCount(evidence = {}) {
      return ["direct", "supporting", "context"]
        .map((key) => Array.isArray(evidence[key]) ? evidence[key].length : 0)
        .reduce((sum, value) => sum + value, 0);
    },
    reasoningConfidence(reasoning = {}) {
      const formatted = this.formatConfidencePercent(this.reasoningConfidenceValue(reasoning));
      if (formatted) {
        return formatted;
      }
      return "";
    },
    reasoningConfidenceValue(reasoning = {}) {
      const resultConfidence = Number(reasoning?.result?.confidence);
      if (Number.isFinite(resultConfidence) && resultConfidence > 0) {
        return resultConfidence;
      }
      const coherence = Number(reasoning?.reasoningTrace?.pathDecision?.pathCoherence);
      if (Number.isFinite(coherence) && coherence > 0) {
        return coherence;
      }
      const steps = Array.isArray(reasoning?.executionSpec?.steps) ? reasoning.executionSpec.steps : [];
      return steps.length
        ? steps.reduce((sum, step) => sum + Number(step.confidence || 0), 0) / steps.length
        : 0;
    },
    reasoningEvidenceLevel(reasoning = {}) {
      const evidence = reasoning.evidence || {};
      const direct = Array.isArray(evidence.direct) ? evidence.direct.length : 0;
      const supporting = Array.isArray(evidence.supporting) ? evidence.supporting.length : 0;
      const context = Array.isArray(evidence.context) ? evidence.context.length : 0;
      if (reasoning.executable && direct >= 2 && supporting + context >= 2) {
        return "\u8f83\u5145\u5206";
      }
      if (direct > 0 && supporting + context > 0) {
        return "\u6709\u9650\u652f\u6301";
      }
      if (direct > 0) {
        return "\u5355\u4e00\u4f9d\u636e";
      }
      return "\u4e0d\u8db3";
    },
    reasoningResultText(reasoning = {}) {
      if (reasoning?.result?.exists === true && String(reasoning.result.answer || "").trim()) {
        return this.shortUiText(this.stripInternalDocumentRefs(reasoning.result.answer), 220);
      }
      const pathState = this.reasoningPathState(reasoning);
      if (pathState === "WEAK_PATH") {
        return "\u5df2\u8bc6\u522b\u8bc1\u636e\u8def\u5f84\uff0c\u4f46\u8def\u5f84\u7a33\u5b9a\u6027\u4e0d\u8db3\uff0c\u5f53\u524d\u672a\u8fbe\u5230\u751f\u6210\u786e\u5b9a\u7ed3\u8bba\u7684\u9608\u503c\u3002";
      }
      if (pathState === "CONFLICTED_PATH") {
        return "\u5df2\u8bc6\u522b\u5019\u9009\u8bc1\u636e\u8def\u5f84\uff0c\u4f46\u8bc1\u636e\u4e4b\u95f4\u5b58\u5728\u51b2\u7a81\uff0c\u5f53\u524d\u65e0\u6cd5\u751f\u6210\u786e\u5b9a\u7ed3\u8bba\u3002";
      }
      if (pathState === "NO_PATH") {
        return "\u5f53\u524d\u672a\u627e\u5230\u8db3\u591f\u8bc1\u636e\u6765\u6e90\uff0c\u6682\u65e0\u6cd5\u751f\u6210\u53ef\u9760\u7ed3\u8bba\u3002";
      }
      const fact = Array.isArray(reasoning.deterministicFacts) ? reasoning.deterministicFacts[0] : null;
      if (fact?.content) {
        return this.shortUiText(this.stripInternalDocumentRefs(fact.content), 180);
      }
      return "\u5df2\u57fa\u4e8e\u53ef\u8ffd\u6eaf\u8bc1\u636e\u8def\u5f84\u751f\u6210\u9501\u5b9a\u5206\u6790\u7ed3\u679c\u3002";
    },
    reasoningPathState(reasoning = {}) {
      const protocolState = String(reasoning.pathState || "").trim().toUpperCase();
      if (["STRONG_PATH", "WEAK_PATH", "CONFLICTED_PATH", "NO_PATH"].includes(protocolState)) {
        return protocolState;
      }
      if (reasoning.executable) {
        return "STRONG_PATH";
      }
      const trace = reasoning.reasoningTrace || {};
      const dag = reasoning.executionDag || {};
      const nodes = Array.isArray(dag.nodes) ? dag.nodes : [];
      const edges = Array.isArray(dag.edges) ? dag.edges : [];
      const selectedPath = Array.isArray(trace.pathDecision?.selectedPath) ? trace.pathDecision.selectedPath : [];
      const conflicts = Array.isArray(trace.conflictResolutions) ? trace.conflictResolutions : [];
      const hasEvidence = this.evidenceCount(reasoning.evidence || {}) > 0;
      const hasCandidatePath = selectedPath.length > 0
        || edges.length > 0
        || nodes.length > 0
        || this.reasoningConfidenceValue(reasoning) > 0
        || hasEvidence;
      if (conflicts.length && hasCandidatePath) {
        return "CONFLICTED_PATH";
      }
      if (hasCandidatePath) {
        return "WEAK_PATH";
      }
      return "NO_PATH";
    },
    reasoningPathStateLabel(state) {
      if (state === "STRONG_PATH") {
        return "\u5df2\u6821\u9a8c";
      }
      if (state === "WEAK_PATH") {
        return "\u9700\u590d\u6838";
      }
      if (state === "CONFLICTED_PATH") {
        return "\u5b58\u5728\u51b2\u7a81";
      }
      return "\u65e0\u6cd5\u786e\u8ba4";
    },
    reasoningModalPayload(reasoning = {}) {
      const trace = reasoning.reasoningTrace || {};
      const decision = trace.pathDecision || {};
      const dag = reasoning.executionDag || {};
      const nodes = Array.isArray(dag.nodes) ? dag.nodes : [];
      const edges = Array.isArray(dag.edges) ? dag.edges : [];
      const selectedPath = Array.isArray(decision.selectedPath) ? decision.selectedPath : [];
      const nodeById = new Map(nodes.map((node) => [node.id, node]));
      const selectedPairs = new Set();
      selectedPath.forEach((nodeId, index) => {
        if (index < selectedPath.length - 1) {
          selectedPairs.add(`${nodeId}->${selectedPath[index + 1]}`);
        }
      });
      return {
        title: reasoning.executable ? "Selected evidence path" : "Evidence path unavailable",
        metrics: [
          { label: "coherence", value: this.formatTraceNumber(decision.pathCoherence) },
          { label: "weakest link", value: this.formatTraceNumber(decision.weakestLink) },
          { label: "bottleneck", value: this.formatTraceNumber(decision.bottleneckPenalty) }
        ].filter((item) => item.value),
        pathNodes: selectedPath.map((nodeId) => {
          const node = nodeById.get(nodeId) || {};
          return {
            id: nodeId,
            confidence: this.formatTraceNumber(node.confidence),
            type: node.type || "",
            source: node.source || ""
          };
        }),
        pathEdges: edges
          .filter((edge) => selectedPairs.has(`${edge.from}->${edge.to}`))
          .map((edge) => ({
            from: edge.from || "",
            to: edge.to || "",
            type: edge.type || "",
            confidence: this.formatTraceNumber(edge.confidence),
            reasoning: edge.reasoning || ""
          })),
        conflicts: (Array.isArray(trace.conflictResolutions) ? trace.conflictResolutions : []).map((item) => ({
          edge: item.edge || "",
          confidence: this.formatTraceNumber(item.confidence),
          decision: item.decision || "",
          reason: item.reason || ""
        })),
        explanation: Array.isArray(trace.explanation) ? trace.explanation : []
      };
    },
    renderTopEvidenceItem(item = {}, env, index = 0) {
      const text = this.semanticEvidenceText(item);
      return [
        '<li>',
        '<div>',
        `<small>${escapeHtml(`\u6765\u6e90 ${index + 1}`)}</small>`,
        '</div>',
        text ? `<p>${markdown.renderInline(text, env)}</p>` : "",
        '</li>'
      ].join("");
    },
    semanticEvidenceText(item = {}) {
      const raw = String(item.text || item.content || item.action || "")
        .replace(/doc:\/\/\S+/g, "")
        .replace(/chunk[-=]\d+/gi, "")
        .replace(/DOC_CHUNK/gi, "")
        .replace(/[@#*_`]/g, " ")
        .replace(/\s+/g, " ")
        .trim();
      return this.shortUiText(raw, 160);
    },
    reasoningConclusion(reasoning = {}) {
      return this.reasoningResultText(reasoning);
    },
    renderReasoningStep(step = {}, env) {
      const action = step.action || step.text || step.nodeId || "";
      const source = step.source || step.refId || "";
      const confidence = this.formatConfidencePercent(step.confidence);
      return [
        '<li>',
        `<span>${markdown.renderInline(String(action || "\u672a\u547d\u540d\u6b65\u9aa4"), env)}</span>`,
        '<small>',
        step.nodeId ? `<i>${escapeHtml(step.nodeId)}</i>` : "",
        source ? `<b class="doc-link">${escapeHtml(source)}</b>` : "",
        confidence ? `<em>${escapeHtml(confidence)}</em>` : "",
        '</small>',
        '</li>'
      ].join("");
    },
    renderReasoningFacts(facts, env) {
      const rows = facts.slice(0, 6).map((fact) => {
        const source = fact.sourceRef || fact.source || "";
        const text = this.shortUiText(fact.content || fact.text || "", 220);
        return [
          '<li>',
          '<div>',
          fact.nodeId ? `<small>${escapeHtml(fact.nodeId)}</small>` : "",
          source ? `<span class="doc-link">${escapeHtml(source)}</span>` : "",
          fact.nodeType ? `<small>${escapeHtml(fact.nodeType)}</small>` : "",
          this.formatConfidencePercent(fact.confidence) ? `<small>${escapeHtml(this.formatConfidencePercent(fact.confidence))}</small>` : "",
          '</div>',
          text ? `<p>${markdown.renderInline(text, env)}</p>` : "",
          '</li>'
        ].join("");
      }).join("");
      return [
        '<section class="reasoning-facts-section">',
        `<strong>${escapeHtml("\u4e8b\u5b9e\u4f9d\u636e")}</strong>`,
        `<ul>${rows}</ul>`,
        '</section>'
      ].join("");
    },
    renderTrustedSqlFacts(items) {
      const rows = items.slice(0, 4).map((item) => [
        '<li>',
        '<div>',
        item.nodeId ? `<small>${escapeHtml(item.nodeId)}</small>` : "",
        item.sourceRef ? `<span class="doc-link">${escapeHtml(item.sourceRef)}</span>` : "",
        item.sqlType ? `<small>${escapeHtml(item.sqlType)}</small>` : "",
        `<small>${escapeHtml(item.executionVerified ? "EXECUTION_VERIFIED" : "NOT_VERIFIED")}</small>`,
        Number.isFinite(Number(item.validationScore)) ? `<small>${escapeHtml(`score ${item.validationScore}`)}</small>` : "",
        '</div>',
        item.normalizedSql ? `<pre>${escapeHtml(this.shortUiText(item.normalizedSql, 360))}</pre>` : "",
        '</li>'
      ].join("")).join("");
      return [
        '<section class="reasoning-facts-section reasoning-sql-section">',
        `<strong>${escapeHtml("\u53ef\u4fe1 SQL")}</strong>`,
        `<ul>${rows}</ul>`,
        '</section>'
      ].join("");
    },
    renderReasoningDag(dag = {}, env) {
      const nodes = Array.isArray(dag.nodes) ? dag.nodes : [];
      const edges = Array.isArray(dag.edges) ? dag.edges : [];
      const nodeRows = nodes.slice(0, 10).map((node) => [
        '<li>',
        '<div>',
        node.id ? `<small>${escapeHtml(node.id)}</small>` : "",
        node.type ? `<small>${escapeHtml(node.type)}</small>` : "",
        this.formatConfidencePercent(node.confidence) ? `<small>${escapeHtml(this.formatConfidencePercent(node.confidence))}</small>` : "",
        '</div>',
        node.text ? `<p>${markdown.renderInline(this.shortUiText(node.text, 120), env)}</p>` : "",
        '</li>'
      ].join("")).join("");
      const edgeRows = edges.slice(0, 12).map((edge) => [
        '<li>',
        `<span>${escapeHtml(`${edge.from || "?"} -> ${edge.to || "?"}`)}</span>`,
        edge.type ? `<small>${escapeHtml(edge.type)}</small>` : "",
        this.formatConfidencePercent(edge.confidence) ? `<small>${escapeHtml(this.formatConfidencePercent(edge.confidence))}</small>` : "",
        edge.reasoning ? `<p>${markdown.renderInline(this.shortUiText(edge.reasoning, 120), env)}</p>` : "",
        '</li>'
      ].join("")).join("");
      return [
        '<details class="reasoning-dag-details">',
        `<summary><span>${escapeHtml("\u6267\u884c DAG")}</span><small>${escapeHtml(`${nodes.length} nodes / ${edges.length} edges`)}</small></summary>`,
        '<div>',
        nodeRows ? `<section><strong>${escapeHtml("Nodes")}</strong><ul>${nodeRows}</ul></section>` : "",
        edgeRows ? `<section><strong>${escapeHtml("Edges")}</strong><ul>${edgeRows}</ul></section>` : "",
        '</div>',
        '</details>'
      ].filter(Boolean).join("");
    },
    renderReasoningTrace(trace = {}, env) {
      const decision = trace.pathDecision || {};
      const conflicts = Array.isArray(trace.conflictResolutions) ? trace.conflictResolutions : [];
      const explanation = Array.isArray(trace.explanation) ? trace.explanation : [];
      const metrics = [
        this.metricChip("coherence", decision.pathCoherence),
        this.metricChip("weakest", decision.weakestLink),
        this.metricChip("bottleneck", decision.bottleneckPenalty)
      ].filter(Boolean).join("");
      const conflictRows = conflicts.length
        ? conflicts.slice(0, 6).map((item) => [
            '<li>',
            `<span>${escapeHtml(item.edge || "")}</span>`,
            item.confidence !== undefined ? `<small>${escapeHtml(this.formatTraceNumber(item.confidence))}</small>` : "",
            item.decision ? `<p>${markdown.renderInline(String(item.decision), env)}</p>` : "",
            item.reason ? `<p>${markdown.renderInline(this.shortUiText(item.reason, 140), env)}</p>` : "",
            '</li>'
          ].join("")).join("")
        : `<li class="empty">${escapeHtml("\u672a\u68c0\u6d4b\u5230\u51b2\u7a81\u8bc1\u636e")}</li>`;
      const explanationRows = explanation.length
        ? explanation.map((item) => `<li>${markdown.renderInline(String(item), env)}</li>`).join("")
        : `<li class="empty">${escapeHtml("\u6682\u65e0\u8def\u5f84\u89e3\u91ca")}</li>`;
      return [
        '<details class="reasoning-trace-details" open>',
        `<summary><span>${escapeHtml("\u8def\u5f84\u89e3\u91ca")}</span><small>${escapeHtml(trace.type || "reasoning_trace")}</small></summary>`,
        '<div>',
        `<section><strong>${escapeHtml("\u51b3\u7b56\u6307\u6807")}</strong><div class="reasoning-trace-metrics">${metrics}</div><p>${markdown.renderInline(String(decision.decision || ""), env)}</p></section>`,
        `<section><strong>${escapeHtml("\u89e3\u91ca")}</strong><ul>${explanationRows}</ul></section>`,
        `<section><strong>${escapeHtml("\u51b2\u7a81\u5904\u7406")}</strong><ul>${conflictRows}</ul></section>`,
        '</div>',
        '</details>'
      ].join("");
    },
    metricChip(label, value) {
      if (value === undefined || value === null || value === "") {
        return "";
      }
      return `<span>${escapeHtml(label)} <b>${escapeHtml(this.formatTraceNumber(value))}</b></span>`;
    },
    formatTraceNumber(value) {
      const number = Number(value);
      return Number.isFinite(number) ? String(Math.round(number * 1000) / 1000) : String(value || "");
    },
    renderEvidenceTier(label, items, open, env) {
      const rows = Array.isArray(items) ? items : [];
      const content = rows.length
        ? rows.map((item) => this.renderEvidenceItem(item, env)).join("")
        : `<li class="empty">${escapeHtml("\u6682\u65e0\u8bc1\u636e")}</li>`;
      return [
        `<details class="reasoning-evidence-tier"${open ? " open" : ""}>`,
        `<summary><span>${escapeHtml(label)}</span><small>${rows.length}</small></summary>`,
        `<ul>${content}</ul>`,
        '</details>'
      ].join("");
    },
    renderEvidenceItem(item = {}, env) {
      const refId = item.refId || item.source || "";
      const text = this.shortUiText(item.text || item.content || item.action || "", 180);
      const confidence = this.formatConfidencePercent(item.confidence);
      return [
        '<li>',
        '<div>',
        refId ? `<span class="doc-link">${escapeHtml(refId)}</span>` : "",
        item.type ? `<small>${escapeHtml(item.type)}</small>` : "",
        confidence ? `<small>${escapeHtml(confidence)}</small>` : "",
        '</div>',
        text ? `<p>${markdown.renderInline(text, env)}</p>` : "",
        '</li>'
      ].join("");
    },
    formatConfidencePercent(value) {
      const number = Number(value);
      if (!Number.isFinite(number) || number <= 0) {
        return "";
      }
      return `${Math.round(number * 100)}%`;
    },
    shortUiText(value, maxLength = 160) {
      const text = String(value || "").replace(/\s+/g, " ").trim();
      return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`;
    },
    parseEvidenceExecutionAnswer(content) {
      const text = String(content || "").replace(/\r\n/g, "\n").trim();
      if (!text || !/doc:\/\/|Evidence Execution Contract|\u5f15\u7528\u6765\u6e90|\u4e8b\u5b9e\u4f9d\u636e|\u6267\u884c\u7ea6\u675f/.test(text)) {
        return null;
      }

      const sourceRe = /\u5f15\u7528\u6765\u6e90\s*[:\uff1a]/;
      const factRe = /\u4e8b\u5b9e\u4f9d\u636e\s*[:\uff1a]/;
      const constraintRe = /\u6267\u884c\u7ea6\u675f\s*[:\uff1a]/;
      const sourceMatch = sourceRe.exec(text);
      const factMatch = factRe.exec(text);
      const constraintMatch = constraintRe.exec(text);
      if (!factMatch || (!sourceMatch && !constraintMatch && !text.includes("doc://"))) {
        return null;
      }

      const firstMarker = Math.min(
        ...[sourceMatch?.index, factMatch?.index, constraintMatch?.index]
          .filter((value) => Number.isInteger(value))
      );
      const intro = text.slice(0, firstMarker).trim();
      const sourceText = sourceMatch
        ? text.slice(sourceMatch.index + sourceMatch[0].length, factMatch.index).trim()
        : "";
      const factEnd = constraintMatch?.index ?? text.length;
      const factText = text.slice(factMatch.index + factMatch[0].length, factEnd).trim();
      const constraintText = constraintMatch
        ? text.slice(constraintMatch.index + constraintMatch[0].length).trim()
        : "";
      const refs = this.extractDocRefs([sourceText, factText].join("\n"));
      const steps = this.extractEvidenceSteps(factText);

      if (!factText && !refs.length) {
        return null;
      }

      return {
        conclusion: intro || "\u672c\u7b54\u6848\u5df2\u57fa\u4e8e\u53ef\u8ffd\u6eaf\u8bc1\u636e\u751f\u6210\u3002",
        refs,
        steps,
        evidenceSummary: this.evidenceSummary(factText),
        constraint: constraintText
      };
    },
    extractDocRefs(value) {
      const refs = new Set();
      const matcher = String(value || "").matchAll(/doc:\/\/[^\s)\]\uff09\uff1b;,\uff0c\u3002]+/g);
      for (const match of matcher) {
        refs.add(match[0].replace(/[.\u3002]+$/, ""));
      }
      return [...refs];
    },
    cleanEvidenceText(value) {
      return String(value || "")
        .replace(/\[?doc:\/\/[^\s)\]\uff09\uff1b;,\uff0c\u3002]+\]?/g, " ")
        .replace(/^\s*[-*]\s+/gm, " ")
        .replace(/\s+/g, " ")
        .trim();
    },
    extractEvidenceSteps(value) {
      const text = this.cleanEvidenceText(value);
      const lower = text.toLowerCase();
      const steps = [];
      const add = (condition, label) => {
        if (condition && !steps.includes(label)) {
          steps.push(label);
        }
      };

      add(/livedata.*\u6570\u636e\u7f16\u7ec7\u6a21\u5757|\u6570\u636e\u7f16\u7ec7\u6a21\u5757/.test(lower), "\u8fdb\u5165 livedata \u6570\u636e\u7f16\u7ec7\u6a21\u5757");
      add(/\u7ef4\u62a4.*\u6570\u636e\u6e90|\u9009\u62e9\u6dfb\u52a0\u6570\u636e\u76ee\u5f55|\u6570\u636e\u6e90\u8fde\u63a5/.test(text), "\u7ef4\u62a4\u5e76\u9009\u62e9\u5206\u6790\u6570\u636e\u6e90");
      add(/\u62a5\u8868\s*SQL|SQL\s*\u67e5\u8be2|SQL\s*\u5f00\u53d1/i.test(text), "\u5f00\u53d1\u62a5\u8868 SQL");
      add(/\u4fdd\u5b58\s*SQL\s*\u6570\u636e\u96c6|\u4fdd\u5b58\u6570\u636e\u96c6|\u6570\u636e\u96c6\u914d\u7f6e/.test(text), "\u4fdd\u5b58\u6570\u636e\u96c6\u914d\u7f6e");
      add(/\u6d4b\u8bd5\u8fde\u63a5|\u9884\u89c8\u6570\u636e|\u89c2\u5bdf\u6570\u636e\u6e90/.test(text), "\u6d4b\u8bd5\u8fde\u63a5\u5e76\u9884\u89c8\u6570\u636e");
      add(/\u53ef\u89c6\u5316\u56fe\u8868|\u5c55\u793a\u65b9\u5f0f|\u65b0\u5efa.*\u62a5\u8868\u5f00\u53d1/.test(text), "\u914d\u7f6e\u62a5\u8868\u53ef\u89c6\u5316");
      add(/\u53d1\u5e03\u62a5\u8868|BI\s*\u53d1\u5e03\u7cfb\u7edf|\u76ee\u5f55\u7ba1\u7406|\u6dfb\u52a0\u6a21\u677f/i.test(text), "\u9884\u89c8\u5e76\u53d1\u5e03\u62a5\u8868");

      if (steps.length) {
        return steps;
      }

      return text
        .split(/(?:^|\s)(?:\d+[.)\u3001]|[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+[\u3001.])\s*/)
        .map((item) => item.trim())
        .filter((item) => item.length > 8)
        .map((item) => item.length > 72 ? `${item.slice(0, 72)}...` : item)
        .slice(0, 8);
    },
    evidenceSummary(value) {
      const text = this.cleanEvidenceText(value)
        .replace(/(?:^|\s)\d+[.)]\s*/g, " ")
        .replace(/\s+/g, " ")
        .trim();
      if (!text) {
        return "";
      }
      return text.length > 180 ? `${text.slice(0, 180)}...` : text;
    },
    renderEvidenceExecutionAnswer(answer, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const stepItems = answer.steps.length
        ? answer.steps.map((step) => `<li>${markdown.renderInline(step, env)}</li>`).join("")
        : `<li>${markdown.renderInline(answer.evidenceSummary || "\u8bc1\u636e\u5df2\u6536\u5f55\uff0c\u672a\u62bd\u53d6\u5230\u53ef\u5206\u6b65\u5c55\u793a\u7684\u8def\u5f84\u3002", env)}</li>`;
      const refItems = answer.refs.length
        ? answer.refs.map((ref) => `<li><span class="doc-link">${escapeHtml(ref)}</span></li>`).join("")
        : `<li class="empty">${escapeHtml("\u6682\u65e0 doc \u5f15\u7528")}</li>`;
      const docCount = answer.refs.length;
      const constraintTitle = answer.constraint ? ` title="${escapeHtml(answer.constraint)}"` : "";

      return [
        '<section class="evidence-execution-card">',
        '<header class="evidence-execution-header">',
        '<div>',
        `<span>${escapeHtml("\u7ed3\u8bba")}</span>`,
        `<p>${markdown.renderInline(answer.conclusion, env)}</p>`,
        '</div>',
        `<strong${constraintTitle}>Evidence-locked</strong>`,
        '</header>',
        '<section class="evidence-path-section">',
        `<strong>${escapeHtml("\u6267\u884c\u8def\u5f84")}</strong>`,
        `<ol>${stepItems}</ol>`,
        '</section>',
        '<details class="execution-evidence-details">',
        `<summary><span>${escapeHtml("\u67e5\u770b\u8bc1\u636e")}</span><small>doc ${docCount} ${escapeHtml("\u6761")}</small></summary>`,
        '<div class="execution-evidence-body">',
        `<ul>${refItems}</ul>`,
        answer.evidenceSummary ? `<p>${markdown.renderInline(answer.evidenceSummary, env)}</p>` : "",
        '</div>',
        '</details>',
        answer.constraint ? `<footer><span>${escapeHtml("\u2713 Evidence Execution Contract")}</span></footer>` : "",
        '</section>'
      ].filter(Boolean).join("");
    },
    parseEvidenceAnswer(content) {
      const lines = String(content || "").replace(/\r\n/g, "\n").split("\n");
      const titleIndex = lines.findIndex((line) => /^\s*#{0,6}\s*EvidenceAnswer\s*$/i.test(line));
      if (titleIndex < 0) {
        return null;
      }

      const fields = {};
      let activeKey = "";
      for (const rawLine of lines.slice(titleIndex + 1)) {
        const line = String(rawLine || "");
        const fieldMatch = line.match(/^\s*[-*]\s+\*{0,2}(answer|citations|confidence|missingInfo)\*{0,2}\s*:\s*(.*)$/i);
        if (fieldMatch) {
          activeKey = fieldMatch[1].toLowerCase();
          fields[activeKey] = fieldMatch[2].trim();
          continue;
        }
        if (activeKey && line.trim()) {
          fields[activeKey] = `${fields[activeKey] || ""} ${line.trim()}`.trim();
        }
      }

      if (!fields.answer && !fields.citations && !fields.confidence && !fields.missinginfo) {
        return null;
      }

      return {
        answer: fields.answer || "",
        citations: this.splitEvidenceCitationText(fields.citations || ""),
        confidence: fields.confidence || "",
        missingInfo: fields.missinginfo || ""
      };
    },
    splitEvidenceCitationText(value) {
      return String(value || "")
        .split(/[闂?闂?]\s*/)
        .map((item) => item.trim())
        .filter(Boolean);
    },
    renderEvidenceAnswer(evidenceAnswer, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const confidence = evidenceAnswer.confidence || "";
      const confidenceLabel = confidence.split(/\s+[-|]\s+|:/)[0]?.trim() || confidence.trim();
      const confidenceClass = this.evidenceConfidenceClass(confidenceLabel);
      const citationItems = evidenceAnswer.citations.length
        ? evidenceAnswer.citations
            .map((item) => `<li>${markdown.renderInline(item, env)}</li>`)
            .join("")
        : `<li class="empty">${escapeHtml("\u6682\u65e0\u5f15\u7528")}</li>`;

      return [
        '<section class="evidence-answer-card">',
        '<header class="evidence-answer-header">',
        '<div>',
        '<span class="evidence-answer-kicker">EvidenceAnswer</span>',
        `<strong>${escapeHtml("\u8bc1\u636e\u56de\u7b54")}</strong>`,
        '</div>',
        confidenceLabel ? `<span class="evidence-confidence-badge ${confidenceClass}">${markdown.renderInline(confidenceLabel, env)}</span>` : "",
        '</header>',
        this.renderEvidenceSection("\u56de\u7b54", evidenceAnswer.answer, env),
        `<section class="evidence-answer-section evidence-citations"><strong>${escapeHtml("\u8bc1\u636e\u5f15\u7528")}</strong><ol>${citationItems}</ol></section>`,
        this.renderEvidenceSection("\u53ef\u4fe1\u5ea6", confidence, env),
        this.renderEvidenceSection("\u4fe1\u606f\u7f3a\u53e3", evidenceAnswer.missingInfo, env),
        '</section>'
      ].filter(Boolean).join("");
    },
    renderEvidenceSection(label, value, env) {
      if (!String(value || "").trim()) {
        return "";
      }
      return [
        '<section class="evidence-answer-section">',
        `<strong>${escapeHtml(label)}</strong>`,
        `<p>${markdown.renderInline(String(value || "").trim(), env)}</p>`,
        '</section>'
      ].join("");
    },
    evidenceConfidenceClass(value) {
      const normalized = String(value || "").toLowerCase();
      if (normalized.includes("high") || normalized.includes("\u9ad8")) {
        return "high";
      }
      if (normalized.includes("low") || normalized.includes("\u4f4e")) {
        return "low";
      }
      return "medium";
    },
    async handleMarkdownClick(event) {
      const sourceToggle = event.target?.closest?.("[data-source-tags-toggle]");
      if (sourceToggle) {
        event.preventDefault();
        event.stopPropagation();
        const cell = sourceToggle.closest("td");
        const hiddenTags = [...(cell?.querySelectorAll("a.source-tag-overflow-hidden") || [])];
        const collapsed = sourceToggle.dataset.sourceTagsToggle === "collapsed";
        hiddenTags.forEach((tag) => tag.classList.toggle("source-tag-overflow-visible", collapsed));
        sourceToggle.dataset.sourceTagsToggle = collapsed ? "expanded" : "collapsed";
        sourceToggle.textContent = collapsed ? "收起" : `+${hiddenTags.length}`;
        return;
      }
      const chartButton = event.target?.closest?.("[data-result-chart-payload]");
      if (chartButton) {
        event.preventDefault();
        event.stopPropagation();
        this.openChartAnalysisModal(chartButton.dataset.resultChartPayload || "");
        return;
      }
      const reasoningButton = event.target?.closest?.("[data-reasoning-path]");
      if (reasoningButton) {
        event.preventDefault();
        event.stopPropagation();
        this.openReasoningModal(reasoningButton.dataset.reasoningPayload || "");
        return;
      }
      const button = event.target?.closest?.("[data-code-copy]");
      if (!button) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      const block = button.closest("[data-code-block]");
      const code = block?.querySelector("pre code");
      const text = code?.innerText || "";
      if (!text) {
        return;
      }
      try {
        await this.writeClipboard(text);
        const previousTitle = button.getAttribute("title") || "Copy code";
        const previousLabel = button.getAttribute("aria-label") || "Copy code";
        button.dataset.copied = "true";
        button.setAttribute("title", "Copied");
        button.setAttribute("aria-label", "Copied code");
        const timer = window.setTimeout(() => {
          delete button.dataset.copied;
          button.setAttribute("title", previousTitle);
          button.setAttribute("aria-label", previousLabel);
          this.codeCopyResetTimers.delete(timer);
        }, 1400);
        this.codeCopyResetTimers.add(timer);
      } catch (error) {
        console.warn("Copy code block failed", error);
      }
    },
    openReasoningModal(payload) {
      try {
        this.reasoningModal = JSON.parse(decodeURIComponent(payload || ""));
      } catch (error) {
        console.warn("Open reasoning path failed", error);
      }
    },
    closeReasoningModal() {
      this.reasoningModal = null;
    },
    async copyMessage(message) {
      const text = this.buildCopyText(message);
      if (!text) {
        return;
      }
      try {
        await this.writeClipboard(text);
        this.copiedMessageId = message.id;
        if (this.copiedResetTimer) {
          window.clearTimeout(this.copiedResetTimer);
        }
        this.copiedResetTimer = window.setTimeout(() => {
          this.copiedMessageId = "";
          this.copiedResetTimer = null;
        }, 1400);
      } catch (error) {
        console.warn("Copy answer failed", error);
      }
    },
    buildCopyText(message = {}) {
      const uiContract = this.uiRenderContract(message, String(message.content || ""));
      const content = uiContract
        ? this.copyUiRenderContractText(uiContract)
        : this.stripInternalDocumentRefs(String(message.content || "").trim());
      const sections = content ? [content] : [];
      const sourceLines = this.copySourceLines(message.sources || []);
      const documentLines = this.copyDocumentPageLines(extractDocumentSearchPagesFromTraces(message.traces || []));
      const pageLines = this.copyWebPageLines(extractWebSearchPagesFromTraces(message.traces || []));
      const metadataCatalogLines = this.copyMetadataCatalogLines(this.metadataTableCatalog(message));
      const metadataColumnLines = this.copyMetadataColumnLines(this.metadataColumnSections(message));

      if (sourceLines.length) {
        sections.push(["Internal sources", ...sourceLines].join("\n"));
      }
      if (documentLines.length) {
        sections.push(["Referenced documents", ...documentLines].join("\n"));
      }
      if (pageLines.length) {
        sections.push(["Web search citations", ...pageLines].join("\n"));
      }
      if (metadataCatalogLines.length) {
        sections.push(["Matched table catalog", ...metadataCatalogLines].join("\n"));
      }
      if (metadataColumnLines.length) {
        sections.push(["Metadata fields", ...metadataColumnLines].join("\n"));
      }
      return sections.join("\n\n").trim();
    },
    copyMetadataCatalogLines(catalog = {}) {
      const rows = Array.isArray(catalog.rows) ? catalog.rows : [];
      if (!rows.length) {
        return [];
      }
      const header = `${catalog.totalMatched || rows.length} matched, returned ${rows.length}${catalog.catalogTruncated ? ", truncated" : ""}`;
      const body = rows.map((row, index) => [
        index + 1,
        row.database || "-",
        row.schema || "-",
        row.tableName || "-",
        row.tableComment || "-",
        row.score || "-"
      ].join("\t"));
      return [header, "#\tdatabase\tschema\ttable\tcomment\tscore", ...body];
    },
    copyMetadataColumnLines(sections = []) {
      return sections.flatMap((section) => {
        const header = `${section.title} (${section.columns.length} fields)`;
        const rows = section.columns.map((column) => [
          column.ordinal,
          column.name,
          column.type || "-",
          column.key || "-",
          column.nullable || "-",
          column.comment || "-"
        ].join("\t"));
        return [header, "ordinal\tname\ttype\tkey\tnullable\tcomment", ...rows];
      });
    },
    copyUiRenderContractText(contract = {}) {
      const sections = [];
      const answer = (contract.answerBlocks || [])
        .map((block) => this.cleanUiProtocolText(block?.text || ""))
        .filter(Boolean)
        .join("\n\n")
        .trim();
      if (answer) {
        sections.push(answer);
      }
      const steps = (contract.steps || [])
        .map((step, index) => {
          const title = this.cleanUiProtocolText(step?.title || `\u6b65\u9aa4 ${index + 1}`);
          const text = this.cleanUiProtocolText(step?.text || "");
          return [title, text].filter(Boolean).join(": ");
        })
        .filter(Boolean);
      if (steps.length) {
        sections.push(["\u6267\u884c\u6b65\u9aa4", ...steps.map((step, index) => `${index + 1}. ${step}`)].join("\n"));
      }
      const warnings = (contract.warnings || [])
        .map((warning) => this.cleanUiProtocolText(warning))
        .filter(Boolean);
      if (warnings.length) {
        sections.push(["\u8fb9\u754c\u8bf4\u660e", ...warnings.map((warning) => `- ${warning}`)].join("\n"));
      }
      return sections.join("\n\n").trim();
    },
    copySourceLines(sources = []) {
      return sources
        .map((source, index) => {
          const rank = source?.rank || index + 1;
          const title = this.cleanUiCitationTitle(source?.source || source?.title || "", index);
          const snippet = this.cleanUiProtocolText(source?.snippet || source?.content || "");
          const url = source?.url || source?.link || source?.href || source?.sourceUrl || "";
          return [`${rank}. ${title}`, url, snippet].filter(Boolean).join(" - ");
        })
        .filter(Boolean);
    },
    copyDocumentPageLines(pages = []) {
      return pages
        .map((page, index) => {
          const rank = page?.rank || index + 1;
          const title = this.cleanUiCitationTitle(page?.title || page?.docId || "", index);
          const url = page?.url || "";
          const snippet = this.cleanUiProtocolText(page?.snippet || "");
          return [`闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁惧墽鎳撻—鍐偓锝庝簼閹癸綁鏌ｉ鐐搭棞闁靛棙甯掗～婵嬫晲閸涱剙顥氬┑掳鍊楁慨鐑藉磻閻愮儤鍋嬮柣妯荤湽閳ь兛绶氬鎾閳╁啯鐝栭梻渚€鈧偛鑻晶鏉款熆鐟欏嫭绀嬬€规洜鍏橀、姗€鎮╃喊澶屽簥闂備浇顕ч崙鐣岀礊閸℃稑纾婚柟鎹愬煐椤洟鏌嶉崫鍕偓鑽ょ不閸撗€鍋撻悷鏉款棌闁哥姵娲滈懞杈ㄧ附閸涘﹦鍘搁梺鍛婁緱閸犳氨绮婚悙鐑樼厸閻忕偛澧介妴鎺懨归悪鍛洭缂佽鲸甯℃慨鈧柣妯诲墯閸?${rank}: ${title}`, url, snippet].filter(Boolean).join(" - ");
        })
        .filter(Boolean);
    },
    copyWebPageLines(pages = []) {
      return pages
        .map((page, index) => {
          const rank = page?.rank || index + 1;
          const title = page?.title || page?.url || "Untitled source";
          const url = page?.url || "";
          const snippet = page?.snippet || "";
          return [`闂傚倸鍊搁崐鎼佸磹閹间礁纾圭€瑰嫭鍣磋ぐ鎺戠倞妞ゆ帒顦伴弲顏堟偡濠婂啰绠婚柛鈹惧亾濡炪倖甯婇懗鍫曞煝閹剧粯鐓涢柛娑卞灠閳诲牓鏌曢崱鏇狀槮闁宠閰ｉ獮姗€宕橀幓鎺撴殢濠碉紕鍋戦崐鏍箰妤ｅ啫纾婚柣鏂垮悑閸嬫﹢鏌曟径鍫濆姉闁衡偓娴犲绠抽柟鎯版绾惧綊鏌熼悧鍫熺凡缁炬儳顭烽弻鐔煎礈瑜忕敮娑㈡煕鐎ｎ亜鈧潡寮婚弴銏犻唶婵犻潧娴傚Λ銈囩磽娴ｅ弶顎嗛柛瀣尭閳规垿鎮╅崹顐ｆ瘎婵犳鍠楅幐鍐茬暦閹邦喚纾兼俊顖滅帛閻濈兘姊洪崫鍕偍闁搞劍妞介幃?${rank}: ${title}`, url, snippet].filter(Boolean).join(" - ");
        })
        .filter(Boolean);
    },
    async writeClipboard(text) {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        return;
      }
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.left = "-9999px";
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand("copy");
      document.body.removeChild(textarea);
    },
    formatTime(value) {
      if (!value) {
        return "";
      }
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) {
        return "";
      }
      return date.toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      });
    }
  }
};
