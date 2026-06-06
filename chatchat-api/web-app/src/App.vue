<template>
  <AssistantLayout
    :active-view="activeView"
    :nav-items="navItems"
    :recent-conversations="recentConversations"
    @navigate="activeView = $event"
  >
    <component :is="activeComponent" />

    <template #right-panel>
      <RightPanel />
    </template>
  </AssistantLayout>
</template>

<script>
import AssistantLayout from "./components/AssistantLayout.vue";
import RightPanel from "./components/RightPanel.vue";
import ChatAssistantView from "./views/ChatAssistantView.vue";
import CapabilityMarketView from "./views/CapabilityMarketView.vue";
import AiSearchView from "./views/AiSearchView.vue";
import LibraryView from "./views/LibraryView.vue";
import SkillHubView from "./views/SkillHubView.vue";
import ReportsView from "./views/ReportsView.vue";
import FavoritesView from "./views/FavoritesView.vue";
import SystemManagementView from "./views/SystemManagementView.vue";

const views = {
  chat: ChatAssistantView,
  market: CapabilityMarketView,
  search: AiSearchView,
  library: LibraryView,
  skillhub: SkillHubView,
  reports: ReportsView,
  favorites: FavoritesView,
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
      navItems: [
        { id: "chat", label: "对话助手", icon: "chat" },
        { id: "market", label: "能力广场", icon: "grid" },
        { id: "search", label: "AI搜索", icon: "search" },
        { id: "library", label: "投研库", icon: "book" },
        { id: "skillhub", label: "SkillHub", icon: "hub" },
        { id: "reports", label: "我的报告", icon: "file" },
        { id: "favorites", label: "我的收藏", icon: "star" },
        { id: "system", label: "系统管理", icon: "gear" }
      ],
      recentConversations: [
        "分析贵州茅台未来半年投资价值",
        "半导体行业趋势分析报告",
        "中芯国际最新财报解读",
        "AI算力产业链梳理分析",
        "北向资金近期流向分析"
      ]
    };
  },
  computed: {
    activeComponent() {
      return views[this.activeView] || ChatAssistantView;
    }
  }
};
</script>
