import {
  BookOpen,
  Bot,
  Boxes,
  CalendarClock,
  ChevronDown,
  ClipboardList,
  FileText,
  LayoutGrid,
  LogOut,
  MessageCircle,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings,
  Star,
  Trash2,
  Wrench,
  X
} from "@lucide/vue";

const HISTORY_MANAGER_PAGE_SIZE = 10;

export default {
  name: "AssistantSidebar",
  components: {
    ChevronDown,
    LogOut,
    MessageCircle,
    PanelLeftClose,
    PanelLeftOpen,
    Search,
    Star,
    Trash2,
    X
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
    historyHasMore: {
      type: Boolean,
      default: false
    },
    historyLoading: {
      type: Boolean,
      default: false
    },
    historyDeleting: {
      type: Boolean,
      default: false
    },
    favoriteConversationIds: {
      type: Array,
      default: () => []
    },
    favoriteSavingIds: {
      type: Object,
      default: () => ({})
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
  emits: [
    "delete-conversation",
    "delete-conversations",
    "favorite-conversation",
    "logout",
    "load-more-history",
    "navigate",
    "refresh-history",
    "select-conversation",
    "toggle-sidebar"
  ],
  data() {
    return {
      collapsedGroups: {
        platform: true
      },
      agentRuntimeLogo: "/lingdong-insight-logo.svg",
      historyKeyword: "",
      historyManagerOpen: false,
      managerKeyword: "",
      managerCurrentPage: 1,
      selectedHistoryKeys: [],
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
    historyCountLabel() {
      return this.recentConversations.length > 99 ? "99+" : String(this.recentConversations.length);
    },
    managerFilteredConversations() {
      const keyword = this.managerKeyword.trim().toLowerCase();
      if (!keyword) {
        return this.recentConversations;
      }
      return this.recentConversations.filter((conversation) => [
        conversation.question,
        conversation.id,
        conversation.conversationId,
        this.statusLabel(conversation),
        this.resolveStatus(conversation)
      ].some((field) => String(field || "").toLowerCase().includes(keyword)));
    },
    selectedManagerConversations() {
      const selected = new Set(this.selectedHistoryKeys);
      return this.recentConversations.filter((conversation) => selected.has(this.conversationKey(conversation)));
    },
    managerPageCount() {
      return Math.max(1, Math.ceil(this.managerFilteredConversations.length / HISTORY_MANAGER_PAGE_SIZE));
    },
    managerPageStart() {
      return (this.managerCurrentPage - 1) * HISTORY_MANAGER_PAGE_SIZE;
    },
    managerPageEnd() {
      return Math.min(this.managerPageStart + HISTORY_MANAGER_PAGE_SIZE, this.managerFilteredConversations.length);
    },
    managerPageConversations() {
      return this.managerFilteredConversations.slice(this.managerPageStart, this.managerPageEnd);
    },
    allManagerConversationsSelected() {
      return this.managerPageConversations.length > 0
        && this.managerPageConversations.every((conversation) => this.selectedHistoryKeys.includes(this.conversationKey(conversation)));
    },
    someManagerConversationsSelected() {
      if (this.allManagerConversationsSelected) {
        return false;
      }
      return this.managerPageConversations.some((conversation) => this.selectedHistoryKeys.includes(this.conversationKey(conversation)));
    },
    displayUserId() {
      return this.userId || "default-user";
    },
    userAvatarLabel() {
      return this.displayUserId.slice(0, 2).toUpperCase();
    }
  },
  watch: {
    managerKeyword() {
      this.managerCurrentPage = 1;
    },
    historyDeleting(deleting) {
      if (!deleting && this.historyManagerOpen && this.selectedManagerConversations.length === 0) {
        this.closeHistoryManager();
      }
    },
    recentConversations(conversations) {
      const availableKeys = new Set(conversations.map((conversation) => this.conversationKey(conversation)));
      this.selectedHistoryKeys = this.selectedHistoryKeys.filter((key) => availableKeys.has(key));
      this.$nextTick(() => {
        this.managerCurrentPage = Math.min(this.managerCurrentPage, this.managerPageCount);
      });
    }
  },
  methods: {
    iconComponent(icon) {
      return {
        agent: Bot,
        book: BookOpen,
        schedule: CalendarClock,
        chat: MessageSquare,
        file: FileText,
        gear: Settings,
        grid: LayoutGrid,
        hub: Boxes,
        mcp: Wrench,
        runtime: Boxes,
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
    conversationCreatedAt(conversation) {
      const value = conversation?.createdAt ?? conversation?.timestamp;
      if (value === null || value === undefined || value === "") {
        return null;
      }
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? null : date;
    },
    formatConversationCreatedAt(conversation) {
      const date = this.conversationCreatedAt(conversation);
      if (!date) {
        return "时间未知";
      }
      const pad = (value) => String(value).padStart(2, "0");
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    },
    conversationCreatedAtIso(conversation) {
      return this.conversationCreatedAt(conversation)?.toISOString() || "";
    },
    conversationCreatedAtTitle(conversation) {
      const date = this.conversationCreatedAt(conversation);
      return date ? `创建时间：${date.toLocaleString("zh-CN", { hour12: false })}` : "创建时间未知";
    },
    isConversationActive(conversation) {
      return conversation.id && conversation.id === this.activeConversationId;
    },
    conversationFavoriteKey(conversation) {
      return conversation.conversationId || conversation.id || "";
    },
    isConversationFavorited(conversation) {
      const key = this.conversationFavoriteKey(conversation);
      return !!key && this.favoriteConversationIds.includes(key);
    },
    isFavoriteSaving(conversation) {
      const key = this.conversationFavoriteKey(conversation);
      return !!key && !!this.favoriteSavingIds[key];
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
    favoriteConversation(conversation) {
      if (this.isFavoriteSaving(conversation)) {
        return;
      }
      this.$emit("favorite-conversation", conversation);
    },
    deleteConversation(conversation) {
      this.$emit("delete-conversation", conversation);
    },
    openHistoryManager() {
      this.managerKeyword = "";
      this.managerCurrentPage = 1;
      this.selectedHistoryKeys = [];
      this.historyManagerOpen = true;
      this.$nextTick(() => this.$refs.historyManagerSearch?.focus());
    },
    closeHistoryManager() {
      if (this.historyDeleting) {
        return;
      }
      this.historyManagerOpen = false;
      this.selectedHistoryKeys = [];
    },
    toggleAllManagerConversations(event) {
      const visibleKeys = this.managerPageConversations.map((conversation) => this.conversationKey(conversation));
      const selected = new Set(this.selectedHistoryKeys);
      if (event.target.checked) {
        visibleKeys.forEach((key) => selected.add(key));
      } else {
        visibleKeys.forEach((key) => selected.delete(key));
      }
      this.selectedHistoryKeys = [...selected];
    },
    changeManagerPage(page) {
      this.managerCurrentPage = Math.max(1, Math.min(this.managerPageCount, page));
    },
    deleteSelectedHistory() {
      if (this.selectedManagerConversations.length === 0 || this.historyDeleting) {
        return;
      }
      this.$emit("delete-conversations", [...this.selectedManagerConversations]);
    }
  }
};
