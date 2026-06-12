import ChatMessageList from "../../components/ChatMessageList.vue";
import PromptComposer from "../../components/PromptComposer.vue";
import {
  addUserFavorite,
  analyzeChatImage,
  apiRequest,
  fetchAgentWorkshop,
  recordUserActivity,
  saveConversationHistory,
  sendInteractionMessage,
  sendInteractionMessageStream,
  updateWorkshopAgent,
  uploadChatImage,
  uploadSearchDocument
} from "../../services/api";
import "../../styles/pages/chat-assistant.css";
import { onAgentTaskCancelled } from "../utils/agentTaskEvents";

const EMPTY_RESPONSE = {
  sources: [],
  toolTraces: []
};
const AGENT_TASK_POLL_TIMEOUT_MS = 1200;
const AGENT_TASK_MAX_WAIT_MS = 300000;
const MAX_UPLOAD_SIZE = 5 * 1024 * 1024;
const MAX_IMAGE_UPLOAD_SIZE = 10 * 1024 * 1024;

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

function isConfirmationTrace(trace) {
  const runtime = trace?.runtimeMetadata || {};
  const outcome = String(runtime.outcome || trace?.outcome || "").toLowerCase();
  const errorCode = String(trace?.errorCode || trace?.exceptionType || "").toUpperCase();
  return outcome === "confirmation_required" || errorCode === "MCP_CONFIRMATION_REQUIRED";
}

