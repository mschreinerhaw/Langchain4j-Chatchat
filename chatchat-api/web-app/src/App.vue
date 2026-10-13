<template>
  <LoginView
    v-if="!authSession"
    @login-success="handleLoginSuccess"
  />
  <AssistantLayout
    v-else
    :class="{ 'workspace-changing': workspaceLoading && workspaceReady }"
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
      <div class="workspace-switcher" :class="{ 'is-switching': workspaceLoading }">
        <div class="workspace-switcher-heading">
          <label for="workspace-switch">工作空间</label>
          <span class="workspace-switcher-state" role="status">{{ workspaceLoading ? '切换中…' : '当前环境' }}</span>
        </div>
        <div class="workspace-switcher-control">
          <Layers3 :size="17" stroke-width="2" aria-hidden="true" />
          <select id="workspace-switch" :value="currentWorkspace?.workspaceId || ''" :disabled="workspaceLoading"
            aria-label="切换工作空间" @change="switchWorkspace($event.target.value)">
            <option v-if="!currentWorkspace" value="" disabled>选择工作空间</option>
            <option v-for="workspace in workspaceItems" :key="workspace.workspaceId" :value="workspace.workspaceId">{{ workspace.name }}</option>
          </select>
          <ChevronDown class="workspace-switcher-chevron" :size="16" stroke-width="2" aria-hidden="true" />
        </div>
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
.workspace-switcher {
  grid-column: 1 / -1;
  min-width: 0;
  display: grid;
  gap: 8px;
  margin-top: 5px;
  padding: 12px;
  border: 1px solid #dbe6f8;
  border-radius: 12px;
  background: linear-gradient(145deg, #f8fbff, #edf4ff);
  box-shadow: 0 3px 12px rgba(37, 99, 235, 0.05);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}
.workspace-switcher:hover, .workspace-switcher:focus-within {
  border-color: #9dbdf5;
  box-shadow: 0 5px 16px rgba(37, 99, 235, 0.12);
}
.workspace-switcher-heading { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.workspace-switcher label { color: #475569; font-size: 11px; font-weight: 700; }
.workspace-switcher .workspace-switcher-state { margin: 0; color: #7890ae; font-size: 10px; }
.workspace-switcher.is-switching .workspace-switcher-state { color: #1d64d8; }
.workspace-switcher-control {
  min-width: 0;
  height: 38px;
  display: grid;
  grid-template-columns: 17px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid #cbdcf6;
  border-radius: 8px;
  background: #fff;
  color: #2868ca;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.workspace-switcher-control:focus-within { border-color: #4084eb; box-shadow: 0 0 0 3px rgba(64, 132, 235, 0.14); }
.workspace-switcher select {
  min-width: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  appearance: none;
  border: 0;
  outline: 0;
  background: transparent;
  color: #183b72;
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}
.workspace-switcher select:disabled { cursor: progress; }
.workspace-switcher-chevron { pointer-events: none; transition: transform 0.18s ease; }
.workspace-switcher:hover .workspace-switcher-chevron,
.workspace-switcher:focus-within .workspace-switcher-chevron { transform: translateY(2px); }
.workspace-switcher.is-switching .workspace-switcher-chevron { animation: workspace-chevron-pulse 0.8s ease-in-out infinite alternate; }
:global(.assistant-app.sidebar-collapsed) .workspace-switcher { display: none; }
@keyframes workspace-chevron-pulse { to { opacity: 0.35; transform: translateY(2px); } }
@media (prefers-reduced-motion: reduce) {
  .workspace-switcher, .workspace-switcher-control, .workspace-switcher-chevron { transition: none; }
  .workspace-switcher.is-switching .workspace-switcher-chevron { animation: none; }
}
.workspace-selection { padding: 30px; }
.workspace-selection h1 { font-size: 24px; }
.workspace-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 18px; margin-top: 24px; }
.workspace-card { padding: 22px; border: 1px solid #e2e8f0; border-radius: 12px; background: white; }
.workspace-card button { cursor: pointer; margin-right: 8px; padding: 6px 10px; border-radius: 6px; border: 1px solid #cbd5e1; }
.workspace-error { padding: 12px; color: #b91c1c; background: #fef2f2; }
</style>
