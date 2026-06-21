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
      :favorite-conversation-ids="favoriteConversationIds"
      :favorite-saving-ids="favoriteSavingIds"
      :nav-items="navItems"
      :recent-conversations="recentConversations"
      :user-id="userId"
      @delete-conversation="$emit('delete-conversation', $event)"
      @favorite-conversation="$emit('favorite-conversation', $event)"
      @navigate="$emit('navigate', $event)"
      @new-conversation="$emit('new-conversation')"
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
