<template>
  <aside class="assistant-sidebar">
    <div class="brand-block">
      <div class="brand-logo">AI</div>
      <div>
        <strong>AI助手</strong>
        <span>证券数据分析</span>
      </div>
      <button class="sidebar-toggle" type="button" aria-label="收起侧栏">
        <PanelLeftClose :size="18" stroke-width="1.8" />
      </button>
    </div>

    <nav class="primary-nav" aria-label="主要功能">
      <button
        v-for="item in primaryItems"
        :key="item.id"
        :class="{ active: activeView === item.id }"
        type="button"
        @click="$emit('navigate', item.id)"
      >
        <component :is="iconComponent(item.icon)" class="nav-symbol" :size="18" stroke-width="2" />
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <nav class="secondary-nav" aria-label="个人功能">
      <button
        v-for="item in secondaryItems"
        :key="item.id"
        :class="{ active: activeView === item.id }"
        type="button"
        @click="$emit('navigate', item.id)"
      >
        <component :is="iconComponent(item.icon)" class="nav-symbol" :size="18" stroke-width="2" />
        <span>{{ item.label }}</span>
        <span v-if="item.id === 'system'" class="nav-caret"></span>
      </button>
    </nav>

    <section class="recent-block">
      <div class="recent-head">
        <span>最近对话</span>
        <span>近30天</span>
      </div>
      <button
        v-for="(conversation, index) in recentConversations"
        :key="conversation"
        :class="{ active: index === 0 }"
        type="button"
      >
        {{ conversation }}
      </button>
      <button class="more-link" type="button">查看全部对话</button>
    </section>

    <div class="user-card">
      <div class="user-avatar">AI</div>
      <div>
        <strong>mx_48991534</strong>
        <span>图谱证券</span>
      </div>
      <button type="button" aria-label="消息" class="user-action">
        <MessageCircle :size="17" stroke-width="1.9" />
      </button>
      <button type="button" aria-label="更多" class="user-more">
        <Ellipsis :size="18" stroke-width="2" />
      </button>
    </div>
  </aside>
</template>

<script>
import {
  BookOpen,
  Boxes,
  Ellipsis,
  FileText,
  LayoutGrid,
  MessageCircle,
  MessageSquare,
  PanelLeftClose,
  Search,
  Settings,
  Star
} from "@lucide/vue";

export default {
  name: "AssistantSidebar",
  components: {
    Ellipsis,
    MessageCircle,
    PanelLeftClose
  },
  props: {
    activeView: {
      type: String,
      required: true
    },
    navItems: {
      type: Array,
      default: () => []
    },
    recentConversations: {
      type: Array,
      default: () => []
    }
  },
  emits: ["navigate"],
  computed: {
    primaryItems() {
      return this.navItems.slice(0, 5);
    },
    secondaryItems() {
      return this.navItems.slice(5);
    }
  },
  methods: {
    iconComponent(icon) {
      return {
        book: BookOpen,
        chat: MessageSquare,
        file: FileText,
        gear: Settings,
        grid: LayoutGrid,
        hub: Boxes,
        search: Search,
        star: Star
      }[icon] || LayoutGrid;
    }
  }
};
</script>
