import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/api-services`;
const LIVEDATA_URL = `${API_BASE}/livedata-apis`;

export function listServices() {
    return apiFetch(BASE_URL);
}

export function saveService(service) {
    const id = service.id;
    const body = JSON.stringify(toPayload(service));
    if (id) {
        return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'PUT', body });
    }
    return apiFetch(BASE_URL, { method: 'POST', body });
}

export function deleteService(id) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function deleteServices(ids) {
    return apiFetch(`${BASE_URL}/batch-delete`, {
        method: 'POST',
        body: JSON.stringify({ ids })
    });
}

export function setEnabled(id, enabled) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' });
}

export function testService(id, args) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/test`, {
        method: 'POST',
        body: JSON.stringify(args || {})
    });
}

export function refreshTools() {
    return apiFetch(`${BASE_URL}/refresh`, { method: 'POST' });
}

export function listLivedataApis() {
    return apiFetch(LIVEDATA_URL);
}

export function registerLivedataApis(ids, overwriteExisting) {
    return apiFetch(`${LIVEDATA_URL}/register`, {
        method: 'POST',
        body: JSON.stringify({ ids, overwriteExisting })
    });
}

function toPayload(service) {
    return {
        toolName: service.toolName,
        title: service.title,
        description: service.description,
        method: service.method,
        urlTemplate: service.urlTemplate,
        headers: service.headers,
        bodyTemplate: service.bodyTemplate,
        inputSchema: service.inputSchema,
        enabled: service.enabled,
        timeoutMs: service.timeoutMs,
        cacheEnabled: service.cacheEnabled,
        cacheTtlSeconds: service.cacheTtlSeconds
    };
}
