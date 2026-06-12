<template>
  <section class="prompt-card">
    <div v-if="showSuggestions" class="prompt-suggestions">
      <span class="prompt-suggestions-label">快捷问题</span>
      <button
        v-for="suggestion in suggestions"
        :key="suggestion"
        type="button"
        @click="$emit('pick', suggestion)"
      >
        {{ suggestion }}
      </button>
    </div>

    <form class="composer-box" @submit.prevent="handlePrimaryAction">
      <textarea
        ref="composerTextarea"
        v-model="draft"
        rows="2"
        :disabled="loading"
        placeholder="输入问题，或使用 @ 调用能力，Shift + Enter 换行"
        @input="adjustTextareaHeight"
        @keydown.enter.exact.prevent="send"
      ></textarea>

      <div class="composer-toolbar">
        <div class="composer-toolbar-group mode-group">
          <span class="toolbar-group-label">当前模式</span>
          <label class="agent-picker" :class="{ active: selectedAgentId, loading: agentsLoading }">
            <Bot class="tool-icon" :size="16" stroke-width="1.9" />
            <span>{{ agentSelectLabel }}</span>
            <select :value="selectedAgentId" :disabled="agentsLoading" @change="updateSelectedAgent">
              <option v-for="agent in agentOptions" :key="agent.id || 'general'" :value="agent.id">
                {{ agent.name }}{{ agent.documentWorkflow ? " · 文档工作流" : "" }}
              </option>
            </select>
          </label>
        </div>
        <div class="composer-toolbar-group tool-group">
          <span class="toolbar-group-label">工具</span>
          <span v-if="documentWorkflowActive" class="workflow-badge">
            <FileText :size="14" stroke-width="2" />
            文档工作流
          </span>
          <button
            type="button"
            class="tool-button"
            :class="{ active: webSearch, disabled: !webSearchAvailable }"
            :disabled="!webSearchAvailable"
            :title="webSearchAvailable ? '联网搜索' : '当前 Agent 未勾选 web_search'"
            @click="toggleWebSearch"
          >
            <Globe class="tool-icon" :size="16" stroke-width="1.9" />
            联网搜索
          </button>
          <button type="button" class="tool-button" @click="$emit('upload')">
            <Upload class="tool-icon" :size="16" stroke-width="1.9" />
            上传文件
          </button>
          <button type="button" class="tool-button" @click="$emit('image-upload')">
            <ImagePlus class="tool-icon" :size="16" stroke-width="1.9" />
            上传图片
          </button>
        </div>
        <button type="button" class="tool-button clear" @click="$emit('clear')">
          <Trash2 class="tool-icon" :size="16" stroke-width="1.9" />
          新建对话
        </button>
      </div>

      <button
        class="send-button"
        :class="{ stopping: loading && stopAvailable }"
        type="submit"
        :aria-label="primaryButtonLabel"
        :title="primaryButtonLabel"
        :disabled="primaryButtonDisabled"
      >
        <XCircle v-if="loading && stopAvailable" :size="18" stroke-width="2.3" />
        <Send v-else :size="18" stroke-width="2" />
      </button>
    </form>
  </section>
</template>

<script src="../js/components/PromptComposer.js"></script>
