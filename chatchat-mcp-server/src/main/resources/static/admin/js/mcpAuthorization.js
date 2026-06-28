import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/mcp-authorization`;

export function getAuthorizationSnapshot() {
    return apiFetch(`${BASE_URL}/snapshot`);
}

export function syncAuthorizationSnapshot() {
    return apiFetch(`${BASE_URL}/sync`, { method: 'POST' });
}

export function listRolePermissions(roleId, tenantId = '') {
    const query = new URLSearchParams({ roleId });
    if (tenantId) {
        query.set('tenantId', tenantId);
    }
    return apiFetch(`${BASE_URL}/role-permissions?${query}`);
}

export function createRolePermission(payload) {
    return apiFetch(`${BASE_URL}/role-permissions`, {
        method: 'POST',
        body: JSON.stringify(payload)
    });
}

export function deleteRolePermission(permissionId) {
    return apiFetch(`${BASE_URL}/role-permissions/${encodeURIComponent(permissionId)}`, {
        method: 'DELETE'
    });
}
