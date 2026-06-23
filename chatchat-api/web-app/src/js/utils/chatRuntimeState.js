const CHAT_RUNTIME_STATE_KEY = "chatchat.chatRuntime.state";
const CHAT_RUNTIME_STATE_VERSION = 1;
const CHAT_RUNTIME_STATE_TTL_MS = 30 * 60 * 1000;
const ACTIVE_STATUSES = new Set(["running", "streaming", "pending", "waiting"]);
const TERMINAL_STATUSES = new Set(["completed", "success", "partial", "empty", "failed", "cancelled", "killed", "rejected"]);

function normalizeStatus(value = "") {
  return String(value || "").trim().toLowerCase();
}

function now() {
  return Date.now();
}

function canUseLocalStorage() {
  return typeof window !== "undefined" && !!window.localStorage;
}

function normalizeEntry(entry = {}) {
  const status = normalizeStatus(entry.status || "running");
  return {
    conversationId: String(entry.conversationId || entry.historyId || "").trim(),
    messageId: String(entry.messageId || "").trim(),
    taskId: String(entry.taskId || "").trim(),
    question: String(entry.question || "").trim(),
    agentName: String(entry.agentName || "").trim(),
    modelName: String(entry.modelName || "").trim(),
    status,
    streaming: entry.streaming !== false,
    lastSync: Number(entry.lastSync || now())
  };
}

function entryKey(entry = {}) {
  return entry.conversationId || entry.taskId || entry.messageId || "";
}

function isFresh(entry = {}) {
  const lastSync = Number(entry.lastSync || 0);
  return Number.isFinite(lastSync) && now() - lastSync <= CHAT_RUNTIME_STATE_TTL_MS;
}

export function isRuntimeStateActiveStatus(status = "") {
  return ACTIVE_STATUSES.has(normalizeStatus(status));
}

export function isRuntimeStateTerminalStatus(status = "") {
  return TERMINAL_STATUSES.has(normalizeStatus(status));
}

export function readChatRuntimeState() {
  if (!canUseLocalStorage()) {
    return { version: CHAT_RUNTIME_STATE_VERSION, updatedAt: now(), entries: {} };
  }
  try {
    const parsed = JSON.parse(window.localStorage.getItem(CHAT_RUNTIME_STATE_KEY) || "null");
    const entries = parsed && typeof parsed === "object" && parsed.entries && typeof parsed.entries === "object"
      ? parsed.entries
      : {};
    const nextEntries = Object.fromEntries(
      Object.entries(entries)
        .map(([key, value]) => [key, normalizeEntry(value)])
        .filter(([, value]) => isFresh(value) && !isRuntimeStateTerminalStatus(value.status))
    );
    return {
      version: CHAT_RUNTIME_STATE_VERSION,
      updatedAt: Number(parsed?.updatedAt || now()),
      entries: nextEntries
    };
  } catch (error) {
    return { version: CHAT_RUNTIME_STATE_VERSION, updatedAt: now(), entries: {} };
  }
}

function writeChatRuntimeState(state) {
  if (!canUseLocalStorage()) {
    return;
  }
  try {
    window.localStorage.setItem(CHAT_RUNTIME_STATE_KEY, JSON.stringify({
      version: CHAT_RUNTIME_STATE_VERSION,
      updatedAt: now(),
      entries: state.entries || {}
    }));
  } catch (error) {
    // Runtime restore is best-effort; history remains the source of truth.
  }
}

export function upsertChatRuntimeState(entry = {}) {
  const normalized = normalizeEntry({
    ...entry,
    lastSync: now()
  });
  const key = entryKey(normalized);
  if (!key) {
    return;
  }
  if (isRuntimeStateTerminalStatus(normalized.status)) {
    clearChatRuntimeState(normalized);
    return;
  }
  const state = readChatRuntimeState();
  state.entries[key] = normalized;
  writeChatRuntimeState(state);
}

export function clearChatRuntimeState(match = {}) {
  const conversationId = String(match.conversationId || match.historyId || "").trim();
  const taskId = String(match.taskId || "").trim();
  const messageId = String(match.messageId || "").trim();
  const state = readChatRuntimeState();
  const entries = Object.fromEntries(
    Object.entries(state.entries || {}).filter(([, entry]) => {
      if (conversationId && entry.conversationId === conversationId) {
        return false;
      }
      if (taskId && entry.taskId === taskId) {
        return false;
      }
      if (messageId && entry.messageId === messageId) {
        return false;
      }
      return true;
    })
  );
  writeChatRuntimeState({ ...state, entries });
}

export function runtimeStateForConversation(conversation = {}) {
  const ids = new Set([conversation.id, conversation.conversationId].filter(Boolean).map(String));
  const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
  const messageIds = new Set(messages.map((message) => message?.id).filter(Boolean).map(String));
  const taskIds = new Set(messages.map((message) => message?.taskId).filter(Boolean).map(String));
  return Object.values(readChatRuntimeState().entries || {}).find((entry) =>
    isFresh(entry)
    && isRuntimeStateActiveStatus(entry.status)
    && (
      (entry.conversationId && ids.has(entry.conversationId))
      || (entry.messageId && messageIds.has(entry.messageId))
      || (entry.taskId && taskIds.has(entry.taskId))
    )
  ) || null;
}

export function mergeChatRuntimeState(conversation = {}) {
  const backendStatus = normalizeStatus(conversation.status);
  if (isRuntimeStateTerminalStatus(backendStatus)) {
    return conversation;
  }
  const entry = runtimeStateForConversation(conversation);
  if (!entry) {
    return conversation;
  }
  const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
  const hasBackendTask = messages.some((message) => message?.role === "assistant" && message.taskId);
  const activeStatus = backendStatus && isRuntimeStateActiveStatus(backendStatus)
    ? backendStatus
    : entry.status || "running";
  if (hasBackendTask) {
    return {
      ...conversation,
      status: conversation.status || activeStatus
    };
  }

  const assistantIndex = [...messages].reverse().findIndex((message) =>
    message?.role === "assistant"
    && (message.streaming || isRuntimeStateActiveStatus(message.status))
  );
  const realIndex = assistantIndex < 0 ? -1 : messages.length - 1 - assistantIndex;
  const runtimeMessage = {
    id: entry.messageId || `${entry.taskId || entry.conversationId}-runtime`,
    role: "assistant",
    content: "",
    timestamp: entry.lastSync || now(),
    sources: [],
    traces: [],
    steps: [],
    streaming: entry.streaming !== false,
    status: activeStatus,
    taskId: entry.taskId || "",
    agentName: entry.agentName || "",
    modelName: entry.modelName || ""
  };
  const nextMessages = realIndex >= 0
    ? messages.map((message, index) => index === realIndex
      ? {
          ...message,
          id: message.id || runtimeMessage.id,
          timestamp: message.timestamp || runtimeMessage.timestamp,
          streaming: runtimeMessage.streaming,
          status: activeStatus,
          taskId: message.taskId || runtimeMessage.taskId,
          agentName: message.agentName || runtimeMessage.agentName,
          modelName: message.modelName || runtimeMessage.modelName,
          steps: Array.isArray(message.steps) && message.steps.length ? message.steps : runtimeMessage.steps
        }
      : message)
    : [...messages, runtimeMessage];

  return {
    ...conversation,
    status: activeStatus,
    timestamp: conversation.timestamp || entry.lastSync,
    messages: nextMessages
  };
}
