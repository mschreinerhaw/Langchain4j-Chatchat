<template>
  <div class="chat-view">
    <section v-if="!hasConversation" class="hero-panel">
      <div class="hero-orb">
        <span>AI</span>
      </div>
      <div class="spark spark-one"></div>
      <div class="spark spark-two"></div>
      <h1>下午好，{{ userId }}</h1>
      <p>AI投资助手已就绪，可进行市场洞察与投资分析</p>
    </section>

    <ChatMessageList
      v-if="hasConversation"
      ref="messageList"
      :messages="messages"
      :loading="loading"
      :user-id="userId"
    />
    <ResponseReferences
      :sources="lastResponse.sources"
      :tool-traces="lastResponse.toolTraces"
    />
    <p v-if="statusNotice" class="chat-status-notice">{{ statusNotice }}</p>
    <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>

    <PromptComposer
      v-model="question"
      v-model:selected-agent-id="selectedAgentId"
      :agents="agents"
      :agents-loading="agentsLoading"
      :suggestions="suggestions"
      :loading="loading"
      :show-suggestions="!hasConversation && suggestions.length > 0"
      @pick="question = $event"
      @send="handleSend"
      @clear="clearChat"
      @upload="handleUpload"
    />

    <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
  </div>
</template>

<script src="../js/views/ChatAssistantView.js"></script>
