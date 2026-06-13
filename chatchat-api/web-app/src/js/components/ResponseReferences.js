import {
  displayUrl,
  extractDocumentSearchPages,
  extractDocumentSearchPagesFromTraces,
  extractWebSearchPages,
  extractWebSearchPagesFromTraces
} from "../utils/webReferences.js";

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
    documentPageRows() {
      return extractDocumentSearchPagesFromTraces(this.toolTraces);
    },
    documentReferenceRows() {
      const sourceRows = this.sources.map((source, index) => this.sourceToDocumentRow(source, index));
      return this.uniqueDocumentRows([...sourceRows, ...this.documentPageRows]);
    },
    toolTraceRows() {
      return this.toolTraces.map((trace) => ({
        ...trace,
        confirmationRequired: this.isConfirmationRequired(trace),
        statusText: this.traceStatusText(trace),
        errorText: this.traceErrorText(trace),
        documentPages: extractDocumentSearchPages(trace),
        webPages: extractWebSearchPages(trace)
      }));
    }
  },
  methods: {
    isConfirmationRequired(trace) {
      const runtime = trace?.runtimeMetadata || {};
      const outcome = String(runtime.outcome || trace?.outcome || "").toLowerCase();
      const errorCode = String(trace?.errorCode || trace?.exceptionType || "").toUpperCase();
      return outcome === "confirmation_required" || errorCode === "MCP_CONFIRMATION_REQUIRED";
    },
    traceStatusText(trace) {
      if (this.isConfirmationRequired(trace)) {
        return "等待权限确认";
      }
      return trace?.success === false ? "调用失败" : "调用成功";
    },
    traceErrorText(trace) {
      if (this.isConfirmationRequired(trace)) {
        return "";
      }
      if (trace?.success !== false) {
        return "";
      }
      return trace?.errorMessage || trace?.error || trace?.message || "";
    },
    sourceToDocumentRow(source = {}, index = 0) {
      return {
        rank: source.rank || index + 1,
        docId: source.docId || source.documentId || source.id || "",
        title: source.source || source.title || source.name || "引用文档",
        url: this.sourceUrl(source),
        snippet: source.snippet || source.content || source.summary || ""
      };
    },
    uniqueDocumentRows(rows = []) {
      const seen = new Set();
      return rows.filter((row) => {
        const key = row.docId || row.url || `${row.rank}:${row.title}:${row.snippet}`;
        if (seen.has(key)) {
          return false;
        }
        seen.add(key);
        return true;
      });
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
        title: "\u67e5\u8be2\u5f15\u7528\u6765\u6e90",
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
