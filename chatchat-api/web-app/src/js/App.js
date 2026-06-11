import AssistantLayout from "../components/AssistantLayout.vue";
import RightPanel from "../components/RightPanel.vue";
import LoginView from "../views/LoginView.vue";
import ChatAssistantView from "../views/ChatAssistantView.vue";
import CapabilityMarketView from "../views/CapabilityMarketView.vue";
import AiSearchView from "../views/AiSearchView.vue";
import LibraryView from "../views/LibraryView.vue";
import McpCenterView from "../views/McpCenterView.vue";
import AgentWorkshopView from "../views/AgentWorkshopView.vue";
import SystemManagementView from "../views/SystemManagementView.vue";
import TasksView from "../views/TasksView.vue";
import { clearAuthSession, deleteConversationHistory, fetchConversationHistory, getStoredAuthSession } from "../services/api";

const USER_ID = "mx_48991534";
const IDLE_LOGOUT_MS = 30 * 60 * 1000;
const ACTIVITY_THROTTLE_MS = 1000;
const ACTIVITY_EVENTS = ["click", "keydown", "mousemove", "mousedown", "scroll", "touchstart", "wheel"];

const views = {
  chat: ChatAssistantView,
  search: AiSearchView,
  market: CapabilityMarketView,
  library: LibraryView,
  mcp: McpCenterView,
  agents: AgentWorkshopView,
  tasks: TasksView,
  system: SystemManagementView
};

export default {
  name: "App",
  components: {
    AssistantLayout,
    LoginView,
    RightPanel
  },
  data() {
    const authSession = getStoredAuthSession();
    const sessionUser = authSession?.user || {};
    return {
      authSession,
      activeView: "search",
      userId: sessionUser.username || sessionUser.id || USER_ID,
      historyLoading: false,
      historyError: "",
      conversationHistory: [],
      selectedConversation: null,
      activeHistoryId: "",
      idleLogoutTimer: null,
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
            { id: "mcp", label: "MCP服务", icon: "mcp" },
            { id: "agents", label: "Agent管理", icon: "agent" },
            { id: "tasks", label: "运行监控", icon: "tasks" },
            { id: "system", label: "系统管理", icon: "gear" }
          ]
        }
      ]
    };
  },
  computed: {
    activeComponent() {
      return views[this.activeView] || ChatAssistantView;
    },
    activeComponentProps() {
      return this.activeView === "chat"
        ? {
            userId: this.userId,
            selectedConversation: this.selectedConversation
          }
        : {
            userId: this.userId
          };
    },
    recentConversations() {
      return this.conversationHistory;
    }
  },
  mounted() {
    if (this.authSession) {
      this.loadConversationHistory();
      this.startIdleLogoutWatcher();
    }
  },
  beforeUnmount() {
    this.stopIdleLogoutWatcher();
  },
  methods: {
    handleLoginSuccess(session) {
      this.authSession = session;
      const sessionUser = session?.user || {};
      this.userId = sessionUser.username || sessionUser.id || USER_ID;
      this.startIdleLogoutWatcher();
      this.loadConversationHistory();
    },
    handleLogout() {
      this.stopIdleLogoutWatcher();
      clearAuthSession();
      this.authSession = null;
      this.userId = USER_ID;
      this.conversationHistory = [];
      this.selectedConversation = null;
      this.activeHistoryId = "";
    },
    startIdleLogoutWatcher() {
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
      this.historyLoading = true;
      this.historyError = "";
      try {
        const history = await fetchConversationHistory(this.userId, {
          limit: 30,
          ...filters
        });
        this.conversationHistory = Array.isArray(history) ? history : [];
      } catch (error) {
        this.historyError = error.message || "历史会话加载失败";
      } finally {
        this.historyLoading = false;
      }
    },
    handleNavigate(view) {
      this.activeView = view;
    },
    selectConversation(conversation) {
      if (!conversation) {
        return;
      }
      this.activeView = "chat";
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
      this.conversationHistory = this.conversationHistory.filter((item) => item.id !== deletedId);
      if (this.activeHistoryId === deletedId) {
        this.activeHistoryId = "";
        this.selectedConversation = null;
      }
      try {
        await deleteConversationHistory(this.userId, deletedId);
      } catch (error) {
        this.historyError = error.message || "历史会话删除失败";
        await this.loadConversationHistory();
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
      const history = Array.isArray(payload?.history) ? payload.history : payload;
      if (Array.isArray(history)) {
        this.conversationHistory = this.applyActiveConversationStatus(
          history,
          payload?.activeHistoryId,
          payload?.activeStatus
        );
      }
      if (payload?.activeHistoryId) {
        this.activeHistoryId = payload.activeHistoryId;
      }
    },
    applyActiveConversationStatus(history, activeHistoryId, activeStatus) {
      if (!activeHistoryId || !activeStatus) {
        return history;
      }
      return history.map((item) =>
        item.id === activeHistoryId
          ? {
              ...item,
              status: activeStatus
            }
          : item
      );
    },
    upsertLocalConversation(conversation) {
      const nextItem = {
        id: conversation.id,
        question: conversation.question,
        timestamp: conversation.timestamp || Date.now(),
        conversationId: conversation.conversationId || "",
        mode: conversation.mode || "llm_chat",
        skillId: conversation.skillId || "",
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
