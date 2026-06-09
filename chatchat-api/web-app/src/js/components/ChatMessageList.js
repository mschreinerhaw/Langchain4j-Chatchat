import MarkdownIt from "markdown-it";
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
  const targetIndex = token.attrIndex("target");
  const relIndex = token.attrIndex("rel");

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
  methods: {
    renderMarkdown(content, message = {}) {
      return markdown.render(this.linkifyWebCitations(String(content ?? ""), message));
    },
    linkifyWebCitations(content, message = {}) {
      const pages = extractWebSearchPagesFromTraces(message.traces || []);
      if (!pages.length) {
        return content;
      }
      return content.replace(
        /【\s*(网页|来源|source)\s*(\d+)\s*】|[［\[]\s*(网页|来源|source)\s*(\d+)\s*[］\]]/gi,
        (match, boxedPrefix, boxedNumber, bracketPrefix, bracketNumber) => {
          const prefix = boxedPrefix || bracketPrefix || "网页";
          const number = Number(boxedNumber || bracketNumber);
          if (!Number.isInteger(number) || number < 1) {
            return match;
          }
          const page = pages[number - 1];
          if (!page?.url) {
            return match;
          }
          const label = `${prefix}${number}`;
          const title = this.escapeMarkdownTitle(page.title || page.url);
          return `[${label}](<${page.url}> "${title}")`;
        }
      );
    },
    escapeMarkdownTitle(value) {
      return String(value || "").replace(/"/g, "&quot;");
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
