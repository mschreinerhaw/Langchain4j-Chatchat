import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/api-services`;

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
