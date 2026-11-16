export class ApiError extends Error {
  constructor(message, status, details) { super(message); this.status = status; this.details = details; }
}
export async function request(path, { method = 'GET', body, signal, headers = {} } = {}) {
  let response;
  try {
    response = await fetch('/api' + path, { method, signal, credentials: 'same-origin',
      headers: { ...(method !== 'GET' ? { 'X-Ops-Request': '1' } : {}),
        ...(body !== undefined && !(body instanceof Blob) ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body) });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new ApiError('无法连接管理平台，请检查网络及平台服务。', 0);
  }
  const text = await response.text(); let value;
  try { value = text ? JSON.parse(text) : null; }
  catch { throw new ApiError('服务返回了无法识别的响应。', response.status); }
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('ops-session-expired'));
    throw new ApiError(value?.error || `请求失败 (${response.status})`, response.status, value);
  }
  return value;
}
export const get = (path, signal) => request(path, { signal });
export const post = (path, body = {}, options = {}) => request(path, { ...options, method: 'POST', body });
export const nodePath = (node, suffix) => `/nodes/${encodeURIComponent(node)}${suffix}`;
