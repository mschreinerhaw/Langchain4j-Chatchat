import { API_BASE } from './config';

const TOKEN_KEY = 'chatchat.mcp.admin.token';
const USER_KEY = 'chatchat.mcp.admin.user';
const EXPIRES_KEY = 'chatchat.mcp.admin.expiresAt';
const LAST_ACTIVITY_KEY = 'chatchat.mcp.admin.lastActivityAt';

export const SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000;

export function getToken() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const expiresAt = Number(sessionStorage.getItem(EXPIRES_KEY) || 0);
  const lastActivityAt = Number(sessionStorage.getItem(LAST_ACTIVITY_KEY) || 0);
  const now = Date.now();
  const idleExpired = lastActivityAt > 0 && now - lastActivityAt >= SESSION_IDLE_TIMEOUT_MS;
  if (!token || expiresAt <= now || idleExpired) {
    clearSession();
    return null;
  }
  return token;
}

export function getUser() {
  return sessionStorage.getItem(USER_KEY) || '';
}

export async function login(username, password) {
  const response = await fetch(`${API_BASE}/admin/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ username, password })
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || '登录失败');
  }
  sessionStorage.setItem(TOKEN_KEY, payload.data.token);
  sessionStorage.setItem(USER_KEY, payload.data.username);
  sessionStorage.setItem(EXPIRES_KEY, String(payload.data.expiresAt));
  markSessionActivity();
  return payload.data;
}

export async function logout() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  clearSession();
  if (token) {
    await fetch(`${API_BASE}/admin/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    }).catch(() => {});
  }
}

export function markSessionActivity(now = Date.now()) {
  if (sessionStorage.getItem(TOKEN_KEY)) {
    sessionStorage.setItem(LAST_ACTIVITY_KEY, String(now));
  }
}

export function getSessionIdleRemainingMs(now = Date.now()) {
  if (!sessionStorage.getItem(TOKEN_KEY)) return 0;
  const lastActivityAt = Number(sessionStorage.getItem(LAST_ACTIVITY_KEY) || 0);
  if (!lastActivityAt) {
    markSessionActivity(now);
    return SESSION_IDLE_TIMEOUT_MS;
  }
  return Math.max(0, SESSION_IDLE_TIMEOUT_MS - (now - lastActivityAt));
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USER_KEY);
  sessionStorage.removeItem(EXPIRES_KEY);
  sessionStorage.removeItem(LAST_ACTIVITY_KEY);
}
