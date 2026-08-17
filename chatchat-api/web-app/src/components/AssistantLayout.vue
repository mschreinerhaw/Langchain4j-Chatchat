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
      :history-has-more="historyHasMore"
      :history-loading="historyLoading"
      :history-deleting="historyDeleting"
      :history-manager-items="historyManagerItems"
      :history-manager-total="historyManagerTotal"
      :history-manager-page="historyManagerPage"
      :history-manager-page-size="historyManagerPageSize"
      :history-manager-page-count="historyManagerPageCount"
      :history-manager-loading="historyManagerLoading"
      :favorite-conversation-ids="favoriteConversationIds"
      :favorite-saving-ids="favoriteSavingIds"
      :nav-items="navItems"
      :recent-conversations="recentConversations"
      :user-id="userId"
      @delete-conversation="$emit('delete-conversation', $event)"
      @delete-conversations="$emit('delete-conversations', $event)"
      @favorite-conversation="$emit('favorite-conversation', $event)"
      @navigate="$emit('navigate', $event)"
      @refresh-history="$emit('refresh-history')"
      @load-more-history="$emit('load-more-history')"
      @load-history-manager="$emit('load-history-manager', $event)"
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
