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
  computed: {
    hasDetails() {
      return this.sources.length || this.toolTraces.length;
    },
    toolTraceRows() {
      return this.toolTraces.map((trace) => ({
        ...trace,
        webPages: this.extractWebSearchPages(trace)
      }));
    }
  },
  methods: {
    sourceUrl(source) {
      const value = source?.url || source?.link || source?.href || source?.sourceUrl;
      if (value) {
        return value;
      }
      const sourceName = source?.source;
      return /^https?:\/\//i.test(sourceName || "") ? sourceName : "";
    },
    extractWebSearchPages(trace) {
      if (!this.isWebSearchTrace(trace)) {
        return [];
      }
      const output = this.parseTraceOutput(trace?.output);
      const candidates = [
        output?.results,
        output?.items,
        output?.organic_results,
        output?.webPages,
        output?.data?.results,
        output?.data?.items
      ].find(Array.isArray) || [];
      return candidates
        .map((item, index) => ({
          rank: item.rank || item.position || index + 1,
          title: item.title || item.name || item.source || item.url || item.link || "网页",
          url: item.url || item.link || item.href || item.sourceUrl || "",
          snippet: item.snippet || item.description || item.content || item.summary || ""
        }))
        .filter((item) => item.url || item.title)
        .slice(0, 8);
    },
    isWebSearchTrace(trace) {
      const name = String(trace?.toolName || trace?.displayName || "").toLowerCase();
      return name.includes("web_search") || name.includes("web search") || name.includes("联网搜索");
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
