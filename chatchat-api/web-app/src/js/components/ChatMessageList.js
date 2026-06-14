import MarkdownIt from "markdown-it";
import { Check, CircleCheck, CircleX, Copy } from "@lucide/vue";
import ResponseReferences from "../../components/ResponseReferences.vue";
import { extractDocumentSearchPagesFromTraces, extractWebSearchPagesFromTraces } from "../utils/webReferences.js";

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true
});

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

export default {
  name: "ChatMessageList",
  components: {
    Check,
    CircleCheck,
    CircleX,
    Copy,
    ResponseReferences
  },
  emits: ["feedback"],
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
      return markdown.render(prepared.content, {
        webCitationUrls: new Set(prepared.citationUrls)
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
      const pages = extractWebSearchPagesFromTraces(message.traces || []);
      if (!pages.length) {
        return { content, citationUrls: [] };
      }
      const citationUrls = [];
      const nextContent = content.replace(
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
