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
    :history-loading="historyLoading"
    :nav-items="navItems"
    :recent-conversations="recentConversations"
    :user-id="userId"
    @navigate="handleNavigate"
    @delete-conversation="deleteConversation"
    @refresh-history="loadConversationHistory"
    @select-conversation="selectConversation"
    @logout="handleLogout"
  >
    <KeepAlive>
      <component
        :is="activeComponent"
        v-bind="activeComponentProps"
        @conversation-active="handleConversationActive"
        @history-saved="handleHistorySaved"
        @navigate="handleNavigate"
      />
    </KeepAlive>

    <template #right-panel="{ collapsed, toggleCollapsed }">
      <RightPanel
        :collapsed="collapsed"
        :user-id="userId"
        @toggle-collapsed="toggleCollapsed"
      />
    </template>
  </AssistantLayout>
</template>

<script src="./js/App.js"></script>
