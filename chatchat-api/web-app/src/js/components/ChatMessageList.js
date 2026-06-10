import MarkdownIt from "markdown-it";
import { Check, Copy } from "@lucide/vue";
import ResponseReferences from "../../components/ResponseReferences.vue";
import { extractWebSearchPagesFromTraces } from "../utils/webReferences.js";

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
    Copy,
    ResponseReferences
  },
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
      copiedResetTimer: null
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
      return this.messages.some((message) => message.streaming);
    }
  },
  beforeUnmount() {
    if (this.copiedResetTimer) {
      window.clearTimeout(this.copiedResetTimer);
    }
  },
  methods: {
    renderMarkdown(content, message = {}) {
      const prepared = this.prepareMarkdownContent(String(content ?? ""), message);
      return markdown.render(prepared.content, {
        webCitationUrls: new Set(prepared.citationUrls)
      });
    },
    prepareMarkdownContent(content, message = {}) {
      const pages = extractWebSearchPagesFromTraces(message.traces || []);
      if (!pages.length) {
        return { content, citationUrls: [] };
      }
      const citationUrls = [];
      const nextContent = content.replace(
        /【\s*(网页|来源|source)\s*(\d+)\s*】|[［\[]\s*(网页|来源|source)\s*(\d+)\s*[］\]]|(?:网页|来源|source)\s*(\d+)/gi,
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

          const prefix = boxedPrefix || bracketPrefix || this.plainCitationPrefix(match) || "网页";
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
      const match = String(value || "").match(/^(网页|来源|source)/i);
      return match?.[1] || "";
    },
    normalizeCitationPrefix(value) {
      const prefix = String(value || "").toLowerCase();
      if (prefix === "source") {
        return "网页";
      }
      return value || "网页";
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
      const pageLines = this.copyWebPageLines(extractWebSearchPagesFromTraces(message.traces || []));

      if (sourceLines.length) {
        sections.push(["内部文档来源", ...sourceLines].join("\n"));
      }
      if (pageLines.length) {
        sections.push(["网络搜索网页", ...pageLines].join("\n"));
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
    copyWebPageLines(pages = []) {
      return pages
        .map((page, index) => {
          const rank = page?.rank || index + 1;
          const title = page?.title || page?.url || "网页";
          const url = page?.url || "";
          const snippet = page?.snippet || "";
          return [`网页 ${rank}: ${title}`, url, snippet].filter(Boolean).join(" - ");
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
