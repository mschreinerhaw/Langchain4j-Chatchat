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

    <article v-if="loading && !hasStreamingMessage" class="chat-message assistant">
      <div class="message-avatar">AI</div>
      <div class="message-bubble loading-bubble">
        <span></span>
        <span></span>
        <span></span>
      </div>
    </article>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
