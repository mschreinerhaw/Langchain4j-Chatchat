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
    <p v-if="uploadNotice" class="chat-status-notice">{{ uploadNotice }}</p>
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

      <div v-if="uploadDialogOpen" class="chat-upload-backdrop" @click.self="closeUploadDialog">
        <form class="chat-upload-dialog" @submit.prevent="uploadChatDocument">
          <header>
            <div>
              <p>文档上传</p>
              <h2>上传到文档库</h2>
            </div>
            <button type="button" class="dialog-close" :disabled="uploadingDocument" @click="closeUploadDialog">×</button>
          </header>

          <div class="chat-file-picker">
            <input
              ref="chatUploadFile"
              type="file"
              accept=".txt,.md,.csv,.pdf,.doc,.docx,.xls,.xlsx"
              @change="handleUploadFileChange"
            >
            <button type="button" class="file-picker-button" @click="triggerUploadFilePicker">选择文件</button>
            <span>{{ uploadForm.file?.name || "未选择文件，最大 5MB" }}</span>
          </div>

          <input v-model="uploadForm.title" placeholder="文档标题">
          <input v-model="uploadForm.source" placeholder="文档来源">
          <input v-model="uploadForm.date" type="date">
          <select v-model="uploadForm.documentType">
            <option v-for="option in documentTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <input v-model="uploadForm.tags" placeholder="标签，多个用逗号分隔">

          <label class="chat-upload-agent-toggle" :class="{ disabled: !selectedAgentId }">
            <input
              v-model="uploadForm.enableForAgent"
              type="checkbox"
              :disabled="!selectedAgentId"
            >
            <span>{{ selectedAgentId ? "启用到当前 Agent" : "选择 Agent 后可自动启用" }}</span>
          </label>

          <p v-if="uploadError" class="chat-error">{{ uploadError }}</p>

          <footer>
            <button type="button" class="secondary-button" :disabled="uploadingDocument" @click="closeUploadDialog">取消</button>
            <button type="submit" class="primary-button" :disabled="uploadingDocument">
              {{ uploadingDocument ? "上传中" : "上传文档" }}
            </button>
          </footer>
        </form>
      </div>

      <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
    </div>
  </div>
</template>

<script src="../js/views/ChatAssistantView.js"></script>
