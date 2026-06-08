import AssistantLayout from "../components/AssistantLayout.vue";
import RightPanel from "../components/RightPanel.vue";
import ChatAssistantView from "../views/ChatAssistantView.vue";
import CapabilityMarketView from "../views/CapabilityMarketView.vue";
import AiSearchView from "../views/AiSearchView.vue";
import LibraryView from "../views/LibraryView.vue";
import McpCenterView from "../views/McpCenterView.vue";
import AgentWorkshopView from "../views/AgentWorkshopView.vue";
import SystemManagementView from "../views/SystemManagementView.vue";
import { deleteConversationHistory, fetchConversationHistory } from "../services/api";

const USER_ID = "mx_48991534";

const views = {
  chat: ChatAssistantView,
  search: AiSearchView,
  market: CapabilityMarketView,
  library: LibraryView,
  mcp: McpCenterView,
  agents: AgentWorkshopView,
  system: SystemManagementView
};

export default {
  name: "App",
  components: {
    AssistantLayout,
    RightPanel
  },
  data() {
    return {
      activeView: "chat",
      userId: USER_ID,
      historyLoading: false,
      historyError: "",
      conversationHistory: [],
      selectedConversation: null,
      activeHistoryId: "",
      navItems: [
        {
          id: "workspace",
          label: "工作台",
          items: [
            { id: "chat", label: "对话助手", icon: "chat" },
            { id: "search", label: "AI搜索", icon: "search" }
          ]
        },
        {
          id: "capability",
          label: "能力中心",
          items: [
            { id: "market", label: "能力广场", icon: "grid" },
            { id: "library", label: "投研中心", icon: "book" }
          ]
        },
        {
          id: "platform",
          label: "平台管理",
          items: [
            { id: "mcp", label: "MCP中心", icon: "mcp" },
            { id: "agents", label: "Agent工坊", icon: "agent" },
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
    this.loadConversationHistory();
  },
  methods: {
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
