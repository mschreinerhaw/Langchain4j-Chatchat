<template>
  <section class="prompt-card" @dragover="handleDragOver" @drop="handleDrop">
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
        placeholder="输入问题，拖拽或粘贴分析附件，Shift + Enter 换行"
        @input="adjustTextareaHeight"
        @paste="handlePaste"
        @keydown.enter.exact="handleEnter"
      ></textarea>
      <p v-if="longText" class="composer-file-notice"
        >超过 8,000 字，发送时将完整内容保存为 Markdown 文件并分析。</p
      >
      <ul v-if="attachments.length" class="composer-attachments" aria-label="本次分析附件">
        <li v-for="file in attachments" :key="file.clientId">
          <span
            >{{ file.name }} ·
            {{
              file.state === "uploading"
                ? "正在保存到用户空间"
                : file.state === "failed"
                  ? "上传失败"
                  : "已保存到用户空间"
            }}</span
          >
          <span v-if="file.error" role="alert">{{ file.error }}</span>
          <button
            v-if="file.state === 'failed'"
            type="button"
            :disabled="loading"
            @click="$emit('retry-file', file.clientId)"
            >重试</button
          >
          <button
            type="button"
            :disabled="loading"
            aria-label="移除附件"
            @click="$emit('remove-file', file.clientId)"
            >×</button
          >
        </li>
      </ul>

      <div class="composer-toolbar">
        <div class="composer-toolbar-group mode-group">
          <span class="toolbar-group-label">当前模式</span>
          <label class="agent-picker" :class="{ active: selectedAgentId, loading: agentsLoading }">
            <Bot class="tool-icon" :size="16" stroke-width="1.9" />
            <span>{{ agentSelectLabel }}</span>
            <select
              :value="selectedAgentId"
              :disabled="agentsLoading"
              @change="updateSelectedAgent"
            >
              <option v-for="agent in agentOptions" :key="agent.id || 'general'" :value="agent.id">
                {{ agent.name }}{{ agent.documentWorkflow ? " · 文档工作流" : "" }}
              </option>
            </select>
          </label>
        </div>
        <button
          type="button"
          role="switch"
          aria-label="图文报告"
          :aria-checked="reportMode === 'visual'"
          class="tool-button visual-report-toggle"
          :class="{ active: reportMode === 'visual' }"
          :disabled="loading"
          :title="reportMode === 'visual' ? '图文报告已开启，点击关闭' : '开启图文报告'"
          @click="reportMode = reportMode === 'visual' ? 'off' : 'visual'"
          ><FileText :size="14" stroke-width="2" />图文报告</button
        >
        <div v-if="documentWorkflowActive" class="composer-toolbar-group tool-group">
          <span v-if="documentWorkflowActive" class="workflow-badge">
            <FileText :size="14" stroke-width="2" />
            文档工作流
          </span>
        </div>
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
<style scoped src="../styles/components/prompt-composer.css"></style>
