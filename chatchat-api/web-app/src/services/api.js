const API_BASE = "/api/v1";

export async function apiRequest(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch (error) {
    payload = null;
  }

  if (!response.ok) {
    throw new Error(payload?.message || `请求失败：${response.status}`);
  }

  return unwrapApiPayload(payload);
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

export function pollAgentTaskResult(taskId, timeoutMs = 1200, tenantId = "") {
  const params = new URLSearchParams();
  params.set("timeoutMs", String(timeoutMs));
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/result?${params.toString()}`);
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

export function fetchAgentTaskEvents(taskId, limit = 50, tenantId = "") {
  const params = new URLSearchParams();
  params.set("limit", String(limit));
  if (tenantId) {
    params.set("tenantId", tenantId);
  }
  return apiRequest(`/agent/tasks/${encodeURIComponent(taskId)}/events?${params.toString()}`);
}

export function fetchConversationHistory(userId, filters = {}) {
  const params = new URLSearchParams();
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
  return apiRequest(`/data/history/${encodeURIComponent(userId)}/${encodeURIComponent(historyId)}/status`, {
    method: "PATCH",
    body: JSON.stringify(payload)
  });
}

export function deleteConversationHistory(userId, historyId) {
  return apiRequest(`/data/history/${encodeURIComponent(userId)}/${encodeURIComponent(historyId)}`, {
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
  return apiRequest(`/search${query ? `?${query}` : ""}`);
}

export function getSearchDocument(docId) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}`);
}

export function getSearchDocumentVersions(docId) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/versions`);
}

export function getSearchDocumentVersion(docId, version) {
  return apiRequest(`/search/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}`);
}

export function fetchResearchLibrary(filters = {}) {
  const params = new URLSearchParams();
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

export function checkDocumentTitleExists(title) {
  const params = new URLSearchParams({ title });
  return apiRequest(`/search/documents/title-exists?${params.toString()}`);
}

export function createResearchCategory(name) {
  return apiRequest("/search/library/categories", {
    method: "POST",
    body: JSON.stringify({ name })
  });
}

export function fetchEnterpriseSummary() {
  return apiRequest("/enterprise/summary");
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

export function fetchPermissions() {
  return apiRequest("/enterprise/permissions");
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

export async function uploadSearchDocument(formData) {
  const response = await fetch(`${API_BASE}/search/documents/upload`, {
    method: "POST",
    body: formData
  });
  const payload = await readJsonSafely(response);
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败：${response.status}`);
  }
  return unwrapApiPayload(payload);
}

async function fetchEventStream(path, payload, handlers) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const contentType = response.headers.get("Content-Type") || "";
  if (!response.ok) {
    const errorPayload = await readJsonSafely(response);
    throw new Error(errorPayload?.message || `请求失败：${response.status}`);
  }

  if (!contentType.includes("text/event-stream") || !response.body) {
    const directPayload = unwrapApiPayload(await readJsonSafely(response));
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

function unwrapApiPayload(payload) {
  if (payload && typeof payload === "object" && "code" in payload) {
    if (payload.code !== 200) {
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
