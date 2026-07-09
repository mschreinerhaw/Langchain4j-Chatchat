import { nextTick } from "vue";
import { MoreHorizontal, Pencil, RefreshCw, Trash2 } from "@lucide/vue";
import MarkdownIt from "markdown-it";
import * as XLSX from "xlsx";
import "../../styles/pages/library.css";
import {
  addUserFavorite,
  createResearchCategory,
  deleteResearchCategory,
  deleteSearchDocument,
  deleteSearchDocuments,
  fetchDocumentFile,
  fetchResearchCategoryReindexStatus,
  fetchResearchLibrary,
  getSearchDocument,
  getSearchDocumentVersion,
  getSearchDocumentVersions,
  reindexResearchCategory,
  reindexSearchDocument,
  renameResearchCategory,
  updateSearchDocumentCategory
} from "../../services/api.js";
import {
  getDocumentPreviewType,
  inferDocumentType,
  isDocumentOnlinePreviewSupported,
  UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE
} from "../utils/documentPreview.js";

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
  components: {
    MoreHorizontal,
    Pencil,
    RefreshCw,
    Trash2
  },
  props: {
    userId: {
      type: String,
      default: "default-user"
    },
    tenantId: {
      type: String,
      default: ""
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
      editingCategoryName: "",
      categoryDeleteDialogOpen: false,
      categoryDeleteItem: null,
      categoryDeleteSubmitting: false,
      openCategoryActionName: "",
      openDocumentActionId: "",
      favoriteSavingIds: {},
      categorySavingNames: {},
      categoryReindexTask: null,
      categoryReindexStatusLoading: false,
      categoryReindexPollTimer: null,
      categoryReindexDialogOpen: false,
      categoryReindexItem: null,
      categoryReindexSubmitting: false,
      documentCategorySavingIds: {},
      documentReindexingIds: {},
      error: "",
      categoryDialogOpen: false,
      documentCategoryDialogOpen: false,
      documentCategoryItem: null,
      selectedDocumentCategory: "",
      documentDeleteDialogOpen: false,
      documentDeleteItem: null,
      documentDeleteSubmitting: false,
      selectedDocumentIds: [],
      documentBatchDeleteDialogOpen: false,
      documentBatchDeleteSubmitting: false,
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
    effectiveTenantId() {
      return this.tenantId || this.userId || "default";
    },
    permissionFilters() {
      return {
        tenantId: this.effectiveTenantId,
        userId: this.userId
      };
    },
    canDeleteDocuments() {
      return String(this.userId || "").toLowerCase() === "admin";
    },
    permissionQuery() {
      const params = new URLSearchParams();
      if (this.effectiveTenantId) {
        params.set("tenantId", this.effectiveTenantId);
      }
      if (this.userId) {
        params.set("userId", this.userId);
      }
      const query = params.toString();
      return query ? `?${query}` : "";
    },
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
        ? `/api/v1/search/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}/file${this.permissionQuery}`
        : `/api/v1/search/documents/${encodeURIComponent(docId)}/file${this.permissionQuery}`;
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
    categoryDialogTitle() {
      return this.editingCategoryName ? "修改分类" : "创建分类";
    },
    categorySubmitLabel() {
      if (this.creatingCategory) {
        return this.editingCategoryName ? "保存中" : "创建中";
      }
      return this.editingCategoryName ? "保存" : "创建";
    },
    editableCategories() {
      return this.categories.filter((category) => this.isMutableCategory(category.name));
    },
    pagedDocuments() {
      return this.documents;
    },
    visibleDocumentIds() {
      return this.pagedDocuments.map((document) => document?.docId).filter(Boolean);
    },
    selectedDocumentIdSet() {
      return new Set(this.selectedDocumentIds);
    },
    selectedDocumentCount() {
      return this.canDeleteDocuments ? this.selectedDocumentIds.length : 0;
    },
    selectedDocuments() {
      const selected = this.selectedDocumentIdSet;
      return this.documents.filter((document) => selected.has(document.docId));
    },
    allVisibleDocumentsSelected() {
      return this.visibleDocumentIds.length > 0
        && this.visibleDocumentIds.every((docId) => this.selectedDocumentIdSet.has(docId));
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
    },
    categoryReindexRunning() {
      return Boolean(this.categoryReindexTask?.running || this.categoryReindexTask?.status === "RUNNING");
    }
  },
  watch: {
    titleKeyword() {
      this.page = 1;
    },
    documents() {
      this.clampPage();
      this.pruneSelectedDocuments();
    }
  },
  activated() {
    this.loadLibrary();
    this.refreshCategoryReindexStatus({ silent: true });
  },
  mounted() {
    this.loadLibrary();
    this.refreshCategoryReindexStatus({ silent: true });
  },
  deactivated() {
    this.stopCategoryReindexPolling();
  },
  beforeUnmount() {
    this.stopCategoryReindexPolling();
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
          pageSize: this.pageSize,
          ...this.permissionFilters
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
      this.closeCategoryActions();
      this.closeDocumentActions();
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
        if (this.editingCategoryName) {
          await renameResearchCategory(this.editingCategoryName, name);
          if (this.activeCategory === this.editingCategoryName) {
            this.activeCategory = name;
          }
          this.message = "分类已更新。";
        } else {
          await createResearchCategory(name);
          this.message = "分类已创建。";
        }
        this.newCategoryName = "";
        this.editingCategoryName = "";
        this.categoryDialogOpen = false;
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || (this.editingCategoryName ? "修改分类失败" : "创建分类失败");
      } finally {
        this.creatingCategory = false;
      }
    },
    async openCategoryDialog(category = null) {
      this.closeCategoryActions();
      this.editingCategoryName = this.isMutableCategory(category?.name) ? category.name : "";
      this.newCategoryName = this.editingCategoryName ? this.categoryLabel(this.editingCategoryName) : "";
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
      this.editingCategoryName = "";
    },
    isMutableCategory(name) {
      return Boolean(name) && name !== "all" && name !== "uncategorized";
    },
    toggleCategoryActions(name) {
      if (!name) {
        return;
      }
      this.closeDocumentActions();
      this.openCategoryActionName = this.openCategoryActionName === name ? "" : name;
    },
    closeCategoryActions() {
      this.openCategoryActionName = "";
    },
    toggleDocumentActions(docId) {
      if (!docId) {
        return;
      }
      this.closeCategoryActions();
      this.openDocumentActionId = this.openDocumentActionId === docId ? "" : docId;
    },
    closeDocumentActions() {
      this.openDocumentActionId = "";
    },
    toggleDocumentSelection(docId, checked) {
      if (!this.canDeleteDocuments) {
        return;
      }
      if (!docId) {
        return;
      }
      const selected = new Set(this.selectedDocumentIds);
      if (checked) {
        selected.add(docId);
      } else {
        selected.delete(docId);
      }
      this.selectedDocumentIds = [...selected];
    },
    toggleAllVisibleDocuments(checked) {
      if (!this.canDeleteDocuments) {
        return;
      }
      const selected = new Set(this.selectedDocumentIds);
      this.visibleDocumentIds.forEach((docId) => {
        if (checked) {
          selected.add(docId);
        } else {
          selected.delete(docId);
        }
      });
      this.selectedDocumentIds = [...selected];
    },
    clearDocumentSelection() {
      this.selectedDocumentIds = [];
    },
    pruneSelectedDocuments() {
      const visible = new Set(this.visibleDocumentIds);
      this.selectedDocumentIds = this.selectedDocumentIds.filter((docId) => visible.has(docId));
    },
    async refreshCategoryReindexStatus(options = {}) {
      if (this.categoryReindexStatusLoading) {
        return;
      }
      this.categoryReindexStatusLoading = true;
      try {
        const status = await fetchResearchCategoryReindexStatus(this.permissionFilters);
        await this.applyCategoryReindexTask(status, options);
      } catch (error) {
        if (!options.silent) {
          this.error = error.message || "获取分类索引重建任务状态失败";
        }
      } finally {
        this.categoryReindexStatusLoading = false;
      }
    },
    async applyCategoryReindexTask(status, options = {}) {
      const previousTask = this.categoryReindexTask;
      const wasRunning = Boolean(previousTask?.running || previousTask?.status === "RUNNING");
      const previousTaskId = previousTask?.taskId || "";
      this.categoryReindexTask = status || null;
      if (this.categoryReindexRunning) {
        this.startCategoryReindexPolling();
        return;
      }
      this.stopCategoryReindexPolling();
      if (!options.silent && wasRunning && status?.taskId && status.taskId === previousTaskId) {
        if (status.status === "COMPLETED") {
          const label = this.categoryLabel(status.category);
          this.message = `分类「${label}」索引重建完成：成功 ${status.reindexedDocuments || 0} 份，失败 ${status.failedDocuments || 0} 份。`;
          await this.loadLibrary();
        } else if (status.status === "FAILED") {
          this.error = status.message || "分类索引重建任务失败";
        }
      }
    },
    startCategoryReindexPolling() {
      if (this.categoryReindexPollTimer) {
        return;
      }
      this.categoryReindexPollTimer = window.setInterval(() => {
        this.refreshCategoryReindexStatus({ silent: false });
      }, 2500);
    },
    stopCategoryReindexPolling() {
      if (!this.categoryReindexPollTimer) {
        return;
      }
      window.clearInterval(this.categoryReindexPollTimer);
      this.categoryReindexPollTimer = null;
    },
    isCategoryReindexTarget(name) {
      return this.categoryReindexRunning && this.categoryReindexTask?.category === name;
    },
    categoryReindexButtonLabel(category) {
      if (!this.categoryReindexRunning) {
        return "重建索引";
      }
      return this.isCategoryReindexTarget(category?.name) ? "重建中" : "任务运行中";
    },
    categoryReindexButtonTitle(category) {
      if (this.categoryReindexRunning) {
        return "当前有分类索引重建任务运行中，请等待完成";
      }
      return Number(category?.count || 0) > 0 ? "重建该分类下文档的索引" : "当前分类暂无文档可重建";
    },
    openCategoryReindexDialog(category) {
      const name = category?.name;
      if (!name) {
        return;
      }
      this.closeCategoryActions();
      if (this.categoryReindexRunning) {
        this.message = "当前有分类索引重建任务运行中，请等待完成。";
        return;
      }
      const count = Number(category?.count || 0);
      const label = this.categoryLabel(name);
      if (count <= 0) {
        this.message = `分类「${label}」暂无文档可重建。`;
        return;
      }
      this.categoryReindexItem = category;
      this.categoryReindexDialogOpen = true;
      this.error = "";
    },
    closeCategoryReindexDialog() {
      if (this.categoryReindexSubmitting) {
        return;
      }
      this.categoryReindexDialogOpen = false;
      this.categoryReindexItem = null;
    },
    async submitCategoryReindex() {
      const category = this.categoryReindexItem;
      const name = category?.name;
      if (!name || this.categoryReindexSubmitting) {
        return;
      }
      if (this.categoryReindexRunning) {
        this.closeCategoryReindexDialog();
        this.message = "当前有分类索引重建任务运行中，请等待完成。";
        return;
      }
      const label = this.categoryLabel(name);
      this.categoryReindexSubmitting = true;
      this.error = "";
      try {
        const response = await reindexResearchCategory(name, this.permissionFilters);
        await this.applyCategoryReindexTask(response?.task, { silent: true });
        this.categoryReindexDialogOpen = false;
        this.categoryReindexItem = null;
        if (response?.accepted === false) {
          this.message = response?.task?.message || "当前有分类索引重建任务运行中，请等待完成。";
          return;
        }
        this.message = `分类「${label}」索引重建任务已开始，后台会逐个重建文档索引，请等待完成。`;
      } catch (error) {
        this.error = error.message || "重建分类索引失败";
      } finally {
        this.categoryReindexSubmitting = false;
      }
    },
    async reindexCategory(category) {
      this.openCategoryReindexDialog(category);
    },
    async renameCategory(category) {
      if (!this.isMutableCategory(category?.name)) {
        return;
      }
      this.closeCategoryActions();
      await this.openCategoryDialog(category);
    },
    async deleteCategory(category) {
      this.openCategoryDeleteDialog(category);
    },
    openCategoryDeleteDialog(category) {
      if (!this.isMutableCategory(category?.name) || this.categorySavingNames[category.name]) {
        return;
      }
      this.closeCategoryActions();
      this.categoryDeleteItem = category;
      this.categoryDeleteDialogOpen = true;
      this.error = "";
    },
    closeCategoryDeleteDialog() {
      if (this.categoryDeleteSubmitting) {
        return;
      }
      this.categoryDeleteDialogOpen = false;
      this.categoryDeleteItem = null;
    },
    async submitCategoryDelete() {
      const category = this.categoryDeleteItem;
      if (!this.isMutableCategory(category?.name) || this.categoryDeleteSubmitting || this.categorySavingNames[category.name]) {
        return;
      }
      const name = category.name;
      this.categorySavingNames = { ...this.categorySavingNames, [category.name]: true };
      this.categoryDeleteSubmitting = true;
      this.error = "";
      try {
        await deleteResearchCategory(name);
        this.categoryDeleteDialogOpen = false;
        this.categoryDeleteItem = null;
        if (this.activeCategory === name) {
          this.activeCategory = "all";
          this.page = 1;
        }
        this.message = "分类已删除。";
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "删除分类失败";
      } finally {
        const next = { ...this.categorySavingNames };
        delete next[name];
        this.categorySavingNames = next;
        this.categoryDeleteSubmitting = false;
      }
    },
    async changeDocumentCategory(item, category) {
      const docId = item?.docId;
      if (!docId || this.documentCategorySavingIds[docId]) {
        return false;
      }
      const nextCategory = (category || "").trim();
      if (!nextCategory) {
        this.error = "请选择文档分类";
        return false;
      }
      if (nextCategory === item.category) {
        return true;
      }
      this.documentCategorySavingIds = { ...this.documentCategorySavingIds, [docId]: true };
      this.error = "";
      try {
        await updateSearchDocumentCategory(docId, nextCategory, this.permissionFilters);
        this.message = "文档分类已更新。";
        await this.loadLibrary();
        return true;
      } catch (error) {
        this.error = error.message || "修改文档分类失败";
        return false;
      } finally {
        const next = { ...this.documentCategorySavingIds };
        delete next[docId];
        this.documentCategorySavingIds = next;
      }
    },
    async openDocumentCategoryDialog(item) {
      if (!item?.docId || this.editableCategories.length === 0) {
        return;
      }
      this.closeDocumentActions();
      this.documentCategoryItem = item;
      this.selectedDocumentCategory = this.isMutableCategory(item.category)
        ? item.category
        : (this.editableCategories[0]?.name || "");
      this.documentCategoryDialogOpen = true;
      this.error = "";
      await nextTick();
      this.$refs.documentCategorySelect?.focus();
    },
    closeDocumentCategoryDialog() {
      const docId = this.documentCategoryItem?.docId;
      if (docId && this.documentCategorySavingIds[docId]) {
        return;
      }
      this.documentCategoryDialogOpen = false;
      this.documentCategoryItem = null;
      this.selectedDocumentCategory = "";
    },
    async submitDocumentCategory() {
      const changed = await this.changeDocumentCategory(this.documentCategoryItem, this.selectedDocumentCategory);
      if (changed) {
        this.closeDocumentCategoryDialog();
      }
    },
    canPreviewDocument(document) {
      return isDocumentOnlinePreviewSupported(document);
    },
    documentPreviewTitle(document) {
      return this.canPreviewDocument(document) ? "" : UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
    },
    async openDocument(docId, documentItem = null) {
      this.viewerOpen = true;
      this.viewerLoading = true;
      this.viewerError = "";
      this.viewerDocument = null;
      this.viewerVersions = [];
      this.selectedVersion = null;
      this.viewerHtml = "";
      this.viewerMode = "text";
      if (documentItem && !this.canPreviewDocument(documentItem)) {
        this.viewerOpen = false;
        this.viewerLoading = false;
        this.message = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
        return;
      }
      try {
        const document = await getSearchDocument(docId, this.permissionFilters);
        if (!this.canPreviewDocument(document)) {
          this.viewerError = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
          this.viewerLoading = false;
          return;
        }
        const versions = await getSearchDocumentVersions(docId, this.permissionFilters);
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
      if (!this.canDeleteDocuments) {
        return;
      }
      this.openDocumentDeleteDialog(item);
    },
    openDocumentBatchDeleteDialog() {
      if (!this.canDeleteDocuments) {
        return;
      }
      if (!this.selectedDocumentCount) {
        return;
      }
      this.closeCategoryActions();
      this.closeDocumentActions();
      this.documentBatchDeleteDialogOpen = true;
      this.error = "";
    },
    closeDocumentBatchDeleteDialog() {
      if (this.documentBatchDeleteSubmitting) {
        return;
      }
      this.documentBatchDeleteDialogOpen = false;
    },
    async submitDocumentBatchDelete() {
      if (!this.canDeleteDocuments) {
        return;
      }
      const docIds = [...this.selectedDocumentIds];
      if (!docIds.length || this.documentBatchDeleteSubmitting) {
        return;
      }
      this.documentBatchDeleteSubmitting = true;
      this.loading = true;
      this.error = "";
      try {
        const result = await deleteSearchDocuments(docIds, this.permissionFilters);
        const deletedDocIds = Array.isArray(result?.deletedDocIds) ? result.deletedDocIds : [];
        const notFoundDocIds = Array.isArray(result?.notFoundDocIds) ? result.notFoundDocIds : [];
        this.documentBatchDeleteDialogOpen = false;
        this.clearDocumentSelection();
        if (this.viewerDocument?.docId && deletedDocIds.includes(this.viewerDocument.docId)) {
          this.closeDocument();
        }
        this.message = notFoundDocIds.length
          ? `已删除 ${deletedDocIds.length} 个文档，${notFoundDocIds.length} 个文档未找到或无权限。`
          : `已删除 ${deletedDocIds.length} 个文档。`;
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "批量删除文档失败";
      } finally {
        this.loading = false;
        this.documentBatchDeleteSubmitting = false;
      }
    },
    openDocumentDeleteDialog(item) {
      if (!this.canDeleteDocuments) {
        return;
      }
      const docId = item?.docId;
      if (!docId) {
        return;
      }
      this.closeDocumentActions();
      this.documentDeleteItem = item;
      this.documentDeleteDialogOpen = true;
      this.error = "";
    },
    closeDocumentDeleteDialog() {
      if (this.documentDeleteSubmitting) {
        return;
      }
      this.documentDeleteDialogOpen = false;
      this.documentDeleteItem = null;
    },
    async submitDocumentDelete() {
      const item = this.documentDeleteItem;
      const docId = item?.docId;
      if (!docId || this.documentDeleteSubmitting) {
        return;
      }
      this.documentDeleteSubmitting = true;
      this.loading = true;
      this.error = "";
      try {
        await deleteSearchDocument(docId, this.permissionFilters);
        this.documentDeleteDialogOpen = false;
        this.documentDeleteItem = null;
        this.selectedDocumentIds = this.selectedDocumentIds.filter((selectedDocId) => selectedDocId !== docId);
        this.message = "文档已删除。";
        if (this.viewerDocument?.docId === docId) {
          this.closeDocument();
        }
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "删除文档失败";
      } finally {
        this.loading = false;
        this.documentDeleteSubmitting = false;
      }
    },
    async reindexDocument(item) {
      const docId = item?.docId;
      if (!docId || this.documentReindexingIds[docId]) {
        return;
      }
      this.closeDocumentActions();
      this.documentReindexingIds = { ...this.documentReindexingIds, [docId]: true };
      this.error = "";
      try {
        await reindexSearchDocument(docId, this.permissionFilters);
        this.message = `文档「${item.title || docId}」索引已重建。`;
        await this.loadLibrary();
      } catch (error) {
        this.error = error.message || "重建文档索引失败";
      } finally {
        const next = { ...this.documentReindexingIds };
        delete next[docId];
        this.documentReindexingIds = next;
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
          tenantId: this.effectiveTenantId,
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
        this.viewerDocument = await getSearchDocumentVersion(this.viewerDocument.docId, version, this.permissionFilters);
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
      if (!isDocumentOnlinePreviewSupported(type)) {
        this.viewerMode = "text";
        this.viewerError = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
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
      return inferDocumentType(fileName);
    },
    documentTypeLabel(type) {
      const normalizedType = getDocumentPreviewType({ documentType: type });
      const labels = {
        pdf: "PDF",
        word: "Word",
        excel: "Excel",
        markdown: "Markdown",
        text: "Text"
      };
      return labels[normalizedType] || "Text";
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
