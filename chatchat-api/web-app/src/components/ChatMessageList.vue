<template>
  <section class="message-panel" aria-live="polite">
    <article
      v-for="message in messages"
      :key="message.id"
      class="chat-message"
      :class="[
        message.role,
        {
          streaming: message.streaming,
          executing: isExecutionRunning(message),
          'streaming-has-content': message.streaming && message.content
        }
      ]"
    >
      <div class="message-avatar">{{ message.role === "user" ? userAvatarLabel : "AI" }}</div>
      <div class="message-bubble">
        <div class="message-meta">
          <strong>{{ message.role === "user" ? displayUserId : assistantName(message) }}</strong>
          <div class="message-actions">
            <time>{{ formatTime(message.timestamp) }}</time>
            <button
              v-if="messageHasRenderableContent(message)"
              type="button"
              class="message-copy-button"
              :data-copied="copiedMessageId === message.id ? 'true' : undefined"
              :title="copiedMessageId === message.id ? '已复制' : '复制回答'"
              :aria-label="copiedMessageId === message.id ? '已复制回答' : '复制回答内容'"
              @click="copyMessage(message)"
            >
              <Check v-if="copiedMessageId === message.id" :size="14" stroke-width="2.4" />
              <Copy v-else :size="14" stroke-width="2.2" />
            </button>
          </div>
        </div>
        <div
          v-if="shouldShowSteps(message)"
          class="analysis-progress"
          :class="{ compact: !!message.content, running: isExecutionRunning(message) }"
        >
          <strong>
            <span>{{ executionTitle(message) }}</span>
            <i v-if="isExecutionRunning(message)" class="execution-live-indicator" aria-hidden="true"></i>
          </strong>
          <TransitionGroup name="execution-step-list" tag="div">
            <span
              v-for="step in visibleExecutionSteps(message)"
              :key="step.id"
              :class="stepStatusClass(step)"
              :aria-current="step.status === 'active' ? 'step' : undefined"
            >
              <b>{{ step.title }}</b>
              <small v-if="step.detail">{{ step.detail }}</small>
            </span>
          </TransitionGroup>
          <div v-if="isExecutionRunning(message)" class="execution-flow-bar" aria-hidden="true"></div>
        </div>
        <div v-else-if="message.role === 'assistant' && message.streaming && !message.content" class="analysis-progress">
          <strong>正在分析{{ activeAgent?.name ? `：${activeAgent.name}` : "" }}</strong>
          <div>
            <span class="done">获取业务上下文</span>
            <span class="done">匹配可用工具</span>
            <span class="active">生成分析结论</span>
          </div>
        </div>
        <div
          v-if="messageHasRenderableContent(message)"
          class="message-markdown"
          v-html="renderMarkdown(message.content, message)"
          @click="handleMarkdownClick"
        ></div>
        <VisualizationRenderer
          v-if="message.role === 'assistant' && message.visualizationSpec && !message.streaming"
          :spec="message.visualizationSpec"
          @drill-down="handleVisualizationDrillDown(message, $event)"
        />
        <div v-if="message.latencyMs" class="message-extra">耗时 {{ message.latencyMs }}ms</div>
        <ResponseReferences
          v-if="message.role === 'assistant' && !message.streaming && !isExecutionRunning(message) && message.status !== 'waiting'"
          :sources="message.sources || []"
          :evidence-premises="message.evidencePremises || []"
          :tool-traces="message.traces || []"
          compact
        />
        <div v-if="canShowEvaluation(message)" class="message-feedback" aria-label="回答评价">
          <button
            v-for="option in feedbackOptions"
            :key="option.value"
            type="button"
            class="message-feedback-button"
            :class="{ unresolved: option.value === 'unresolved' }"
            :disabled="message.feedbackSubmitting"
            :title="`评价为${option.label}`"
            @click="$emit('feedback', { message, action: option.value })"
          >
            <CircleX v-if="option.value === 'unresolved'" :size="16" stroke-width="2.2" />
            <CircleCheck v-else :size="16" stroke-width="2.2" />
            <span>{{ option.label }}</span>
          </button>
        </div>
        <p v-else-if="message.feedbackTime" class="message-feedback-done">已参与评价</p>
        <p v-if="message.feedbackError" class="message-feedback-error">{{ message.feedbackError }}</p>
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

    <div
      v-if="reasoningModal"
      class="reasoning-modal-backdrop"
      role="presentation"
      @click.self="closeReasoningModal"
    >
      <section class="reasoning-modal" role="dialog" aria-modal="true" aria-label="Reasoning path">
        <header>
          <div>
            <span>Reasoning Path</span>
            <h2>{{ reasoningModal.title }}</h2>
          </div>
          <button type="button" aria-label="关闭" @click="closeReasoningModal">×</button>
        </header>

        <div class="reasoning-modal-metrics">
          <span v-for="metric in reasoningModal.metrics" :key="metric.label">
            {{ metric.label }} <b>{{ metric.value }}</b>
          </span>
        </div>

        <section class="reasoning-modal-section">
          <strong>Selected path</strong>
          <div class="reasoning-modal-path">
            <span v-for="(node, index) in reasoningModal.pathNodes" :key="node.id">
              <b>{{ node.id }}</b>
              <small>{{ node.confidence }}</small>
              <i v-if="index < reasoningModal.pathNodes.length - 1">→</i>
            </span>
          </div>
        </section>

        <section class="reasoning-modal-section">
          <strong>Path edges</strong>
          <ul>
            <li v-for="edge in reasoningModal.pathEdges" :key="`${edge.from}-${edge.to}-${edge.type}`">
              <span>{{ edge.from }} → {{ edge.to }}</span>
              <small>{{ edge.type }} · {{ edge.confidence }}</small>
              <p v-if="edge.reasoning">{{ edge.reasoning }}</p>
            </li>
            <li v-if="!reasoningModal.pathEdges.length" class="empty">No path edge available.</li>
          </ul>
        </section>

        <section class="reasoning-modal-section">
          <strong>Conflict resolution</strong>
          <ul>
            <li v-for="item in reasoningModal.conflicts" :key="item.edge">
              <span>{{ item.edge }}</span>
              <small>{{ item.confidence }}</small>
              <p>{{ item.decision }}</p>
            </li>
            <li v-if="!reasoningModal.conflicts.length" class="empty">No conflict evidence participated in the selected path.</li>
          </ul>
        </section>

        <section class="reasoning-modal-section">
          <strong>Decision trace</strong>
          <ul>
            <li v-for="item in reasoningModal.explanation" :key="item">{{ item }}</li>
          </ul>
        </section>
      </section>
    </div>
  </section>
</template>

<script src="../js/components/ChatMessageList.js"></script>
