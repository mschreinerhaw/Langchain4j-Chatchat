import { API_BASE } from './config';

const TOKEN_KEY = 'chatchat.mcp.admin.token';
const USER_KEY = 'chatchat.mcp.admin.user';
const EXPIRES_KEY = 'chatchat.mcp.admin.expiresAt';

export function getToken() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const expiresAt = Number(sessionStorage.getItem(EXPIRES_KEY) || 0);
  if (!token || expiresAt <= Date.now()) {
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
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || '登录失败');
  }
  sessionStorage.setItem(TOKEN_KEY, payload.data.token);
  sessionStorage.setItem(USER_KEY, payload.data.username);
  sessionStorage.setItem(EXPIRES_KEY, String(payload.data.expiresAt));
  return payload.data;
}

export async function logout() {
  const token = getToken();
  if (token) {
    await fetch(`${API_BASE}/admin/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    }).catch(() => {});
  }
  clearSession();
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USER_KEY);
  sessionStorage.removeItem(EXPIRES_KEY);
}
