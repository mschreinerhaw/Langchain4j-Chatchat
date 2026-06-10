import ChatMessageList from "../../components/ChatMessageList.vue";
import PromptComposer from "../../components/PromptComposer.vue";
import {
  apiRequest,
  fetchAgentWorkshop,
  saveConversationHistory,
  sendInteractionMessage,
  sendInteractionMessageStream,
  updateWorkshopAgent,
  uploadSearchDocument
} from "../../services/api";
import "../../styles/pages/chat-assistant.css";

const EMPTY_RESPONSE = {
  sources: [],
  toolTraces: []
};
const AGENT_TASK_POLL_TIMEOUT_MS = 1200;
const AGENT_TASK_MAX_WAIT_MS = 300000;
const MAX_UPLOAD_SIZE = 5 * 1024 * 1024;

function uid() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function normalizeStatus(value, messages = []) {
  if (value) {
    return String(value).toLowerCase();
  }
  if (messages.some((message) => message.streaming || message.status === "streaming" || message.status === "running")) {
    return "running";
  }
  const lastMessage = messages[messages.length - 1];
  return lastMessage?.role === "user" ? "pending" : "completed";
}

function normalizeMessages(messages, status = "") {
  if (!Array.isArray(messages)) {
    return [];
  }
  const allowEmptyAssistant = status === "running" || status === "pending";
  return messages
    .filter((message) => {
      if (!message || !message.role) {
        return false;
      }
      return !!message.content || !!message.streaming || (allowEmptyAssistant && message.role === "assistant");
    })
    .map((message) => ({
      id: message.id || uid(),
      role: message.role,
      content: message.content || "",
      timestamp: message.timestamp || Date.now(),
      sources: Array.isArray(message.sources) ? message.sources : [],
      traces: Array.isArray(message.traces) ? message.traces : [],
      streaming: !!message.streaming || message.status === "streaming" || message.status === "running",
      status: message.status || (message.streaming ? "streaming" : "completed")
    }));
}

function isRunningStatus(status) {
  return status === "running" || status === "pending" || status === "streaming";
}

function shouldFallbackToDirect(error) {
  return /请求失败：(404|405)|Request failed:\s*(404|405)/.test(error?.message || "");
}

function sleep(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function todayString() {
  return new Date().toISOString().slice(0, 10);
}

function inferDocumentType(fileName = "") {
  const extension = fileName.includes(".") ? fileName.split(".").pop().toLowerCase() : "";
  if (extension === "pdf") {
    return "pdf";
  }
  if (["doc", "docx"].includes(extension)) {
    return "word";
  }
  if (["xls", "xlsx", "csv"].includes(extension)) {
    return "excel";
  }
  if (extension === "md") {
    return "markdown";
  }
  return "text";
}

function defaultUploadForm() {
  return {
    file: null,
    title: "",
    source: "智能对话",
    date: todayString(),
    tags: "",
    documentType: "auto",
    enableForAgent: true
  };
}

function uniqueList(values) {
  return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))];
}

function parseJsonPayload(value) {
  if (!value) {
    return {};
  }
  if (typeof value === "object") {
    return value;
  }
  try {
    return JSON.parse(value);
  } catch (error) {
    return { message: String(value) };
  }
}

