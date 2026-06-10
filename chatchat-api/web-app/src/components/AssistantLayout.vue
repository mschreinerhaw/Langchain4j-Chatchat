<template>
  <div
    class="assistant-app"
    :class="{
      'sidebar-collapsed': sidebarCollapsed,
      'assistant-right-collapsed': rightPanelCollapsed
    }"
  >
    <AssistantSidebar
      :active-view="activeView"
      :active-conversation-id="activeConversationId"
      :collapsed="sidebarCollapsed"
      :history-error="historyError"
      :history-loading="historyLoading"
      :nav-items="navItems"
      :recent-conversations="recentConversations"
      :user-id="userId"
      @delete-conversation="$emit('delete-conversation', $event)"
      @navigate="$emit('navigate', $event)"
      @refresh-history="$emit('refresh-history')"
      @select-conversation="$emit('select-conversation', $event)"
      @toggle-sidebar="sidebarCollapsed = !sidebarCollapsed"
      @logout="$emit('logout')"
    />

    <main class="assistant-main">
      <section class="assistant-content">
        <slot />
      </section>

      <aside class="assistant-right">
        <slot
          name="right-panel"
          :collapsed="rightPanelCollapsed"
          :toggle-collapsed="toggleRightPanel"
        />
      </aside>
    </main>
  </div>
</template>

<script src="../js/components/AssistantLayout.js"></script>