function isWaitingConfirmationMessage(message, conversationStatus = "") {
  if (message?.status === "waiting") {
    return true;
  }
  if (message?.role !== "assistant") {
    return false;
  }
  const content = String(message?.content || "");
  const traces = Array.isArray(message?.traces) ? message.traces : [];
  return (conversationStatus === "pending" && content.includes("等待权限确认"))
    || traces.some(isConfirmationTrace);
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
    .map((message) => {
      const waitingConfirmation = isWaitingConfirmationMessage(message, status);
      const streaming = !waitingConfirmation
        && (!!message.streaming || message.status === "streaming" || message.status === "running");
      return {
        id: message.id || uid(),
        role: message.role,
        content: message.content || "",
        timestamp: message.timestamp || Date.now(),
        sources: waitingConfirmation ? [] : (Array.isArray(message.sources) ? message.sources : []),
        traces: waitingConfirmation ? [] : (Array.isArray(message.traces) ? message.traces : []),
        streaming,
        status: waitingConfirmation ? "waiting" : (message.status || (streaming ? "streaming" : "completed"))
      };
    });
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

function defaultImageForm() {
  return {
    file: null,
    mode: "auto",
    question: ""
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

function cancelAgentTask(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/cancel${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

function killRuntimeTask(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/tasks/${encodeURIComponent(taskId)}/kill${query ? `?${query}` : ""}`, {
    method: "POST"
  });
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
    },
    pendingDraft: {
      type: Object,
      default: null
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
      appliedDraftId: "",
      agentsLoading: false,
      selectedAgentId: "",
      activeRunId: "",
      runningContexts: {},
      restoredRunning: false,
      uploadDialogOpen: false,
      uploadingDocument: false,
      uploadError: "",
      uploadNotice: "",
      favoriteSaving: false,
      favoriteNotice: "",
      imageDialogOpen: false,
      uploadingImage: false,
      imageUploadError: "",
      imageForm: defaultImageForm(),
      pendingImageAnalysis: null,
      contextImageAnalyses: [],
      pendingMcpConfirmation: null,
      pendingMcpRequest: null,
      pendingMcpTaskId: "",
      pendingMcpExpiredAt: "",
      confirmationNow: Date.now(),
      confirmationTimerId: null,
      stopAgentTaskCancelledListener: null,
      confirmationRemember: "",
      uploadForm: defaultUploadForm(),
      imageModeOptions: [
        { value: "auto", label: "自动识别" },
        { value: "screenshot", label: "截图理解" },
        { value: "document", label: "表格/文档图片" },
        { value: "chart", label: "图表分析" }
      ],
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
    currentConversationTitle() {
      return this.lastUserQuestion() || this.selectedConversation?.question || this.selectedAgent?.name || "本轮会话";
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
    },
    activeRunContext() {
      return this.runningContexts[this.historyId] || null;
    },
    canKillActiveRun() {
      return this.loading || !!this.activeRunContext?.taskId || !!this.pendingMcpTaskId;
    },
    pendingMcpRemainingMs() {
      if (!this.pendingMcpExpiredAt) {
        return 0;
      }
      return Math.max(0, new Date(this.pendingMcpExpiredAt).getTime() - this.confirmationNow);
    },
    pendingMcpCountdownText() {
      const totalSeconds = Math.ceil(this.pendingMcpRemainingMs / 1000);
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return `${minutes}:${String(seconds).padStart(2, "0")}`;
    }
  },
  mounted() {
    this.loadAgents();
    this.stopAgentTaskCancelledListener = onAgentTaskCancelled(this.handleAgentTaskCancelled);
  },
  beforeUnmount() {
    if (this.stopAgentTaskCancelledListener) {
      this.stopAgentTaskCancelledListener();
      this.stopAgentTaskCancelledListener = null;
    }
    this.clearConfirmationTimer();
  },
  watch: {
    selectedConversation(conversation) {
      if (conversation) {
        this.restoreConversation(conversation);
      }
    },
    pendingDraft: {
      immediate: true,
      handler(draft) {
        this.applyPendingDraft(draft);
      }
    }
  },
  methods: {
    applyPendingDraft(draft) {
      if (!draft || draft.id === this.appliedDraftId) {
        return;
      }
      this.appliedDraftId = draft.id;
      if (draft.agentId) {
        this.selectedAgentId = draft.agentId;
        if (draft.newSession && !this.loading) {
          this.historyId = "";
          this.conversationId = "";
          this.messages = [];
          this.conversationStatus = "completed";
          this.lastResponse = { ...EMPTY_RESPONSE };
        }
        this.uploadNotice = draft.title
          ? `已切换到 Agent：${draft.title}。`
          : `已切换到 Agent：${draft.agentId}。`;
        this.recordAgentUse(draft.agentId, draft.title || draft.agentId);
      }
      if (!draft.prompt) {
        return;
      }
      this.question = draft.prompt;
      if (!draft.agentId) {
        this.uploadNotice = draft.title
          ? `已从搜索结果《${draft.title}》生成提问草稿。`
          : "已从搜索结果生成提问草稿。";
      }
      this.$nextTick(() => {
        this.$refs.promptComposer?.focusComposer?.();
      });
    },
    async handleSend(payload) {
      const query = typeof payload === "string" ? payload.trim() : payload?.query?.trim();
      if (!query || this.loading) {
        return;
      }
      const attachedImageAnalyses = [...this.contextImageAnalyses];
      const imageAnalysisIds = attachedImageAnalyses
        .map((item) => item?.id)
        .filter(Boolean);

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
        imageAnalysisIds,
        availableTools: !this.selectedAgentId && payload?.webSearch ? ["web_search"] : [],
        toolInput: {
          agentName: payload?.agentName || "",
          documentWorkflow: !!payload?.documentWorkflow,
          webSearch: !!payload?.webSearch,
          imageAnalysisIds
        }
      };
      if (this.selectedAgentId) {
        this.recordAgentUse(this.selectedAgentId, this.selectedAgent?.name || this.selectedAgentId, query);
      }
      if (imageAnalysisIds.length) {
        this.contextImageAnalyses = [];
        this.uploadNotice = `已加入 ${imageAnalysisIds.length} 张图片解析上下文，本次任务将使用这些内容。`;
      }

      try {
        const answerState = await this.requestAssistantAnswer(query, requestPayload, runContext);
        if (answerState?.waitingConfirmation) {
          runContext.status = "pending";
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = "pending";
          }
          this.emitActiveConversationSnapshot(query, "pending", runContext);
          await this.saveHistory(query, "pending", runContext);
          return;
        }
        if (answerState?.cancelled) {
          runContext.status = "cancelled";
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = "cancelled";
          }
          this.emitActiveConversationSnapshot(query, "cancelled", runContext);
          await this.saveHistory(query, "cancelled", runContext);
          return;
        }
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
        if (!this.pendingMcpConfirmation) {
          delete this.runningContexts[runContext.historyId];
        }
        if (this.isActiveRun(runContext)) {
          this.loading = false;
          this.activeRunId = "";
          this.scrollMessages();
        }
      }
    },
    async recordAgentUse(agentId, title, summary = "") {
      if (!agentId) {
        return;
      }
      try {
        await recordUserActivity({
          tenantId: this.userId,
          userId: this.userId,
          targetType: "AGENT",
          targetId: agentId,
          actionType: "USE",
          title: title || agentId,
          summary,
          extra: {
            conversationId: this.conversationId || "",
            historyId: this.historyId || ""
          }
        });
      } catch (error) {
        // Shortcut memory is best-effort and should not block chat.
      }
    },
    async favoriteCurrentSession() {
      const targetId = this.conversationId || this.historyId;
      if (!targetId || this.favoriteSaving) {
        return;
      }
      this.favoriteSaving = true;
      this.favoriteNotice = "";
      this.errorMessage = "";
      try {
        await addUserFavorite({
          tenantId: this.userId,
          userId: this.userId,
          targetType: "SESSION",
          targetId,
          title: this.currentConversationTitle,
          category: "会话"
        });
        this.favoriteNotice = "本轮会话已收藏。";
      } catch (error) {
        this.errorMessage = error.message || "收藏本轮会话失败";
      } finally {
        this.favoriteSaving = false;
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
      const assistantMessage = this.reuseWaitingAssistantMessage(runContext) || this.createAssistantMessage({
        content: "",
        streaming: true,
        status: "streaming"
      });
      if (!runContext.messages.some((message) => message.id === assistantMessage.id)) {
        runContext.messages.push(assistantMessage);
      }
      if (this.isActiveRun(runContext)) {
        this.messages = runContext.messages;
        this.scrollMessages();
      }

      const task = await submitAgentTask({
        tenantId: this.userId,
        resumeTaskId: requestPayload.resumeTaskId || "",
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
        imageAnalysisIds: requestPayload.imageAnalysisIds || [],
        toolInput: requestPayload.toolInput || {}
      });
      runContext.taskId = task?.taskId || "";

      const event = await this.waitForAgentTaskResult(runContext.taskId, this.userId);
      const eventPayload = parseJsonPayload(event?.payload);
      const eventType = String(event?.type || "").toUpperCase();
      const eventStatus = String(event?.status || "").toUpperCase();

      if (eventType === "NEEDS_CONFIRMATION" || eventStatus === "WAIT_CONFIRMATION") {
        const response = eventPayload || {};
        this.applyResponseMetadata(response, runContext);
        this.captureMcpConfirmation(response, query, requestPayload, runContext.taskId);
        assistantMessage.content = "已完成执行计划，等待权限确认后继续。";
        assistantMessage.timestamp = response.timestamp || event?.createTime || Date.now();
        assistantMessage.latencyMs = response.latencyMs;
        assistantMessage.sources = runContext.lastResponse.sources;
        assistantMessage.traces = [];
        assistantMessage.streaming = false;
        assistantMessage.status = "waiting";
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        this.emitActiveConversationSnapshot(query, "pending", runContext);
        return { waitingConfirmation: true };
      }

      if (eventStatus === "CANCELLED") {
        const message = eventPayload.message || "Agent task cancelled";
        assistantMessage.content = message;
        assistantMessage.streaming = false;
        assistantMessage.status = "cancelled";
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        return { cancelled: true };
      }

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
      this.captureMcpConfirmation(response, query, requestPayload);
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
        if (
          type === "ANSWER"
          || type === "ERROR"
          || type === "NEEDS_CONFIRMATION"
          || status === "SUCCESS"
          || status === "FAILED"
          || status === "CANCELLED"
          || status === "WAIT_CONFIRMATION"
        ) {
          return event;
        }
        await sleep(300);
      }
      throw new Error("Agent task polling timed out");
    },
    appendDirectAssistantMessage(response, runContext = null) {
      const targetContext = runContext || this.createRunContext("");
      this.applyResponseMetadata(response, targetContext);
      this.captureMcpConfirmation(response, targetContext.question || "", null, targetContext.taskId || "");
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
    reuseWaitingAssistantMessage(runContext) {
      const message = [...(runContext?.messages || [])].reverse()
        .find((item) => item?.role === "assistant" && item.status === "waiting");
      if (!message) {
        return null;
      }
      message.content = "";
      message.streaming = true;
      message.status = "streaming";
      message.sources = [];
      message.traces = [];
      message.latencyMs = undefined;
      message.timestamp = Date.now();
      return message;
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
    findRunContextForTask(task = {}) {
      const taskId = task?.taskId || "";
      const sessionId = task?.sessionId || task?.conversationId || "";
      return Object.values(this.runningContexts).find((context) =>
        (taskId && context.taskId === taskId)
        || (sessionId && (context.historyId === sessionId || context.conversationId === sessionId))
      ) || null;
    },
    taskMatchesVisibleConversation(task = {}) {
      const sessionId = task?.sessionId || task?.conversationId || "";
      return !!sessionId && (sessionId === this.historyId || sessionId === this.conversationId);
    },
    lastUserQuestion(messages = this.messages) {
      return [...messages].reverse().find((message) => message.role === "user")?.content || "";
    },
    markContextCancelled(context, message) {
      const lastAssistantMessage = [...context.messages].reverse()
        .find((item) => item.role === "assistant" && (item.streaming || item.status === "waiting" || item.status === "streaming"));
      if (lastAssistantMessage) {
        lastAssistantMessage.streaming = false;
        lastAssistantMessage.status = "cancelled";
        lastAssistantMessage.content = lastAssistantMessage.content || message;
      } else {
        context.messages.push(this.createAssistantMessage({
          content: message,
          streaming: false,
          status: "cancelled"
        }));
      }
      context.status = "cancelled";
      context.question = context.question || this.lastUserQuestion(context.messages);
    },
    async handleAgentTaskCancelled(task = {}) {
      const taskId = task?.taskId || "";
      const sessionId = task?.sessionId || task?.conversationId || "";
      const context = this.findRunContextForTask(task);
      const previousHistoryId = context?.historyId || "";
      const pendingMatches = taskId && (this.pendingMcpTaskId === taskId || this.pendingMcpRequest?.taskId === taskId);
      if (!context && !pendingMatches && !this.taskMatchesVisibleConversation(task)) {
        return;
      }

      const targetContext = context || this.createRunContext(this.pendingMcpRequest?.query || task.question || this.lastUserQuestion());
      targetContext.taskId = taskId || targetContext.taskId || "";
      targetContext.conversationId = sessionId || targetContext.conversationId || this.conversationId;
      targetContext.historyId = sessionId || targetContext.historyId || this.historyId;
      targetContext.question = targetContext.question || task.question || this.lastUserQuestion(targetContext.messages);

      const activeBeforeCancel = this.isActiveRun(targetContext) || this.taskMatchesVisibleConversation(task);
      this.markContextCancelled(targetContext, task.message || "Agent task cancelled");
      if (activeBeforeCancel) {
        this.messages = targetContext.messages;
        this.conversationStatus = "cancelled";
        this.errorMessage = "";
      }
      this.emitActiveConversationSnapshot(targetContext.question || task.question || "", "cancelled", targetContext);
      await this.saveHistory(targetContext.question || task.question || "", "cancelled", targetContext);

      if (previousHistoryId) {
        delete this.runningContexts[previousHistoryId];
      }
      delete this.runningContexts[targetContext.historyId];
      if (pendingMatches) {
        this.cancelMcpConfirmation();
      }
      if (activeBeforeCancel) {
        this.loading = false;
        this.activeRunId = "";
        this.scrollMessages();
      }
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
      this.favoriteNotice = "";
      this.contextImageAnalyses = [];
      this.pendingImageAnalysis = null;
      this.lastResponse = { ...EMPTY_RESPONSE };
      this.pendingMcpConfirmation = null;
      this.pendingMcpRequest = null;
      this.pendingMcpTaskId = "";
      this.confirmationRemember = "";
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
    openImageDialog() {
      this.imageUploadError = "";
      this.pendingImageAnalysis = null;
      this.imageForm = {
        ...defaultImageForm(),
        question: this.question || ""
      };
      this.imageDialogOpen = true;
    },
    closeImageDialog() {
      if (this.uploadingImage) {
        return;
      }
      this.imageDialogOpen = false;
      this.imageUploadError = "";
      this.pendingImageAnalysis = null;
      this.resetImagePicker();
    },
    triggerImageFilePicker() {
      this.$refs.chatImageFile?.click();
    },
    handleImageFileChange(event) {
      const file = event.target.files?.[0] || null;
      this.imageUploadError = "";
      this.pendingImageAnalysis = null;
      if (file && file.size > MAX_IMAGE_UPLOAD_SIZE) {
        this.imageForm.file = null;
        event.target.value = "";
        this.imageUploadError = "图片文件不能超过 10MB";
        return;
      }
      if (file && !String(file.type || "").startsWith("image/")) {
        this.imageForm.file = null;
        event.target.value = "";
        this.imageUploadError = "仅支持 png、jpg、jpeg、webp、gif 图片";
        return;
      }
      this.imageForm.file = file;
    },
    async uploadAndAnalyzeImage() {
      if (!this.imageForm.file) {
        this.imageUploadError = "请选择要解析的图片";
        return;
      }
      this.uploadingImage = true;
      this.imageUploadError = "";
      this.pendingImageAnalysis = null;
      try {
        const formData = new FormData();
        formData.append("file", this.imageForm.file);
        formData.append("tenantId", this.userId);
        const asset = await uploadChatImage(formData);
        const analysis = await analyzeChatImage({
          fileId: asset?.fileId,
          question: this.imageForm.question || this.question || "",
          mode: this.imageForm.mode || "auto",
          tenantId: this.userId
        });
        this.pendingImageAnalysis = analysis;
      } catch (error) {
        this.imageUploadError = error.message || "图片解析失败";
      } finally {
        this.uploadingImage = false;
      }
    },
    confirmImageContext() {
      if (!this.pendingImageAnalysis?.id) {
        return;
      }
      const exists = this.contextImageAnalyses.some((item) => item.id === this.pendingImageAnalysis.id);
      if (!exists) {
        this.contextImageAnalyses.push(this.pendingImageAnalysis);
      }
      this.uploadNotice = "图片解析已加入上下文，将随下一次提问进入 Planner。";
      this.imageDialogOpen = false;
      this.imageUploadError = "";
      this.pendingImageAnalysis = null;
      this.imageForm = defaultImageForm();
      this.resetImagePicker();
    },
    removeImageContext(analysisId) {
      this.contextImageAnalyses = this.contextImageAnalyses.filter((item) => item.id !== analysisId);
    },
    resetImagePicker() {
      const input = this.$refs.chatImageFile;
      if (input) {
        input.value = "";
      }
    },
    formatImageType(value) {
      const labels = {
        screenshot: "截图理解",
        document: "表格/文档图片",
        chart: "图表分析"
      };
      return labels[value] || value || "未知类型";
    },
    formatConfidence(value) {
      if (typeof value !== "number") {
        return "未知";
      }
      return `${Math.round(value * 100)}%`;
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
    },
    captureMcpConfirmation(response = {}, query = "", requestPayload = null, taskId = "") {
      const confirmation = this.findMcpConfirmation(response);
      if (!confirmation?.token) {
        return;
      }
      const expiredAt = this.resolveConfirmationExpiredAt(response, confirmation);
      this.pendingMcpConfirmation = confirmation;
      this.pendingMcpRequest = {
        query,
        requestPayload: requestPayload ? JSON.parse(JSON.stringify(requestPayload)) : null,
        taskId
      };
      this.pendingMcpTaskId = taskId || "";
      this.pendingMcpExpiredAt = expiredAt;
      this.confirmationRemember = "";
      this.startConfirmationTimer();
    },
    findMcpConfirmation(response = {}) {
      const traces = Array.isArray(response.toolTraces) ? response.toolTraces : [];
      for (const trace of traces) {
        const runtime = trace?.runtimeMetadata || {};
        if (runtime.outcome === "confirmation_required" && runtime.confirmation) {
          return runtime.confirmation;
        }
      }
      const agentSteps = response?.metadata?.agent?.plannerSteps || response?.metadata?.plannerSteps || [];
      for (const step of Array.isArray(agentSteps) ? agentSteps : []) {
        const confirmation = step?.runtime?.confirmation || step?.confirmation;
        if (confirmation?.token) {
          return confirmation;
        }
      }
      return null;
    },
    resolveConfirmationExpiredAt(response = {}, confirmation = {}) {
      const value = confirmation.expiredAt || response.expiredAt || response.confirmExpiredAt;
      const timestamp = value ? new Date(value).getTime() : NaN;
      if (Number.isFinite(timestamp) && timestamp > Date.now()) {
        return new Date(timestamp).toISOString();
      }
      return new Date(Date.now() + 180000).toISOString();
    },
    startConfirmationTimer() {
      this.clearConfirmationTimer();
      this.confirmationNow = Date.now();
      this.confirmationTimerId = window.setInterval(() => {
        this.confirmationNow = Date.now();
        if (this.pendingMcpConfirmation && this.pendingMcpRemainingMs <= 0) {
          this.handleMcpConfirmationTimeout();
        }
      }, 1000);
    },
    clearConfirmationTimer() {
      if (this.confirmationTimerId) {
        window.clearInterval(this.confirmationTimerId);
        this.confirmationTimerId = null;
      }
    },
    formatConfirmationParameters(parameters = {}) {
      try {
        return JSON.stringify(parameters || {}, null, 2);
      } catch (error) {
        return String(parameters || "");
      }
    },
    cancelMcpConfirmation() {
      this.clearConfirmationTimer();
      this.pendingMcpConfirmation = null;
      this.pendingMcpRequest = null;
      this.pendingMcpTaskId = "";
      this.pendingMcpExpiredAt = "";
      this.confirmationRemember = "";
    },
    async handleMcpConfirmationTimeout() {
      const taskId = this.pendingMcpTaskId || this.pendingMcpRequest?.taskId || "";
      if (!taskId || this.loading) {
        this.cancelMcpConfirmation();
        return;
      }
      try {
        await killRuntimeTask(taskId, this.userId);
        await this.handleAgentTaskCancelled({
          taskId,
          tenantId: this.userId,
          message: "该操作超过 3 分钟未确认，任务已自动取消。",
          status: "KILLED"
        });
      } catch (error) {
        this.errorMessage = error.message || "确认超时取消任务失败";
      } finally {
        this.cancelMcpConfirmation();
      }
    },
    async denyMcpConfirmation() {
      const taskId = this.pendingMcpTaskId || this.pendingMcpRequest?.taskId || "";
      if (taskId) {
        try {
          await apiRequest(`/agent/tasks/runtime/tasks/${encodeURIComponent(taskId)}/reject?tenantId=${encodeURIComponent(this.userId)}`, {
            method: "POST",
            body: JSON.stringify({ userId: this.userId })
          });
        } catch (error) {
          this.errorMessage = error.message || "取消任务失败";
        }
      }
      this.cancelMcpConfirmation();
    },
    async confirmMcpExecution() {
      if (!this.pendingMcpConfirmation?.token || !this.pendingMcpRequest?.requestPayload || this.loading) {
        return;
      }
      const query = this.pendingMcpRequest.query;
      const requestPayload = JSON.parse(JSON.stringify(this.pendingMcpRequest.requestPayload));
      requestPayload.resumeTaskId = this.pendingMcpTaskId || this.pendingMcpRequest.taskId || "";
      requestPayload.toolInput = {
        ...(requestPayload.toolInput || {}),
        mcpConfirmation: {
          token: this.pendingMcpConfirmation.token,
          approved: true,
          decision: this.confirmationRemember === "tool_deny" ? "deny" : "allow_once",
          remember: this.confirmationRemember || undefined
        }
      };
      this.pendingMcpConfirmation = null;
      this.pendingMcpRequest = null;
      this.pendingMcpTaskId = "";
      this.pendingMcpExpiredAt = "";
      this.clearConfirmationTimer();
      this.confirmationRemember = "";

      const runContext = this.createRunContext(query);
      runContext.messages = this.messages;
      this.runningContexts[runContext.historyId] = runContext;
      this.loading = true;
      this.activeRunId = runContext.runId;
      this.conversationStatus = "running";
      try {
        const answerState = await this.requestAssistantAnswer(query, requestPayload, runContext);
        if (answerState?.waitingConfirmation) {
          runContext.status = "pending";
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = "pending";
          }
          this.emitActiveConversationSnapshot(query, "pending", runContext);
          await this.saveHistory(query, "pending", runContext);
          return;
        }
        if (answerState?.cancelled) {
          runContext.status = "cancelled";
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = "cancelled";
          }
          this.emitActiveConversationSnapshot(query, "cancelled", runContext);
          await this.saveHistory(query, "cancelled", runContext);
          return;
        }
        runContext.status = "completed";
        if (this.isActiveRun(runContext)) {
          this.conversationStatus = "completed";
        }
        this.emitActiveConversationSnapshot(query, "completed", runContext);
        await this.saveHistory(query, "completed", runContext);
      } catch (error) {
        runContext.status = "failed";
        if (this.isActiveRun(runContext)) {
          this.errorMessage = error.message || "MCP confirmation execution failed";
          this.conversationStatus = "failed";
        }
      } finally {
        if (!this.pendingMcpConfirmation) {
          delete this.runningContexts[runContext.historyId];
        }
        if (this.isActiveRun(runContext)) {
          this.loading = false;
          this.activeRunId = "";
          this.scrollMessages();
        }
      }
    },
    async killActiveRun() {
      const runContext = this.activeRunContext;
      const taskId = runContext?.taskId || this.pendingMcpTaskId || this.pendingMcpRequest?.taskId || "";
      if (!taskId) {
        this.loading = false;
        this.activeRunId = "";
        this.conversationStatus = "cancelled";
        this.cancelMcpConfirmation();
        return;
      }
      try {
        await killRuntimeTask(taskId, this.userId);
        const targetContext = runContext || this.createRunContext(this.pendingMcpRequest?.query || "");
        const lastAssistantMessage = [...targetContext.messages].reverse()
          .find((message) => message.role === "assistant" && (message.streaming || message.status === "waiting"));
        if (lastAssistantMessage) {
          lastAssistantMessage.streaming = false;
          lastAssistantMessage.status = "cancelled";
          lastAssistantMessage.content = lastAssistantMessage.content || "当前会话已停止。";
        } else {
          targetContext.messages.push(this.createAssistantMessage({
            content: "当前会话已停止。",
            streaming: false,
            status: "cancelled"
          }));
        }
        targetContext.status = "cancelled";
        if (this.isActiveRun(targetContext) || !runContext || targetContext.historyId === this.historyId) {
          this.messages = targetContext.messages;
          this.conversationStatus = "cancelled";
          this.errorMessage = "";
        }
        this.emitActiveConversationSnapshot(targetContext.question || "", "cancelled", targetContext);
        await this.saveHistory(targetContext.question || "", "cancelled", targetContext);
      } catch (error) {
        this.errorMessage = error.message || "停止任务失败";
      } finally {
        if (runContext) {
          delete this.runningContexts[runContext.historyId];
        }
        this.cancelMcpConfirmation();
        this.loading = false;
        this.activeRunId = "";
        this.scrollMessages();
      }
    }
  }
};
