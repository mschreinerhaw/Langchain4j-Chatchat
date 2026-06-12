import { nextTick } from "vue";
import MarkdownIt from "markdown-it";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { renderAsync } from "docx-preview";
import * as XLSX from "xlsx";
import "../../styles/pages/library.css";
import {
  addUserFavorite,
  createResearchCategory,
  deleteSearchDocument,
  fetchDocumentFile,
  fetchResearchLibrary,
  getSearchDocument,
  getSearchDocumentVersion,
  getSearchDocumentVersions
} from "../../services/api.js";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
});

const MESSAGE_TEXT = {
  library_empty: "文档库暂无文档，请先在文档检索页上传文档。",
  title_not_found: "没有找到这个标题的文档。",
  no_documents: "当前分类暂无文档。",
  ok: ""
};

export default {
  name: "LibraryView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  emits: ["navigate"],
  data() {
    return {
      titleKeyword: "",
      newCategoryName: "",
      activeCategory: "all",
      categories: [],
      documents: [],
      total: 0,
      documentCount: 0,
      page: 1,
      pageSize: 6,
      pageCount: 1,
      titleExists: false,
      exactTitleDocId: "",
      message: "",
      loading: false,
      creatingCategory: false,
      favoriteSavingIds: {},
      error: "",
      categoryDialogOpen: false,
      viewerOpen: false,
      viewerLoading: false,
      viewerError: "",
      viewerDocument: null,
      viewerVersions: [],
      selectedVersion: null,
      viewerHtml: "",
      viewerMode: "text"
    };
  },
  computed: {
    viewerType() {
      return this.viewerDocument?.documentType || this.inferDocumentType(this.viewerDocument?.fileName);
    },
    viewerFileUrl() {
      const docId = this.viewerDocument?.docId;
      const version = this.selectedVersion || this.viewerDocument?.version;
      if (!docId) {
        return "";
      }
      return version
        ? `/api/v1/search/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}/file`
        : `/api/v1/search/documents/${encodeURIComponent(docId)}/file`;
    },
    hasMultipleVersions() {
      return this.viewerVersions.length > 1;
    },
    selectedVersionLabel() {
      return this.selectedVersion ? `v${this.selectedVersion}` : "v1";
    },
    latestVersionNumber() {
      return this.viewerVersions.find((version) => version.latestVersion)?.version
        || this.viewerVersions[0]?.version
        || this.viewerDocument?.version
        || 1;
    },
    viewerVersionSummary() {
      return this.hasMultipleVersions
        ? `${this.selectedVersionLabel} / 最新 v${this.latestVersionNumber}`
        : "";
    },
    pagedDocuments() {
      return this.documents;
    },
    pageStart() {
      if (!this.documents.length) {
        return 0;
      }
      return (Math.min(Math.max(1, this.page), this.pageCount) - 1) * this.pageSize + 1;
    },
    pageEnd() {
      return Math.min(this.page * this.pageSize, this.total);
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
    titleKeyword() {
      this.page = 1;
    },
    documents() {
      this.clampPage();
    }
  },
  activated() {
    this.loadLibrary();
  },
  mounted() {
    this.loadLibrary();
  },
  methods: {
    async loadLibrary() {
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchResearchLibrary({
          category: this.activeCategory,
          title: this.titleKeyword.trim(),
          page: this.page,
          pageSize: this.pageSize
        });
        this.categories = payload?.categories || [];
        this.documents = payload?.documents || [];
        this.total = payload?.total || 0;
        this.page = payload?.page || this.page;
        this.pageSize = payload?.pageSize || this.pageSize;
        this.pageCount = payload?.totalPages || 1;
        this.documentCount = payload?.documentCount || 0;
        this.titleExists = Boolean(payload?.titleExists);
        this.exactTitleDocId = payload?.exactTitleDocId || "";
        this.message = MESSAGE_TEXT[payload?.message] || "";
        this.clampPage();
      } catch (error) {
        this.error = error.message || "加载文档库失败";
      } finally {
        this.loading = false;
      }
    },
    async selectCategory(category) {
      this.activeCategory = category;
      this.page = 1;
      await this.loadLibrary();
    },
    async searchByTitle() {
      this.page = 1;
      await this.loadLibrary();
      const title = this.titleKeyword.trim();
      if (title) {
        this.message = this.titleExists ? "文档已存在。" : "文档不存在。";
      }
    },
    async createCategory() {
      const name = this.newCategoryName.trim();
      if (!name) {
        this.message = "请输入分类名称。";
        this.categoryDialogOpen = true;
        await nextTick();
        this.$refs.categoryNameInput?.focus();
        return;
      }
      this.creatingCategory = true;
      this.error = "";
      try {
        await createResearchCategory(name);
        this.newCategoryName = "";
        this.categoryDialogOpen = false;
        this.message = "分类已创建。";
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "创建分类失败";
      } finally {
        this.creatingCategory = false;
      }
    },
    async openCategoryDialog() {
      this.categoryDialogOpen = true;
      this.message = "";
      this.error = "";
      await nextTick();
      this.$refs.categoryNameInput?.focus();
    },
    closeCategoryDialog() {
      if (this.creatingCategory) {
        return;
      }
      this.categoryDialogOpen = false;
      this.newCategoryName = "";
    },
    async openDocument(docId) {
      this.viewerOpen = true;
      this.viewerLoading = true;
      this.viewerError = "";
      this.viewerDocument = null;
      this.viewerVersions = [];
      this.selectedVersion = null;
      this.viewerHtml = "";
      this.viewerMode = "text";
      try {
        const document = await getSearchDocument(docId);
        const versions = await getSearchDocumentVersions(docId);
        this.viewerDocument = document;
        this.viewerVersions = versions?.length ? versions : [this.versionItemFromDocument(document)];
        this.selectedVersion = document?.version || this.viewerVersions.find((version) => version.latestVersion)?.version || 1;
        this.viewerLoading = false;
        await nextTick();
        await this.renderDocument();
      } catch (error) {
        this.viewerError = error.message || "加载文档失败";
        this.viewerLoading = false;
      } finally {
        this.viewerLoading = false;
      }
    },
    async removeDocument(item) {
      const docId = item?.docId;
      if (!docId) {
        return;
      }
      const title = item.title || docId;
      if (!window.confirm(`确认删除文档「${title}」？删除后不可恢复。`)) {
        return;
      }
      this.loading = true;
      this.error = "";
      try {
        await deleteSearchDocument(docId);
        this.message = "文档已删除。";
        if (this.viewerDocument?.docId === docId) {
          this.closeDocument();
        }
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "删除文档失败";
      } finally {
        this.loading = false;
      }
    },
    async favoriteDocument(item) {
      const docId = item?.docId;
      if (!docId || this.favoriteSavingIds[docId]) {
        return;
      }
      this.favoriteSavingIds = {
        ...this.favoriteSavingIds,
        [docId]: true
      };
      this.error = "";
      try {
        await addUserFavorite({
          tenantId: this.userId,
          userId: this.userId,
          targetType: "DOCUMENT",
          targetId: docId,
          title: item.title || docId,
          category: this.favoriteCategoryForDocument(item)
        });
        this.message = "文档已收藏。";
      } catch (error) {
        this.error = error.message || "收藏文档失败";
      } finally {
        const next = { ...this.favoriteSavingIds };
        delete next[docId];
        this.favoriteSavingIds = next;
      }
    },
    async switchDocumentVersion(version) {
      if (!version || version === this.selectedVersion || !this.viewerDocument?.docId) {
        return;
      }
      this.viewerLoading = true;
      this.viewerError = "";
      this.viewerHtml = "";
      this.viewerMode = "text";
      try {
        this.viewerDocument = await getSearchDocumentVersion(this.viewerDocument.docId, version);
        this.selectedVersion = version;
        this.viewerLoading = false;
        await nextTick();
        await this.renderDocument();
      } catch (error) {
        this.viewerError = error.message || "加载文档版本失败";
        this.viewerLoading = false;
      } finally {
        this.viewerLoading = false;
      }
    },
    async renderDocument() {
      const type = this.viewerType;
      if (type === "pdf") {
        this.viewerMode = "pdf";
        await this.renderPdf();
        return;
      }
      if (type === "word") {
        this.viewerMode = "word";
        try {
          await this.renderWord();
        } catch (error) {
          this.viewerMode = "text";
          if (!this.viewerDocument?.content) {
            this.viewerError = error.message || "Word 文档预览失败";
          }
        }
        return;
      }
      if (type === "excel") {
        this.viewerMode = "html";
        try {
          await this.renderExcel();
        } catch (error) {
          this.viewerMode = "text";
        }
        return;
      }
      if (type === "markdown") {
        this.viewerMode = "html";
        this.viewerHtml = markdown.render(this.viewerDocument?.content || "");
        return;
      }
      this.viewerMode = "text";
    },
    async renderPdf() {
      const container = this.$refs.pdfViewer;
      if (!container || !this.viewerFileUrl) {
        return;
      }
      container.innerHTML = "";
      const { buffer } = await fetchDocumentFile(this.viewerFileUrl);
      const pdf = await pdfjsLib.getDocument({ data: buffer }).promise;
      const pageCount = Math.min(pdf.numPages, 20);
      for (let pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
        const page = await pdf.getPage(pageNumber);
        const viewport = page.getViewport({ scale: 1.25 });
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d");
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        container.appendChild(canvas);
        await page.render({ canvasContext: context, viewport }).promise;
      }
    },
    async renderWord() {
      const container = this.$refs.wordViewer;
      if (!container || !this.viewerFileUrl) {
        return;
      }
      container.innerHTML = "";
      const { buffer } = await fetchDocumentFile(this.viewerFileUrl);
      if (!this.isZipDocument(buffer)) {
        throw new Error("当前 Word 文件不是 docx 格式，无法直接预览");
      }
      await renderAsync(buffer, container, null, {
        className: "docx-rendered",
        inWrapper: true,
        ignoreFonts: true,
        useBase64URL: true
      });
      if (!container.textContent.trim()) {
        throw new Error("Word 文档没有渲染出可见文字");
      }
    },
    async renderExcel() {
      const { buffer } = await fetchDocumentFile(this.viewerFileUrl);
      const workbook = XLSX.read(buffer, { type: "array" });
      const firstSheet = workbook.SheetNames[0];
      this.viewerHtml = firstSheet ? XLSX.utils.sheet_to_html(workbook.Sheets[firstSheet]) : "";
    },
    closeDocument() {
      this.viewerOpen = false;
      this.viewerDocument = null;
      this.viewerVersions = [];
      this.selectedVersion = null;
      this.viewerError = "";
      this.viewerHtml = "";
    },
    backToSearch() {
      this.$emit("navigate", "search");
    },
    goPage(page) {
      this.page = Math.min(Math.max(1, Number(page) || 1), this.pageCount);
      this.loadLibrary();
    },
    clampPage() {
      if (this.page > this.pageCount) {
        this.page = this.pageCount;
      }
      if (this.page < 1) {
        this.page = 1;
      }
    },
    categoryLabel(name) {
      if (name === "all") {
        return "全部文档";
      }
      if (name === "uncategorized") {
        return "未分类";
      }
      return name;
    },
    favoriteCategoryForDocument(item) {
      const category = item?.category;
      if (!category || category === "all" || category === "uncategorized") {
        return "文档";
      }
      return this.categoryLabel(category);
    },
    inferDocumentType(fileName = "") {
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
    },
    isZipDocument(buffer) {
      const bytes = new Uint8Array(buffer, 0, Math.min(buffer.byteLength, 4));
      return bytes[0] === 0x50 && bytes[1] === 0x4b;
    },
    documentTypeLabel(type) {
      const labels = {
        pdf: "PDF",
        word: "Word",
        excel: "Excel",
        markdown: "Markdown",
        text: "Text"
      };
      return labels[type] || "Text";
    },
    versionItemFromDocument(document) {
      return {
        docId: document?.docId,
        versionGroupId: document?.versionGroupId || document?.docId,
        version: document?.version || 1,
        latestVersion: document?.latestVersion !== false,
        title: document?.title,
        source: document?.source,
        date: document?.date,
        fileName: document?.fileName,
        documentType: document?.documentType,
        uploadedAt: document?.uploadedAt,
        updatedAt: document?.updatedAt
      };
    },
    formatVersionTime(value) {
      if (!value) {
        return "";
      }
      return new Date(value).toLocaleString();
    }
  }
};
