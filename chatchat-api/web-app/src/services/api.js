const API_BASE = "/api/v1";
const AUTH_SESSION_KEY = "chatchat.auth.session";
export const AUTH_REQUIRED_EVENT = "chatchat:auth-required";
let lastAuthRequiredEventAt = 0;
const BATCH_UPLOAD_MAX_FILES = 5;
const BATCH_UPLOAD_MAX_BYTES = 4 * 1024 * 1024;

export function getStoredAuthSession() {
  try {
    return JSON.parse(localStorage.getItem(AUTH_SESSION_KEY) || "null");
  } catch (error) {
    return null;
  }
}

export function storeAuthSession(session) {
  localStorage.setItem(AUTH_SESSION_KEY, JSON.stringify(session));
}

export function clearAuthSession() {
  localStorage.removeItem(AUTH_SESSION_KEY);
}

export async function apiRequest(path, options = {}) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
      ...(options.headers || {})
    }
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch (error) {
    payload = null;
  }

  notifyAuthRequiredIfNeeded(response, payload, path);

  if (!response.ok) {
    const userMessage = userFacingApiErrorMessage(response, payload);
    if (userMessage) {
      throw new Error(userMessage);
    }
    throw new Error(payload?.message || `请求失败：${response.status}`);
  }

  return unwrapApiPayload(payload, path);
}

export function sendInteractionMessage(payload) {
  return apiRequest("/interactions/chat", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function sendInteractionMessageStream(payload, handlers = {}) {
  return fetchEventStream("/interactions/chat/stream", payload, handlers);
}

export function submitAgentTask(payload) {
  return apiRequest("/agent/tasks", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function pollAgentTaskResult(taskId, timeoutMs = 5000, tenantId = "") {
  const params = new URLSearchParams();
  params.set("timeoutMs", String(timeoutMs));
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/result?${params.toString()}`);
}

export function cancelAgentTask(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/cancel${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

export function killRuntimeTask(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/tasks/${encodeURIComponent(taskId)}/kill${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

export function submitAgentTaskFeedback(taskId, tenantId = "", payload = {}) {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/feedback${query ? `?${query}` : ""}`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function fetchAgentRuntimeSummary(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.latestLimit) {
    params.set("latestLimit", String(filters.latestLimit));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime${query ? `?${query}` : ""}`);
}

export function fetchAgentTodos(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/todos${query ? `?${query}` : ""}`);
}

export function actAgentTodo(todoId, payload = {}) {
  return apiRequest(`/agent/tasks/runtime/todos/${encodeURIComponent(todoId)}/actions`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function fetchAgentRuntimeToolAudits(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.outcome) {
    params.set("outcome", filters.outcome);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/tool-audits${query ? `?${query}` : ""}`);
}

export function fetchAgentEffectAnalytics(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.lowScoreLimit) {
    params.set("lowScoreLimit", String(filters.lowScoreLimit));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/effects${query ? `?${query}` : ""}`);
}

export function fetchAgentExperiences(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/experiences${query ? `?${query}` : ""}`);
}

export function fetchToolGovernance(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/tool-governance${query ? `?${query}` : ""}`);
}

export function fetchAgentTasks(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.sessionId) {
    params.set("sessionId", filters.sessionId);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks${query ? `?${query}` : ""}`);
}

export function fetchAgentSchedules(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.agentId) {
    params.set("agentId", filters.agentId);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/schedules${query ? `?${query}` : ""}`);
}

export function fetchAgentScheduleNotificationChannels() {
  return apiRequest("/agent/tasks/runtime/schedules/notification-channels");
}

export function saveAgentScheduleNotificationRecipient(channelType, receiver) {
  return apiRequest(`/agent/tasks/runtime/schedules/notification-recipients/${encodeURIComponent(channelType)}`, {
    method: "PUT",
    body: JSON.stringify({ receiver })
  });
}

export function deleteAgentScheduleNotificationRecipient(channelType) {
  return apiRequest(`/agent/tasks/runtime/schedules/notification-recipients/${encodeURIComponent(channelType)}`, {
    method: "DELETE"
  });
}

export function createAgentSchedule(payload) {
  return apiRequest("/agent/tasks/runtime/schedules", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateAgentSchedule(scheduleId, payload) {
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function pauseAgentSchedule(scheduleId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}/pause?${params.toString()}`, {
    method: "POST"
  });
}

export function resumeAgentSchedule(scheduleId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}/resume?${params.toString()}`, {
    method: "POST"
  });
}

export function rerunAgentSchedule(scheduleId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}/rerun?${params.toString()}`, {
    method: "POST"
  });
}

export function deleteAgentSchedule(scheduleId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}?${params.toString()}`, {
    method: "DELETE"
  });
}

export function fetchAgentScheduleHistory(scheduleId, filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}/history${query ? `?${query}` : ""}`);
}

export function fetchAgentScheduleNotificationHistory(scheduleId, filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  params.set("page", String(filters.page || 1));
  params.set("pageSize", String(filters.pageSize || 10));
  return apiRequest(`/agent/tasks/runtime/schedules/${encodeURIComponent(scheduleId)}/notification-history?${params.toString()}`);
}

export function fetchAgentTaskEvents(taskId, limit = 50, tenantId = "") {
  const params = new URLSearchParams();
  params.set("limit", String(limit));
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/events?${params.toString()}`);
}

