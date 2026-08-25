import AssistantSidebar from "../../components/AssistantSidebar.vue";
import "../../styles/layout.css";

export default {
  name: "AssistantLayout",
  components: {
    AssistantSidebar
  },
  data() {
    return {
      sidebarCollapsed: false,
      rightPanelCollapsed: true
    };
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
    historyManagerItems: { type: Array, default: () => [] },
    historyManagerTotal: { type: Number, default: 0 },
    historyManagerPage: { type: Number, default: 1 },
    historyManagerPageSize: { type: Number, default: 10 },
    historyManagerPageCount: { type: Number, default: 1 },
    historyManagerLoading: { type: Boolean, default: false },
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
    "load-history-manager",
    "navigate",
    "refresh-history",
    "rename-conversation",
    "select-conversation"
  ],
  methods: {
    toggleRightPanel() {
      this.rightPanelCollapsed = !this.rightPanelCollapsed;
    }
  }
};
