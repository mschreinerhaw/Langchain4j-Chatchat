import {
  Bot,
  CheckCircle2,
  ChevronDown,
  Circle,
  ClipboardList,
  FileText,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  RefreshCw,
  Star,
  Trash2,
  XCircle
} from "@lucide/vue";
import {
  createPersonalTodo,
  deletePersonalTodo,
  fetchPersonalTodos,
  fetchWorkbenchShortcuts,
  recordUserActivity,
  removeUserFavorite,
  updatePersonalTodo
} from "../../services/api";
import {
  getDocumentPreviewType,
  isDocumentOnlinePreviewSupported,
  UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE
} from "../utils/documentPreview.js";
import "../../styles/components/right-panel.css";

export default {
  name: "RightPanel",
  components: {
    Bot,
    CheckCircle2,
    ChevronDown,
    Circle,
    ClipboardList,
    FileText,
    PanelRightClose,
    PanelRightOpen,
    Plus,
    RefreshCw,
    Star,
    Trash2,
    XCircle
  },
  props: {
    collapsed: {
      type: Boolean,
      default: false
    },
    tenantId: {
      type: String,
      default: ""
    },
    userId: {
      type: String,
      default: ""
    }
  },
  emits: [
    "ask-ai",
    "navigate",
    "open-favorite",
    "open-document",
    "select-agent",
    "toggle-collapsed"
  ],
  computed: {
    displayUserId() {
      return this.userId || "default-user";
    },
    effectiveTenantId() {
      return String(this.tenantId || "").trim();
    },
    workbenchScopeKey() {
      return `${this.effectiveTenantId}::${this.displayUserId}`;
    },
    railItems() {
      return [
        {
          id: "todos",
          label: "待办任务",
          icon: ClipboardList,
          count: this.activeTodoCount,
          urgent: this.activeTodoCount > 0
        },
        {
          id: "reports",
          label: "最近文档",
          icon: FileText,
          count: this.recentDocuments.length
        },
        {
          id: "favorites",
          label: "收藏夹",
          icon: Star,
          count: this.favorites.length
        },
        {
          id: "agents",
          label: "最近使用Agent",
          icon: Bot,
          count: this.recentAgents.length
        }
      ];
    },
    activeTodoCount() {
      return this.personalTodos.filter((todo) => !todo.completed).length;
    },
    visibleTodos() {
      return this.personalTodos.filter((todo) => !todo.completed).slice(0, 5);
    },
    managerTodos() {
      return this.personalTodos.filter((todo) => todo.completed === this.showCompleted);
    }
  },
  data() {
    return {
      loading: false,
      error: "",
      recentDocuments: [],
      favorites: [],
      recentAgents: [],
      personalTodos: [],
      todoLoading: false,
      todoSaving: false,
      todoError: "",
      todoManagerOpen: false,
      editingTodo: null,
      todoEditorMode: "list",
      showCompleted: false,
      todoDraft: {
        title: "",
        notes: "",
        dueAt: "",
        important: false
      },
      shortcutRequestToken: 0,
      todoRequestToken: 0,
      collapsedModules: {
        todos: true,
        reports: true,
        favorites: true,
        agents: true
      }
    };
  },
  watch: {
    workbenchScopeKey: {
      immediate: true,
      handler() {
        this.recentDocuments = [];
        this.recentAgents = [];
        this.favorites = [];
        this.personalTodos = [];
        this.error = "";
        this.todoError = "";
        this.loadShortcuts();
        this.loadTodos();
      }
    }
  },
  methods: {
    toggleModule(moduleId) {
      if (!Object.prototype.hasOwnProperty.call(this.collapsedModules, moduleId)) {
        return;
      }
      this.collapsedModules = {
        ...this.collapsedModules,
        [moduleId]: !this.collapsedModules[moduleId]
      };
    },
    async loadTodos() {
      if (!this.displayUserId || !this.effectiveTenantId) {
        this.personalTodos = [];
        return;
      }
      const requestToken = ++this.todoRequestToken;
      const scopeKey = this.workbenchScopeKey;
      this.todoLoading = true;
      this.todoError = "";
      try {
        const payload = await fetchPersonalTodos({
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId,
          includeCompleted: true,
          limit: 100
        });
        if (requestToken !== this.todoRequestToken || scopeKey !== this.workbenchScopeKey) {
          return;
        }
        this.personalTodos = Array.isArray(payload) ? payload : [];
      } catch (error) {
        if (requestToken !== this.todoRequestToken || scopeKey !== this.workbenchScopeKey) {
          return;
        }
        this.todoError = error.message || "个人待办加载失败";
      } finally {
        if (requestToken === this.todoRequestToken) {
          this.todoLoading = false;
        }
      }
    },
    async patchTodo(todo, changes) {
      if (!todo?.id || this.todoSaving) {
        return null;
      }
      this.todoSaving = true;
      this.todoError = "";
      try {
        const updated = await updatePersonalTodo(todo.id, {
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId,
          title: null,
          notes: null,
          dueAt: null,
          dueAtChanged: false,
          completed: null,
          important: null,
          ...changes
        });
        this.personalTodos = this.personalTodos.map((item) => item.id === updated.id ? updated : item);
        return updated;
      } catch (error) {
        this.todoError = error.message || "待办更新失败";
        return null;
      } finally {
        this.todoSaving = false;
      }
    },
    toggleTodo(todo) {
      return this.patchTodo(todo, { completed: !todo.completed });
    },
    toggleImportant(todo) {
      return this.patchTodo(todo, { important: !todo.important });
    },
    openTodoManager() {
      this.todoManagerOpen = true;
      this.editingTodo = null;
      this.todoEditorMode = "list";
      this.showCompleted = false;
    },
    openNewTodoEditor() {
      this.todoManagerOpen = true;
      this.editingTodo = null;
      this.todoEditorMode = "create";
      this.todoError = "";
      this.todoDraft = {
        title: "",
        notes: "",
        dueAt: "",
        important: false
      };
      this.$nextTick(() => this.$refs.todoTitleInput?.focus());
    },
    closeTodoManager() {
      this.todoManagerOpen = false;
      this.editingTodo = null;
      this.todoEditorMode = "list";
    },
    editTodo(todo) {
      this.todoManagerOpen = true;
      this.editingTodo = todo;
      this.todoEditorMode = "edit";
      this.todoError = "";
      this.todoDraft = {
        title: todo.title || "",
        notes: todo.notes || "",
        dueAt: this.toLocalDateTime(todo.dueAt),
        important: !!todo.important
      };
      this.$nextTick(() => this.$refs.todoTitleInput?.focus());
    },
    returnToTodoList() {
      this.editingTodo = null;
      this.todoEditorMode = "list";
    },
    async saveTodoEditor() {
      const title = this.todoDraft.title.trim();
      if (!title || this.todoSaving) {
        return;
      }
      if (this.todoEditorMode === "create") {
        this.todoSaving = true;
        this.todoError = "";
        try {
          const item = await createPersonalTodo({
            tenantId: this.effectiveTenantId,
            userId: this.displayUserId,
            title,
            notes: this.todoDraft.notes.trim(),
            dueAt: this.todoDraft.dueAt ? new Date(this.todoDraft.dueAt).toISOString() : null,
            important: this.todoDraft.important
          });
          this.personalTodos = [item, ...this.personalTodos];
          this.closeTodoManager();
        } catch (error) {
          this.todoError = error.message || "添加便签失败";
        } finally {
          this.todoSaving = false;
        }
        return;
      }
      if (!this.editingTodo) {
        return;
      }
      const updated = await this.patchTodo(this.editingTodo, {
        title,
        notes: this.todoDraft.notes.trim(),
        dueAt: this.todoDraft.dueAt ? new Date(this.todoDraft.dueAt).toISOString() : null,
        dueAtChanged: true,
        important: this.todoDraft.important
      });
      if (updated) {
        this.returnToTodoList();
      }
    },
    async removeTodo(todo) {
      if (!todo?.id || this.todoSaving) {
        return;
      }
      this.todoSaving = true;
      try {
        await deletePersonalTodo(todo.id, {
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId
        });
        this.personalTodos = this.personalTodos.filter((item) => item.id !== todo.id);
        this.returnToTodoList();
      } catch (error) {
        this.todoError = error.message || "删除待办失败";
      } finally {
        this.todoSaving = false;
      }
    },
    toLocalDateTime(value) {
      if (!value) {
        return "";
      }
      const date = new Date(value);
      const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
      return local.toISOString().slice(0, 16);
    },
    todoDueLabel(todo) {
      if (!todo?.dueAt) {
        return "";
      }
      return `截止 ${new Intl.DateTimeFormat("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit"
      }).format(new Date(todo.dueAt))}`;
    },
    isOverdue(todo) {
      return !todo?.completed && todo?.dueAt && new Date(todo.dueAt).getTime() < Date.now();
    },
    async loadShortcuts() {
      if (!this.displayUserId || !this.effectiveTenantId) {
        this.recentDocuments = [];
        this.recentAgents = [];
        this.favorites = [];
        return;
      }
      const requestToken = ++this.shortcutRequestToken;
      const scopeKey = this.workbenchScopeKey;
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchWorkbenchShortcuts({
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId,
          limit: 6
        });
        if (requestToken !== this.shortcutRequestToken || scopeKey !== this.workbenchScopeKey) {
          return;
        }
        this.favorites = this.tenantScopedItems(payload?.favorites);
        this.recentAgents = this.tenantScopedItems(payload?.recentAgents);
        this.recentDocuments = this.tenantScopedItems(payload?.recentDocuments);
      } catch (error) {
        if (requestToken !== this.shortcutRequestToken || scopeKey !== this.workbenchScopeKey) {
          return;
        }
        this.error = error.message || "快捷入口加载失败";
      } finally {
        if (requestToken === this.shortcutRequestToken) {
          this.loading = false;
        }
      }
    },
    tenantScopedItems(items) {
      if (!Array.isArray(items)) {
        return [];
      }
      return items.filter((item) => String(item?.tenantId || "").trim() === this.effectiveTenantId);
    },
    async openDocument(item) {
      if (!item?.targetId) {
        return;
      }
      if (!this.canPreviewDocument(item)) {
        this.error = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
        return;
      }
      await this.recordShortcutAction(item, "VIEW");
      this.$emit("open-document", {
        docId: item.targetId,
        title: item.title || "",
        summary: item.summary || "",
        source: "workbench",
        fileName: item.fileName || item.extra?.fileName || "",
        documentType: item.documentType || item.extra?.documentType || ""
      });
    },
    async askAiAboutDocument(item) {
      if (!item?.targetId) {
        return;
      }
      await this.recordShortcutAction(item, "ASK");
      this.$emit("ask-ai", {
        id: `workbench-doc-${item.targetId}-${Date.now()}`,
        source: "workbench_recent_document",
        documentId: item.targetId,
        title: item.title || "",
        snippet: item.summary || "",
        prompt: this.buildDocumentPrompt(item)
      });
    },
    async continueAgent(item, newSession = false) {
      if (!item?.targetId) {
        return;
      }
      await this.recordShortcutAction(item, "USE");
      this.$emit("select-agent", {
        agentId: item.targetId,
        title: item.title || item.targetId,
        newSession
      });
    },
    async openFavorite(item) {
      if (!item?.targetType) {
        return;
      }
      const type = String(item.targetType).toUpperCase();
      if (type === "DOCUMENT") {
        if (!this.canPreviewDocument(item)) {
          this.error = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
          return;
        }
        await this.openDocument(item);
        return;
      }
      if (type === "AGENT") {
        await this.continueAgent(item, false);
        return;
      }
      if (type === "SESSION") {
        this.$emit("open-favorite", item);
        return;
      }
      if (type === "TASK") {
        this.$emit("navigate", "tasks");
      }
    },
    async deleteFavorite(item) {
      if (!item?.id) {
        return;
      }
      try {
        await removeUserFavorite(item.id, {
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId
        });
        this.favorites = this.favorites.filter((favorite) => favorite.id !== item.id);
      } catch (error) {
        this.error = error.message || "取消收藏失败";
      }
    },
    async recordShortcutAction(item, actionType) {
      try {
        await recordUserActivity({
          tenantId: this.effectiveTenantId,
          userId: this.displayUserId,
          targetType: item.targetType,
          targetId: item.targetId,
          actionType,
          title: item.title,
          summary: item.summary,
          extra: item.extra || {}
        });
      } catch (error) {
        // Shortcut navigation should not be blocked by activity logging.
      }
    },
    buildDocumentPrompt(item) {
      return [
        `我想继续了解文档《${item.title || item.targetId}》。`,
        item.summary ? `摘要：${item.summary}` : "",
        `文档ID：${item.targetId}`,
        "",
        "请结合这份文档，帮我提炼重点并给出可追问的问题。"
      ].filter(Boolean).join("\n");
    },
    canPreviewDocument(item) {
      return isDocumentOnlinePreviewSupported(item);
    },
    isUnsupportedDocumentFavorite(item) {
      return String(item?.targetType || "").toUpperCase() === "DOCUMENT" && !this.canPreviewDocument(item);
    },
    documentPreviewTitle(item) {
      if (item?.targetType && String(item.targetType).toUpperCase() !== "DOCUMENT") {
        return "";
      }
      return this.canPreviewDocument(item) ? "" : UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
    },
    shortcutTime(value) {
      if (!value) {
        return "";
      }
      return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
      }).format(new Date(value));
    },
    docMark(item) {
      const type = getDocumentPreviewType(item);
      if (type === "pdf") {
        return "PDF";
      }
      if (type === "word") {
        return "DOC";
      }
      if (type === "excel") {
        return "XLS";
      }
      if (type === "markdown") {
        return "MD";
      }
      if (type === "text") {
        return "TXT";
      }
      return "DOC";
    },
    docBadgeClass(item) {
      const mark = this.docMark(item);
      return {
        PDF: "red",
        DOC: "blue",
        XLS: "green",
        MD: "amber",
        TXT: "amber"
      }[mark] || "amber";
    },
    agentShortName(item) {
      return String(item?.title || item?.targetId || "A").slice(0, 1).toUpperCase();
    }
  }
};
