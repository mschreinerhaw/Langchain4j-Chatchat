<template>
  <LoginView
    v-if="!authSession"
    @login-success="handleLoginSuccess"
  />
  <AssistantLayout
    v-else
    :active-view="activeView"
    :active-conversation-id="activeHistoryId"
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
    :nav-items="visibleNavItems"
    :recent-conversations="recentConversations"
    :user-id="userId"
    @navigate="handleNavigate"
    @delete-conversation="deleteConversation"
    @delete-conversations="deleteConversations"
    @favorite-conversation="favoriteConversation"
    @refresh-history="loadConversationHistory"
    @load-more-history="loadMoreConversationHistory"
    @load-history-manager="loadHistoryManager"
    @rename-conversation="renameConversation"
    @select-conversation="selectConversation"
    @logout="handleLogout"
  >
    <template #workspace-switcher>
      <WorkspaceSwitcher v-if="workspaceEnabled" :spaces="workspaces" :active-id="activeWorkspaceId" :busy="workspaceBusy"
        @switch="switchWorkspace" @select="openWorkspaceSelection" @default="setDefaultWorkspace" />
    </template>
    <WorkspaceSelection v-if="workspaceSelection" :spaces="workspaces" :busy="workspaceBusy" :error="workspaceError"
      @switch="switchWorkspace" @retry="initializeWorkspaces" />
    <p v-else-if="workspaceError" role="alert">{{ workspaceError }}</p>
    <KeepAlive :max="12">
      <component
        v-if="!workspaceSelection"
        :key="activeViewKey"
        :ref="activeViewRef"
        :is="activeComponent"
        v-bind="{ ...activeComponentProps, workspaceId: activeWorkspaceId }"
        @conversation-active="handleConversationActive"
        @history-saved="handleHistorySaved"
        @ask-ai="handleAskAiFromSearch"
        @navigate="handleNavigate"
        @analyze-with-agent="handleAnalyzeWithAgent"
        @select-agent="handleSelectAgentShortcut"
        @open-favorite="handleOpenFavoriteShortcut"
      />
    </KeepAlive>

    <button
      v-if="canAccessView('chat')"
      class="floating-new-conversation"
      v-floating-drag
      type="button"
      title="新建对话"
      aria-label="新建对话"
      @click="handleNewConversation"
    >
      <Plus :size="16" stroke-width="2.2" aria-hidden="true" />
      <span>新建对话</span>
    </button>

    <template #right-panel="{ collapsed, toggleCollapsed }">
      <RightPanel
        :collapsed="collapsed"
        :tenant-id="tenantId"
        :user-id="userId"
        @ask-ai="handleAskAiFromSearch"
        @navigate="handleNavigate"
        @open-document="handleOpenDocumentShortcut"
        @open-favorite="handleOpenFavoriteShortcut"
        @select-agent="handleSelectAgentShortcut"
        @toggle-collapsed="toggleCollapsed"
      />
    </template>
  </AssistantLayout>
</template>

<script src="./js/App.js"></script>
