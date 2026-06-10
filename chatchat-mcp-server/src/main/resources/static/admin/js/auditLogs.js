import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

export function listAuditLogs() {
    return apiFetch(`${API_BASE}/audit-logs`);
}

export function getAuditLog(id) {
    return apiFetch(`${API_BASE}/audit-logs/${encodeURIComponent(id)}`);
}
