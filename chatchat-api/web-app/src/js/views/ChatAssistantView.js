import ChatMessageList from "../../components/ChatMessageList.vue";
import PromptComposer from "../../components/PromptComposer.vue";
import {
  analyzeChatImage,
  apiRequest,
  fetchAgentTaskEvents,
  fetchAgentWorkshop,
  recordUserActivity,
  saveConversationHistory,
  sendInteractionMessage,
  sendInteractionMessageStream,
  submitAgentTaskFeedback,
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
const MESSAGE_FEEDBACK_PAYLOADS = {
  useful: { useful: true },
  adopted: { adopted: true },
  resolved: { resolved: true },
  unresolved: { resolved: false }
};
const AGENT_TASK_POLL_TIMEOUT_MS = 5000;
const AGENT_TASK_EVENT_LIMIT = 80;
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
        sources: waitingConfirmation ? [] : normalizeMessageSources(message),
        traces: waitingConfirmation ? [] : normalizeMessageTraces(message),
        steps: normalizeMessageSteps(message),
        streaming,
        status: waitingConfirmation ? "waiting" : (message.status || (streaming ? "streaming" : "completed")),
        taskId: message.taskId || "",
        feedbackTime: message.feedbackTime || "",
        feedbackAction: message.feedbackAction || "",
        feedbackUseful: message.feedbackUseful,
        feedbackAdopted: message.feedbackAdopted,
        feedbackResolved: message.feedbackResolved,
        feedbackComment: message.feedbackComment || "",
        feedbackReasonCategory: message.feedbackReasonCategory || "",
        feedbackSubmitting: false,
        feedbackError: ""
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

function compactText(value, maxLength = 96) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  if (!text) {
    return "";
  }
  return text.length <= maxLength ? text : `${text.slice(0, Math.max(0, maxLength - 1))}…`;
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

function firstArray(...values) {
  return values.find(Array.isArray) || [];
}

function normalizeMessageSources(message = {}) {
  return firstArray(
    message.sources,
    message.references,
    message.citations,
    message.metadata?.sources,
    message.extra?.sources
  );
}

function normalizeMessageTraces(message = {}) {
  return firstArray(
    message.traces,
    message.toolTraces,
    message.tool_traces,
    message.metadata?.toolTraces,
    message.metadata?.traces,
    message.extra?.toolTraces,
    message.extra?.traces
  );
}

function normalizeMessageSteps(message = {}) {
  return firstArray(
    message.steps,
    message.executionSteps,
    message.backendSteps,
    message.metadata?.steps,
    message.metadata?.executionSteps,
    message.extra?.steps
  ).map((step, index) => normalizeExecutionStep(step, index));
}

function normalizeExecutionStep(step = {}, index = 0) {
  const status = String(step.status || "pending").toLowerCase();
  return {
    id: step.id || step.eventId || `step-${index}`,
    title: step.title || step.label || "\u6267\u884c\u6b65\u9aa4",
    detail: step.detail || step.description || step.message || "",
    status: ["pending", "active", "done", "partial", "empty", "error", "cancelled"].includes(status) ? status : "pending",
    type: step.type || "",
    toolName: step.toolName || "",
    timestamp: step.timestamp || step.createTime || Date.now(),
    order: step.order,
    latencyMs: step.latencyMs
  };
}

function normalizeEventType(event = {}) {
  return String((event || {}).type || "").toUpperCase();
}

function normalizeEventStatus(event = {}) {
  return String((event || {}).status || "").toUpperCase();
}

function statusTitle(status) {
  switch (status) {
    case "PENDING":
      return "\u6392\u961f\u7b49\u5f85";
    case "RUNNING":
      return "\u540e\u7aef\u6267\u884c\u4e2d";
    case "WAIT_MODEL":
      return "\u6a21\u578b\u63a8\u7406\u4e2d";
    case "WAIT_TOOL":
      return "\u7b49\u5f85\u5de5\u5177\u6267\u884c";
    case "WAIT_CONFIRMATION":
      return "\u7b49\u5f85\u6743\u9650\u786e\u8ba4";
    case "SUCCESS":
      return "\u6267\u884c\u5b8c\u6210";
    case "PARTIAL":
      return "\u83b7\u5f97\u90e8\u5206\u7ed3\u679c";
    case "EMPTY":
      return "\u672a\u4ea7\u751f\u6709\u6548\u7ed3\u679c";
    case "FAILED":
      return "\u6267\u884c\u5931\u8d25";
    case "CANCELLED":
    case "KILLED":
      return "\u5df2\u505c\u6b62";
    default:
      return "\u540e\u7aef\u5904\u7406\u4e2d";
  }
}

const STEP_PHASE_ORDER = {
  "receive-question": 10,
  "backend-running": 20,
  "model-inference": 30,
  planning: 40,
  "tool-execution": 50,
  analysis: 60,
  "final-answer": 70,
  confirmation: 80,
  "final-result": 90
};

function stepPhaseOrder(step = {}) {
  return STEP_PHASE_ORDER[step.id] || step.phaseOrder || step.order || step.timestamp || 999;
}

function runtimePayloadOf(payload = {}) {
  return payload.payload && typeof payload.payload === "object" ? payload.payload : {};
}

function runtimeActionOf(runtimePayload = {}) {
  return String(runtimePayload.action || runtimePayload.step || runtimePayload.name || runtimePayload.reason || "").toLowerCase();
}

function isFinalAnswerRuntimeStep(runtimePayload = {}) {
  const action = runtimeActionOf(runtimePayload);
  return action.includes("final_answer") || action.includes("final answer") || action.includes("answer");
}

function runtimeToolNameOf(runtimePayload = {}) {
  return runtimePayload.resolvedToolName || runtimePayload.toolName || runtimePayload.source || "";
}

function isTerminalAgentEvent(event = {}) {
  const type = normalizeEventType(event);
  const status = normalizeEventStatus(event);
  return type === "ANSWER"
    || type === "RESULT"
    || type === "ERROR"
    || type === "NEEDS_CONFIRMATION"
    || type === "COMPLETE"
    || status === "SUCCESS"
    || status === "PARTIAL"
    || status === "EMPTY"
    || status === "FAILED"
    || status === "CANCELLED"
    || status === "WAIT_CONFIRMATION";
}

function terminalEventFromEvents(events = []) {
  const terminalEvents = [...(Array.isArray(events) ? events : [])]
    .filter(isTerminalAgentEvent)
    .sort((left, right) => (left.createTime || left.timestamp || 0) - (right.createTime || right.timestamp || 0));
  return terminalEvents
    .filter((event) => ["ANSWER", "RESULT", "ERROR", "NEEDS_CONFIRMATION"].includes(normalizeEventType(event)))
    .at(-1)
    || terminalEvents.at(-1)
    || null;
}

function eventOrderValue(event = {}) {
  const sequence = Number(event?.sequence);
  if (Number.isFinite(sequence) && sequence > 0) {
    return sequence;
  }
  return Number(event?.createTime || event?.timestamp || Date.now());
}

function eventStepId(event = {}, payload = {}) {
  const type = normalizeEventType(event);
  const status = normalizeEventStatus(event);
  if (type === "QUESTION") {
    return "receive-question";
  }
  if (type === "STATUS") {
    if (status === "RUNNING") {
      return "backend-running";
    }
    if (status === "WAIT_MODEL") {
      return "model-inference";
    }
    if (status === "WAIT_TOOL") {
      return "tool-execution";
    }
    if (["FAILED", "CANCELLED", "KILLED"].includes(status)) {
      return "final-result";
    }
    return `status:${status || "runtime"}`;
  }
  if (type === "PLAN") {
    return "planning";
  }
  if (type === "RUNTIME_STEP") {
    const runtimePayload = runtimePayloadOf(payload);
    if (isFinalAnswerRuntimeStep(runtimePayload)) {
      return "final-answer";
    }
    return runtimeToolNameOf(runtimePayload) ? "tool-execution" : "planning";
  }
  if (type === "RUNTIME_OBSERVATION") {
    const runtimePayload = runtimePayloadOf(payload);
    return runtimeToolNameOf(runtimePayload) ? "tool-execution" : "analysis";
  }
  if (type === "THINK") {
    return "analysis";
  }
  if (type === "TOOL_CALL" || type === "TOOL_RESULT") {
    return "tool-execution";
  }
  if (type === "ANSWER" || type === "RESULT") {
    return "final-answer";
  }
  if (type === "ERROR" || type === "COMPLETE") {
    return "final-result";
  }
  if (type === "NEEDS_CONFIRMATION") {
    return "confirmation";
  }
  return event?.eventId || `${type || "event"}-${eventOrderValue(event)}`;
}

function agentEventToExecutionStep(event = {}) {
  if (!event) {
    return null;
  }
  const payload = parseJsonPayload(event.payload);
  const type = normalizeEventType(event);
  const status = normalizeEventStatus(event);
  const toolName = event.toolName || payload.toolName || "";
  const displayToolName = payload.displayName || payload.serviceName || toolName;
  const stepId = eventStepId(event, payload);
  const base = {
    id: stepId,
    type,
    toolName,
    timestamp: event.createTime || Date.now(),
    order: eventOrderValue(event),
    phaseOrder: STEP_PHASE_ORDER[stepId] || 999,
    latencyMs: event.latencyMs
  };

  if (type === "QUESTION") {
    return {
      ...base,
      title: "\u63a5\u6536\u95ee\u9898",
      detail: "\u5df2\u5efa\u7acb\u672c\u6b21\u4f1a\u8bdd\u4efb\u52a1",
      status: "done"
    };
  }
  if (type === "PLAN") {
    return {
      ...base,
      title: "\u751f\u6210\u6267\u884c\u8ba1\u5212",
      detail: compactText([payload.action, displayToolName].filter(Boolean).join(" - "), 72),
      status: "done"
    };
  }
  if (type === "RUNTIME_STEP") {
    const runtimePayload = runtimePayloadOf(payload);
    const runtimeToolName = runtimeToolNameOf(runtimePayload);
    if (isFinalAnswerRuntimeStep(runtimePayload)) {
      return {
        ...base,
        title: "\u751f\u6210\u56de\u7b54",
        detail: compactText([
          runtimePayload.action,
          runtimePayload.reason || "\u6b63\u5728\u7ec4\u7ec7\u6700\u7ec8\u7ed3\u8bba"
        ].filter(Boolean).join(" - "), 96),
        status: "active"
      };
    }
    return {
      ...base,
      title: runtimeToolName ? "\u8c03\u7528\u5de5\u5177" : "\u751f\u6210\u6267\u884c\u8ba1\u5212",
      detail: compactText([
        runtimePayload.action,
        runtimeToolName,
        runtimePayload.reason
      ].filter(Boolean).join(" - "), 96),
      status: "active"
    };
  }
  if (type === "RUNTIME_OBSERVATION") {
    const runtimePayload = runtimePayloadOf(payload);
    const source = runtimeToolNameOf(runtimePayload);
    return {
      ...base,
      title: source ? "\u5de5\u5177\u8fd4\u56de\u7ed3\u679c" : "\u5206\u6790\u4e2d",
      detail: compactText([
        source,
        runtimePayload.contentPreview
      ].filter(Boolean).join(" - "), 120),
      status: "done"
    };
  }
  if (type === "RUNTIME_STARTED") {
    return {
      ...base,
      id: "backend-running",
      phaseOrder: STEP_PHASE_ORDER["backend-running"],
      title: "\u540e\u7aef\u6267\u884c\u4e2d",
      detail: compactText(payload.message || "\u5df2\u8fdb\u5165 Agent Runtime", 72),
      status: "done"
    };
  }
  if (type === "RUNTIME_FAILED" || type === "RUNTIME_CANCELLED") {
    return {
      ...base,
      title: type === "RUNTIME_CANCELLED" ? "\u8fd0\u884c\u5df2\u53d6\u6d88" : "\u8fd0\u884c\u5931\u8d25",
      detail: compactText(payload.message || event.errorCode || "", 96),
      status: type === "RUNTIME_CANCELLED" ? "cancelled" : "error"
    };
  }
  if (type === "RUNTIME_CONFIRMATION") {
    return {
      ...base,
      title: "\u7b49\u5f85\u6743\u9650\u786e\u8ba4",
      detail: compactText(payload.message || "", 96),
      status: "active"
    };
  }
  if (type === "THINK") {
    return {
      ...base,
      title: "\u5206\u6790\u4e2d",
      detail: compactText(payload.action || "\u6b63\u5728\u6574\u7406\u53ef\u6267\u884c\u7684\u4e0b\u4e00\u6b65", 72),
      status: "active"
    };
  }
  if (type === "TOOL_CALL") {
    return {
      ...base,
      title: "\u8c03\u7528\u5de5\u5177",
      detail: compactText(displayToolName || "\u540e\u7aef\u5de5\u5177", 72),
      status: "active"
    };
  }
  if (type === "TOOL_RESULT") {
    const success = payload.success !== false && status !== "FAILED";
    const duration = payload.durationMs || event.latencyMs;
    return {
      ...base,
      title: success ? "\u5de5\u5177\u8fd4\u56de\u7ed3\u679c" : "\u5de5\u5177\u6267\u884c\u5931\u8d25",
      detail: compactText([
        displayToolName,
        duration ? `${duration}ms` : "",
        success ? "" : (payload.errorMessage || event.errorCode || "")
      ].filter(Boolean).join(" - "), 96),
      status: success ? "done" : "error"
    };
  }
  if (type === "ANSWER") {
    return {
      ...base,
      title: "\u751f\u6210\u56de\u7b54",
      detail: "\u5df2\u7ec4\u7ec7\u6700\u7ec8\u7ed3\u8bba",
      status: "done"
    };
  }
  if (type === "RESULT") {
    return {
      ...base,
      title: status === "PARTIAL" ? "\u83b7\u5f97\u90e8\u5206\u7ed3\u679c" : "\u672a\u4ea7\u751f\u6709\u6548\u7ed3\u679c",
      detail: compactText(payload.executionResult?.message || payload.message || "", 96),
      status: status === "EMPTY" ? "empty" : "partial"
    };
  }
  if (type === "COMPLETE") {
    return null;
  }
  if (type === "ERROR") {
    return {
      ...base,
      title: "\u6267\u884c\u5931\u8d25",
      detail: compactText(payload.message || event.errorCode || "\u540e\u7aef\u4efb\u52a1\u5931\u8d25", 96),
      status: "error"
    };
  }
  if (type === "STATUS") {
    const failed = ["FAILED", "CANCELLED", "KILLED"].includes(status);
    return {
      ...base,
      title: statusTitle(status),
      detail: compactText(payload.message || displayToolName || "", 96),
      status: failed ? (status === "CANCELLED" || status === "KILLED" ? "cancelled" : "error") : "active"
    };
  }
  if (type === "NEEDS_CONFIRMATION") {
    return {
      ...base,
      title: "\u7b49\u5f85\u6743\u9650\u786e\u8ba4",
      detail: compactText(displayToolName || payload.message || "", 96),
      status: "active"
    };
  }
  return null;
}

function mergeStepState(previous = {}, next = {}) {
  return {
    ...previous,
    ...next,
    title: next.title || previous.title || "\u6267\u884c\u6b65\u9aa4",
    detail: next.detail || previous.detail || "",
    status: next.status || previous.status || "pending",
    timestamp: next.timestamp || previous.timestamp || Date.now(),
    order: next.order || previous.order || 0,
    phaseOrder: next.phaseOrder || previous.phaseOrder || 999,
    latencyMs: next.latencyMs ?? previous.latencyMs
  };
}

function initialExecutionSteps(agentName = "") {
  return [
    normalizeExecutionStep({
      id: "submitted",
      title: "\u63d0\u4ea4\u4efb\u52a1",
      detail: agentName ? `Agent: ${agentName}` : "\u5df2\u8fdb\u5165\u540e\u7aef\u6267\u884c\u961f\u5217",
      status: "done",
      timestamp: Date.now()
    }),
    normalizeExecutionStep({
      id: "waiting-events",
      title: "\u83b7\u53d6\u6267\u884c\u6b65\u9aa4",
      detail: "\u6b63\u5728\u540c\u6b65\u540e\u7aef\u8fd0\u884c\u8f68\u8ff9",
      status: "active",
      timestamp: Date.now()
    })
  ];
}

function mergeExecutionSteps(previousSteps = [], events = []) {
  const eventSteps = events
    .filter(Boolean)
    .sort((left, right) => eventOrderValue(left) - eventOrderValue(right))
    .map(agentEventToExecutionStep)
    .filter(Boolean);
  if (!eventSteps.length) {
    return previousSteps.length ? previousSteps : initialExecutionSteps();
  }
  const byId = new Map();
  eventSteps.forEach((step, index) => {
    const normalized = normalizeExecutionStep(step, index);
    byId.set(normalized.id, mergeStepState(byId.get(normalized.id), normalized));
  });
  const steps = [...byId.values()]
    .sort((left, right) => stepPhaseOrder(left) - stepPhaseOrder(right));
  const hasTerminal = events.some(isTerminalAgentEvent);
  if (hasTerminal) {
    return steps.map((step) => step.status === "active" ? { ...step, status: "done" } : step);
  }
  return steps.map((step, index) => {
    if (step.status === "active" && index < steps.length - 1) {
      return { ...step, status: "done" };
    }
    return step;
  });
}

function normalizeResponsePayload(response = {}) {
  const payload = response?.data && typeof response.data === "object" ? response.data : response;
  return {
    ...payload,
    sources: firstArray(
      payload?.sources,
      payload?.references,
      payload?.citations,
      payload?.metadata?.sources
    ),
    toolTraces: firstArray(
      payload?.toolTraces,
      payload?.traces,
      payload?.tool_traces,
      payload?.metadata?.toolTraces,
      payload?.metadata?.traces,
      payload?.metadata?.agent?.toolTraces,
      payload?.metadata?.agent?.traces
    )
  };
}

function submitAgentTask(payload) {
  return apiRequest("/agent/tasks", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

function pollAgentTaskResult(taskId, timeoutMs = AGENT_TASK_POLL_TIMEOUT_MS, tenantId = "") {
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
    heroTitle() {
      if (this.selectedAgent) {
        return compactText(this.selectedAgent.name || "Agent", 32);
      }
      return compactText(this.heroGreeting, 32);
    },
    heroIntro() {
      if (this.selectedAgent) {
        return compactText(this.selectedAgent.firstUseGreeting || this.heroDescription, 108);
      }
      return compactText(this.heroDescription, 108);
    },
    displayAgentResponsibilities() {
      return this.agentResponsibilities
        .filter(Boolean)
        .slice(0, 4)
        .map((item) => compactText(item, 14));
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
      this.emitActiveConversationSnapshot(query, "completed", runContext);
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
        if (answerState?.partial || answerState?.empty) {
          const resultStatus = answerState.empty ? "empty" : "partial";
          runContext.status = resultStatus;
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = resultStatus;
          }
          this.emitActiveConversationSnapshot(query, resultStatus, runContext);
          await this.saveHistory(query, resultStatus, runContext);
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
        status: "streaming",
        steps: initialExecutionSteps(runContext.agentName)
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
      assistantMessage.taskId = runContext.taskId;
      assistantMessage.steps = initialExecutionSteps(runContext.agentName);

      const refreshSteps = () => this.refreshAgentTaskSteps(runContext.taskId, this.userId, runContext, assistantMessage, query);
      await refreshSteps();
      const event = await this.waitForAgentTaskResult(
        runContext.taskId,
        this.userId,
        refreshSteps,
        () => this.isActiveRun(runContext)
      );
      await refreshSteps();
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
        await refreshSteps();
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
        await refreshSteps();
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
        await refreshSteps();
        assistantMessage.streaming = false;
        assistantMessage.status = "failed";
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        throw new Error(message);
      }

      if (eventType === "RESULT" || eventStatus === "PARTIAL" || eventStatus === "EMPTY") {
        const response = eventPayload || {};
        const resultStatus = eventStatus === "EMPTY" ? "empty" : "partial";
        this.applyResponseMetadata(response, runContext);
        assistantMessage.content = response.answer || (resultStatus === "empty"
          ? "\u672c\u6b21\u6267\u884c\u5df2\u7ed3\u675f\uff0c\u4f46\u6ca1\u6709\u4ea7\u751f\u53ef\u5c55\u793a\u7684\u56de\u7b54\u6216\u7ed3\u679c\u4ea7\u7269\u3002"
          : "\u672c\u6b21\u6267\u884c\u5df2\u5b8c\u6210\uff0c\u5e76\u83b7\u53d6\u5230\u90e8\u5206\u5de5\u5177\u7ed3\u679c\u6216\u4e2d\u95f4\u4ea7\u7269\uff0c\u4f46\u6ca1\u6709\u751f\u6210\u6700\u7ec8\u56de\u7b54\u3002");
        assistantMessage.timestamp = response.timestamp || event?.createTime || Date.now();
        assistantMessage.latencyMs = response.latencyMs || event?.latencyMs;
        assistantMessage.sources = runContext.lastResponse.sources;
        assistantMessage.traces = runContext.lastResponse.toolTraces;
        await refreshSteps();
        assistantMessage.streaming = false;
        assistantMessage.status = resultStatus;
        if (this.isActiveRun(runContext)) {
          this.messages = runContext.messages;
          this.scrollMessages();
        }
        this.emitActiveConversationSnapshot(query, resultStatus, runContext);
        return { [resultStatus]: true };
      }

      const response = eventPayload || {};
      this.applyResponseMetadata(response, runContext);
      this.captureMcpConfirmation(response, query, requestPayload, runContext.taskId);
      assistantMessage.content = response.answer || "\u540e\u7aef\u6ca1\u6709\u8fd4\u56de\u6709\u6548\u56de\u7b54\uff0c\u8bf7\u68c0\u67e5\u6a21\u578b\u670d\u52a1\u6216 Agent \u914d\u7f6e\u540e\u91cd\u8bd5\u3002";
      assistantMessage.timestamp = response.timestamp || event?.createTime || Date.now();
      assistantMessage.latencyMs = response.latencyMs;
      assistantMessage.sources = runContext.lastResponse.sources;
      assistantMessage.traces = runContext.lastResponse.toolTraces;
      await refreshSteps();
      assistantMessage.streaming = false;
      assistantMessage.status = "completed";
      if (this.isActiveRun(runContext)) {
        this.messages = runContext.messages;
        this.scrollMessages();
      }
      this.emitActiveConversationSnapshot(query, "completed", runContext);
    },
    async waitForAgentTaskResult(taskId, tenantId = "", onProgress = null, shouldContinue = null) {
      if (!taskId) {
        throw new Error("Agent task was not created");
      }
      while (shouldContinue ? shouldContinue() : true) {
        const leadingProgressEvent = onProgress ? await onProgress() : null;
        if (isTerminalAgentEvent(leadingProgressEvent)) {
          return leadingProgressEvent;
        }
        const event = await pollAgentTaskResult(taskId, AGENT_TASK_POLL_TIMEOUT_MS, tenantId);
        const progressEvent = onProgress ? await onProgress() : null;
        if (isTerminalAgentEvent(event)) {
          return event;
        }
        if (isTerminalAgentEvent(progressEvent)) {
          return progressEvent;
        }
        await sleep(300);
      }
      throw new Error("Agent task polling stopped");
    },
    async refreshAgentTaskSteps(taskId, tenantId, runContext, assistantMessage, query = "") {
      if (!taskId || !assistantMessage) {
        return;
      }
      try {
        const events = await fetchAgentTaskEvents(taskId, AGENT_TASK_EVENT_LIMIT, tenantId);
        assistantMessage.steps = mergeExecutionSteps(assistantMessage.steps || [], Array.isArray(events) ? events : []);
        const terminalEvent = terminalEventFromEvents(events);
        if (this.isActiveRun(runContext)) {
          this.messages = [...runContext.messages];
          this.scrollMessages();
        }
        if (!terminalEvent) {
          this.emitActiveConversationSnapshot(query || runContext?.question || "", "running", runContext);
        }
        return terminalEvent;
      } catch (error) {
        if (!assistantMessage.steps?.length) {
          assistantMessage.steps = initialExecutionSteps(runContext?.agentName || "");
        }
      }
      return null;
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
          status: "completed",
          taskId: targetContext.taskId || ""
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
        steps: [],
        streaming: false,
        status: "completed",
        taskId: "",
        feedbackTime: "",
        feedbackAction: "",
        feedbackUseful: undefined,
        feedbackAdopted: undefined,
        feedbackResolved: undefined,
        feedbackComment: "",
        feedbackReasonCategory: "",
        feedbackSubmitting: false,
        feedbackError: "",
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
      message.steps = initialExecutionSteps(runContext?.agentName || "");
      message.latencyMs = undefined;
      message.timestamp = Date.now();
      message.feedbackTime = "";
      message.feedbackAction = "";
      message.feedbackUseful = undefined;
      message.feedbackAdopted = undefined;
      message.feedbackResolved = undefined;
      message.feedbackComment = "";
      message.feedbackReasonCategory = "";
      message.feedbackSubmitting = false;
      message.feedbackError = "";
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
        lastAssistantMessage.steps = (lastAssistantMessage.steps || []).map((step) =>
          step.status === "active" ? { ...step, status: "cancelled" } : step
        );
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
        steps: message.steps || [],
        streaming: !!message.streaming,
        status: message.status || (message.streaming ? "streaming" : "completed"),
        taskId: message.taskId || "",
        feedbackTime: message.feedbackTime || "",
        feedbackAction: message.feedbackAction || "",
        feedbackUseful: message.feedbackUseful,
        feedbackAdopted: message.feedbackAdopted,
        feedbackResolved: message.feedbackResolved,
        feedbackComment: message.feedbackComment || "",
        feedbackReasonCategory: message.feedbackReasonCategory || ""
      }));
    },
    async handleMessageFeedback({ message, action } = {}) {
      const targetMessage = this.messages.find((item) => item.id === message?.id);
      const feedbackPayload = MESSAGE_FEEDBACK_PAYLOADS[action];
      if (!targetMessage?.taskId || !feedbackPayload || targetMessage.feedbackSubmitting || targetMessage.feedbackTime) {
        return;
      }

      targetMessage.feedbackSubmitting = true;
      targetMessage.feedbackError = "";
      this.messages = [...this.messages];
      try {
        const updatedTask = await submitAgentTaskFeedback(targetMessage.taskId, this.userId, {
          tenantId: this.userId,
          userId: this.userId,
          ...feedbackPayload
        });
        this.applyFeedbackToMessage(targetMessage, action, updatedTask, feedbackPayload);
        this.messages = [...this.messages];
        const question = this.lastUserQuestion();
        this.emitActiveConversationSnapshot(question, this.conversationStatus || "completed");
        await this.saveHistory(question, this.conversationStatus || "completed");
      } catch (error) {
        targetMessage.feedbackError = error.message || "评价提交失败，请稍后重试";
        this.messages = [...this.messages];
      } finally {
        targetMessage.feedbackSubmitting = false;
        this.messages = [...this.messages];
      }
    },
    applyFeedbackToMessage(message, action, updatedTask = {}, fallback = {}) {
      message.feedbackAction = action || "";
      message.feedbackTime = updatedTask?.feedbackTime || new Date().toISOString();
      message.feedbackUseful = updatedTask?.feedbackUseful ?? fallback.useful;
      message.feedbackAdopted = updatedTask?.feedbackAdopted ?? fallback.adopted;
      message.feedbackResolved = updatedTask?.feedbackResolved ?? fallback.resolved;
      message.feedbackComment = updatedTask?.feedbackComment || fallback.comment || "";
      message.feedbackReasonCategory = updatedTask?.feedbackReasonCategory || fallback.reasonCategory || "";
      message.feedbackError = "";
    },
    ensureRunningAssistantMessage(messages, status) {
      if (!isRunningStatus(status)) {
        return messages;
      }
      if (messages.some((message) => message.role === "assistant" && message.streaming)) {
        return messages.map((message) => {
          if (message.role !== "assistant" || !message.streaming || message.steps?.length) {
            return message;
          }
          return {
            ...message,
            steps: initialExecutionSteps(this.selectedAgent?.name || "")
          };
        });
      }
      return [
        ...messages,
        this.createAssistantMessage({
          content: "",
          streaming: true,
          status: "running",
          steps: initialExecutionSteps(this.selectedAgent?.name || ""),
          restored: true
        })
      ];
    },
    applyResponseMetadata(response = {}, runContext = null) {
      const normalizedResponse = normalizeResponsePayload(response);
      const nextResponse = {
        sources: normalizedResponse.sources,
        toolTraces: normalizedResponse.toolTraces
      };
      if (runContext) {
        const previousHistoryId = runContext.historyId;
        const activeRun = this.isActiveRun(runContext);
        runContext.conversationId = normalizedResponse.conversationId || runContext.conversationId;
        if (normalizedResponse.conversationId && runContext.historyId !== normalizedResponse.conversationId) {
          runContext.historyId = normalizedResponse.conversationId;
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
      this.conversationId = normalizedResponse.conversationId || this.conversationId;
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
      const normalizedResponse = normalizeResponsePayload(response);
      const traces = normalizedResponse.toolTraces;
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
        if (answerState?.partial || answerState?.empty) {
          const resultStatus = answerState.empty ? "empty" : "partial";
          runContext.status = resultStatus;
          if (this.isActiveRun(runContext)) {
            this.conversationStatus = resultStatus;
          }
          this.emitActiveConversationSnapshot(query, resultStatus, runContext);
          await this.saveHistory(query, resultStatus, runContext);
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
