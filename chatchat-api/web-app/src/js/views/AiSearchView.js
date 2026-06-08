import "../../styles/pages/ai-search.css";
import { searchDocuments, uploadSearchDocument } from "../../services/api.js";

const MAX_UPLOAD_SIZE = 5 * 1024 * 1024;
const MESSAGE_TEXT = {
  library_empty: "资料库暂无文档，请先上传资料。",
  no_match: "没有找到匹配资料，请换一个关键词。",
  no_documents: "暂无可展示资料。",
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
    source: "投研资料库",
    date: todayString(),
    tags: "",
    documentType: "auto"
  };
}

export default {
  name: "AiSearchView",
  emits: ["navigate"],
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
      showUploadDialog: false,
      error: "",
      uploadError: "",
      uploadForm: defaultUploadForm(),
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
      } catch (error) {
        this.error = error.message || "搜索失败";
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
        this.uploadError = "资料文件不能超过 5MB";
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
        this.uploadError = "请选择要上传的资料文件";
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
        await uploadSearchDocument(formData);
        this.showUploadDialog = false;
        this.resetUploadForm();
        this.$emit("navigate", "library");
      } catch (error) {
        this.uploadError = error.message || "上传失败";
      } finally {
        this.uploading = false;
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
