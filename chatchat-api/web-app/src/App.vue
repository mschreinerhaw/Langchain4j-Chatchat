<template>
  <AssistantLayout
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
  >
    <KeepAlive>
      <component
        :is="activeComponent"
        v-bind="activeComponentProps"
        @conversation-active="handleConversationActive"
        @history-saved="handleHistorySaved"
      />
    </KeepAlive>

    <template #right-panel>
      <RightPanel :user-id="userId" />
    </template>
  </AssistantLayout>
</template>

<script src="./js/App.js"></script>