function submitAgentTask(payload) {
  return apiRequest("/agent/tasks", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

function pollAgentTaskResult(taskId, timeoutMs = 1200, tenantId = "") {
  const params = new URLSearchParams();
  params.set("timeoutMs", String(timeoutMs));
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/result?${params.toString()}`);
}

export default {
  name: "ChatAssistantView",
  components: {
    ChatMessageList,
    PromptComposer
  },
  props: {
    selectedConversation: {
      type: Object,
      default: null
    },
    userId: {
      type: String,
      default: "mx_48991534"
    }
  },
  emits: ["conversation-active", "history-saved"],
  data() {
    return {
      question: "",
      loading: false,
      errorMessage: "",
      historyId: "",
      conversationId: "",
      conversationStatus: "completed",
      messages: [],
      lastResponse: { ...EMPTY_RESPONSE },
      suggestions: [],
      agents: [],
      agentsLoading: false,
      selectedAgentId: "",
      activeRunId: "",
      runningContexts: {},
      restoredRunning: false,
      uploadDialogOpen: false,
      uploadingDocument: false,
      uploadError: "",
      uploadNotice: "",
      uploadForm: defaultUploadForm(),
      documentTypeOptions: [
        { value: "auto", label: "自动识别" },
        { value: "pdf", label: "PDF" },
        { value: "word", label: "Word" },
        { value: "excel", label: "Excel" },
        { value: "markdown", label: "Markdown" },
        { value: "text", label: "文本" }
      ]
    };
  },
  computed: {
    hasConversation() {
      return this.messages.length > 0 || this.loading;
    },
    statusNotice() {
      if (!this.hasConversation || this.loading) {
        return "";
      }
      if (isRunningStatus(this.conversationStatus)) {
        return "";
      }
      if (this.conversationStatus === "failed") {
        return "该历史会话上次请求失败，可继续输入或重新提问。";
      }
      return "";
    },
    selectedAgent() {
      return this.agents.find((agent) => agent.id === this.selectedAgentId) || null;
    },
    heroGreeting() {
      return this.selectedAgent?.firstUseGreeting || `下午好，${this.userId}`;
    },
    heroDescription() {
      if (this.selectedAgent) {
        return `${this.selectedAgent.name}已就绪，可基于配置的业务场景开展分析。`;
      }
      return "AI文档助手已就绪，可基于文档内容进行检索、分析并提供建议。";
    },
    heroQuickQuestions() {
      return Array.isArray(this.selectedAgent?.quickQuestions)
        ? this.selectedAgent.quickQuestions.filter(Boolean).slice(0, 6)
        : [];
    },
    agentResponsibilities() {
      if (!this.selectedAgent) {
        return [];
      }
      const scenarios = Array.isArray(this.selectedAgent.usageScenarios) ? this.selectedAgent.usageScenarios : [];
      const tags = Array.isArray(this.selectedAgent.skillTags) ? this.selectedAgent.skillTags : [];
      return [...scenarios, ...tags].filter(Boolean).slice(0, 3);
    },
    activeSuggestions() {
      return this.heroQuickQuestions.length ? this.heroQuickQuestions : this.suggestions;
    },
    composerBusy() {
      return this.loading || isRunningStatus(this.conversationStatus);
    }
  },
  mounted() {
    this.loadAgents();
  },
  watch: {
    selectedConversation(conversation) {
      if (conversation) {
        this.restoreConversation(conversation);
      }
    }
  },
  methods: {
    async handleSend(payload) {
      const query = typeof payload === "string" ? payload.trim() : payload?.query?.trim();
      if (!query || this.loading) {
        return;
      }

      if (!this.historyId) {
        this.historyId = uid();
      }
      if (!this.conversationId) {
        this.conversationId = this.historyId;
      }

      this.errorMessage = "";
      this.conversationStatus = "running";
      this.messages.push({
        id: uid(),
        role: "user",
        content: query,
        timestamp: Date.now(),
        status: "completed",
        streaming: false
      });
      const runContext = this.createRunContext(query);
      this.runningContexts[runContext.historyId] = runContext;
      this.question = "";
      this.loading = true;
      this.activeRunId = runContext.runId;
      this.emitActiveConversationSnapshot(query, "running", runContext);
      this.scrollMessages();
      await this.saveHistory(query, "running", runContext);

      const requestPayload = {
        conversationId: this.conversationId || undefined,
        userId: this.userId,
        mode: this.selectedAgentId || payload?.webSearch ? "agent_chat" : "llm_chat",
        skillId: this.selectedAgentId || undefined,
        modelName: this.selectedAgent?.modelName || undefined,
        query,
        maxResults: 10,
        historyWindow: 8,
        stream: true,
        availableTools: !this.selectedAgentId && payload?.webSearch ? ["web_search"] : [],
        toolInput: {
          agentName: payload?.agentName || "",
          documentWorkflow: !!payload?.documentWorkflow,
          webSearch: !!payload?.webSearch
        }
      };

      try {
        await this.requestAssistantAnswer(query, requestPayload, runContext);
        runContext.status = "completed";
        if (this.isActiveRun(runContext)) {
          this.conversationStatus = "completed";
        }
        this.emitActiveConversationSnapshot(query, "completed", runContext);
        await this.saveHistory(query, "completed", runContext);
      } catch (error) {
        const message = error.message || "请求后端失败";
        runContext.status = "failed";
        if (this.isActiveRun(runContext)) {
          this.errorMessage = message;
          this.conversationStatus = "failed";
        }
        this.emitActiveConversationSnapshot(query, "failed", runContext);
        await this.saveHistory(query, "failed", runContext);
      } finally {
        delete this.runningContexts[runContext.historyId];
        if (this.isActiveRun(runContext)) {
          this.loading = false;
          this.activeRunId = "";
          this.scrollMessages();
        }
      }
    },
    async requestAssistantAnswerLegacy(query, requestPayload, runContext) {
      let assistantMessage = null;

      const ensureStreamingMessage = () => {
        if (assistantMessage) {
          return assistantMessage;
        }
        assistantMessage = this.createAssistantMessage({
          content: "",
          streaming: true,
          status: "streaming"
        });
        runContext.messages.push(assistantMessage);
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        return assistantMessage;
      };

      try {
        const result = await sendInteractionMessageStream(requestPayload, {
          start: () => {
            ensureStreamingMessage();
          },
          meta: (metadata) => {
            this.applyResponseMetadata(metadata, runContext);
            if (assistantMessage) {
              assistantMessage.timestamp = metadata.timestamp || Date.now();
              assistantMessage.latencyMs = metadata.latencyMs;
              assistantMessage.sources = runContext.lastResponse.sources;
              assistantMessage.traces = runContext.lastResponse.toolTraces;
            }
            this.emitActiveConversationSnapshot(query, "running", runContext);
          },
          delta: (payload) => {
            const message = ensureStreamingMessage();
            message.content += typeof payload === "string" ? payload : payload?.content || "";
            message.streaming = true;
            message.status = "streaming";
            this.emitActiveConversationSnapshot(query, "running", runContext);
            if (this.isActiveRun(runContext)) {
              this.scrollMessages();
            }
          },
          direct: (response) => {
            this.appendDirectAssistantMessage(response, runContext);
          },
          error: (payload) => {
            throw new Error(payload?.message || "流式请求失败");
          }
        });

        if (result?.type === "stream" && assistantMessage) {
          assistantMessage.streaming = false;
          assistantMessage.status = "completed";
        }
      } catch (error) {
        if (assistantMessage) {
          assistantMessage.streaming = false;
          assistantMessage.status = "failed";
        }
        if (!shouldFallbackToDirect(error)) {
          throw error;
        }
        const response = await sendInteractionMessage({
          ...requestPayload,
          stream: false
        });
        this.appendDirectAssistantMessage(response, runContext);
      }
    },
    async requestAssistantAnswer(query, requestPayload, runContext) {
      const assistantMessage = this.createAssistantMessage({
        content: "",
        streaming: true,
        status: "streaming"
      });
      runContext.messages.push(assistantMessage);
      if (this.isActiveRun(runContext)) {
        this.messages = runContext.messages;
        this.scrollMessages();
      }

      const task = await submitAgentTask({
        tenantId: this.userId,
        userId: requestPayload.userId,
        agentId: requestPayload.skillId || "general",
        sessionId: requestPayload.conversationId,
        query,
        mode: requestPayload.mode,
        skillId: requestPayload.skillId,
        modelName: requestPayload.modelName,
        maxResults: requestPayload.maxResults,
        historyWindow: requestPayload.historyWindow,
        stream: false,
        availableTools: requestPayload.availableTools || [],
        toolInput: requestPayload.toolInput || {}
      });
      runContext.taskId = task?.taskId || "";

      const event = await this.waitForAgentTaskResult(runContext.taskId, this.userId);
      const eventPayload = parseJsonPayload(event?.payload);
      const eventType = String(event?.type || "").toUpperCase();
      const eventStatus = String(event?.status || "").toUpperCase();

      if (eventType === "ERROR" || eventStatus === "FAILED") {
        const message = eventPayload.message || "Agent task failed";
        assistantMessage.content = message;
        assistantMessage.streaming = false;
        assistantMessage.status = "failed";
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        throw new Error(message);
      }

      const response = eventPayload || {};
      this.applyResponseMetadata(response, runContext);
      assistantMessage.content = response.answer || "No response generated";
      assistantMessage.timestamp = response.timestamp || event?.createTime || Date.now();
      assistantMessage.latencyMs = response.latencyMs;
      assistantMessage.sources = runContext.lastResponse.sources;
      assistantMessage.traces = runContext.lastResponse.toolTraces;
      assistantMessage.streaming = false;
      assistantMessage.status = "completed";
      if (this.isActiveRun(runContext)) {
        this.messages = runContext.messages;
        this.scrollMessages();
      }
      this.emitActiveConversationSnapshot(query, "running", runContext);
    },
    async waitForAgentTaskResult(taskId, tenantId = "") {
      if (!taskId) {
        throw new Error("Agent task was not created");
      }
      const deadline = Date.now() + AGENT_TASK_MAX_WAIT_MS;
      while (Date.now() < deadline) {
        const event = await pollAgentTaskResult(taskId, AGENT_TASK_POLL_TIMEOUT_MS, tenantId);
        const type = String(event?.type || "").toUpperCase();
        const status = String(event?.status || "").toUpperCase();
        if (type === "ANSWER" || type === "ERROR" || status === "SUCCESS" || status === "FAILED") {
          return event;
        }
        await sleep(300);
      }
      throw new Error("Agent task polling timed out");
    },
    appendDirectAssistantMessage(response, runContext = null) {
      const targetContext = runContext || this.createRunContext("");
      this.applyResponseMetadata(response, targetContext);
      targetContext.messages.push(
        this.createAssistantMessage({
          content: response?.answer || "服务端没有返回内容。",
          timestamp: response?.timestamp || Date.now(),
          latencyMs: response?.latencyMs,
          sources: targetContext.lastResponse.sources,
          traces: targetContext.lastResponse.toolTraces,
          streaming: false,
          status: "completed"
        })
      );
      if (this.isActiveRun(targetContext)) {
        this.messages = targetContext.messages;
        this.scrollMessages();
      }
    },
    createAssistantMessage(overrides = {}) {
      return {
        id: uid(),
        role: "assistant",
        content: "",
        timestamp: Date.now(),
        latencyMs: undefined,
        sources: [],
        traces: [],
        streaming: false,
        status: "completed",
        ...overrides
      };
    },
    createRunContext(question) {
      return {
        runId: uid(),
        historyId: this.historyId,
        question,
        conversationId: this.conversationId,
        selectedAgentId: this.selectedAgentId || "",
        agentName: this.selectedAgent?.name || "",
        mode: this.selectedAgentId ? "agent_chat" : "llm_chat",
        status: this.conversationStatus,
        messages: this.messages,
        lastResponse: { ...EMPTY_RESPONSE }
      };
    },
    isActiveRun(context) {
      return !!context && this.activeRunId === context.runId && this.historyId === context.historyId;
    },
    serializeMessages(messages = []) {
      return messages.map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
        timestamp: message.timestamp,
        sources: message.sources || [],
        traces: message.traces || [],
        streaming: !!message.streaming,
        status: message.status || (message.streaming ? "streaming" : "completed")
      }));
    },
    ensureRunningAssistantMessage(messages, status) {
      if (!isRunningStatus(status)) {
        return messages;
      }
      if (messages.some((message) => message.role === "assistant" && message.streaming)) {
        return messages;
      }
      return [
        ...messages,
        this.createAssistantMessage({
          content: "",
          streaming: true,
          status: "running",
          restored: true
        })
      ];
    },
    applyResponseMetadata(response = {}, runContext = null) {
      const nextResponse = {
        sources: Array.isArray(response.sources) ? response.sources : [],
        toolTraces: Array.isArray(response.toolTraces) ? response.toolTraces : []
      };
      if (runContext) {
        const previousHistoryId = runContext.historyId;
        const activeRun = this.isActiveRun(runContext);
        runContext.conversationId = response.conversationId || runContext.conversationId;
        if (response.conversationId && runContext.historyId !== response.conversationId) {
          runContext.historyId = response.conversationId;
          if (this.runningContexts[previousHistoryId] === runContext) {
            delete this.runningContexts[previousHistoryId];
            this.runningContexts[runContext.historyId] = runContext;
          }
        }
        runContext.lastResponse = nextResponse;
        if (activeRun) {
          this.conversationId = runContext.conversationId || this.conversationId;
          this.historyId = runContext.historyId || this.historyId;
          this.lastResponse = nextResponse;
        }
        return;
      }
      this.conversationId = response.conversationId || this.conversationId;
      this.historyId = this.conversationId || this.historyId;
      this.lastResponse = nextResponse;
    },
    clearChat() {
      if (this.loading) {
        const lastUserMessage = [...this.messages].reverse().find((message) => message.role === "user");
        const backgroundContext = this.runningContexts[this.historyId];
        if (backgroundContext) {
          this.emitActiveConversationSnapshot(backgroundContext.question || lastUserMessage?.content || "", "running", backgroundContext);
        }
        this.activeRunId = "";
        this.loading = false;
      }
      this.question = "";
      this.messages = [];
      this.historyId = "";
      this.conversationId = "";
      this.conversationStatus = "completed";
      this.errorMessage = "";
      this.uploadNotice = "";
      this.lastResponse = { ...EMPTY_RESPONSE };
      this.restoredRunning = false;
      this.$emit("conversation-active", null);
    },
    async loadAgents() {
      this.agentsLoading = true;
      try {
        const payload = await fetchAgentWorkshop({ status: "published" });
        const agents = Array.isArray(payload?.agents) ? payload.agents : [];
        this.agents = agents
          .filter((agent) => agent?.id && agent.marketStatus === "published")
          .sort((left, right) => {
            return String(left.name || left.id).localeCompare(String(right.name || right.id), "zh-CN");
          });
        if (this.selectedAgentId && !this.agents.some((agent) => agent.id === this.selectedAgentId)) {
          this.selectedAgentId = "";
        }
      } catch (error) {
        this.errorMessage = error.message || "Agent列表加载失败";
      } finally {
        this.agentsLoading = false;
      }
    },
    handleUpload() {
      this.uploadError = "";
      this.uploadNotice = "";
      this.uploadForm = {
        ...this.uploadForm,
        date: this.uploadForm.date || todayString(),
        enableForAgent: !!this.selectedAgentId
      };
      this.uploadDialogOpen = true;
    },
    closeUploadDialog() {
      if (this.uploadingDocument) {
        return;
      }
      this.uploadDialogOpen = false;
      this.uploadError = "";
    },
    triggerUploadFilePicker() {
      this.$refs.chatUploadFile?.click();
    },
    handleUploadFileChange(event) {
      const file = event.target.files?.[0] || null;
      this.uploadError = "";
      if (file && file.size > MAX_UPLOAD_SIZE) {
        this.uploadForm.file = null;
        event.target.value = "";
        this.uploadError = "文档文件不能超过 5MB";
        return;
      }
      this.uploadForm.file = file;
      if (file && !this.uploadForm.title) {
        this.uploadForm.title = file.name.replace(/\.[^.]+$/, "");
      }
      if (file) {
        this.uploadForm.documentType = inferDocumentType(file.name);
      }
    },
    async uploadChatDocument() {
      if (!this.uploadForm.file) {
        this.uploadError = "请选择要上传的文档文件";
        return;
      }
      this.uploadingDocument = true;
      this.uploadError = "";
      this.uploadNotice = "";
      try {
        const formData = new FormData();
        formData.append("file", this.uploadForm.file);
        formData.append("title", this.uploadForm.title);
        formData.append("source", this.uploadForm.source);
        formData.append("date", this.uploadForm.date);
        formData.append("tags", this.uploadForm.tags);
        formData.append("documentType", this.uploadForm.documentType);
        const uploadedDocument = await uploadSearchDocument(formData);
        const docId = this.extractUploadedDocId(uploadedDocument);
        const shouldBindAgent = this.uploadForm.enableForAgent && this.selectedAgentId;
        if (shouldBindAgent && docId) {
          try {
            await this.enableDocumentForSelectedAgent(docId);
            this.uploadNotice = `文档已上传，并已启用到当前 Agent：${this.selectedAgent?.name || this.selectedAgentId}`;
          } catch (bindError) {
            this.uploadNotice = `文档已上传到文档库，但启用到当前 Agent 失败：${bindError.message || "绑定失败"}`;
          }
        } else {
          this.uploadNotice = docId
            ? "文档已上传到文档库。"
            : "文档已上传，但未能识别返回的文档 ID。";
        }
        this.closeAfterUpload();
      } catch (error) {
        this.uploadError = error.message || "上传失败";
      } finally {
        this.uploadingDocument = false;
      }
    },
    closeAfterUpload() {
      this.uploadDialogOpen = false;
      this.uploadError = "";
      this.uploadForm = defaultUploadForm();
      const input = this.$refs.chatUploadFile;
      if (input) {
        input.value = "";
      }
    },
    extractUploadedDocId(document) {
      return document?.docId || document?.id || document?.documentId || "";
    },
    async enableDocumentForSelectedAgent(docId) {
      const agent = this.selectedAgent;
      if (!agent?.id || !docId) {
        return;
      }
      const nextDocumentIds = uniqueList([...(agent.boundDocumentIds || []), docId]);
      const updatedAgent = await updateWorkshopAgent(agent.id, this.agentToUpdatePayload(agent, nextDocumentIds));
      if (updatedAgent?.id) {
        this.agents = this.agents.map((item) => (item.id === updatedAgent.id ? updatedAgent : item));
      } else {
        this.agents = this.agents.map((item) =>
          item.id === agent.id
            ? {
                ...item,
                boundDocumentIds: nextDocumentIds,
                boundDocumentCount: nextDocumentIds.length
              }
            : item
        );
      }
      await this.loadAgents();
    },
    agentToUpdatePayload(agent, boundDocumentIds) {
      return {
        id: agent.id,
        name: agent.name,
        description: agent.description || "",
        usageScenarios: agent.usageScenarios || [],
        skillTags: agent.skillTags || [],
        defaultMode: agent.defaultMode || "agent_chat",
        modelName: agent.modelName || "",
        systemPrompt: agent.systemPrompt || "",
        firstUseGreeting: agent.firstUseGreeting || "",
        preferredToolPrefixes: agent.preferredToolPrefixes || [],
        boundMcpServiceIds: agent.boundMcpServiceIds || [],
        boundMcpToolNames: agent.boundMcpToolNames || [],
        boundDocumentIds,
        boundDocumentTags: agent.boundDocumentTags || [],
        toolConfigs: agent.toolConfigs || [],
        routingSettings: agent.routingSettings || {},
        quickQuestions: agent.quickQuestions || [],
        marketStatus: agent.marketStatus || "published"
      };
    },
    emitActiveConversationSnapshot(question, status = this.conversationStatus, runContext = null) {
      const context = runContext || this.createRunContext(question);
      const active = !runContext || this.isActiveRun(runContext);
      this.$emit("conversation-active", {
        id: context.historyId,
        question,
        conversationId: context.conversationId,
        mode: context.mode,
        skillId: context.selectedAgentId || "",
        agentName: context.agentName || "",
        status,
        active,
        timestamp: Date.now(),
        messages: this.serializeMessages(context.messages)
      });
    },
    async saveHistory(question, status = this.conversationStatus, runContext = null) {
      const context = runContext || this.createRunContext(question);
      if (!context.historyId || !question) {
        return;
      }
      const active = !runContext || this.isActiveRun(runContext);
      try {
        const history = await saveConversationHistory({
          historyId: context.conversationId || context.historyId,
          userId: this.userId,
          question,
          conversationId: context.conversationId,
          mode: context.mode,
          skillId: context.selectedAgentId || "",
          status,
          messages: this.serializeMessages(context.messages)
        });
        this.$emit("history-saved", {
          history,
          activeHistoryId: active ? context.conversationId || context.historyId : "",
          activeStatus: active ? status : ""
        });
      } catch (error) {
        this.$emit("history-saved", {
          history: null,
          activeHistoryId: active ? context.conversationId || context.historyId : "",
          activeStatus: active ? status : ""
        });
      }
    },
    restoreConversation(conversation) {
      const runningContext = conversation?.id ? this.runningContexts[conversation.id] : null;
      if (runningContext) {
        this.question = "";
        this.historyId = runningContext.historyId;
        this.conversationId = runningContext.conversationId || "";
        this.selectedAgentId = runningContext.selectedAgentId || "";
        this.conversationStatus = "running";
        this.messages = runningContext.messages;
        this.lastResponse = runningContext.lastResponse || { ...EMPTY_RESPONSE };
        this.errorMessage = "";
        this.loading = true;
        this.activeRunId = runningContext.runId;
        this.restoredRunning = false;
        this.emitActiveConversationSnapshot(runningContext.question || conversation.question || "", "running", runningContext);
        this.scrollMessages();
        return;
      }

      if (this.loading && conversation.id === this.historyId) {
        const lastUserMessage = [...this.messages].reverse().find((message) => message.role === "user");
        this.emitActiveConversationSnapshot(lastUserMessage?.content || conversation.question || "", "running");
        this.$emit("conversation-active", { id: this.historyId });
        this.scrollMessages();
        return;
      }

      if (this.loading && conversation.id !== this.historyId) {
        const lastUserMessage = [...this.messages].reverse().find((message) => message.role === "user");
        const backgroundContext = this.runningContexts[this.historyId];
        if (backgroundContext) {
          this.emitActiveConversationSnapshot(backgroundContext.question || lastUserMessage?.content || "", "running", backgroundContext);
        }
        this.activeRunId = "";
        this.loading = false;
      }

      const status = normalizeStatus(conversation.status, Array.isArray(conversation.messages) ? conversation.messages : []);
      const messages = this.ensureRunningAssistantMessage(normalizeMessages(conversation.messages, status), status);
      this.question = "";
      this.historyId = conversation.id || "";
      this.conversationId = conversation.conversationId || "";
      this.selectedAgentId = conversation.skillId || "";
      this.conversationStatus = normalizeStatus(status, messages);
      this.messages = messages;
      this.errorMessage = "";
      this.loading = false;
      this.activeRunId = "";
      this.restoredRunning = isRunningStatus(this.conversationStatus);
      const lastAssistantMessage = [...messages].reverse().find((message) => message.role === "assistant");
      this.lastResponse = {
        sources: Array.isArray(lastAssistantMessage?.sources) ? lastAssistantMessage.sources : [],
        toolTraces: Array.isArray(lastAssistantMessage?.traces) ? lastAssistantMessage.traces : []
      };
      this.$emit("conversation-active", { id: this.historyId });
      this.scrollMessages();
    },
    scrollMessages() {
      this.$nextTick(() => {
        const panel = this.$refs.messageList?.$el;
        if (panel) {
          panel.scrollTop = panel.scrollHeight;
        }
      });
    }
  }
};
