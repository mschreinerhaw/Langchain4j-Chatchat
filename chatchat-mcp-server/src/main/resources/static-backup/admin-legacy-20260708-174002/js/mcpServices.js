import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/mcp-services`;

export function listMcpServices() {
    return apiFetch(BASE_URL);
}

export function saveMcpService(service) {
    const id = service.id;
    const body = JSON.stringify(toPayload(service));
    if (id) {
        return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'PUT', body });
    }
    return apiFetch(BASE_URL, { method: 'POST', body });
}

export function deleteMcpService(id) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function setMcpEnabled(id, enabled) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' });
}

export function generateMcpToken() {
    return apiFetch(`${BASE_URL}/generate-token`, { method: 'POST' });
}

export function regenerateMcpToken(id) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/token`, { method: 'POST' });
}

function toPayload(service) {
    return {
        name: service.name,
        endpoint: service.endpoint,
        serviceToken: service.serviceToken,
        serviceType: service.serviceType,
        permissionGroup: service.permissionGroup,
        environment: service.environment,
        routingLabelsJson: service.routingLabelsJson,
        routingLabels: service.routingLabels,
        capabilitiesJson: service.capabilitiesJson,
        capabilities: service.capabilities,
        enabled: service.enabled,
        status: service.status
    };
}