export function fetchAgentTaskPlan(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/plan${query ? `?${query}` : ""}`);
}

export function fetchAgentTaskPlanDag(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/plan-dag${query ? `?${query}` : ""}`);
}

export function fetchAgentTaskPlanVersions(taskId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/plan/versions${query ? `?${query}` : ""}`);
}

export function fetchGenericAgentRuntimeSnapshot() {
  return apiRequest("/agent/runtime/snapshot");
}

export function fetchGenericAgentRuns(filters = {}) {
  const params = new URLSearchParams();
  if (filters.status) {
    params.set("status", filters.status);
  }
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.conversationId) {
    params.set("conversationId", filters.conversationId);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  if (filters.offset) {
    params.set("offset", String(filters.offset));
  }
  const query = params.toString();
  return apiRequest(`/agent/runtime/runs${query ? `?${query}` : ""}`);
}

export function fetchGenericAgentRunTimeline(runId, filters = {}) {
  const params = new URLSearchParams();
  if (filters.afterCreatedAt) {
    params.set("afterCreatedAt", String(filters.afterCreatedAt));
  }
  if (filters.eventLimit) {
    params.set("eventLimit", String(filters.eventLimit));
  }
  if (filters.afterStep) {
    params.set("afterStep", String(filters.afterStep));
  }
  if (filters.stepLimit) {
    params.set("stepLimit", String(filters.stepLimit));
  }
  if (filters.observationOffset) {
    params.set("observationOffset", String(filters.observationOffset));
  }
  if (filters.observationLimit) {
    params.set("observationLimit", String(filters.observationLimit));
  }
  const query = params.toString();
  return apiRequest(`/agent/runtime/runs/${encodeURIComponent(runId)}/timeline${query ? `?${query}` : ""}`);
}

export function fetchGenericAgentRunTrace(runId) {
  return apiRequest(`/agent/runtime/runs/${encodeURIComponent(runId)}/trace`);
}

export function evaluateGenericAgentRun(runId, payload = {}) {
  return apiRequest(`/agent/runtime/runs/${encodeURIComponent(runId)}/evaluation`, {
    method: "POST",
    body: JSON.stringify(payload || {})
  });
}

export function cancelGenericAgentRun(runId) {
  return apiRequest(`/agent/runtime/runs/${encodeURIComponent(runId)}/cancel`, {
    method: "POST"
  });
}

export function streamGenericAgentRunEvents(runId, filters = {}, handlers = {}) {
  const params = new URLSearchParams();
  if (filters.afterCreatedAt) {
    params.set("afterCreatedAt", String(filters.afterCreatedAt));
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  if (filters.pollIntervalMs) {
    params.set("pollIntervalMs", String(filters.pollIntervalMs));
  }
  if (filters.timeoutMs) {
    params.set("timeoutMs", String(filters.timeoutMs));
  }
  const query = params.toString();
  return fetchEventStreamGet(
    `/agent/runtime/runs/${encodeURIComponent(runId)}/events/stream${query ? `?${query}` : ""}`,
    handlers
  );
}

export function fetchRetrievalRules() {
  return apiRequest("/retrieval/rules");
}

export function refreshRetrievalRules() {
  return apiRequest("/retrieval/rules/refresh", {
    method: "POST"
  });
}

export function publishRetrievalRules() {
  return apiRequest("/retrieval/rules/versions/publish", {
    method: "POST"
  });
}

export function publishRetrievalRuleType(type, version = "") {
  const params = new URLSearchParams();
  if (version) {
    params.set("version", String(version));
  }
  const query = params.toString();
  return apiRequest(`/retrieval/rules/versions/${encodeURIComponent(type)}/publish${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

export function activateRetrievalRuleVersion(type, version) {
  return apiRequest(`/retrieval/rules/versions/${encodeURIComponent(type)}/activate/${encodeURIComponent(version)}`, {
    method: "POST"
  });
}

export function saveIntentRule(payload) {
  const id = payload?.id;
  return apiRequest(`/retrieval/rules/intent${id ? `/${encodeURIComponent(id)}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify(payload || {})
  });
}

export function deleteIntentRule(id) {
  return apiRequest(`/retrieval/rules/intent/${encodeURIComponent(id)}`, {
    method: "DELETE"
  });
}

export function saveChunkTypeRule(payload) {
  const id = payload?.id;
  return apiRequest(`/retrieval/rules/chunk-type${id ? `/${encodeURIComponent(id)}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify(payload || {})
  });
}

