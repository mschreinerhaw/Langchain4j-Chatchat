<template>
  <section class="message-panel" aria-live="polite">
    <article v-for="message in messages" :key="message.id" class="chat-message" :class="message.role">
      <div class="message-avatar">{{ message.role === "user" ? "我" : "AI" }}</div>
      <div class="message-bubble">
        <div class="message-meta">
          <strong>{{ message.role === "user" ? "您" : "AI投资助手" }}</strong>
          <time>{{ formatTime(message.timestamp) }}</time>
        </div>
        <p>{{ message.content }}</p>
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
      </div>
    </article>

    <article v-if="loading" class="chat-message assistant">
      <div class="message-avatar">AI</div>
      <div class="message-bubble loading-bubble">
        <span></span>
        <span></span>
        <span></span>
      </div>
    </article>
  </section>
</template>

<script>
export default {
  name: "ChatMessageList",
  props: {
    messages: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  methods: {
    formatTime(value) {
      if (!value) {
        return "";
      }
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) {
        return "";
      }
      return date.toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false
      });
    }
  }
};
</script>
