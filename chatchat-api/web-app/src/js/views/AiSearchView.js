import "../../styles/pages/ai-search.css";
import {
  deleteSearchDocument,
  getSearchDocument,
  recordUserActivity,
  searchDocuments,
  uploadSearchDocument
} from "../../services/api.js";

const MAX_UPLOAD_SIZE = 5 * 1024 * 1024;
const MESSAGE_TEXT = {
  library_empty: "文档库暂无文档，请先上传文档。",
  no_match: "没有找到匹配文档，请换一个关键词。",
  no_documents: "暂无可展示文档。",
  ok: ""
};

function todayString() {
  return new Date().toISOString().slice(0, 10);
}

function inferDocumentType(fileName = "") {
  const extension = fileName.includes(".") ? fileName.split(".").pop().toLowerCase() : "";
  if (extension === "pdf") {
    return "pdf";
  }
  if (["doc", "docx"].includes(extension)) {
    return "word";
  }
  if (["xls", "xlsx", "csv"].includes(extension)) {
    return "excel";
  }
  if (extension === "md") {
    return "markdown";
  }
  return "text";
}

function defaultUploadForm() {
  return {
    file: null,
    title: "",
    source: "文档库",
    date: todayString(),
    tags: "",
    documentType: "auto"
  };
}

export default {
  name: "AiSearchView",
  props: {
    pendingDocumentShortcut: {
      type: Object,
      default: null
    },
    userId: {
      type: String,
      default: "default-user"
    }
  },
  emits: ["ask-ai", "navigate"],
  data() {
    return {
      keyword: "",
      searchedKeyword: "",
      searched: false,
      results: [],
      resultTotal: 0,
      resultMessage: "",
      searchTookMs: 0,
      hasMoreResults: false,
      page: 1,
      pageSize: 6,
      pageCount: 1,
      loading: false,
      uploading: false,
      viewerOpen: false,
      viewerLoading: false,
      viewerError: "",
      viewerDocument: null,
      viewerResult: null,
      showUploadDialog: false,
      error: "",
      uploadError: "",
      uploadForm: defaultUploadForm(),
      appliedDocumentShortcutId: "",
      documentTypeOptions: [
        { value: "auto", label: "自动识别" },
        { value: "pdf", label: "PDF" },
        { value: "word", label: "Word" },
        { value: "excel", label: "Excel" },
        { value: "markdown", label: "Markdown" },
        { value: "text", label: "文本" }
      ]
    };
  },
  computed: {
    pagedResults() {
      return this.results;
    },
    pageStart() {
      if (!this.results.length) {
        return 0;
      }
      return (Math.min(Math.max(1, this.page), this.pageCount) - 1) * this.pageSize + 1;
    },
    pageEnd() {
      return Math.min(this.page * this.pageSize, this.resultTotal);
    },
    pageButtons() {
      const total = this.pageCount;
      const current = Math.min(Math.max(1, this.page), total);
      const start = Math.max(1, Math.min(current - 2, total - 4));
      const end = Math.min(total, start + 4);
      return Array.from({ length: end - start + 1 }, (_, index) => start + index);
    }
  },
  watch: {
    pendingDocumentShortcut: {
      immediate: true,
      handler(shortcut) {
        this.applyDocumentShortcut(shortcut);
      }
    }
  },
  methods: {
    async performSearch(resetPage = true) {
      this.loading = true;
      this.error = "";
      this.searched = true;
      this.searchedKeyword = this.keyword.trim();
      if (resetPage) {
        this.page = 1;
      }
      try {
        const payload = await searchDocuments({
          keyword: this.searchedKeyword,
          page: this.page,
          pageSize: this.pageSize
        });
        this.results = payload?.results || [];
        this.resultTotal = payload?.total || 0;
        this.page = payload?.page || this.page;
        this.pageSize = payload?.pageSize || this.pageSize;
        this.pageCount = payload?.totalPages || 1;
        this.resultMessage = MESSAGE_TEXT[payload?.message] || "";
        this.searchTookMs = payload?.tookMs || 0;
        this.hasMoreResults = Boolean(payload?.hasMore);
        this.clampPage();
        this.recordSearchHits();
      } catch (error) {
        this.error = error.message || "检索失败";
        this.results = [];
        this.resultTotal = 0;
        this.pageCount = 1;
        this.resultMessage = "";
        this.searchTookMs = 0;
        this.hasMoreResults = false;
        this.page = 1;
      } finally {
        this.loading = false;
      }
    },
    openUploadDialog() {
      this.uploadError = "";
      if (!this.uploadForm.date) {
        this.uploadForm.date = todayString();
      }
      this.showUploadDialog = true;
    },
    closeUploadDialog() {
      if (this.uploading) {
        return;
      }
      this.showUploadDialog = false;
      this.uploadError = "";
    },
    triggerFilePicker() {
      this.$refs.uploadFile?.click();
    },
    handleFileChange(event) {
      const file = event.target.files?.[0] || null;
      this.uploadError = "";
      if (file && file.size > MAX_UPLOAD_SIZE) {
        this.uploadForm.file = null;
        event.target.value = "";
        this.uploadError = "文档文件不能超过 5MB";
        return;
      }
      this.uploadForm.file = file;
      if (file && !this.uploadForm.title) {
        this.uploadForm.title = file.name.replace(/\.[^.]+$/, "");
      }
      if (file) {
        this.uploadForm.documentType = inferDocumentType(file.name);
      }
    },
    async uploadDocument() {
      if (!this.uploadForm.file) {
        this.uploadError = "请选择要上传的文档文件";
        return;
      }
      this.uploading = true;
      this.uploadError = "";
      try {
        const formData = new FormData();
        formData.append("file", this.uploadForm.file);
        formData.append("title", this.uploadForm.title);
        formData.append("source", this.uploadForm.source);
        formData.append("date", this.uploadForm.date);
        formData.append("tags", this.uploadForm.tags);
        formData.append("documentType", this.uploadForm.documentType);
        const document = await uploadSearchDocument(formData);
        this.recordDocumentActivity(document, "VIEW");
        this.showUploadDialog = false;
        this.resetUploadForm();
        this.$emit("navigate", "library");
      } catch (error) {
        this.uploadError = error.message || "上传失败";
      } finally {
        this.uploading = false;
      }
    },
    async openResult(result) {
      if (!result?.docId) {
        return;
      }
      this.viewerOpen = true;
      this.viewerLoading = true;
      this.viewerError = "";
      this.viewerDocument = null;
      this.viewerResult = result;
      try {
        this.viewerDocument = await getSearchDocument(result.docId);
        this.recordDocumentActivity(result, "VIEW");
      } catch (error) {
        this.viewerError = error.message || "加载文档内容失败";
      } finally {
        this.viewerLoading = false;
      }
    },
    closeViewer() {
      this.viewerOpen = false;
      this.viewerLoading = false;
      this.viewerError = "";
      this.viewerDocument = null;
      this.viewerResult = null;
    },
    askAiAboutResult(result) {
      if (!result) {
        return;
      }
      this.$emit("ask-ai", {
        id: `${result.docId || result.id || Date.now()}-${Date.now()}`,
        source: "search_result",
        documentId: result.docId || result.documentId || "",
        title: result.title || "",
        snippet: result.summary || "",
        sourceName: result.source || "",
        date: result.date || "",
        keyword: this.searchedKeyword || this.keyword || "",
        prompt: this.buildAskAiPrompt(result)
      });
      this.recordDocumentActivity(result, "ASK");
    },
    applyDocumentShortcut(shortcut) {
      if (!shortcut?.docId || shortcut.id === this.appliedDocumentShortcutId) {
        return;
      }
      this.appliedDocumentShortcutId = shortcut.id;
      this.openResult({
        docId: shortcut.docId,
        title: shortcut.title || shortcut.docId,
        summary: shortcut.summary || "",
        source: shortcut.source || "workbench"
      });
    },
    recordSearchHits() {
      this.results.slice(0, 5).forEach((result) => {
        this.recordDocumentActivity(result, "SEARCH");
      });
    },
    async recordDocumentActivity(result, actionType) {
      const docId = result?.docId || result?.documentId;
      if (!docId) {
        return;
      }
      try {
        await recordUserActivity({
          tenantId: this.userId,
          userId: this.userId,
          targetType: "DOCUMENT",
          targetId: docId,
          actionType,
          title: result.title || docId,
          summary: result.summary || "",
          extra: {
            source: result.source || "",
            date: result.date || "",
            keyword: this.searchedKeyword || this.keyword || ""
          }
        });
      } catch (error) {
        // Activity logging is best-effort and should not interrupt document work.
      }
    },
    buildAskAiPrompt(result) {
      const chunks = Array.isArray(result?.matchedChunks)
        ? result.matchedChunks
            .map((chunk) => String(chunk?.text || "").trim())
            .filter(Boolean)
            .slice(0, 2)
        : [];
      const parts = [
        `我想基于《${result?.title || "这条搜索结果"}》这条搜索结果继续提问：`,
        "",
        "搜索结果信息：",
        `- 文档ID：${result?.docId || result?.documentId || "未知"}`,
        `- 标题：${result?.title || "未知"}`,
        `- 来源：${result?.source || "未知"}`,
        `- 日期：${result?.date || "未知"}`,
        `- 检索关键词：${this.searchedKeyword || this.keyword || "未指定"}`,
        "",
        "文档摘要：",
        result?.summary || "暂无摘要"
      ];
      if (chunks.length) {
        parts.push("", "命中片段：");
        chunks.forEach((text, index) => {
          parts.push(`${index + 1}. ${text}`);
        });
      }
      parts.push("", "请帮我分析：");
      return parts.join("\n");
    },
    async removeDocument(result) {
      const docId = result?.docId;
      if (!docId) {
        return;
      }
      const title = result.title || docId;
      if (!window.confirm(`确认删除文档「${title}」？删除后不可恢复。`)) {
        return;
      }
      this.loading = true;
      this.error = "";
      try {
        await deleteSearchDocument(docId);
        if (this.viewerDocument?.docId === docId) {
          this.closeViewer();
        }
        await this.performSearch(false);
      } catch (error) {
        this.error = error.message || "删除文档失败";
      } finally {
        this.loading = false;
      }
    },
    resetUploadForm() {
      this.uploadForm = defaultUploadForm();
      const input = this.$refs.uploadFile;
      if (input) {
        input.value = "";
      }
    },
    goToLibrary() {
      this.$emit("navigate", "library");
    },
    goPage(page) {
      this.page = Math.min(Math.max(1, Number(page) || 1), this.pageCount);
      this.performSearch(false);
    },
    clampPage() {
      if (this.page > this.pageCount) {
        this.page = this.pageCount;
      }
      if (this.page < 1) {
        this.page = 1;
      }
    }
  }
};
