import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

export function listAuditLogs() {
    return apiFetch(`${API_BASE}/audit-logs`);
}
