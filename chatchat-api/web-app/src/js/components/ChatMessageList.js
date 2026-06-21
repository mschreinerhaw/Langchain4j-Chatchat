import MarkdownIt from "markdown-it";
import { Check, CircleCheck, CircleX, Copy } from "@lucide/vue";
import ResponseReferences from "../../components/ResponseReferences.vue";
import VisualizationRenderer from "../../components/VisualizationRenderer.vue";
import { extractDocumentSearchPagesFromTraces, extractWebSearchPagesFromTraces } from "../utils/webReferences.js";

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

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
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
    '<button type="button" class="markdown-code-copy" data-code-copy title="Copy code" aria-label="Copy code">Copy</button>',
    "</div>",
    `<pre><code${languageAttr}>${escapeHtml(token.content)}</code></pre>`,
    "</div>\n"
  ].join("");
};

export default {
  name: "ChatMessageList",
  components: {
    Check,
    CircleCheck,
    CircleX,
    Copy,
    ResponseReferences,
    VisualizationRenderer
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
      return this.activeAgent?.name || "AI投资助手";
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
    shouldShowSteps(message = {}) {
      return message.role === "assistant"
        && ((Array.isArray(message.steps) && message.steps.length > 0) || this.isExecutionRunning(message));
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
      const visible = running && !steps.length ? this.defaultRunningSteps(message) : (running ? steps : steps.slice(-8));
      const normalized = visible.map((step, index) => ({
        id: step.id || `${message.id || "message"}-step-${index}`,
        title: step.title || "\u6267\u884c\u6b65\u9aa4",
        detail: step.detail || "",
        status: step.status || "pending"
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
        return `\u6b63\u5728\u6267\u884c${this.activeAgent?.name ? `: ${this.activeAgent.name}` : ""}`;
      }
      return "\u6267\u884c\u6b65\u9aa4";
    },
    canShowEvaluation(message = {}) {
      const status = String(message.status || "").toLowerCase();
      return message.role === "assistant"
        && !!message.content
        && !!message.taskId
        && !message.streaming
        && !message.feedbackTime
        && !["waiting", "cancelled", "failed", "streaming", "running"].includes(status);
    },
    renderMarkdown(content, message = {}) {
      const prepared = this.prepareMarkdownContent(String(content ?? ""), message);
      const reasoning = this.parseEvidenceReasoning(prepared.content);
      if (reasoning?.fallback) {
        return this.renderEvidenceReasoningFallback(prepared.content, new Set(prepared.citationUrls));
      }
      if (reasoning) {
        return this.renderEvidenceReasoning(reasoning, new Set(prepared.citationUrls));
      }
      const executionAnswer = this.parseEvidenceExecutionAnswer(prepared.content);
      if (executionAnswer) {
        return this.renderEvidenceExecutionAnswer(executionAnswer, new Set(prepared.citationUrls));
      }
      const evidenceAnswer = this.parseEvidenceAnswer(prepared.content);
      if (evidenceAnswer) {
        return this.renderEvidenceAnswer(evidenceAnswer, new Set(prepared.citationUrls));
      }
      return markdown.render(prepared.content, {
        webCitationUrls: new Set(prepared.citationUrls)
      });
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
          id: `${message.id || "message"}-sync-events`,
          title: "\u540c\u6b65\u6267\u884c\u72b6\u6001",
          detail: "\u6b63\u5728\u83b7\u53d6\u540e\u7aef\u8fd0\u884c\u4e8b\u4ef6",
          status: "done"
        },
        {
          id: `${message.id || "message"}-waiting-progress`,
          title: "\u7b49\u5f85\u4e0b\u4e00\u6b65",
          detail: "\u5de5\u5177\u6216\u6a21\u578b\u6267\u884c\u4e2d",
          status: "active"
        }
      ];
    },
    prepareMarkdownContent(content, message = {}) {
      const displayContent = this.stripVisualizationSpecBlocks(content);
      const normalizedContent = this.normalizeRenderContractBlocks(displayContent);
      const pages = extractWebSearchPagesFromTraces(message.traces || []);
      if (!pages.length) {
        return { content: normalizedContent, citationUrls: [] };
      }
      const citationUrls = [];
      const nextContent = normalizedContent.replace(
        /【\s*(引用|网页|来源|source)\s*(\d+)\s*】|[［\[]\s*(引用|网页|来源|source)\s*(\d+)\s*[］\]]|(?:引用|网页|来源|source)\s*(\d+)/gi,
        (...args) => {
          const match = args[0];
          const boxedPrefix = args[1];
          const boxedNumber = args[2];
          const bracketPrefix = args[3];
          const bracketNumber = args[4];
          const plainNumber = args[5];
          const offset = args[args.length - 2];
          const source = args[args.length - 1];

          if (source[offset + match.length] === "(") {
            return match;
          }
          if (plainNumber && /[A-Za-z0-9_./:#?=&%-]/.test(source[offset - 1] || "")) {
            return match;
          }

          const prefix = boxedPrefix || bracketPrefix || this.plainCitationPrefix(match) || "引用";
          const normalizedNumber = Number(boxedNumber || bracketNumber || plainNumber);
          if (!Number.isInteger(normalizedNumber) || normalizedNumber < 1) {
            return match;
          }
          const page = pages[normalizedNumber - 1];
          if (!page?.url) {
            return match;
          }
          citationUrls.push(page.url);
          const label = `${this.normalizeCitationPrefix(prefix)} ${normalizedNumber}`;
          const title = this.escapeMarkdownTitle(page.title || page.url);
          return `[${label}](<${page.url}> "${title}")`;
        }
      );
      return { content: nextContent, citationUrls };
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
    plainCitationPrefix(value) {
      const match = String(value || "").match(/^(引用|网页|来源|source)/i);
      return match?.[1] || "";
    },
    normalizeCitationPrefix(value) {
      const prefix = String(value || "").toLowerCase();
      if (prefix === "source" || prefix === "网页" || prefix === "来源") {
        return "引用";
      }
      return value || "引用";
    },
    escapeMarkdownTitle(value) {
      return String(value || "").replace(/"/g, "&quot;");
    },
    parseEvidenceReasoning(content) {
      const text = String(content || "");
      const fencePattern = /```(?:json)?\s*([\s\S]*?)```/gi;
      let match;
      while ((match = fencePattern.exec(text)) !== null) {
        try {
          const parsed = JSON.parse(match[1]);
          if (parsed?.type === "evidence_reasoning_v2" || (parsed?.executionSpec && parsed?.evidence && parsed?.executionDag)) {
            return parsed;
          }
        } catch (error) {
          if (/evidence_reasoning_v2|executionSpec|executionDag/.test(match[1] || "")) {
            return { fallback: true };
          }
        }
      }
      return null;
    },
    renderEvidenceReasoningFallback(content, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const cleaned = this.stripEvidenceReasoningJson(content);
      const fallback = this.parseEvidenceExecutionAnswer(cleaned);
      const body = fallback
        ? this.renderEvidenceExecutionAnswer(fallback, citationUrls)
        : markdown.render(cleaned, env);
      return [
        '<p class="evidence-structure-fallback">v2 structure fallback</p>',
        body
      ].join("");
    },
    stripEvidenceReasoningJson(content) {
      return String(content || "").replace(/```(?:json)?\s*([\s\S]*?)```/gi, (match, body) => (
        /evidence_reasoning_v2|executionSpec|executionDag/.test(body || "") ? "" : match
      )).trim();
    },
    renderEvidenceReasoning(reasoning, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const steps = Array.isArray(reasoning?.executionSpec?.steps) ? reasoning.executionSpec.steps : [];
      const evidence = reasoning?.evidence || {};
      const dag = reasoning?.executionDag || {};
      const nodes = Array.isArray(dag.nodes) ? dag.nodes : [];
      const edges = Array.isArray(dag.edges) ? dag.edges : [];
      const stepItems = steps.length
        ? steps.map((step) => this.renderReasoningStep(step, env)).join("")
        : `<li>${escapeHtml("\u672a\u63d0\u4f9b\u663e\u5f0f\u6267\u884c\u6b65\u9aa4")}</li>`;

      return [
        '<section class="evidence-reasoning-card">',
        '<header class="evidence-reasoning-header">',
        '<div>',
        '<span>Evidence Reasoning Engine v2</span>',
        `<p>${escapeHtml(reasoning?.executable ? "\u56de\u7b54\u5df2\u7531\u53ef\u8ffd\u6eaf\u6267\u884c\u56fe\u9501\u5b9a" : "\u5f53\u524d\u8bc1\u636e\u56fe\u672a\u5f62\u6210\u53ef\u6267\u884c\u8def\u5f84")}</p>`,
        '</div>',
        `<strong>${escapeHtml(reasoning?.decision || "Evidence-locked")}</strong>`,
        '</header>',
        '<section class="evidence-path-section reasoning-path-section">',
        `<strong>${escapeHtml("\u6267\u884c\u89c4\u683c")}</strong>`,
        `<ol>${stepItems}</ol>`,
        '</section>',
        '<section class="reasoning-evidence-section">',
        `<strong>${escapeHtml("\u8bc1\u636e\u5206\u5c42")}</strong>`,
        '<div>',
        this.renderEvidenceTier("Direct Evidence", evidence.direct, true, env),
        this.renderEvidenceTier("Supporting Evidence", evidence.supporting, false, env),
        this.renderEvidenceTier("Context Evidence", evidence.context, false, env),
        '</div>',
        '</section>',
        '<footer class="reasoning-contract-footer">',
        `<span>${escapeHtml(`DAG ${nodes.length} nodes / ${edges.length} edges`)}</span>`,
        reasoning?.contractHash ? `<span>${escapeHtml(`contract ${String(reasoning.contractHash).slice(0, 10)}`)}</span>` : "",
        reasoning?.graphViewHash ? `<span>${escapeHtml(`graph ${String(reasoning.graphViewHash).slice(0, 10)}`)}</span>` : "",
        '</footer>',
        '</section>'
      ].filter(Boolean).join("");
    },
    renderReasoningStep(step = {}, env) {
      const action = step.action || step.text || step.nodeId || "";
      const source = step.source || step.refId || "";
      const confidence = this.formatConfidencePercent(step.confidence);
      return [
        '<li>',
        `<span>${markdown.renderInline(String(action || "\u672a\u547d\u540d\u6b65\u9aa4"), env)}</span>`,
        '<small>',
        source ? `<b class="doc-link">${escapeHtml(source)}</b>` : "",
        confidence ? `<em>${escapeHtml(confidence)}</em>` : "",
        '</small>',
        '</li>'
      ].join("");
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
        const fieldMatch = line.match(/^\s*[-*]\s+\*{0,2}(answer|citations|confidence|missingInfo)\*{0,2}\s*[:：]\s*(.*)$/i);
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
        .split(/[；;]\s*/)
        .map((item) => item.trim())
        .filter(Boolean);
    },
    renderEvidenceAnswer(evidenceAnswer, citationUrls) {
      const env = { webCitationUrls: citationUrls };
      const confidence = evidenceAnswer.confidence || "";
      const confidenceLabel = confidence.split(/\s+[—-]\s+|[:：]/)[0]?.trim() || confidence.trim();
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
        const previousText = button.textContent || "Copy";
        button.textContent = "Copied";
        button.dataset.copied = "true";
        const timer = window.setTimeout(() => {
          button.textContent = previousText;
          delete button.dataset.copied;
          this.codeCopyResetTimers.delete(timer);
        }, 1400);
        this.codeCopyResetTimers.add(timer);
      } catch (error) {
        console.warn("Copy code block failed", error);
      }
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
      const content = String(message.content || "").trim();
      const sections = content ? [content] : [];
      const sourceLines = this.copySourceLines(message.sources || []);
      const documentLines = this.copyDocumentPageLines(extractDocumentSearchPagesFromTraces(message.traces || []));
      const pageLines = this.copyWebPageLines(extractWebSearchPagesFromTraces(message.traces || []));

      if (sourceLines.length) {
        sections.push(["内部文档来源", ...sourceLines].join("\n"));
      }
      if (documentLines.length) {
        sections.push(["引用文档", ...documentLines].join("\n"));
      }
      if (pageLines.length) {
        sections.push(["网络搜索引用", ...pageLines].join("\n"));
      }
      return sections.join("\n\n").trim();
    },
    copySourceLines(sources = []) {
      return sources
        .map((source, index) => {
          const rank = source?.rank || index + 1;
          const title = source?.source || source?.title || "来源";
          const snippet = source?.snippet || source?.content || "";
          const url = source?.url || source?.link || source?.href || source?.sourceUrl || "";
          return [`${rank}. ${title}`, url, snippet].filter(Boolean).join(" - ");
        })
        .filter(Boolean);
    },
    copyDocumentPageLines(pages = []) {
      return pages
        .map((page, index) => {
          const rank = page?.rank || index + 1;
          const title = page?.title || page?.docId || "引用文档";
          const docId = page?.docId || "";
          const url = page?.url || "";
          const snippet = page?.snippet || "";
          return [`文档 ${rank}: ${title}`, docId, url, snippet].filter(Boolean).join(" - ");
        })
        .filter(Boolean);
    },
    copyWebPageLines(pages = []) {
      return pages
        .map((page, index) => {
          const rank = page?.rank || index + 1;
          const title = page?.title || page?.url || "引用";
          const url = page?.url || "";
          const snippet = page?.snippet || "";
          return [`引用 ${rank}: ${title}`, url, snippet].filter(Boolean).join(" - ");
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
