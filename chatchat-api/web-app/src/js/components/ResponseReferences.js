export default {
  name: "ResponseReferences",
  props: {
    sources: {
      type: Array,
      default: () => []
    },
    toolTraces: {
      type: Array,
      default: () => []
    },
    compact: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      webPagesDialog: {
        open: false,
        title: "",
        pages: []
      }
    };
  },
  computed: {
    hasDetails() {
      return this.sources.length || this.toolTraces.length;
    },
    toolTraceRows() {
      return this.toolTraces.map((trace) => ({
        ...trace,
        errorText: this.traceErrorText(trace),
        webPages: this.extractWebSearchPages(trace)
      }));
    }
  },
  methods: {
    traceErrorText(trace) {
      if (trace?.success !== false) {
        return "";
      }
      return trace?.errorMessage || trace?.error || trace?.message || "";
    },
    sourceUrl(source) {
      const value = source?.url || source?.link || source?.href || source?.sourceUrl;
      if (value) {
        return value;
      }
      const sourceName = source?.source;
      return /^https?:\/\//i.test(sourceName || "") ? sourceName : "";
    },
    openWebPagesDialog(trace) {
      this.webPagesDialog = {
        open: true,
        title: "\u67e5\u8be2\u7f51\u9875\u6765\u6e90",
        pages: trace?.webPages || []
      };
    },
    closeWebPagesDialog() {
      this.webPagesDialog = {
        open: false,
        title: "",
        pages: []
      };
    },
    extractWebSearchPages(trace) {
      if (!this.isWebSearchTrace(trace)) {
        return [];
      }
      const output = this.parseTraceOutput(trace?.output);
      const containers = this.webSearchContainers(output);
      const referenceUrls = containers
        .map((item) => item?.reference_urls || item?.referenceUrls)
        .find(Array.isArray);
      const candidates = containers
        .flatMap((item) => [
          item?.results,
          item?.items,
          item?.organic_results,
          item?.webPages,
          item?.pageExcerpts,
          item?.evidenceSnippets
        ])
        .filter(Array.isArray)
        .flat();
      const pages = candidates
        .map((item, index) => ({
          rank: item.rank || item.position || index + 1,
          title: item.title || item.name || item.source || item.url || item.link || "\u7f51\u9875",
          url: item.url || item.link || item.href || item.sourceUrl || "",
          snippet: this.shortSnippet(
            item.snippet
              || item.excerpt
              || item.pageExcerpt
              || item.contentExcerpt
              || item.description
              || item.summary
              || item.content
              || item.text
          )
        }))
        .filter((item) => item.url || item.title);
      if (!referenceUrls?.length) {
        return this.uniquePages(pages).slice(0, 10);
      }
      const pagesByUrl = new Map(pages.filter((page) => page.url).map((page) => [page.url, page]));
      return this.uniquePages(referenceUrls
        .map((url, index) => {
          const matched = pagesByUrl.get(url) || {};
          return {
            rank: matched.rank || index + 1,
            title: matched.title || url,
            url,
            snippet: matched.snippet || ""
          };
        })
        .filter((item) => item.url || item.title))
        .slice(0, 10);
    },
    webSearchContainers(output) {
      const containers = [];
      const visit = (value, depth = 0) => {
        if (!value || typeof value !== "object" || depth > 3) {
          return;
        }
        containers.push(value);
        [
          value.data,
          value.result,
          value.structuredContent,
          value.structured_content,
          value.payload
        ].forEach((item) => visit(item, depth + 1));
      };
      visit(output);
      return containers;
    },
    uniquePages(pages) {
      const seen = new Set();
      return pages.filter((page) => {
        const key = page.url || `${page.rank}:${page.title}`;
        if (seen.has(key)) {
          return false;
        }
        seen.add(key);
        return true;
      });
    },
    displayUrl(url) {
      if (!url) {
        return "";
      }
      try {
        const parsed = new URL(url);
        return parsed.hostname.replace(/^www\./, "");
      } catch (error) {
        return url;
      }
    },
    shortSnippet(value) {
      const normalized = String(value || "").replace(/\s+/g, " ").trim();
      if (!normalized) {
        return "";
      }
      return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized;
    },
    isWebSearchTrace(trace) {
      const name = String(trace?.toolName || trace?.displayName || "").toLowerCase();
      return name.includes("web_search") || name.includes("web search") || name.includes("\u8054\u7f51\u641c\u7d22");
    },
    parseTraceOutput(output) {
      if (!output) {
        return {};
      }
      if (typeof output === "object") {
        return output;
      }
      try {
        return JSON.parse(output);
      } catch (error) {
        return {};
      }
    }
  }
};
