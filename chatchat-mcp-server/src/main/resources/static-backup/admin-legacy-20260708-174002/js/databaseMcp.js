import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/database-query`;
const SEARCH_INDEX_URL = `${API_BASE}/mcp-search-index`;
const TEMPLATE_DSL_URL = `${API_BASE}/template-dsl`;
const TRADING_CALENDAR_URL = `${API_BASE}/dynamic-date-params/trading-calendar`;

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

export function deleteDatabaseQueries(ids) {
    return apiFetch(`${BASE_URL}/batch-delete`, {
        method: 'POST',
        body: JSON.stringify({ ids })
    });
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

export function rebuildDatabaseQueryIndex() {
    return apiFetch(`${SEARCH_INDEX_URL}/database-queries/rebuild`, { method: 'POST' });
}

export function validateDatabaseQueryTemplateDsl(request) {
    return apiFetch(`${TEMPLATE_DSL_URL}/validate`, {
        method: 'POST',
        body: JSON.stringify({
            ...(request || {}),
            templateType: 'DATABASE_QUERY'
        })
    });
}

export function importDatabaseQueryTemplateDsl(request) {
    return apiFetch(`${TEMPLATE_DSL_URL}/import`, {
        method: 'POST',
        body: JSON.stringify({
            ...(request || {}),
            templateType: 'DATABASE_QUERY'
        })
    });
}

export function getTradingCalendarConfig() {
    return apiFetch(`${TRADING_CALENDAR_URL}/config`);
}

export function saveTradingCalendarConfig(config) {
    return apiFetch(`${TRADING_CALENDAR_URL}/config`, {
        method: 'PUT',
        body: JSON.stringify(config || {})
    });
}

export function testTradingCalendarConfig(config) {
    return apiFetch(`${TRADING_CALENDAR_URL}/test`, {
        method: 'POST',
        body: JSON.stringify(config || {})
    });
}

export function testTradingCalendarFunction(config) {
    return apiFetch(`${TRADING_CALENDAR_URL}/function-test`, {
        method: 'POST',
        body: JSON.stringify(config || {})
    });
}