export function deleteChunkTypeRule(id) {
  return apiRequest(`/retrieval/rules/chunk-type/${encodeURIComponent(id)}`, {
    method: "DELETE"
  });
}

export function saveExpandRule(payload) {
  const id = payload?.id;
  return apiRequest(`/retrieval/rules/expand${id ? `/${encodeURIComponent(id)}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify(payload || {})
  });
}

export function deleteExpandRule(id) {
  return apiRequest(`/retrieval/rules/expand/${encodeURIComponent(id)}`, {
    method: "DELETE"
  });
}

export function saveSemanticLexiconEntry(payload) {
  const id = payload?.id;
  return apiRequest(`/retrieval/rules/lexicon${id ? `/${encodeURIComponent(id)}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify(payload || {})
  });
}

export function deleteSemanticLexiconEntry(id) {
  return apiRequest(`/retrieval/rules/lexicon/${encodeURIComponent(id)}`, {
    method: "DELETE"
  });
}

export function fetchConversationHistory(userId, filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  if (filters.status) {
    params.set("status", filters.status);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  const query = params.toString();
  return apiRequest(`/data/history/${encodeURIComponent(userId)}${query ? `?${query}` : ""}`);
}

export function saveConversationHistory(payload) {
  return apiRequest("/data/history", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateConversationHistoryStatus(userId, historyId, payload) {
  const params = new URLSearchParams();
  if (payload?.tenantId) {
    params.set("tenantId", payload.tenantId);
  }
  const query = params.toString();
  return apiRequest(`/data/history/${encodeURIComponent(userId)}/${encodeURIComponent(historyId)}/status${query ? `?${query}` : ""}`, {
    method: "PATCH",
    body: JSON.stringify(payload)
  });
}

export function deleteConversationHistory(userId, historyId, tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/data/history/${encodeURIComponent(userId)}/${encodeURIComponent(historyId)}${query ? `?${query}` : ""}`, {
    method: "DELETE"
  });
}

export function fetchWorkbenchShortcuts(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  if (filters.category) {
    params.set("category", filters.category);
  }
  if (filters.targetType) {
    params.set("targetType", filters.targetType);
  }
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  const query = params.toString();
  return apiRequest(`/data/workbench${query ? `?${query}` : ""}`);
}

export function recordUserActivity(payload) {
  return apiRequest("/data/workbench/activities", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function addUserFavorite(payload) {
  return apiRequest("/data/workbench/favorites", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function removeUserFavorite(favoriteId) {
  return apiRequest(`/data/workbench/favorites/${encodeURIComponent(favoriteId)}`, {
    method: "DELETE"
  });
}

export function fetchSkills(filters = {}) {
  const params = new URLSearchParams();
  if (filters.scope) {
    params.set("scope", filters.scope);
  }
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  if (filters.category) {
    params.set("category", filters.category);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/data/skills${query ? `?${query}` : ""}`);
}

export function createSkill(payload) {
  return apiRequest("/data/skills", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateSkill(skillId, payload) {
  return apiRequest(`/data/skills/${encodeURIComponent(skillId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function deleteSkill(skillId) {
  return apiRequest(`/data/skills/${encodeURIComponent(skillId)}`, {
    method: "DELETE"
  });
}

export function setDefaultAgentSkill(skillId) {
  return apiRequest(`/data/skills/${encodeURIComponent(skillId)}/default-agent`, {
    method: "POST"
  });
}

export function fetchSkillVersions(skillId) {
  return apiRequest(`/data/skills/${encodeURIComponent(skillId)}/versions`);
}

export function rollbackSkillVersion(skillId, versionId) {
  return apiRequest(`/data/skills/${encodeURIComponent(skillId)}/rollback/${encodeURIComponent(versionId)}`, {
    method: "POST"
  });
}

export function fetchAgentWorkshop(filters = {}) {
  const params = new URLSearchParams();
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  if (filters.category) {
    params.set("category", filters.category);
  }
  if (filters.status) {
    params.set("status", filters.status);
  }
  if (filters.model) {
    params.set("model", filters.model);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/agents/workshop${query ? `?${query}` : ""}`);
}

export function createWorkshopAgent(payload) {
  return apiRequest("/agents/workshop", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateWorkshopAgent(agentId, payload) {
  return apiRequest(`/agents/workshop/${encodeURIComponent(agentId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function deleteWorkshopAgent(agentId) {
  return apiRequest(`/agents/workshop/${encodeURIComponent(agentId)}`, {
    method: "DELETE"
  });
}

export function setDefaultWorkshopAgent(agentId) {
  return apiRequest(`/agents/workshop/${encodeURIComponent(agentId)}/default`, {
    method: "POST"
  });
}

export function publishWorkshopAgent(agentId) {
  return apiRequest(`/agents/workshop/${encodeURIComponent(agentId)}/publish`, {
    method: "POST"
  });
}

export function recallWorkshopAgent(agentId) {
  return apiRequest(`/agents/workshop/${encodeURIComponent(agentId)}/recall`, {
    method: "POST"
  });
}

export function fetchToolNames() {
  return apiRequest("/data/tools");
}

export function fetchMcpServices() {
  return apiRequest("/mcp/services");
}

export function fetchMcpRegisteredTools() {
  return apiRequest("/mcp/tools");
}

export function fetchMcpToolCards(filters = {}) {
  const params = new URLSearchParams();
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  if (filters.service) {
    params.set("service", filters.service);
  }
  if (filters.category) {
    params.set("category", filters.category);
  }
  if (filters.sourceType) {
    params.set("sourceType", filters.sourceType);
  }
  if (filters.groupMode) {
    params.set("groupMode", filters.groupMode);
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/mcp/tool-cards${query ? `?${query}` : ""}`);
}

export function refreshMcpRegistry() {
  return apiRequest("/mcp/refresh", {
    method: "POST"
  });
}

export function fetchMcpCenterStatus() {
  return apiRequest("/mcp/center/status");
}

export function syncMcpCenter() {
  return apiRequest("/mcp/center/sync", {
    method: "POST"
  });
}

export function searchDocuments(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.roles) {
    params.set("roles", Array.isArray(filters.roles) ? filters.roles.join(",") : filters.roles);
  }
  if (filters.keyword) {
    params.set("keyword", filters.keyword);
  }
  if (filters.tag) {
    params.set("tag", filters.tag);
  }
  if (filters.company) {
    params.set("company", filters.company);
  }
  if (filters.industry) {
    params.set("industry", filters.industry);
  }
  if (filters.docIds) {
    params.set("docIds", Array.isArray(filters.docIds) ? filters.docIds.join(",") : filters.docIds);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/search/frontend${query ? `?${query}` : ""}`);
}

export function debugDocumentDecision(payload = {}) {
  return apiRequest("/search/document-search", {
    method: "POST",
    body: JSON.stringify({
      query: payload.query || "",
      topK: payload.topK || 8,
      filters: payload.filters || null,
      fileIds: payload.fileIds || [],
      tenantId: payload.tenantId || "",
      userId: payload.userId || "",
      roles: payload.roles || [],
      debug: payload.debug === true
    })
  });
}

function searchPermissionQuery(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.roles) {
    params.set("roles", Array.isArray(filters.roles) ? filters.roles.join(",") : filters.roles);
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function getSearchDocument(docId, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}${searchPermissionQuery(filters)}`);
}

export function getSearchDocumentVersions(docId, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/versions${searchPermissionQuery(filters)}`);
}

export function getSearchDocumentVersion(docId, version, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}${searchPermissionQuery(filters)}`);
}

export function deleteSearchDocument(docId, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}${searchPermissionQuery(filters)}`, {
    method: "DELETE"
  });
}

export function deleteSearchDocuments(docIds, filters = {}) {
  return apiRequest(`/search/documents/delete/batch${searchPermissionQuery(filters)}`, {
    method: "POST",
    body: JSON.stringify({ docIds: Array.isArray(docIds) ? docIds : [] })
  });
}

export function reindexSearchDocument(docId, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/reindex${searchPermissionQuery(filters)}`, {
    method: "POST"
  });
}

export async function fetchDocumentFile(fileUrl) {
  const session = getStoredAuthSession();
  const response = await fetch(fileUrl, {
    headers: {
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {})
    }
  });

  if (!response.ok) {
    const errorPayload = await readJsonSafely(response);
    notifyAuthRequiredIfNeeded(response, errorPayload, fileUrl);
    throw new Error(errorPayload?.message || `文档文件下载失败：${response.status}`);
  }

  const contentType = response.headers.get("Content-Type") || "";
  if (contentType.includes("application/json")) {
    const errorPayload = await readJsonSafely(response);
    throw new Error(errorPayload?.message || "文档文件返回格式异常");
  }

  const buffer = await response.arrayBuffer();
  if (!buffer.byteLength) {
    throw new Error("文档文件为空，无法预览");
  }

  return {
    buffer,
    contentType,
    fileName: response.headers.get("Content-Disposition") || ""
  };
}

export function fetchResearchLibrary(filters = {}) {
  const params = new URLSearchParams();
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.roles) {
    params.set("roles", Array.isArray(filters.roles) ? filters.roles.join(",") : filters.roles);
  }
  if (filters.category) {
    params.set("category", filters.category);
  }
  if (filters.title) {
    params.set("title", filters.title);
  }
  if (filters.limit) {
    params.set("limit", String(filters.limit));
  }
  if (filters.page) {
    params.set("page", String(filters.page));
  }
  if (filters.pageSize) {
    params.set("pageSize", String(filters.pageSize));
  }
  const query = params.toString();
  return apiRequest(`/search/library${query ? `?${query}` : ""}`);
}

