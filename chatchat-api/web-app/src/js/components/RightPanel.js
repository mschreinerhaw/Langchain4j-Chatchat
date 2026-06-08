import {
  AlertTriangle,
  Bot,
  Briefcase,
  ClipboardList,
  FileText,
  PanelRightClose,
  PanelRightOpen,
  Star
} from "@lucide/vue";
import "../../styles/components/right-panel.css";

export default {
  name: "RightPanel",
  components: {
    AlertTriangle,
    Bot,
    Briefcase,
    ClipboardList,
    FileText,
    PanelRightClose,
    PanelRightOpen,
    Star
  },
  props: {
    collapsed: {
      type: Boolean,
      default: false
    },
    userId: {
      type: String,
      default: ""
    }
  },
  emits: ["toggle-collapsed"],
  computed: {
    displayUserId() {
      return this.userId || "default-user";
    },
    railItems() {
      return [
        {
          id: "holdings",
          label: "我的持仓",
          icon: Briefcase,
          count: this.holdings.length
        },
        {
          id: "risks",
          label: "风险事件",
          icon: AlertTriangle,
          count: this.riskEvents.length
        },
        {
          id: "reports",
          label: "最近报告",
          icon: FileText,
          count: this.reports.length
        },
        {
          id: "favorites",
          label: "收藏夹",
          icon: Star,
          count: this.favorites.length
        },
        {
          id: "tasks",
          label: "待办任务",
          icon: ClipboardList,
          count: this.tasks.length
        },
        {
          id: "agents",
          label: "最近使用Agent",
          icon: Bot,
          count: this.recentAgents.length
        }
      ];
    }
  },
  data() {
    return {
      holdings: [],
      riskEvents: [],
      reports: [],
      favorites: [],
      tasks: [],
      recentAgents: []
    };
  }
};
