import AssistantLayout from "../components/AssistantLayout.vue";
import RightPanel from "../components/RightPanel.vue";
import LoginView from "../views/LoginView.vue";
import ChatAssistantView from "../views/ChatAssistantView.vue";
import CapabilityMarketView from "../views/CapabilityMarketView.vue";
import AiSearchView from "../views/AiSearchView.vue";
import LibraryView from "../views/LibraryView.vue";
import FavoritesView from "../views/FavoritesView.vue";
import McpCenterView from "../views/McpCenterView.vue";
import AgentWorkshopView from "../views/AgentWorkshopView.vue";
import AgentScheduleView from "../views/AgentScheduleView.vue";
import AgentRuntimeView from "../views/AgentRuntimeView.vue";
import RetrievalRulesView from "../views/RetrievalRulesView.vue";
import EvidenceDebuggerView from "../views/EvidenceDebuggerView.vue";
import SystemManagementView from "../views/SystemManagementView.vue";
import TasksView from "../views/TasksView.vue";
import { Plus } from "@lucide/vue";
import {
  actAgentTodo,
  addUserFavorite,
  AUTH_REQUIRED_EVENT,
  clearAuthSession,
  deleteConversationHistory,
  fetchAgentRuntimeSummary,
  fetchAgentTodos,
  fetchConversationHistory,
  fetchWorkbenchShortcuts,
  getStoredAuthSession,
  killRuntimeTask,
  loginEnterpriseWithEmbedToken,
  updateConversationHistoryStatus
} from "../services/api";
import { notifyAgentTaskCancelled, onAgentTaskCancelled } from "./utils/agentTaskEvents";
import { clearChatRuntimeState, mergeChatRuntimeState } from "./utils/chatRuntimeState";
import floatingDrag from "./directives/floatingDrag";
import "../styles/app.css";
import "../styles/components/dialog-close.css";

const USER_ID = "mx_48991534";
const IDLE_LOGOUT_MS = 30 * 60 * 1000;
const ACTIVITY_THROTTLE_MS = 1000;
const TODO_REFRESH_MS = 30000;
const ACTIVITY_EVENTS = ["click", "keydown", "mousemove", "mousedown", "scroll", "touchstart", "wheel"];
const ACTIVE_AGENT_TASK_STATUSES = new Set(["PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION", "WAITING_CONFIRM", "CONFIRMED"]);
const DEFAULT_VIEW = "chat";
const LOGIN_ROUTE = "login";
const REDIRECT_VIEW_KEY = "chatchat.auth.redirectView";

const views = {
  chat: ChatAssistantView,
  search: AiSearchView,
  market: CapabilityMarketView,
  favorites: FavoritesView,
  library: LibraryView,
  mcp: McpCenterView,
  agents: AgentWorkshopView,
  schedules: AgentScheduleView,
  runtime: AgentRuntimeView,
  rules: RetrievalRulesView,
  debugger: EvidenceDebuggerView,
  tasks: TasksView,
  system: SystemManagementView
};

