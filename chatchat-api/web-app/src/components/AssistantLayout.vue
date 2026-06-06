<template>
  <div class="assistant-app">
    <AssistantSidebar
      :active-view="activeView"
      :nav-items="navItems"
      :recent-conversations="recentConversations"
      @navigate="$emit('navigate', $event)"
    />

    <main class="assistant-main">
      <section class="assistant-content">
        <slot />
      </section>

      <aside class="assistant-right">
        <slot name="right-panel" />
      </aside>
    </main>
  </div>
</template>

<script>
import AssistantSidebar from "./AssistantSidebar.vue";

export default {
  name: "AssistantLayout",
  components: {
    AssistantSidebar
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
  emits: ["navigate"]
};
</script>
