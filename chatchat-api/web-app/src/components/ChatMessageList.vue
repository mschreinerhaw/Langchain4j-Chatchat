<template>
  <section class="message-panel" aria-live="polite">
    <article
      v-for="message in messages"
      :key="message.id"
      class="chat-message"
      :class="[message.role, { streaming: message.streaming, 'streaming-has-content': message.streaming && message.content }]"
    >
      <div class="message-avatar">{{ message.role === "user" ? userAvatarLabel : "AI" }}</div>
      <div class="message-bubble">
        <div class="message-meta">
          <strong>{{ message.role === "user" ? displayUserId : assistantDisplayName }}</strong>
          <div class="message-actions">
            <time>{{ formatTime(message.timestamp) }}</time>
            <button
              v-if="message.role === 'assistant' && message.content"
              type="button"
              class="message-copy-button"
              :title="copiedMessageId === message.id ? '已复制' : '复制回答'"
              :aria-label="copiedMessageId === message.id ? '已复制回答' : '复制回答内容'"
              @click="copyMessage(message)"
            >
              <Check v-if="copiedMessageId === message.id" :size="14" stroke-width="2.4" />
              <Copy v-else :size="14" stroke-width="2.2" />
            </button>
          </div>
        </div>
        <div v-if="message.role === 'assistant' && message.streaming && !message.content" class="analysis-progress">
          <strong>正在分析{{ activeAgent?.name ? `：${activeAgent.name}` : "" }}</strong>
          <div>
            <span class="done">获取业务上下文</span>
            <span class="done">匹配可用工具</span>
            <span class="active">生成分析结论</span>
          </div>
        </div>
        <div v-else class="message-markdown" v-html="renderMarkdown(message.content, message)"></div>
        <ResponseReferences
          v-if="message.role === 'assistant' && !message.streaming"
          :sources="message.sources || []"
          :tool-traces="message.traces || []"
          compact
        />
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
      </div>
    </article>

    <article v-if="loading && !hasStreamingMessage" class="chat-message assistant thinking-message">
      <div class="message-avatar">AI</div>
      <div class="message-bubble loading-bubble" aria-label="AI投资助手正在思考">
        <div class="thinking-brief" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div class="thinking-copy">
          <strong>AI投资助手正在梳理分析</strong>
          <small>研读市场信息、校验依据并组织结论</small>
          <div class="thinking-steps" aria-hidden="true">
            <span>理解问题</span>
            <span>检索依据</span>
            <span>形成观点</span>
          </div>
        </div>
      </div>
    </article>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