export function checkDocumentTitleExists(title, filters = {}) {
  const params = new URLSearchParams({ title });
  if (filters.tenantId) {
    params.set("tenantId", filters.tenantId);
  }
  if (filters.userId) {
    params.set("userId", filters.userId);
  }
  if (filters.roles) {
    params.set("roles", Array.isArray(filters.roles) ? filters.roles.join(",") : filters.roles);
  }
  return apiRequest(`/search/documents/title-exists?${params.toString()}`);
}

export function createResearchCategory(name) {
  return apiRequest("/search/library/categories", {
    method: "POST",
    body: JSON.stringify({ name })
  });
}

export function renameResearchCategory(name, nextName) {
  return apiRequest(`/search/library/categories/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify({ name: nextName })
  });
}

export function deleteResearchCategory(name) {
  return apiRequest(`/search/library/categories/${encodeURIComponent(name)}`, {
    method: "DELETE"
  });
}

export function reindexResearchCategory(name, filters = {}) {
  return apiRequest(`/search/library/categories/reindex${searchPermissionQuery(filters)}`, {
    method: "POST",
    body: JSON.stringify({ name })
  });
}

export function fetchResearchCategoryReindexStatus(filters = {}) {
  return apiRequest(`/search/library/categories/reindex/status${searchPermissionQuery(filters)}`);
}

export function updateSearchDocumentCategory(docId, category, filters = {}) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/category${searchPermissionQuery(filters)}`, {
    method: "PUT",
    body: JSON.stringify({ category })
  });
}

