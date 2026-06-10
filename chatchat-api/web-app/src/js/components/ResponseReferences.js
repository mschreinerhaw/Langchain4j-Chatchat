import { displayUrl, extractWebSearchPages, extractWebSearchPagesFromTraces } from "../utils/webReferences.js";

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
    webPageRows() {
      return extractWebSearchPagesFromTraces(this.toolTraces);
    },
    toolTraceRows() {
      return this.toolTraces.map((trace) => ({
        ...trace,
        errorText: this.traceErrorText(trace),
        webPages: extractWebSearchPages(trace)
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
    displayUrl(url) {
      return displayUrl(url);
    }
  }
};
