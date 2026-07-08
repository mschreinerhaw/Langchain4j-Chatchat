import { API_BASE } from './config.js';
import { apiFetch } from './http.js';
import { confirmDangerAction } from './ui.js';

const BASE_URL = `${API_BASE}/cache/database-query`;

let onErrorHandler = error => console.error(error);

export function bindCacheSettingsPanel({ onError } = {}) {
    onErrorHandler = onError || onErrorHandler;
    bind('cacheSettingsRefreshBtn', 'click', loadCacheSettingsPanel);
    bind('cacheSettingsSaveBtn', 'click', saveCacheSettingsPanel);
    bind('dbQueryCacheCleanupExpiredBtn', 'click', cleanupExpiredCache);
    bind('dbQueryCacheEvictAllBtn', 'click', evictAllCache);
}

export async function loadCacheSettingsPanel() {
    try {
        const [config, stats] = await Promise.all([getConfig(), getStats()]);
        fillConfig(config);
        renderStats(stats);
    } catch (error) {
        onErrorHandler(error);
    }
}

async function saveCacheSettingsPanel() {
    const button = document.getElementById('cacheSettingsSaveBtn');
    try {
        setButtonBusy(button, true);
        const config = await saveConfig(readConfig());
        fillConfig(config);
        await loadCacheSettingsPanel();
    } catch (error) {
        onErrorHandler(error);
    } finally {
        setButtonBusy(button, false);
    }
}

async function cleanupExpiredCache() {
    const confirmed = await confirmDangerAction({
        title: '清理过期数据库查询缓存',
        message: '确定清理已过期的数据库查询缓存吗？',
        target: '过期缓存条目',
        detail: '仅会移除已经过期的缓存条目，不影响仍在有效期内的查询缓存。',
        confirmText: '确认清理'
    });
    if (!confirmed) return;
    await runCacheAction('dbQueryCacheCleanupExpiredBtn', () => apiFetch(`${BASE_URL}/cleanup-expired`, { method: 'POST' }));
}

async function evictAllCache() {
    const confirmed = await confirmDangerAction({
        title: '清理全部数据库查询缓存',
        message: '确定清理全部数据库查询缓存吗？',
        target: '数据库查询缓存',
        detail: '清理后已有缓存结果会被移除，后续查询会重新访问数据库并写入新的缓存。',
        confirmText: '确认清理'
    });
    if (!confirmed) return;
    await runCacheAction('dbQueryCacheEvictAllBtn', () => apiFetch(`${BASE_URL}/evict`, { method: 'POST' }));
}

async function runCacheAction(buttonId, action) {
    const button = document.getElementById(buttonId);
    try {
        setButtonBusy(button, true);
        await action();
        await loadCacheSettingsPanel();
    } catch (error) {
        onErrorHandler(error);
    } finally {
        setButtonBusy(button, false);
    }
}

function setButtonBusy(button, busy) {
    if (!button) {
        return;
    }
    button.disabled = busy;
    button.classList.toggle('is-busy', busy);
    button.setAttribute('aria-busy', String(busy));
}

function getConfig() {
    return apiFetch(`${BASE_URL}/config`);
}

function saveConfig(config) {
    return apiFetch(`${BASE_URL}/config`, {
        method: 'PUT',
        body: JSON.stringify(config)
    });
}

function getStats() {
    return apiFetch(`${BASE_URL}/stats`);
}

function readConfig() {
    return {
        enabled: value('dbQueryCacheEnabled') === 'true',
        defaultTtlSeconds: numberValue('dbQueryCacheTtl', 300),
        maxRows: numberValue('dbQueryCacheMaxRows', 1000),
        maxEntryKb: numberValue('dbQueryCacheMaxBytes', 512),
        keyStrategy: value('dbQueryCacheKey') || 'SQL_PARAMS_DATASOURCE',
        cacheEmptyResults: value('dbQueryCacheEmpty') === 'true',
        cacheErrorResults: value('dbQueryCacheError') === 'true'
    };
}

function fillConfig(config = {}) {
    setValue('dbQueryCacheEnabled', String(config.enabled ?? false));
    setValue('dbQueryCacheTtl', String(config.defaultTtlSeconds || 300));
    setValue('dbQueryCacheMaxRows', String(config.maxRows || 1000));
    setValue('dbQueryCacheMaxBytes', String(config.maxEntryKb || 512));
    setValue('dbQueryCacheKey', config.keyStrategy || 'SQL_PARAMS_DATASOURCE');
    setValue('dbQueryCacheEmpty', String(config.cacheEmptyResults ?? false));
    setValue('dbQueryCacheError', String(config.cacheErrorResults ?? false));
}

function renderStats(stats = {}) {
    const badge = document.getElementById('dbQueryCacheStatusBadge');
    if (badge) {
        badge.className = `badge ${stats.cacheEnabled && stats.storeAvailable ? 'text-bg-success' : 'text-bg-secondary'}`;
        badge.textContent = stats.cacheEnabled && stats.storeAvailable ? '运行中' : '未启用';
    }
    setText('dbQueryCacheStoreStatus', stats.storeAvailable ? '可用' : '不可用');
    setText('dbQueryCacheEntryCount', String(stats.entries ?? 0));
    setText('dbQueryCacheExpiredCount', String(stats.expiredEntries ?? 0));
    setText('dbQueryCacheBytes', formatBytes(stats.bytes || 0));
    setText('dbQueryCacheMeasuredAt', stats.measuredAt ? `最近刷新：${new Date(stats.measuredAt).toLocaleString()}` : '尚未刷新。');
}

function formatBytes(bytes) {
    if (bytes < 1024) {
        return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function bind(id, event, handler) {
    document.getElementById(id)?.addEventListener(event, handler);
}

function value(id) {
    return document.getElementById(id)?.value?.trim() || '';
}

function numberValue(id, fallback) {
    const next = Number(value(id));
    return Number.isFinite(next) && next > 0 ? next : fallback;
}

function setValue(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.value = value ?? '';
    }
}

function setText(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.textContent = value ?? '';
    }
}
