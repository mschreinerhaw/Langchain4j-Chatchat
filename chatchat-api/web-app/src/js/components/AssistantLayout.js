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
  emits: ["delete-conversation", "logout", "navigate", "refresh-history", "select-conversation"],
  methods: {
    toggleRightPanel() {
      this.rightPanelCollapsed = !this.rightPanelCollapsed;
    }
  }
};
