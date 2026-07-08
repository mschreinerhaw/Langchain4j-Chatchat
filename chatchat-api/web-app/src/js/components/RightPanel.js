import {
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  ClipboardList,
  FileText,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  RotateCcw,
  Star,
  XCircle
} from "@lucide/vue";
import {
  fetchWorkbenchShortcuts,
  recordUserActivity,
  removeUserFavorite
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
    ChevronUp,
    ClipboardList,
    FileText,
    PanelRightClose,
    PanelRightOpen,
    RefreshCw,
    RotateCcw,
    Star,
    XCircle
  },
  props: {
    collapsed: {
      type: Boolean,
      default: false
    },
    runtimeTodos: {
      type: Array,
      default: () => []
    },
    todoActionLoadingIds: {
      type: Object,
      default: () => ({})
    },
    todoError: {
      type: String,
      default: ""
    },
    todoLoading: {
      type: Boolean,
      default: false
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
    "refresh-todos",
    "select-agent",
    "todo-action",
    "todo-detail",
    "toggle-collapsed"
  ],
  computed: {
    displayUserId() {
      return this.userId || "default-user";
    },
    railItems() {
      return [
        {
          id: "todos",
          label: "待办任务",
          icon: ClipboardList,
          count: this.todoItems.length,
          urgent: this.todoItems.length > 0
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
    todoItems() {
      return Array.isArray(this.runtimeTodos) ? this.runtimeTodos : [];
    },
    visibleTodos() {
      return this.todoItems.slice(0, 5);
    },
    selectedTodoPayload() {
      return this.selectedTodo?.payload && typeof this.selectedTodo.payload === "object"
        ? this.selectedTodo.payload
        : {};
    },
    selectedTodoReason() {
      return this.selectedTodoPayload.reason || this.todoTypeReason(this.selectedTodo?.todoType);
    },
    selectedTodoContent() {
      const task = this.selectedTodoPayload.task || {};
      const confirmation = this.selectedTodoPayload.confirmation || {};
      return (
        confirmation.purpose
        || task.errorMessage
        || task.answerSummary
        || task.question
        || "需要你处理后，LiveRuntime 才能继续闭环。"
      );
    },
    selectedTodoContentCanToggle() {
      return String(this.selectedTodoContent || "").length > 120;
    },
    selectedTodoCountdown() {
      return this.todoCountdown(this.selectedTodo);
    }
  },
  data() {
    return {
      loading: false,
      error: "",
      recentDocuments: [],
      favorites: [],
      recentAgents: [],
      selectedTodo: null,
      todoContentExpanded: false,
      now: Date.now(),
      countdownTimer: null
    };
  },
  mounted() {
    this.countdownTimer = window.setInterval(() => {
      this.now = Date.now();
    }, 1000);
  },
  beforeUnmount() {
    if (this.countdownTimer) {
      window.clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
  },
  watch: {
    userId: {
      immediate: true,
      handler() {
        this.loadShortcuts();
      }
    }
  },
  methods: {
    async loadShortcuts() {
      if (!this.displayUserId) {
        return;
      }
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchWorkbenchShortcuts({
          tenantId: this.displayUserId,
          userId: this.displayUserId,
          limit: 6
        });
        this.favorites = Array.isArray(payload?.favorites) ? payload.favorites : [];
        this.recentAgents = Array.isArray(payload?.recentAgents) ? payload.recentAgents : [];
        this.recentDocuments = Array.isArray(payload?.recentDocuments) ? payload.recentDocuments : [];
      } catch (error) {
        this.error = error.message || "快捷入口加载失败";
      } finally {
        this.loading = false;
      }
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
        await removeUserFavorite(item.id);
        this.favorites = this.favorites.filter((favorite) => favorite.id !== item.id);
      } catch (error) {
        this.error = error.message || "取消收藏失败";
      }
    },
    openTodo(todo) {
      this.selectedTodo = todo;
      this.todoContentExpanded = false;
    },
    closeTodo() {
      this.selectedTodo = null;
      this.todoContentExpanded = false;
    },
    emitTodoAction(action, payload = {}) {
      if (!this.selectedTodo || this.isTodoActionLoading(this.selectedTodo)) {
        return;
      }
      this.$emit("todo-action", {
        todo: this.selectedTodo,
        action,
        payload
      });
      this.closeTodo();
    },
    openTodoDetail() {
      if (!this.selectedTodo) {
        return;
      }
      this.$emit("todo-detail", this.selectedTodo);
      this.closeTodo();
    },
    isTodoActionLoading(todo) {
      return !!this.todoActionLoadingIds?.[todo?.id];
    },
    todoTypeLabel(type) {
      return (
        {
          TOOL_CONFIRMATION: "工具确认",
          FAILURE_RETRY: "失败重试",
          FEEDBACK_REQUIRED: "反馈补录"
        }[type] || "待办"
      );
    },
    todoTypeReason(type) {
      return (
        {
          TOOL_CONFIRMATION: "confirm_required 工具等待用户确认",
          FAILURE_RETRY: "任务失败后等待用户选择重试或终止",
          FEEDBACK_REQUIRED: "任务完成后等待用户评价：有用/采纳/解决"
        }[type] || "需要用户处理的 Agent 执行节点"
      );
    },
    todoTypeClass(type) {
      return {
        confirm: type === "TOOL_CONFIRMATION",
        retry: type === "FAILURE_RETRY",
        feedback: type === "FEEDBACK_REQUIRED"
      };
    },
    todoTime(todo) {
      const value = todo?.updatedAt || todo?.createdAt;
      if (!value) {
        return "";
      }
      return new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit"
      }).format(new Date(value));
    },
    todoCountdown(todo) {
      if (todo?.todoType !== "TOOL_CONFIRMATION" || !todo.expiredAt) {
        return "";
      }
      const remainingMs = Math.max(0, new Date(todo.expiredAt).getTime() - this.now);
      const totalSeconds = Math.ceil(remainingMs / 1000);
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return `${minutes}:${String(seconds).padStart(2, "0")}`;
    },
    async recordShortcutAction(item, actionType) {
      try {
        await recordUserActivity({
          tenantId: this.displayUserId,
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
