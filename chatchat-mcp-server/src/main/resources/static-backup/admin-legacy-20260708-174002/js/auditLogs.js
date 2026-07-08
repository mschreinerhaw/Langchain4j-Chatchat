import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

export function listAuditLogs(params = {}) {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value) !== '') {
            query.set(key, value);
        }
    });
    const suffix = query.toString() ? `?${query}` : '';
    return apiFetch(`${API_BASE}/audit-logs${suffix}`);
}

export function getAuditLog(id) {
    return apiFetch(`${API_BASE}/audit-logs/${encodeURIComponent(id)}`);
}
