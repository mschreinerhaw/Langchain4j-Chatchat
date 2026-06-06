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
