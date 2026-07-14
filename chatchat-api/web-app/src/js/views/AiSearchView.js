import "../../styles/pages/ai-search.css";
import {
  cancelSearchDocumentUpload,
  deleteSearchDocument,
  fetchResearchLibrary,
  getSearchDocument,
  recordUserActivity,
  searchDocuments,
  uploadSearchDocument,
  uploadSearchDocumentsInBatches
} from "../../services/api.js";
import {
  inferDocumentType,
  isDocumentOnlinePreviewSupported,
  UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE
} from "../utils/documentPreview.js";

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

function defaultUploadForm() {
  return {
    file: null,
    files: [],
    title: "",
    source: "文档库",
    date: todayString(),
    categoryMode: "existing",
    category: "",
    newCategory: "",
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
    },
    tenantId: {
      type: String,
      default: ""
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
      documentUploadController: null,
      documentUploadRequestId: "",
      viewerOpen: false,
      viewerLoading: false,
      viewerError: "",
      viewerDocument: null,
      viewerResult: null,
      showUploadDialog: false,
      error: "",
      uploadError: "",
      uploadNotice: "",
      uploadCategories: [],
      uploadCategoriesLoading: false,
      uploadForm: defaultUploadForm(),
      appliedDocumentShortcutId: "",
      documentTypeOptions: [
        { value: "auto", label: "自动识别" },
        { value: "pdf", label: "PDF" },
        { value: "word", label: "Word" },
        { value: "excel", label: "Excel" },
        { value: "markdown", label: "Markdown" },
        { value: "sql", label: "SQL" },
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
    },
    uploadCategoryOptions() {
      return this.uploadCategories.filter((category) =>
        category?.name && category.name !== "all" && category.name !== "uncategorized"
      );
    },
    effectiveTenantId() {
      return this.tenantId || this.userId || "default";
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
          pageSize: this.pageSize,
          tenantId: this.effectiveTenantId,
          userId: this.userId
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
    async openUploadDialog() {
      this.uploadError = "";
      this.uploadNotice = "";
      if (!this.uploadForm.date) {
        this.uploadForm.date = todayString();
      }
      this.showUploadDialog = true;
      await this.loadUploadCategories();
    },
    closeUploadDialog() {
      if (this.uploading) {
        return;
      }
      this.showUploadDialog = false;
      this.uploadError = "";
      this.uploadNotice = "";
    },
    async loadUploadCategories() {
      this.uploadCategoriesLoading = true;
      try {
        const payload = await fetchResearchLibrary({
          page: 1,
          pageSize: 1,
          tenantId: this.effectiveTenantId,
          userId: this.userId
        });
        this.uploadCategories = payload?.categories || [];
        const options = this.uploadCategoryOptions;
        if (options.length && this.uploadForm.categoryMode !== "custom") {
          this.uploadForm.categoryMode = "existing";
          if (!this.uploadForm.category || !options.some((category) => category.name === this.uploadForm.category)) {
            this.uploadForm.category = options[0].name;
          }
        } else if (!options.length) {
          this.uploadForm.categoryMode = "custom";
          this.uploadForm.category = "";
        }
      } catch (error) {
        this.uploadCategories = [];
        this.uploadForm.categoryMode = "custom";
      } finally {
        this.uploadCategoriesLoading = false;
      }
    },
    resolveUploadCategory() {
      return String(
        this.uploadForm.categoryMode === "custom"
          ? this.uploadForm.newCategory
          : this.uploadForm.category
      ).trim();
    },
    triggerFilePicker() {
      this.$refs.uploadFile?.click();
    },
    handleFileChange(event) {
      const files = Array.from(event.target.files || []);
      const file = files[0] || null;
      this.uploadError = "";
      const oversized = files.find((item) => item.size > MAX_UPLOAD_SIZE);
      if (oversized) {
        this.uploadForm.file = null;
        this.uploadForm.files = [];
        event.target.value = "";
        this.uploadError = `文件不能超过 5MB: ${oversized.name}`;
        return;
      }
      this.uploadForm.file = file;
      this.uploadForm.files = files;
      if (file && !this.uploadForm.title) {
        this.uploadForm.title = file.name.replace(/\.[^.]+$/, "");
      }
      if (file && files.length === 1) {
        this.uploadForm.documentType = inferDocumentType(file.name);
      } else if (files.length > 1) {
        this.uploadForm.title = "";
        this.uploadForm.documentType = "auto";
      }
    },
    async uploadDocument() {
      const files = this.uploadForm.files?.length ? this.uploadForm.files : (this.uploadForm.file ? [this.uploadForm.file] : []);
      const category = this.resolveUploadCategory();
      if (!files.length) {
        this.uploadError = "请选择要上传的文件";
        return;
      }
      if (!category) {
        this.uploadError = this.uploadForm.categoryMode === "custom" ? "请输入新分类名称" : "请选择文档分类";
        return;
      }
      this.uploading = true;
      this.uploadError = "";
      this.uploadNotice = "";
      const uploadRequestId = `upload-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      const uploadController = new AbortController();
      this.documentUploadRequestId = uploadRequestId;
      this.documentUploadController = uploadController;
      try {
        const formData = new FormData();
        formData.append("source", this.uploadForm.source);
        formData.append("date", this.uploadForm.date);
        formData.append("tags", this.uploadForm.tags);
        formData.append("category", category);
        formData.append("documentType", this.uploadForm.documentType);
        formData.append("tenantId", this.effectiveTenantId);
        formData.append("userId", this.userId);
        let documents = [];
        if (files.length > 1) {
          documents = await uploadSearchDocumentsInBatches(formData, files, {
            signal: uploadController.signal,
            uploadRequestId
          });
        } else {
          formData.append("file", files[0]);
          formData.append("title", this.uploadForm.title);
          formData.set("tags", [category, this.uploadForm.tags].filter(Boolean).join(","));
          documents = [await uploadSearchDocument(formData, {
            signal: uploadController.signal,
            uploadRequestId
          })];
        }
        const uploadedDocuments = Array.isArray(documents) ? documents : [documents];
        uploadedDocuments.forEach((document) => this.recordDocumentActivity(document, "VIEW"));
        this.uploadNotice = `已上传 ${uploadedDocuments.length} 个文档，请点击右上角关闭按钮关闭窗口。`;
        this.resetUploadForm();
      } catch (error) {
        this.uploadError = error?.name === "AbortError" ? "上传已终止。" : (error.message || "上传失败");
      } finally {
        if (this.documentUploadController === uploadController) {
          this.documentUploadController = null;
          this.documentUploadRequestId = "";
        }
        this.uploading = false;
      }
    },
    async terminateDocumentUpload() {
      if (!this.uploading || !this.documentUploadController) {
        return;
      }
      const uploadRequestId = this.documentUploadRequestId;
      const cancellation = cancelSearchDocumentUpload(uploadRequestId).catch(() => false);
      this.documentUploadController.abort();
      this.uploadError = "正在终止上传...";
      await cancellation;
    },
    async openResult(result) {
      if (!result?.docId) {
        return;
      }
      if (!this.canPreviewResult(result)) {
        this.error = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
        return;
      }
      this.viewerOpen = true;
      this.viewerLoading = true;
      this.viewerError = "";
      this.viewerDocument = null;
      this.viewerResult = result;
      try {
        this.viewerDocument = await getSearchDocument(result.docId, {
          tenantId: this.effectiveTenantId,
          userId: this.userId
        });
        if (!this.canPreviewResult(this.viewerDocument)) {
          this.viewerError = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
          return;
        }
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
    canPreviewResult(result) {
      return isDocumentOnlinePreviewSupported(result);
    },
    visibleResultTags(result) {
      if (!Array.isArray(result?.tags)) {
        return [];
      }
      return [...new Set(result.tags.map((tag) => String(tag || "").trim()).filter(Boolean))]
        .slice(0, 12);
    },
    documentPreviewTitle(result) {
      return this.canPreviewResult(result) ? "" : UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
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
        source: shortcut.source || "workbench",
        fileName: shortcut.fileName || "",
        documentType: shortcut.documentType || ""
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
          tenantId: this.effectiveTenantId,
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
        await deleteSearchDocument(docId, {
          tenantId: this.effectiveTenantId,
          userId: this.userId
        });
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
