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
    :layout-type="currentWorkspace?.layoutType || 'assistant'"
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
      <div class="workspace-switcher">
        <label for="workspace-switch">工作空间</label>
        <select id="workspace-switch" :value="currentWorkspace?.workspaceId || ''" :disabled="workspaceLoading"
          @change="switchWorkspace($event.target.value)">
          <option v-if="!currentWorkspace" value="" disabled>选择工作空间</option>
          <option v-for="workspace in workspaceItems" :key="workspace.workspaceId" :value="workspace.workspaceId">{{ workspace.name }}</option>
        </select>
      </div>
    </template>
    <p v-if="workspaceError" class="workspace-error" role="alert">{{ workspaceError }}</p>
    <section v-if="showWorkspaceSelection || !workspaceReady" class="workspace-selection" aria-label="选择工作空间">
      <h1>选择工作空间</h1>
      <p>使用当前登录身份进入已授权的应用环境。</p>
      <p v-if="workspaceLoading" role="status">正在加载工作空间…</p>
      <div class="workspace-cards">
        <article v-for="workspace in workspaceItems" :key="workspace.workspaceId" class="workspace-card">
          <h2>{{ workspace.name }}</h2><p>{{ workspace.description }}</p>
          <button type="button" :disabled="workspaceLoading" @click="switchWorkspace(workspace.workspaceId)">进入空间</button>
          <button type="button" :disabled="workspaceLoading" @click="useDefaultWorkspace(workspace.workspaceId)">设为我的默认空间</button>
        </article>
      </div>
      <button v-if="workspaceError" type="button" @click="loadWorkspaces()">重新加载</button>
    </section>
    <KeepAlive>
      <component
        v-if="workspaceReady && !showWorkspaceSelection"
        :key="workspaceComponentKey"
        ref="activeViewComponent"
        :is="activeComponent"
        v-bind="activeComponentProps"
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

<style scoped>
.workspace-switcher { grid-column: 1 / -1; display: flex; align-items: center; gap: 6px; padding-top: 8px; border-top: 1px solid #e2e8f0; }
.workspace-switcher label { font-size: 12px; color: #64748b; }
.workspace-switcher select { flex: 1; min-width: 100px; border: 1px solid #cbd5e1; border-radius: 6px; padding: 5px; }
.workspace-selection { padding: 30px; }
.workspace-selection h1 { font-size: 24px; }
.workspace-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 18px; margin-top: 24px; }
.workspace-card { padding: 22px; border: 1px solid #e2e8f0; border-radius: 12px; background: white; }
.workspace-card button, .workspace-switcher button { cursor: pointer; margin-right: 8px; padding: 6px 10px; border-radius: 6px; border: 1px solid #cbd5e1; }
.workspace-error { padding: 12px; color: #b91c1c; background: #fef2f2; }
</style>
