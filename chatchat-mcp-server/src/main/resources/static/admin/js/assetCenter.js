import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const OPS_URL = `${API_BASE}/ops`;
const SQL_URL = `${API_BASE}/sql`;
const SEARCH_INDEX_URL = `${API_BASE}/mcp-search-index`;

export function listSshAssets() {
    return apiFetch(`${OPS_URL}/ssh-hosts`);
}

export function listCommandTemplates() {
    return apiFetch(`${OPS_URL}/command-templates`);
}

export function saveCommandTemplate(template) {
    const method = template.id ? 'PUT' : 'POST';
    const url = template.id
        ? `${OPS_URL}/command-templates/${encodeURIComponent(template.id)}`
        : `${OPS_URL}/command-templates`;
    return apiFetch(url, { method, body: JSON.stringify(template) });
}

export function deleteCommandTemplate(id) {
    return apiFetch(`${OPS_URL}/command-templates/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function listSqlTemplates() {
    return apiFetch(`${SQL_URL}/templates`);
}

export function saveSqlTemplate(template) {
    const method = template.id ? 'PUT' : 'POST';
    const url = template.id ? `${SQL_URL}/templates/${encodeURIComponent(template.id)}` : `${SQL_URL}/templates`;
    return apiFetch(url, { method, body: JSON.stringify(template) });
}

export function deleteSqlTemplate(id) {
    return apiFetch(`${SQL_URL}/templates/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function listHttpAssets() {
    return apiFetch(`${OPS_URL}/http-endpoints`);
}

export function saveSshAsset(asset) {
    const method = asset.id ? 'PUT' : 'POST';
    const url = asset.id ? `${OPS_URL}/ssh-hosts/${encodeURIComponent(asset.id)}` : `${OPS_URL}/ssh-hosts`;
    return apiFetch(url, { method, body: JSON.stringify(asset) });
}

export function deleteSshAsset(id) {
    return apiFetch(`${OPS_URL}/ssh-hosts/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function testSshAsset(asset) {
    return apiFetch(`${OPS_URL}/ssh-hosts/test`, { method: 'POST', body: JSON.stringify(asset) });
}

export function saveHttpAsset(asset) {
    const method = asset.id ? 'PUT' : 'POST';
    const url = asset.id ? `${OPS_URL}/http-endpoints/${encodeURIComponent(asset.id)}` : `${OPS_URL}/http-endpoints`;
    return apiFetch(url, { method, body: JSON.stringify(asset) });
}

export function deleteHttpAsset(id) {
    return apiFetch(`${OPS_URL}/http-endpoints/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function testHttpAsset(asset) {
    return apiFetch(`${OPS_URL}/http-endpoints/test`, { method: 'POST', body: JSON.stringify(asset) });
}

export function refreshOpsTools() {
    return apiFetch(`${OPS_URL}/refresh-tools`, { method: 'POST' });
}

export function listSqlAssets() {
    return apiFetch(`${SQL_URL}/datasources`);
}

export function saveSqlAsset(asset) {
    const method = asset.id ? 'PUT' : 'POST';
    const url = asset.id ? `${SQL_URL}/datasources/${encodeURIComponent(asset.id)}` : `${SQL_URL}/datasources`;
    return apiFetch(url, { method, body: JSON.stringify(asset) });
}

export function deleteSqlAsset(id) {
    return apiFetch(`${SQL_URL}/datasources/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function testSqlAsset(asset) {
    return apiFetch(`${SQL_URL}/datasources/test`, { method: 'POST', body: JSON.stringify(asset) });
}

export function refreshSqlAssetMetadata(id) {
    return apiFetch(`${SQL_URL}/datasources/${encodeURIComponent(id)}/metadata/refresh`, { method: 'POST' });
}

export function refreshSqlTools() {
    return apiFetch(`${SQL_URL}/refresh-tools`, { method: 'POST' });
}

export function rebuildAssetIndex(assetType = '') {
    const normalized = String(assetType || '').trim();
    const path = normalized
        ? `/assets/${encodeURIComponent(normalized)}/rebuild`
        : '/assets/rebuild';
    return apiFetch(`${SEARCH_INDEX_URL}${path}`, { method: 'POST' });
}

export function rebuildTemplateIndex() {
    return apiFetch(`${SEARCH_INDEX_URL}/templates/rebuild`, { method: 'POST' });
}

export function searchMcpIndex(request) {
    return apiFetch(`${SEARCH_INDEX_URL}/search`, { method: 'POST', body: JSON.stringify(request || {}) });
}
