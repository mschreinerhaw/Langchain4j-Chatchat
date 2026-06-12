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

      <div v-if="pendingMcpConfirmation" class="mcp-confirm-backdrop" @click.self="cancelMcpConfirmation">
        <section class="mcp-confirm-dialog" role="dialog" aria-modal="true" aria-label="MCP tool confirmation">
          <header>
            <div>
              <p>MCP Policy Check</p>
              <h2>Confirm tool execution</h2>
            </div>
            <button type="button" class="dialog-close" :disabled="loading" @click="cancelMcpConfirmation">x</button>
          </header>
          <dl>
            <div>
              <dt>Purpose</dt>
              <dd>{{ pendingMcpConfirmation.purpose || "Tool execution requested by agent" }}</dd>
            </div>
            <div>
              <dt>Tool</dt>
              <dd>{{ pendingMcpConfirmation.displayName || pendingMcpConfirmation.toolName }}</dd>
            </div>
            <div>
              <dt>Risk</dt>
              <dd>{{ pendingMcpConfirmation.riskLevel || "unknown" }}</dd>
            </div>
            <div>
              <dt>Data scope</dt>
              <dd>{{ pendingMcpConfirmation.dataScope || "unknown" }}</dd>
            </div>
            <div>
              <dt>Action</dt>
              <dd>{{ pendingMcpConfirmation.operationType || "read" }}</dd>
            </div>
          </dl>
          <pre>{{ formatConfirmationParameters(pendingMcpConfirmation.parameters) }}</pre>
          <label>
            <span>After confirmation</span>
            <select v-model="confirmationRemember">
              <option value="">Allow once</option>
              <option value="tool_auto_execute">Always allow this tool</option>
              <option value="tool_always_confirm">Always confirm this tool</option>
              <option value="tool_deny">Deny this tool</option>
            </select>
          </label>
          <footer>
            <button type="button" class="secondary-button" :disabled="loading" @click="cancelMcpConfirmation">Cancel</button>
            <button type="button" class="danger-button" :disabled="loading" @click="denyMcpConfirmation">Deny</button>
            <button type="button" class="primary-button" :disabled="loading" @click="confirmMcpExecution">Confirm</button>
          </footer>
        </section>
      </div>

    <div class="chat-input-dock">
      <PromptComposer
        v-model="question"
        v-model:selected-agent-id="selectedAgentId"
        :agents="agents"
        :agents-loading="agentsLoading"
        :suggestions="activeSuggestions"
        :loading="composerBusy"
        :stop-available="canKillActiveRun"
        :show-suggestions="!hasConversation && activeSuggestions.length > 0"
        @pick="question = $event"
        @send="handleSend"
        @stop="killActiveRun"
        @clear="clearChat"
        @upload="handleUpload"
        @image-upload="openImageDialog"
      />

      <div v-if="contextImageAnalyses.length" class="image-context-bar">
        <span>图片上下文</span>
        <button
          v-for="item in contextImageAnalyses"
          :key="item.id"
          type="button"
          class="image-context-chip"
          @click="removeImageContext(item.id)"
        >
          {{ formatImageType(item.imageType) }} · {{ formatConfidence(item.confidence) }} ×
        </button>
      </div>

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

      <div v-if="imageDialogOpen" class="chat-upload-backdrop" @click.self="closeImageDialog">
        <form class="chat-upload-dialog image-understanding-dialog" @submit.prevent="uploadAndAnalyzeImage">
          <header>
            <div>
              <p>多模态输入</p>
              <h2>上传图片并解析</h2>
            </div>
            <button type="button" class="dialog-close" :disabled="uploadingImage" @click="closeImageDialog">x</button>
          </header>

          <div class="chat-file-picker">
            <input
              ref="chatImageFile"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              @change="handleImageFileChange"
            >
            <button type="button" class="file-picker-button" :disabled="uploadingImage" @click="triggerImageFilePicker">选择图片</button>
            <span>{{ imageForm.file?.name || "未选择图片，最大 10MB" }}</span>
          </div>

          <select v-model="imageForm.mode" :disabled="uploadingImage">
            <option v-for="option in imageModeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <textarea
            v-model="imageForm.question"
            rows="3"
            :disabled="uploadingImage"
            placeholder="可选：你希望 Agent 重点看什么？"
          ></textarea>

          <section v-if="pendingImageAnalysis" class="image-analysis-preview">
            <div class="image-analysis-summary">
              <strong>{{ formatImageType(pendingImageAnalysis.imageType) }}</strong>
              <span>置信度 {{ formatConfidence(pendingImageAnalysis.confidence) }}</span>
            </div>
            <p>{{ pendingImageAnalysis.summary }}</p>
            <pre>{{ pendingImageAnalysis.extractedText }}</pre>
          </section>

          <p v-if="imageUploadError" class="chat-error">{{ imageUploadError }}</p>

          <footer>
            <button type="button" class="secondary-button" :disabled="uploadingImage" @click="closeImageDialog">取消</button>
            <button
              v-if="pendingImageAnalysis"
              type="button"
              class="primary-button"
              :disabled="uploadingImage"
              @click="confirmImageContext"
            >
              加入上下文
            </button>
            <button v-else type="submit" class="primary-button" :disabled="uploadingImage">
              {{ uploadingImage ? "解析中" : "上传并解析" }}
            </button>
          </footer>
        </form>
      </div>

      <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
    </div>
  </div>
</template>

<script src="../js/views/ChatAssistantView.js"></script>