function currentHashRoute() {
  return decodeURIComponent(window.location.hash || "")
    .replace(/^#\/?/, "")
    .split("?")[0]
    .trim();
}

function viewFromHash() {
  const route = currentHashRoute();
  return views[route] ? route : "";
}

function isAuthenticatedSession(session) {
  return !!session?.token;
}

function resolveSessionTenantId(session, fallbackUserId = USER_ID) {
  const user = session?.user || {};
  return user.tenantId || user.tenant_id || session?.tenantId || fallbackUserId;
}

export default {
  name: "App",
  components: {
    AssistantLayout,
    LoginView,
    Plus,
    RightPanel
  },
  directives: {
    floatingDrag
  },
  data() {
    const storedAuthSession = getStoredAuthSession();
    const authSession = isAuthenticatedSession(storedAuthSession) ? storedAuthSession : null;
    const sessionUser = authSession?.user || {};
    return {
      authSession,
      activeView: authSession ? (viewFromHash() || DEFAULT_VIEW) : DEFAULT_VIEW,
      userId: sessionUser.username || sessionUser.id || USER_ID,
      tenantId: resolveSessionTenantId(authSession, sessionUser.username || sessionUser.id || USER_ID),
      historyLoading: false,
      historyError: "",
      conversationHistory: [],
      favoriteConversationIds: [],
      favoriteSavingIds: {},
      todoLoading: false,
      todoError: "",
      runtimeTodos: [],
      todoRefreshTimer: null,
      todoKillTimer: null,
      todoActionLoadingIds: {},
      selectedConversation: null,
      pendingChatDraft: null,
      pendingDocumentShortcut: null,
      activeHistoryId: "",
      idleLogoutTimer: null,
      stopAgentTaskCancelledListener: null,
      lastActivityAt: Date.now(),
      navItems: [
        {
          id: "workspace",
          label: "工作台",
          items: [
            { id: "chat", label: "智能对话", icon: "chat" },
            { id: "search", label: "文档检索", icon: "search" }
          ]
        },
        {
          id: "capability",
          label: "能力管理",
          items: [
            { id: "market", label: "能力市场", icon: "grid" },
            { id: "library", label: "文档库", icon: "book" }
          ]
        },
        {
          id: "platform",
          label: "平台管理",
          items: [
            { id: "mcp", label: "MCP能力", icon: "mcp" },
            { id: "agents", label: "Agent管理", icon: "agent" },
            { id: "schedules", label: "Agent调度", icon: "schedule" },
            { id: "rules", label: "关键词规则", icon: "search" },
            { id: "debugger", label: "证据调试", icon: "tasks" },
            { id: "tasks", label: "运行监控", icon: "tasks" },
            { id: "system", label: "系统管理", icon: "gear" }
          ]
        }
      ]
    };
  },
  computed: {
    visibleNavItems() {
      return this.navItems.map((group) => ({
        ...group,
        items: Array.isArray(group.items)
          ? group.items.filter((item) => item.id !== "debugger")
          : []
      }));
    },
    activeComponent() {
      return views[this.activeView] || ChatAssistantView;
    },
    activeComponentProps() {
      return this.activeView === "chat"
        ? {
            userId: this.userId,
            tenantId: this.tenantId,
            selectedConversation: this.selectedConversation,
            pendingDraft: this.pendingChatDraft
          }
        : {
            userId: this.userId,
            tenantId: this.tenantId,
            pendingDocumentShortcut: this.activeView === "search" ? this.pendingDocumentShortcut : null
          };
    },
    recentConversations() {
      return this.conversationHistory.map((conversation) => this.normalizeHistoryConversationStatus(conversation));
    }
  },
  watch: {
    authSession(session) {
      if (!isAuthenticatedSession(session)) {
        this.handleUnauthenticated();
      }
    }
  },
  mounted() {
    window.addEventListener("hashchange", this.handleHashChange);
    window.addEventListener(AUTH_REQUIRED_EVENT, this.handleAuthRequired);
    this.stopAgentTaskCancelledListener = onAgentTaskCancelled(this.handleAgentTaskCancelled);
    const embedToken = this.consumeEmbedLoginToken();
    if (embedToken) {
      this.loginWithEmbedToken(embedToken);
      return;
    }
    if (isAuthenticatedSession(this.authSession)) {
      this.ensureAuthenticatedRoute();
      this.loadConversationHistory({ suppressError: true });
      this.loadFavoriteConversationIds();
      this.loadRuntimeTodos({ silent: true, suppressError: true });
      this.startTodoRefresh();
      if (!this.authSession?.embedded) {
        this.startIdleLogoutWatcher();
      }
      return;
    }
    this.handleUnauthenticated();
  },
  beforeUnmount() {
    window.removeEventListener("hashchange", this.handleHashChange);
    window.removeEventListener(AUTH_REQUIRED_EVENT, this.handleAuthRequired);
    if (this.stopAgentTaskCancelledListener) {
      this.stopAgentTaskCancelledListener();
      this.stopAgentTaskCancelledListener = null;
    }
    this.stopIdleLogoutWatcher();
    this.stopTodoRefresh();
    this.stopTodoTimeoutKill();
  },
  methods: {
    handleLoginSuccess(session) {
      if (!isAuthenticatedSession(session)) {
        this.handleUnauthenticated();
        return;
      }
      this.authSession = session;
      const sessionUser = session?.user || {};
      this.userId = sessionUser.username || sessionUser.id || USER_ID;
      this.tenantId = resolveSessionTenantId(session, this.userId);
      this.navigateToView(this.consumeRedirectView() || viewFromHash() || DEFAULT_VIEW);
      if (session?.embedded) {
        this.stopIdleLogoutWatcher();
      } else {
        this.startIdleLogoutWatcher();
      }
      this.loadConversationHistory({ suppressError: true });
      this.loadFavoriteConversationIds();
      this.loadRuntimeTodos({ silent: true, suppressError: true });
      this.startTodoRefresh();
    },
    async loginWithEmbedToken(token) {
      try {
        const session = await loginEnterpriseWithEmbedToken(token);
        this.handleLoginSuccess(session);
      } catch (error) {
        this.handleUnauthenticated();
      }
    },
    handleLogout() {
      this.handleUnauthenticated();
    },
    handleAuthRequired() {
      this.handleUnauthenticated();
    },
    handleUnauthenticated() {
      this.stopIdleLogoutWatcher();
      this.stopTodoRefresh();
      this.stopTodoTimeoutKill();
      clearAuthSession();
      this.authSession = null;
      this.userId = USER_ID;
      this.tenantId = USER_ID;
      this.conversationHistory = [];
      this.favoriteConversationIds = [];
      this.favoriteSavingIds = {};
      this.runtimeTodos = [];
      this.todoActionLoadingIds = {};
      this.selectedConversation = null;
      this.activeHistoryId = "";
      this.redirectToLogin();
    },
    handleHashChange() {
      if (!isAuthenticatedSession(this.authSession)) {
        this.handleUnauthenticated();
        return;
      }
      const route = currentHashRoute();
      if (route === LOGIN_ROUTE) {
        this.navigateToView(DEFAULT_VIEW);
        return;
      }
      const nextView = viewFromHash();
      if (nextView) {
        this.activeView = nextView;
      }
    },
    ensureAuthenticatedRoute() {
      const route = currentHashRoute();
      if (route === LOGIN_ROUTE || !viewFromHash()) {
        this.navigateToView(this.consumeRedirectView() || this.activeView || DEFAULT_VIEW);
      }
    },
    redirectToLogin() {
      const targetView = viewFromHash();
      if (targetView) {
        sessionStorage.setItem(REDIRECT_VIEW_KEY, targetView);
      }
      this.setHashRoute(LOGIN_ROUTE);
    },
    consumeRedirectView() {
      const value = sessionStorage.getItem(REDIRECT_VIEW_KEY);
      sessionStorage.removeItem(REDIRECT_VIEW_KEY);
      return views[value] ? value : "";
    },
    consumeEmbedLoginToken() {
      const url = new URL(window.location.href);
      const token = url.searchParams.get("embedToken") || "";
      if (!token) {
        return "";
      }
      url.searchParams.delete("embedToken");
      const query = url.searchParams.toString();
      const nextUrl = `${url.pathname}${query ? `?${query}` : ""}${url.hash}`;
      window.history.replaceState({}, document.title, nextUrl);
      return token.trim();
    },
    navigateToView(view) {
      const nextView = views[view] ? view : DEFAULT_VIEW;
      this.activeView = nextView;
      this.setHashRoute(nextView);
    },
    setHashRoute(route) {
      const nextHash = `#/${route}`;
      if (window.location.hash !== nextHash) {
        window.location.hash = nextHash;
      }
    },
    startIdleLogoutWatcher() {
      if (this.authSession?.embedded) {
        this.stopIdleLogoutWatcher();
        return;
      }
      this.stopIdleLogoutWatcher();
      this.lastActivityAt = Date.now();
      ACTIVITY_EVENTS.forEach((eventName) => {
        window.addEventListener(eventName, this.handleUserActivity, { passive: true });
      });
      this.scheduleIdleLogout();
    },
    stopIdleLogoutWatcher() {
      if (this.idleLogoutTimer) {
        window.clearTimeout(this.idleLogoutTimer);
        this.idleLogoutTimer = null;
      }
      ACTIVITY_EVENTS.forEach((eventName) => {
        window.removeEventListener(eventName, this.handleUserActivity);
      });
    },
    startTodoRefresh() {
      this.stopTodoRefresh();
      this.todoRefreshTimer = window.setInterval(() => {
        this.loadRuntimeTodos({ silent: true, suppressError: true });
      }, TODO_REFRESH_MS);
    },
    stopTodoRefresh() {
      if (this.todoRefreshTimer) {
        window.clearInterval(this.todoRefreshTimer);
        this.todoRefreshTimer = null;
      }
    },
    scheduleTodoTimeoutKill() {
      this.stopTodoTimeoutKill();
      const confirmationTodos = this.runtimeTodos
        .filter((todo) => todo?.todoType === "TOOL_CONFIRMATION" && todo.taskId && todo.expiredAt)
        .map((todo) => ({
          todo,
          expiresAt: new Date(todo.expiredAt).getTime()
        }))
        .filter((item) => Number.isFinite(item.expiresAt));
      if (confirmationTodos.length === 0) {
        return;
      }
      const next = confirmationTodos.sort((left, right) => left.expiresAt - right.expiresAt)[0];
      const delay = Math.max(0, next.expiresAt - Date.now());
      this.todoKillTimer = window.setTimeout(() => {
        this.killExpiredConfirmationTodos();
      }, delay);
    },
    stopTodoTimeoutKill() {
      if (this.todoKillTimer) {
        window.clearTimeout(this.todoKillTimer);
        this.todoKillTimer = null;
      }
    },
    async killExpiredConfirmationTodos() {
      const expiredTodos = this.runtimeTodos.filter((todo) => {
        if (todo?.todoType !== "TOOL_CONFIRMATION" || !todo.taskId || !todo.expiredAt) {
          return false;
        }
        return new Date(todo.expiredAt).getTime() <= Date.now();
      });
      for (const todo of expiredTodos) {
        try {
          const killedTask = await killRuntimeTask(todo.taskId, todo.tenantId || this.tenantId);
          notifyAgentTaskCancelled({
            ...(killedTask || {}),
            taskId: todo.taskId,
            tenantId: todo.tenantId || this.tenantId,
            message: "该操作超过 3 分钟未确认，任务已自动取消。"
          });
        } catch (error) {
          this.todoError = error.message || "确认超时取消任务失败";
        }
      }
      await this.loadRuntimeTodos({ silent: true });
    },
    handleUserActivity() {
      if (!this.authSession) {
        return;
      }
      const now = Date.now();
      if (now - this.lastActivityAt < ACTIVITY_THROTTLE_MS) {
        return;
      }
      this.lastActivityAt = now;
      this.scheduleIdleLogout();
    },
    scheduleIdleLogout() {
      if (this.idleLogoutTimer) {
        window.clearTimeout(this.idleLogoutTimer);
      }
      this.idleLogoutTimer = window.setTimeout(() => {
        this.handleLogout();
      }, IDLE_LOGOUT_MS);
    },
    async loadConversationHistory(filters = {}) {
      const { suppressError = false, ...historyFilters } = filters || {};
      const previousHistory = this.conversationHistory;
      this.historyLoading = true;
      this.historyError = "";
      try {
        const history = await fetchConversationHistory(this.userId, {
          tenantId: this.tenantId,
          limit: 30,
          ...historyFilters
        });
        const nextHistory = Array.isArray(history) ? history : [];
        const verifiedHistory = await this.verifyRuntimeHistory(nextHistory);
        this.conversationHistory = verifiedHistory;
        this.resetActiveConversationWhenRemoved(previousHistory, verifiedHistory);
      } catch (error) {
        this.conversationHistory = [];
        if (suppressError) {
          return;
        }
        this.historyError = error.message || "历史会话加载失败";
      } finally {
        this.historyLoading = false;
      }
    },
    async verifyRuntimeHistory(history = []) {
      const mergedHistory = history.map((conversation) => mergeChatRuntimeState(conversation));
      try {
        const summary = await fetchAgentRuntimeSummary({
          tenantId: this.tenantId,
          latestLimit: 50
        });
        const tasks = Array.isArray(summary?.latestTasks) ? summary.latestTasks : [];
        const verified = mergedHistory.map((conversation) => this.applyRuntimeTaskSnapshot(conversation, tasks));
        this.persistVerifiedHistoryStatus(verified);
        return verified.map(({ __runtimeStatusChanged, ...conversation }) => conversation);
      } catch (error) {
        return mergedHistory;
      }
    },
    applyRuntimeTaskSnapshot(conversation = {}, tasks = []) {
      const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
      const conversationIds = new Set([conversation.id, conversation.conversationId].filter(Boolean));
      const taskIds = new Set(messages.map((message) => message?.taskId).filter(Boolean));
      const task = tasks.find((item) =>
        item?.taskId
        && (
          taskIds.has(item.taskId)
          || (item.sessionId && conversationIds.has(item.sessionId))
        )
      );
      if (!task) {
        return conversation;
      }
      const status = this.conversationStatusFromRuntimeTask(task);
      const active = ACTIVE_AGENT_TASK_STATUSES.has(String(task.status || "").toUpperCase());
      if (!active) {
        clearChatRuntimeState({
          conversationId: task.sessionId || conversation.conversationId || conversation.id || "",
          taskId: task.taskId
        });
      }
      const nextMessages = this.mergeRuntimeTaskIntoMessages(messages, task, status, active, conversation);
      const changed = String(conversation.status || "").toLowerCase() !== status;
      return {
        ...conversation,
        status,
        conversationId: conversation.conversationId || task.sessionId || "",
        messages: nextMessages,
        __runtimeStatusChanged: changed && !active
      };
    },
    conversationStatusFromRuntimeTask(task = {}) {
      const status = String(task.status || "").toUpperCase();
      if (ACTIVE_AGENT_TASK_STATUSES.has(status)) {
        return status === "WAIT_CONFIRMATION" || status === "WAITING_CONFIRM" ? "pending" : "running";
      }
      if (status === "SUCCESS") {
        return "completed";
      }
      if (status === "PARTIAL") {
        return "partial";
      }
      if (status === "EMPTY") {
        return "empty";
      }
      if (["CANCELLED", "KILLED", "REJECTED", "TIMEOUT_CANCELLED"].includes(status)) {
        return "cancelled";
      }
      if (status === "FAILED") {
        return "failed";
      }
      return "completed";
    },
    mergeRuntimeTaskIntoMessages(messages = [], task = {}, status = "running", active = false, conversation = {}) {
      const index = [...messages].reverse().findIndex((message) =>
        message?.role === "assistant"
        && (message.taskId === task.taskId || message.streaming || ["running", "streaming", "pending", "waiting"].includes(String(message.status || "").toLowerCase()))
      );
      const realIndex = index < 0 ? -1 : messages.length - 1 - index;
      if (realIndex >= 0) {
        return messages.map((message, messageIndex) => messageIndex === realIndex
          ? {
              ...message,
              taskId: message.taskId || task.taskId || "",
              agentName: message.agentName || conversation.agentName || "",
              modelName: message.modelName || conversation.modelName || "",
              streaming: active,
              status: active ? (status === "pending" ? "waiting" : "running") : status,
              content: message.content || (!active ? (task.answerSummary || task.errorMessage || "") : "")
            }
          : message);
      }
      if (!active) {
        return messages;
      }
      return [
        ...messages,
        {
          id: `${task.taskId}-runtime`,
          role: "assistant",
          content: "",
          timestamp: task.updateTime ? new Date(task.updateTime).getTime() : Date.now(),
          sources: [],
          traces: [],
          steps: [],
          streaming: true,
          status: status === "pending" ? "waiting" : "running",
          taskId: task.taskId || "",
          agentName: conversation.agentName || "",
          modelName: conversation.modelName || ""
        }
      ];
    },
    persistVerifiedHistoryStatus(verifiedHistory = []) {
      verifiedHistory
        .filter((conversation) => conversation.__runtimeStatusChanged && conversation.id)
        .forEach((conversation) => {
          updateConversationHistoryStatus(this.userId, conversation.id, {
            tenantId: this.tenantId,
            conversationId: conversation.conversationId || conversation.id,
            status: conversation.status,
            messages: conversation.messages || []
          }).catch(() => {});
        });
    },
    async loadFavoriteConversationIds() {
      if (!this.authSession || !this.userId) {
        return;
      }
      try {
        const payload = await fetchWorkbenchShortcuts({
          tenantId: this.tenantId,
          userId: this.userId,
          targetType: "SESSION",
          limit: 100
        });
        const favorites = Array.isArray(payload?.favorites) ? payload.favorites : [];
        this.favoriteConversationIds = favorites
          .map((favorite) => favorite?.targetId)
          .filter(Boolean);
      } catch (error) {
        // Favorite badges are best-effort; history loading should stay quiet.
      }
    },
    async loadRuntimeTodos(options = {}) {
      if (!this.authSession || !this.userId) {
        return;
      }
      if (!options.silent) {
        this.todoLoading = true;
      }
      this.todoError = "";
      try {
        const payload = await fetchAgentTodos({
          tenantId: this.tenantId,
          userId: this.userId,
          limit: 20
        });
        this.runtimeTodos = Array.isArray(payload?.items) ? payload.items : [];
        this.scheduleTodoTimeoutKill();
      } catch (error) {
        this.runtimeTodos = [];
        this.stopTodoTimeoutKill();
        if (options.suppressError) {
          return;
        }
        this.todoError = error.message || "待办任务加载失败";
      } finally {
        if (!options.silent) {
          this.todoLoading = false;
        }
      }
    },
    async handleTodoAction({ todo, action, payload = {} } = {}) {
      if (!todo?.id || !action || this.todoActionLoadingIds[todo.id]) {
        return;
      }
      this.todoActionLoadingIds = {
        ...this.todoActionLoadingIds,
        [todo.id]: true
      };
      this.todoError = "";
      try {
        await actAgentTodo(todo.id, {
          action,
          userId: this.userId,
          ...payload
        });
        this.runtimeTodos = this.runtimeTodos.filter((item) => item?.id !== todo.id);
        this.scheduleTodoTimeoutKill();
        await this.loadRuntimeTodos({ silent: true });
        if (action === "reject" || action === "deny" || action === "terminate") {
          await this.loadConversationHistory();
        }
      } catch (error) {
        this.todoError = error.message || "待办任务处理失败";
      } finally {
        const next = { ...this.todoActionLoadingIds };
        delete next[todo.id];
        this.todoActionLoadingIds = next;
      }
    },
    handleTodoDetail(todo) {
      this.navigateToView("tasks");
      if (todo?.taskId) {
        sessionStorage.setItem("chatchat.runtime.selectedTaskId", todo.taskId);
      }
    },
    handleNavigate(view) {
      this.navigateToView(view);
    },
    handleNewConversation() {
      this.selectedConversation = null;
      this.activeHistoryId = "";
      this.pendingChatDraft = null;
      this.navigateToView("chat");
      this.$nextTick(() => {
        this.$refs.activeViewComponent?.clearChat?.();
      });
    },
    handleAskAiFromSearch(payload) {
      if (!payload?.prompt) {
        return;
      }
      this.pendingChatDraft = {
        ...payload,
        id: payload.id || `${Date.now()}`
      };
      this.navigateToView("chat");
    },
    handleOpenDocumentShortcut(payload) {
      if (!payload?.docId) {
        return;
      }
      this.pendingDocumentShortcut = {
        ...payload,
        id: `${payload.docId}-${Date.now()}`
      };
      this.navigateToView("search");
    },
    async handleOpenFavoriteShortcut(item = {}) {
      const type = String(item.targetType || "").toUpperCase();
      if (type === "DOCUMENT") {
        this.handleOpenDocumentShortcut({
          docId: item.targetId,
          title: item.title || "",
          summary: item.summary || "",
          source: "favorite"
        });
        return;
      }
      if (type === "SESSION") {
        await this.openFavoriteConversation(item);
        return;
      }
      if (type === "AGENT") {
        this.handleSelectAgentShortcut({
          agentId: item.targetId,
          title: item.title || item.targetId
        });
        return;
      }
      if (type === "TASK") {
        this.navigateToView("tasks");
      }
    },
    async openFavoriteConversation(item = {}) {
      const targetId = item.targetId || "";
      if (!targetId) {
        return;
      }
      let conversation = this.conversationHistory.find((history) =>
        history.id === targetId || history.conversationId === targetId
      );
      if (!conversation) {
        try {
          const history = await fetchConversationHistory(this.userId, { tenantId: this.tenantId, limit: 100 });
          if (Array.isArray(history)) {
            this.conversationHistory = history;
            conversation = history.find((entry) => entry.id === targetId || entry.conversationId === targetId);
          }
        } catch (error) {
          this.historyError = error.message || "收藏会话加载失败";
        }
      }
      if (conversation) {
        this.selectConversation(conversation);
      } else {
        this.historyError = "没有找到这条收藏会话。";
      }
    },
    handleSelectAgentShortcut(payload = {}) {
      if (!payload.agentId) {
        return;
      }
      this.pendingChatDraft = {
        id: `agent-shortcut-${payload.agentId}-${Date.now()}`,
        agentId: payload.agentId,
        title: payload.title || payload.agentId,
        newSession: !!payload.newSession
      };
      this.navigateToView("chat");
    },
    selectConversation(conversation) {
      if (!conversation) {
        return;
      }
      this.navigateToView("chat");
      this.activeHistoryId = conversation.id || "";
      this.selectedConversation = {
        ...conversation,
        selectedAt: Date.now()
      };
    },
    async deleteConversation(conversation) {
      if (!conversation?.id) {
        return;
      }
      const deletedId = conversation.id;
      const deletedConversationId = conversation.conversationId || "";
      const deletedActiveConversation = this.isActiveConversationId(deletedId, deletedConversationId);
      this.conversationHistory = this.conversationHistory.filter((item) => item.id !== deletedId);
      if (deletedActiveConversation) {
        this.resetCurrentConversationView();
      }
      try {
        await deleteConversationHistory(this.userId, deletedId, this.tenantId);
      } catch (error) {
        this.historyError = error.message || "历史会话删除失败";
        await this.loadConversationHistory();
      }
    },
    async favoriteConversation(conversation) {
      const targetId = conversation?.conversationId || conversation?.id || "";
      if (!targetId || this.favoriteSavingIds[targetId] || this.favoriteConversationIds.includes(targetId)) {
        return;
      }
      this.favoriteSavingIds = {
        ...this.favoriteSavingIds,
        [targetId]: true
      };
      this.historyError = "";
      try {
        await addUserFavorite({
          tenantId: this.tenantId,
          userId: this.userId,
          targetType: "SESSION",
          targetId,
          title: conversation.question || "会话",
          category: "会话"
        });
        this.favoriteConversationIds = [...this.favoriteConversationIds, targetId];
      } catch (error) {
        this.historyError = error.message || "收藏会话失败";
      } finally {
        const next = { ...this.favoriteSavingIds };
        delete next[targetId];
        this.favoriteSavingIds = next;
      }
    },
    handleConversationActive(conversation) {
      if (conversation?.active !== false) {
        this.activeHistoryId = conversation?.id || "";
      }
      if (conversation?.id && conversation.question) {
        this.upsertLocalConversation(conversation);
      }
    },
    handleHistorySaved(payload) {
      const previousHistory = this.conversationHistory;
      const history = Array.isArray(payload?.history) ? payload.history : payload;
      if (Array.isArray(history)) {
        this.conversationHistory = this.applyActiveConversationStatus(
          history,
          payload?.activeHistoryId,
          payload?.activeStatus
        );
        this.resetActiveConversationWhenRemoved(previousHistory, this.conversationHistory);
      }
      if (payload?.activeHistoryId) {
        this.activeHistoryId = payload.activeHistoryId;
      }
    },
    resetActiveConversationWhenRemoved(previousHistory = [], nextHistory = []) {
      const activeIds = this.activeConversationIds();
      if (activeIds.size === 0) {
        return;
      }
      const existedBefore = this.historyContainsAnyConversationId(previousHistory, activeIds);
      const existsNow = this.historyContainsAnyConversationId(nextHistory, activeIds);
      if (existedBefore && !existsNow) {
        this.resetCurrentConversationView();
      }
    },
    resetCurrentConversationView() {
      this.selectedConversation = null;
      this.activeHistoryId = "";
      this.pendingChatDraft = null;
      this.navigateToView("chat");
      this.$nextTick(() => {
        this.$refs.activeViewComponent?.clearChat?.();
      });
    },
    isActiveConversationId(...ids) {
      const activeIds = this.activeConversationIds();
      return ids.some((id) => id && activeIds.has(id));
    },
    activeConversationIds() {
      return new Set([
        this.activeHistoryId,
        this.selectedConversation?.id,
        this.selectedConversation?.conversationId
      ].filter(Boolean));
    },
    historyContainsAnyConversationId(history = [], ids = new Set()) {
      if (!Array.isArray(history) || ids.size === 0) {
        return false;
      }
      return history.some((conversation) =>
        ids.has(conversation?.id) || ids.has(conversation?.conversationId)
      );
    },
    applyActiveConversationStatus(history, activeHistoryId, activeStatus) {
      if (!activeHistoryId || !activeStatus) {
        return history.map((item) => this.normalizeHistoryConversationStatus(item));
      }
      return history.map((item) =>
        item.id === activeHistoryId
          ? this.normalizeHistoryConversationStatus({
              ...item,
              status: activeStatus
            })
          : this.normalizeHistoryConversationStatus(item)
      );
    },
    normalizeHistoryConversationStatus(conversation = {}) {
      const mergedConversation = mergeChatRuntimeState(conversation);
      const status = String(mergedConversation.status || "").toLowerCase();
      if (!["running", "streaming", "pending"].includes(status)) {
        return mergedConversation;
      }
      const messages = Array.isArray(mergedConversation.messages) ? mergedConversation.messages : [];
      const hasRestorableTask = messages.some((message) => message?.role === "assistant" && message.taskId);
      const recentRunning = Date.now() - Number(mergedConversation.timestamp || 0) <= 10 * 60 * 1000;
      if (hasRestorableTask || recentRunning) {
        return mergedConversation;
      }
      const hasLiveAssistant = messages.some((message) =>
        message?.role === "assistant"
        && (message.streaming || message.status === "streaming" || message.status === "running")
        && (
          String(message.content || "").trim()
          || Date.now() - Number(message.timestamp || mergedConversation.timestamp || 0) <= 120000
        )
      );
      if (hasLiveAssistant) {
        return mergedConversation;
      }
      return {
        ...mergedConversation,
        status: "completed",
        messages: messages.map((message) =>
          message?.role === "assistant" && (message.streaming || message.status === "streaming" || message.status === "running")
            ? { ...message, streaming: false, status: String(message.content || "").trim() ? "completed" : "empty" }
            : message
        )
      };
    },
    handleAgentTaskCancelled(task = {}) {
      const sessionId = task.sessionId || task.conversationId || "";
      if (!sessionId) {
        return;
      }
      clearChatRuntimeState({
        conversationId: sessionId,
        taskId: task.taskId || ""
      });
      const applyCancelled = (item) =>
        item && (item.id === sessionId || item.conversationId === sessionId)
          ? {
              ...item,
              status: "cancelled",
              messages: Array.isArray(item.messages)
                ? item.messages.map((message) => ({
                    ...message,
                    streaming: false,
                    status: message.streaming
                      || message.status === "streaming"
                      || message.status === "running"
                      || message.status === "waiting"
                      ? "cancelled"
                      : message.status
                  }))
                : item.messages
            }
          : item;
      this.conversationHistory = this.conversationHistory.map(applyCancelled);
      if (this.selectedConversation && (
        this.selectedConversation.id === sessionId || this.selectedConversation.conversationId === sessionId
      )) {
        this.selectedConversation = {
          ...applyCancelled(this.selectedConversation),
          selectedAt: Date.now()
        };
      }
    },
    upsertLocalConversation(conversation) {
      const nextItem = {
        id: conversation.id,
        question: conversation.question,
        timestamp: conversation.timestamp || Date.now(),
        conversationId: conversation.conversationId || "",
        mode: conversation.mode || "llm_chat",
        skillId: conversation.skillId || "",
        modelName: conversation.modelName || "",
        agentName: conversation.agentName || "",
        status: conversation.status || "running",
        messages: Array.isArray(conversation.messages)
          ? conversation.messages.map((message) => ({
              ...message,
              streaming: !!message.streaming,
              status: message.status || (message.streaming ? "streaming" : "completed")
            }))
          : []
      };
      this.conversationHistory = [
        nextItem,
        ...this.conversationHistory.filter((item) => item.id !== nextItem.id)
      ];
    }
  }
};
