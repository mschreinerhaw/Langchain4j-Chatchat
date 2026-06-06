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

  if (payload && typeof payload === "object" && "code" in payload) {
    if (payload.code !== 200) {
      throw new Error(payload.message || "服务端返回异常");
    }
    return payload.data;
  }

  return payload;
}

export function sendInteractionMessage(payload) {
  return apiRequest("/interactions/chat", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function saveConversationHistory(payload) {
  return apiRequest("/data/history", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}
