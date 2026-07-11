import { clearSession, getToken } from './session';

export class UnauthorizedError extends Error {}

export async function apiFetch(url, options = {}) {
  const token = getToken();
  if (!token) {
    throw new UnauthorizedError('请先登录');
  }

  const headers = new Headers(options.headers || {});
  headers.set('Authorization', `Bearer ${token}`);
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json; charset=UTF-8');
  }

  const response = await fetch(url, { ...options, headers });
  const payload = await parseJson(response);
  if (response.status === 401) {
    clearSession();
    throw new UnauthorizedError(payload.message || '登录已过期，请重新登录');
  }
  if (!response.ok || payload.code >= 400) {
    throw new Error(payload.message || `请求失败：${response.status}`);
  }
  return payload.data;
}

async function parseJson(response) {
  const text = await response.text();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    return { code: response.status, message: text };
  }
}