export function fetchEnterpriseSummary() {
  return apiRequest("/enterprise/summary");
}

export async function loginEnterprise(payload) {
  const session = await apiRequest("/enterprise/auth/login", {
    method: "POST",
    body: JSON.stringify(payload)
  });
  if (session?.token) {
    storeAuthSession(session);
  }
  return session;
}

export async function loginEnterpriseWithEmbedToken(token) {
  const session = await apiRequest("/enterprise/auth/embed-login", {
    method: "POST",
    body: JSON.stringify({ token })
  });
  if (session?.token) {
    storeAuthSession(session);
  }
  return session;
}

export function fetchEmbedLoginTokens() {
  return apiRequest("/enterprise/auth/embed-tokens");
}

export function createEmbedLoginToken(expiresInSeconds) {
  return apiRequest("/enterprise/auth/embed-tokens", {
    method: "POST",
    body: JSON.stringify({ expiresInSeconds })
  });
}

export function expireEmbedLoginToken(tokenId) {
  return apiRequest(`/enterprise/auth/embed-tokens/${encodeURIComponent(tokenId)}/expire`, {
    method: "POST"
  });
}

export function fetchTenants() {
  return apiRequest("/enterprise/tenants");
}

export function fetchOrgs(tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/enterprise/orgs${query ? `?${query}` : ""}`);
}

export function createOrg(payload) {
  return apiRequest("/enterprise/orgs", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateOrg(orgId, payload) {
  return apiRequest(`/enterprise/orgs/${encodeURIComponent(orgId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function deleteOrg(orgId) {
  return apiRequest(`/enterprise/orgs/${encodeURIComponent(orgId)}`, {
    method: "DELETE"
  });
}

export function fetchRoles(tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/enterprise/roles${query ? `?${query}` : ""}`);
}

export function createRole(payload) {
  return apiRequest("/enterprise/roles", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateRole(roleId, payload) {
  return apiRequest(`/enterprise/roles/${encodeURIComponent(roleId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function deleteRole(roleId) {
  return apiRequest(`/enterprise/roles/${encodeURIComponent(roleId)}`, {
    method: "DELETE"
  });
}

export function fetchUsers(tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/enterprise/users${query ? `?${query}` : ""}`);
}

export function fetchLoginAuditLogs(filters = {}) {
  const params = new URLSearchParams();
  Object.entries(filters || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value) !== "") {
      params.set(key, String(value));
    }
  });
  const query = params.toString();
  return apiRequest(`/enterprise/audit-logs/logins${query ? `?${query}` : ""}`);
}

export function createUser(payload) {
  return apiRequest("/enterprise/users", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updateUser(userId, payload) {
  return apiRequest(`/enterprise/users/${encodeURIComponent(userId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function changeAdminPassword(payload) {
  return apiRequest("/enterprise/users/admin/password", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function deleteUser(userId) {
  return apiRequest(`/enterprise/users/${encodeURIComponent(userId)}`, {
    method: "DELETE"
  });
}

export function fetchPermissions() {
  return apiRequest("/enterprise/permissions");
}

export function fetchAgentOptions() {
  return apiRequest("/enterprise/agent-options");
}

export function createPermission(payload) {
  return apiRequest("/enterprise/permissions", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function updatePermission(permissionId, payload) {
  return apiRequest(`/enterprise/permissions/${encodeURIComponent(permissionId)}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function deletePermission(permissionId) {
  return apiRequest(`/enterprise/permissions/${encodeURIComponent(permissionId)}`, {
    method: "DELETE"
  });
}

export function fetchRoleAuthorization(roleId) {
  return apiRequest(`/enterprise/roles/${encodeURIComponent(roleId)}/authorization`);
}

export function saveRoleAuthorization(roleId, payload) {
  return apiRequest(`/enterprise/roles/${encodeURIComponent(roleId)}/authorization`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
}

export function syncEnterpriseOrgs(tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/enterprise/sync/orgs${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

export function syncEnterpriseUsers(tenantId = "") {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  const query = params.toString();
  return apiRequest(`/enterprise/sync/users${query ? `?${query}` : ""}`, {
    method: "POST"
  });
}

export async function uploadSearchDocument(formData, options = {}) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}/search/documents/upload`, {
    method: "POST",
    headers: {
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
      ...(options.uploadRequestId ? { "X-Upload-Request-Id": options.uploadRequestId } : {})
    },
    body: formData,
    signal: options.signal
  });
  const payload = await readJsonSafely(response);
  notifyAuthRequiredIfNeeded(response, payload, "/search/documents/upload");
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败：${response.status}`);
  }
  return unwrapApiPayload(payload, "/search/documents/upload");
}

export async function uploadSearchDocuments(formData, options = {}) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}/search/documents/upload/batch`, {
    method: "POST",
    headers: {
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
      ...(options.uploadRequestId ? { "X-Upload-Request-Id": options.uploadRequestId } : {})
    },
    body: formData,
    signal: options.signal
  });
  const payload = await readJsonSafely(response);
  notifyAuthRequiredIfNeeded(response, payload, "/search/documents/upload/batch");
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败：${response.status}`);
  }
  return unwrapApiPayload(payload, "/search/documents/upload/batch");
}

export async function uploadSearchDocumentsInBatches(formData, files, options = {}) {
  const fileList = Array.from(files || []).filter(Boolean);
  if (!fileList.length) {
    return [];
  }
  const batches = createUploadBatches(
    fileList,
    options.maxFiles || BATCH_UPLOAD_MAX_FILES,
    options.maxBytes || BATCH_UPLOAD_MAX_BYTES
  );
  const uploaded = [];
  for (let index = 0; index < batches.length; index += 1) {
    const batchFormData = cloneFormDataWithoutFiles(formData);
    batches[index].forEach((file) => batchFormData.append("files", file));
    try {
      const result = await uploadSearchDocuments(batchFormData, options);
      uploaded.push(...(Array.isArray(result) ? result : [result]));
    } catch (error) {
      if (error?.name === "AbortError") {
        throw error;
      }
      throw new Error(`第 ${index + 1}/${batches.length} 批上传失败：${error.message || "上传失败"}`);
    }
  }
  return uploaded;
}

export function cancelSearchDocumentUpload(uploadRequestId) {
  const normalized = String(uploadRequestId || "").trim();
  if (!normalized) {
    return Promise.resolve(false);
  }
  return apiRequest(`/search/documents/upload/${encodeURIComponent(normalized)}/cancel`, {
    method: "POST"
  });
}

function createUploadBatches(files, maxFiles, maxBytes) {
  const batches = [];
  let batch = [];
  let batchBytes = 0;
  files.forEach((file) => {
    const fileBytes = Number(file?.size || 0);
    if (batch.length && (batch.length >= maxFiles || batchBytes + fileBytes > maxBytes)) {
      batches.push(batch);
      batch = [];
      batchBytes = 0;
    }
    batch.push(file);
    batchBytes += fileBytes;
  });
  if (batch.length) {
    batches.push(batch);
  }
  return batches;
}

function cloneFormDataWithoutFiles(formData) {
  const cloned = new FormData();
  for (const [key, value] of formData.entries()) {
    if (key !== "file" && key !== "files") {
      cloned.append(key, value);
    }
  }
  return cloned;
}

export async function uploadChatImage(formData) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}/images/upload`, {
    method: "POST",
    headers: {
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {})
    },
    body: formData
  });
  const payload = await readJsonSafely(response);
  notifyAuthRequiredIfNeeded(response, payload, "/images/upload");
  if (!response.ok) {
    throw new Error(payload?.message || `Request failed: ${response.status}`);
  }
  return unwrapApiPayload(payload, "/images/upload");
}

export function analyzeChatImage(payload) {
  return apiRequest("/images/analyze", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function fetchImageAnalysis(analysisId) {
  return apiRequest(`/images/analysis/${encodeURIComponent(analysisId)}`);
}

async function fetchEventStream(path, payload, handlers) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {})
    },
    body: JSON.stringify(payload)
  });

  const contentType = response.headers.get("Content-Type") || "";
  if (!response.ok) {
    const errorPayload = await readJsonSafely(response);
    notifyAuthRequiredIfNeeded(response, errorPayload, path);
    throw new Error(errorPayload?.message || `请求失败：${response.status}`);
  }

  if (!contentType.includes("text/event-stream") || !response.body) {
    const directPayload = unwrapApiPayload(await readJsonSafely(response), path);
    handlers.direct?.(directPayload);
    handlers.done?.({ direct: true });
    return {
      type: "direct",
      data: directPayload
    };
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() || "";
    parts.forEach((part) => dispatchSseEvent(part, handlers));
  }

  if (buffer.trim()) {
    dispatchSseEvent(buffer, handlers);
  }

  return {
    type: "stream"
  };
}

async function fetchEventStreamGet(path, handlers = {}) {
  const session = getStoredAuthSession();
  const response = await fetch(`${API_BASE}${path}`, {
    method: "GET",
    signal: handlers.signal,
    headers: {
      Accept: "text/event-stream",
      ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {})
    }
  });

  const contentType = response.headers.get("Content-Type") || "";
  if (!response.ok) {
    const errorPayload = await readJsonSafely(response);
    notifyAuthRequiredIfNeeded(response, errorPayload, path);
    throw new Error(errorPayload?.message || `璇锋眰澶辫触锛?{response.status}`);
  }

  if (!contentType.includes("text/event-stream") || !response.body) {
    const directPayload = unwrapApiPayload(await readJsonSafely(response), path);
    handlers.direct?.(directPayload);
    handlers.done?.({ direct: true });
    return {
      type: "direct",
      data: directPayload
    };
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() || "";
    parts.forEach((part) => dispatchSseEvent(part, handlers));
  }

  if (buffer.trim()) {
    dispatchSseEvent(buffer, handlers);
  }

  return {
    type: "stream"
  };
}

async function readJsonSafely(response) {
  try {
    return await response.json();
  } catch (error) {
    return null;
  }
}

function isAuthFailure(response, payload) {
  const status = response?.status || Number(payload?.status || payload?.code || 0);
  const message = String(payload?.message || payload?.error || "");
  return status === 401 || payload?.code === 401 || message.includes("请先登录");
}

function userFacingApiErrorMessage(response, payload) {
  const message = String(payload?.message || payload?.error || "");
  if (isAuthFailure(response, payload)) {
    return message || "请先登录";
  }
  if (isInfrastructureFailure(message)) {
    return "服务暂不可用，请稍后重试";
  }
  return "";
}

function isInfrastructureFailure(message) {
  const normalized = String(message || "").toLowerCase();
  return [
    "could not open jpa entitymanager",
    "entitymanager",
    "jdbc",
    "hikaripool",
    "connection is not available",
    "dataaccessresourcefailure",
    "sqltransientconnection"
  ].some((keyword) => normalized.includes(keyword));
}

function notifyAuthRequiredIfNeeded(response, payload, path = "") {
  if (!isAuthFailure(response, payload) || String(path).includes("/enterprise/auth/login")) {
    return;
  }
  clearAuthSession();
  const now = Date.now();
  if (now - lastAuthRequiredEventAt < 500) {
    return;
  }
  lastAuthRequiredEventAt = now;
  if (typeof window !== "undefined") {
    window.dispatchEvent(
      new CustomEvent(AUTH_REQUIRED_EVENT, {
        detail: {
          path,
          status: response?.status || payload?.code || 401,
          message: payload?.message || "请先登录"
        }
      })
    );
  }
}

function unwrapApiPayload(payload, path = "") {
  if (payload && typeof payload === "object" && "code" in payload) {
    if (payload.code !== 200) {
      notifyAuthRequiredIfNeeded(null, payload, path);
      const userMessage = userFacingApiErrorMessage(null, payload);
      if (userMessage) {
        throw new Error(userMessage);
      }
      throw new Error(payload.message || "服务端返回异常");
    }
    return payload.data;
  }
  return payload;
}

function dispatchSseEvent(chunk, handlers) {
  const lines = chunk.split(/\r?\n/);
  const event = lines.find((line) => line.startsWith("event:"))?.slice(6).trim() || "message";
  const data = lines
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trim())
    .join("\n");

  let payload = data;
  try {
    payload = JSON.parse(data);
  } catch (error) {
    payload = data;
  }

  handlers[event]?.(payload);
  handlers.message?.({ event, data: payload });
}
