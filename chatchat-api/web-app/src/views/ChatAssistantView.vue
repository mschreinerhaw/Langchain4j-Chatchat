<template>
  <div class="chat-view">
    <section v-if="!hasConversation" class="hero-panel">
      <div class="hero-orb">
        <span>AI</span>
      </div>
      <div class="spark spark-one"></div>
      <div class="spark spark-two"></div>
      <h1>{{ heroGreeting }}</h1>
      <p>{{ heroDescription }}</p>
      <div v-if="agentResponsibilities.length" class="hero-agent-scope">
        <span>职责</span>
        <strong v-for="item in agentResponsibilities" :key="item">{{ item }}</strong>
      </div>
    </section>

    <ChatMessageList
      v-if="hasConversation"
      ref="messageList"
      :messages="messages"
      :loading="loading"
      :user-id="userId"
      :active-agent="selectedAgent"
    />
    <p v-if="statusNotice" class="chat-status-notice">{{ statusNotice }}</p>
    <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>

    <div class="chat-input-dock">
      <PromptComposer
        v-model="question"
        v-model:selected-agent-id="selectedAgentId"
        :agents="agents"
        :agents-loading="agentsLoading"
        :suggestions="activeSuggestions"
        :loading="composerBusy"
        :show-suggestions="!hasConversation && activeSuggestions.length > 0"
        @pick="question = $event"
        @send="handleSend"
        @clear="clearChat"
        @upload="handleUpload"
      />

      <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
    </div>
  </div>
</template>

<script src="../js/views/ChatAssistantView.js"></script>
