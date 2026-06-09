import {
  BookOpen,
  Bot,
  Boxes,
  ChevronDown,
  ClipboardList,
  Ellipsis,
  FileText,
  LayoutGrid,
  MessageCircle,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings,
  Star,
  Trash2,
  Wrench
} from "@lucide/vue";

export default {
  name: "AssistantSidebar",
  components: {
    ChevronDown,
    Ellipsis,
    MessageCircle,
    PanelLeftClose,
    PanelLeftOpen,
    Search,
    Trash2
  },
  props: {
    activeView: {
      type: String,
      required: true
    },
    activeConversationId: {
      type: String,
      default: ""
    },
    collapsed: {
      type: Boolean,
      default: false
    },
    historyError: {
      type: String,
      default: ""
    },
    historyLoading: {
      type: Boolean,
      default: false
    },
    navItems: {
      type: Array,
      default: () => []
    },
    recentConversations: {
      type: Array,
      default: () => []
    },
    userId: {
      type: String,
      default: ""
    }
  },
  emits: ["delete-conversation", "navigate", "refresh-history", "select-conversation", "toggle-sidebar"],
  data() {
    return {
      collapsedGroups: {},
      agentRuntimeLogo: "/agent-runtime-logo.svg",
      historyKeyword: "",
      showAllHistory: false
    };
  },
  computed: {
    navGroups() {
      if (this.navItems.some((item) => Array.isArray(item.items))) {
        return this.navItems;
      }
      return [
        {
          id: "main",
          label: "导航",
          items: this.navItems
        }
      ];
    },
    filteredConversations() {
      const keyword = this.historyKeyword.trim().toLowerCase();
      if (!keyword) {
        return this.recentConversations;
      }
      return this.recentConversations.filter((conversation) => {
        const fields = [
          conversation.question,
          conversation.conversationId,
          this.statusLabel(conversation),
          this.resolveStatus(conversation)
        ];
        return fields.some((field) => String(field || "").toLowerCase().includes(keyword));
      });
    },
    visibleConversations() {
      return this.showAllHistory ? this.filteredConversations : this.filteredConversations.slice(0, 5);
    },
    displayUserId() {
      return this.userId || "default-user";
    },
    userAvatarLabel() {
      return this.displayUserId.slice(0, 2).toUpperCase();
    }
  },
  methods: {
    iconComponent(icon) {
      return {
        agent: Bot,
        book: BookOpen,
        chat: MessageSquare,
        file: FileText,
        gear: Settings,
        grid: LayoutGrid,
        hub: Boxes,
        mcp: Wrench,
        search: Search,
        star: Star,
        tasks: ClipboardList
      }[icon] || LayoutGrid;
    },
    isGroupCollapsed(group) {
      if (this.collapsed) {
        return false;
      }
      return !!this.collapsedGroups[group.id];
    },
    toggleGroup(group) {
      this.collapsedGroups = {
        ...this.collapsedGroups,
        [group.id]: !this.collapsedGroups[group.id]
      };
    },
    conversationKey(conversation) {
      return conversation.id || conversation.conversationId || conversation.question;
    },
    conversationTitle(conversation) {
      return conversation.question || "未命名会话";
    },
    isConversationActive(conversation) {
      return conversation.id && conversation.id === this.activeConversationId;
    },
    isUnfinished(conversation) {
      const status = this.resolveStatus(conversation);
      return status === "running" || status === "pending";
    },
    resolveStatus(conversation) {
      if (conversation.status) {
        return String(conversation.status).toLowerCase();
      }
      const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
      const lastMessage = messages[messages.length - 1];
      return lastMessage?.role === "user" ? "pending" : "completed";
    },
    statusLabel(conversation) {
      const status = this.resolveStatus(conversation);
      if (status === "running") {
        return "生成中";
      }
      if (status === "pending") {
        return "未完成";
      }
      if (status === "failed") {
        return "失败";
      }
      return "";
    },
    selectConversation(conversation) {
      this.$emit("select-conversation", conversation);
    },
    deleteConversation(conversation) {
      this.$emit("delete-conversation", conversation);
    }
  }
};
