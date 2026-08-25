<template>
  <div class="chat-view">
    <section v-if="historyDetailsLoading" class="history-detail-state" aria-live="polite">
      <span class="history-detail-spinner" aria-hidden="true"></span>
      <strong>正在加载历史会话</strong>
      <p>正在读取会话消息，请稍候。</p>
    </section>

    <section v-else-if="historyDetailError" class="history-detail-state history-detail-error" role="alert">
      <strong>历史会话加载失败</strong>
      <p>{{ historyDetailError }}。可以再次点击左侧会话重试。</p>
    </section>

    <section v-else-if="!hasConversation" class="hero-panel">
      <div
        class="hero-demo"
        role="img"
        :aria-label="`${heroTitle}能力协同演示：从业务请求到可信结果`"
      >
        <div class="hero-demo-glow" aria-hidden="true"></div>
        <div class="hero-demo-stage">
          <article class="hero-demo-card hero-request-card">
            <div class="hero-demo-card-label">
              <i></i>
              <span>业务请求</span>
            </div>
            <strong>{{ heroDemoPrompt }}</strong>
            <div class="hero-request-lines" aria-hidden="true">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </article>

          <div class="hero-flow hero-flow-in" aria-hidden="true">
            <i></i><i></i><i></i>
          </div>

          <div class="hero-assistant-core">
            <span class="hero-core-ring hero-core-ring-outer" aria-hidden="true"></span>
            <span class="hero-core-ring hero-core-ring-inner" aria-hidden="true"></span>
            <div class="hero-core-mark" :title="heroTitle">
              <span>{{ heroCoreName }}</span>
            </div>
            <small>正在为你处理</small>
          </div>

          <div class="hero-flow hero-flow-out" aria-hidden="true">
            <i></i><i></i><i></i>
          </div>

          <article class="hero-demo-card hero-result-card">
            <div class="hero-demo-card-label">
              <i></i>
              <span>可信结果</span>
              <em>LIVE</em>
            </div>
            <div class="hero-result-steps">
              <div
                v-for="(step, index) in heroDemoSteps"
                :key="`${step}-${index}`"
                class="hero-result-step"
                :style="{ '--step-index': index }"
              >
                <span>{{ index + 1 }}</span>
                <strong>{{ step }}</strong>
                <i aria-hidden="true">✓</i>
              </div>
            </div>
          </article>
        </div>

        <div class="hero-capability-track">
          <span
            v-for="(capability, index) in heroDemoCapabilities"
            :key="`${capability}-${index}`"
            :style="{ '--capability-index': index }"
          >
            <i></i>{{ capability }}
          </span>
        </div>
      </div>
      <h1>{{ heroTitle }}</h1>
      <p>{{ heroIntro }}</p>
      <div v-if="displayAgentResponsibilities.length" class="hero-agent-scope">
        <span>职责</span>
        <strong v-for="item in displayAgentResponsibilities" :key="item" :title="item">{{ item }}</strong>
      </div>
    </section>

    <ChatMessageList
      v-if="!historyDetailsLoading && !historyDetailError && hasConversation"
      ref="messageList"
      :messages="messages"
      :loading="loading"
      :allow-message-delete="!!conversationId && !loading"
      :user-id="userId"
      :active-agent="selectedAgent"
      @feedback="handleMessageFeedback"
      @delete-message="deleteMessage"
      @visualization-drill-down="handleVisualizationDrillDown"
    />
      <p v-if="statusNotice" class="chat-status-notice">{{ statusNotice }}</p>
      <p v-if="uploadNotice" class="chat-status-notice">{{ uploadNotice }}</p>
      <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>

      <div v-if="pendingMcpConfirmation" class="mcp-confirm-backdrop">
        <section class="mcp-confirm-dialog" role="dialog" aria-modal="true" aria-label="MCP tool confirmation">
          <header>
            <div>
              <p>MCP Policy Check</p>
              <h2>Confirm tool execution</h2>
            </div>
            <button type="button" class="dialog-close" :disabled="loading" title="拒绝并结束任务" @click="denyMcpConfirmation">x</button>
          </header>
          <p class="mcp-confirm-timeout">
            该操作需要确认。确认期间任务保持等待状态，剩余 {{ pendingMcpCountdownText }}
          </p>
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
            <button type="button" class="secondary-button" :disabled="loading" @click="denyMcpConfirmation">取消任务</button>
            <button type="button" class="danger-button" :disabled="loading" @click="denyMcpConfirmation">Deny</button>
            <button type="button" class="primary-button" :disabled="loading" @click="confirmMcpExecution">Confirm</button>
          </footer>
        </section>
      </div>

    <div class="chat-input-dock">
      <PromptComposer
        ref="promptComposer"
        v-model="question"
        v-model:selected-agent-id="selectedAgentId"
        :agents="agents"
        :agents-loading="agentsLoading"
        :default-model-name="defaultModelName"
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

      <div v-if="uploadDialogOpen" class="chat-upload-backdrop">
        <form class="chat-upload-dialog" @submit.prevent="uploadChatDocument">
          <header>
            <div>
              <p>文档上传</p>
              <h2>上传到文档库</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="uploadingDocument" @click="closeUploadDialog">×</button>
          </header>

          <div class="chat-file-picker">
            <input
              ref="chatUploadFile"
              type="file"
              multiple
              accept=".txt,.md,.sql,.csv,.pdf,.doc,.docx,.xls,.xlsx"
              @change="handleUploadFileChange"
            >
            <button type="button" class="file-picker-button" @click="triggerUploadFilePicker">选择文件</button>
            <span>{{ uploadForm.files?.length > 1 ? `${uploadForm.files.length} 个文件` : (uploadForm.file?.name || "未选择文件，单文件最大 55MB") }}</span>
          </div>
          <p class="chat-upload-size-tip">超过 5MB 的文档仅支持单文件上传，后台将按 5MB 分片处理后建立索引。</p>

          <input v-if="(uploadForm.files?.length || 0) <= 1" v-model="uploadForm.title" placeholder="文档标题">
          <input v-model="uploadForm.source" placeholder="文档来源">
          <section class="upload-category-field">
            <div class="upload-category-mode" aria-label="分类方式">
              <button
                type="button"
                :class="{ active: uploadForm.categoryMode === 'existing' }"
                :disabled="uploadingDocument || uploadCategoryOptions.length === 0"
                @click="uploadForm.categoryMode = 'existing'"
              >
                已有分类
              </button>
              <button
                type="button"
                :class="{ active: uploadForm.categoryMode === 'custom' }"
                :disabled="uploadingDocument"
                @click="uploadForm.categoryMode = 'custom'"
              >
                新建分类
              </button>
            </div>
            <select
              v-if="uploadForm.categoryMode === 'existing'"
              v-model="uploadForm.category"
              required
              :disabled="uploadingDocument || uploadCategoriesLoading"
            >
              <option value="" disabled>{{ uploadCategoriesLoading ? "正在加载分类" : "选择已有分类" }}</option>
              <option
                v-for="category in uploadCategoryOptions"
                :key="category.name"
                :value="category.name"
              >
                {{ category.name }}
              </option>
            </select>
            <input
              v-else
              v-model.trim="uploadForm.newCategory"
              required
              placeholder="输入新分类名称"
            >
          </section>
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
          <p v-if="uploadNotice" class="chat-upload-notice">{{ uploadNotice }}</p>

          <footer>
            <button v-if="uploadingDocument" type="button" class="secondary-button" @click="terminateDocumentUpload">终止上传</button>
            <button type="submit" class="primary-button" :disabled="uploadingDocument">
              {{ uploadingDocument ? "上传中" : "上传文档" }}
            </button>
          </footer>
        </form>
      </div>

      <div v-if="imageDialogOpen" class="chat-upload-backdrop">
        <form class="chat-upload-dialog image-understanding-dialog" @submit.prevent="uploadAndAnalyzeImage">
          <header>
            <div>
              <p>多模态输入</p>
              <h2>使用当前模型分析图片</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="uploadingImage" @click="closeImageDialog">×</button>
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
              <span>{{ formatImageAnalysisSource(pendingImageAnalysis.analysisSource) }} · 置信度 {{ formatConfidence(pendingImageAnalysis.confidence) }}</span>
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
              {{ uploadingImage ? "分析中" : "上传并分析" }}
            </button>
          </footer>
        </form>
      </div>

      <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
    </div>
  </div>
</template>

<script src="../js/views/ChatAssistantView.js"></script>
