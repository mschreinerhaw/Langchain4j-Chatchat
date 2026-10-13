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
            aria-label="切换工作空间" @change="switchWorkspaceFromAccount($event)">
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

<style scoped src="./styles/app-workspace.css"></style>
