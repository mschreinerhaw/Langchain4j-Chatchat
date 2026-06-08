import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/database-query`;

export function listDatabaseQueries() {
    return apiFetch(BASE_URL);
}

export function saveDatabaseQuery(query) {
    const id = query.id;
    const body = JSON.stringify(query);
    if (id) {
        return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'PUT', body });
    }
    return apiFetch(BASE_URL, { method: 'POST', body });
}

export function deleteDatabaseQuery(id) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function setDatabaseQueryEnabled(id, enabled) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' });
}

export function testSavedDatabaseQuery(id, params) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/test`, {
        method: 'POST',
        body: JSON.stringify(params || {})
    });
}

export function testDatabaseQuery(payload) {
    return apiFetch(`${BASE_URL}/test`, {
        method: 'POST',
        body: JSON.stringify(payload)
    });
}
