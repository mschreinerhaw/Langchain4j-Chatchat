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
    "select-conversation"
  ],
  methods: {
    toggleRightPanel() {
      this.rightPanelCollapsed = !this.rightPanelCollapsed;
    }
  }
};
