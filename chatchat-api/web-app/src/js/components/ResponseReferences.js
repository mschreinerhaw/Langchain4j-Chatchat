import {
  displayUrl,
  documentReferenceTitle,
  extractDocumentSearchPages,
  extractDocumentSearchPagesFromTraces,
  extractWebSearchPages,
  extractWebSearchPagesFromTraces
} from "../utils/webReferences.js";

const INTERNAL_DOCUMENT_REF_RE = /[\uFF08(]?\s*doc:\/\/[^\s\uFF09)\]}\>\uFF0C\u3002\uFF1B;]+[\uFF09)]?\s*[:\uFF1A]?/gi;

function cleanReferenceText(value) {
  return String(value || "")
    .replace(INTERNAL_DOCUMENT_REF_RE, "")
    .replace(/\s+/g, " ")
    .trim();
}

export default {
  name: "ResponseReferences",
  props: {
    sources: {
      type: Array,
      default: () => []
    },
    evidencePremises: {
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
      return this.evidencePremiseRows.length
        || this.documentReferenceRows.length
        || this.webPageRows.length
        || this.toolTraceRows.length;
    },
    evidencePremiseRows() {
      return this.evidencePremises
        .map((item, index) => ({
          rank: item?.rank || index + 1,
          text: cleanReferenceText(item?.text || item?.snippet || item?.content || "")
        }))
        .filter((item) => item.text);
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
      const traces = this.compact
        ? this.toolTraces.filter((trace) => trace?.success === false || this.isConfirmationRequired(trace))
        : this.toolTraces;
      return traces.map((trace) => ({
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
      const docId = source.docId || source.documentId || source.id || source.fileId || source.file_id || "";
      return {
        rank: source.rank || index + 1,
        docId,
        title: documentReferenceTitle(source, docId),
        url: this.sourceUrl(source),
        snippet: cleanReferenceText(source.snippet || source.content || source.summary || "")
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

