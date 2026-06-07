<template>
  <section class="message-panel" aria-live="polite">
    <article
      v-for="message in messages"
      :key="message.id"
      class="chat-message"
      :class="[message.role, { streaming: message.streaming }]"
    >
      <div class="message-avatar">{{ message.role === "user" ? userAvatarLabel : "AI" }}</div>
      <div class="message-bubble">
        <div class="message-meta">
          <strong>{{ message.role === "user" ? displayUserId : "AI投资助手" }}</strong>
          <time>{{ formatTime(message.timestamp) }}</time>
        </div>
        <div class="message-markdown" v-html="renderMarkdown(message.content)"></div>
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
      </div>
    </article>

    <article v-if="loading && !hasStreamingMessage" class="chat-message assistant thinking-message">
      <div class="message-avatar">AI</div>
      <div class="message-bubble loading-bubble" aria-label="AI投资助手正在思考">
        <span class="thinking-core" aria-hidden="true">
          <i></i>
          <i></i>
          <i></i>
        </span>
        <span class="thinking-copy">
          <strong>AI投资助手正在思考</strong>
          <small>正在分析问题并组织回答</small>
        </span>
      </div>
    </article>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
