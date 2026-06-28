import { getAuditLog, listAuditLogs } from './auditLogs.js';
import { notify, renderAuditLogs, showResult } from './ui.js';

let onError = error => console.error(error);
let auditLogKeyword = '';
let auditLogTargetType = '';
let auditLogSuccess = '';
let auditLogPage = 1;
let auditLogPageSize = 20;

export function bindAuditLogPanel(options = {}) {
    onError = options.onError || onError;
    document.getElementById('reloadAuditBtn').addEventListener('click', () => loadAuditLogPanel());
    document.getElementById('auditLogSearchBtn').addEventListener('click', applyAuditLogFilters);
    document.getElementById('auditLogResetBtn').addEventListener('click', resetAuditLogFilters);
    document.getElementById('auditLogSearchInput').addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            applyAuditLogFilters();
        }
    });
    document.getElementById('auditLogTargetTypeSelect').addEventListener('change', applyAuditLogFilters);
    document.getElementById('auditLogSuccessSelect').addEventListener('change', applyAuditLogFilters);
    document.getElementById('auditLogPageSizeSelect').addEventListener('change', changeAuditLogPageSize);
    document.getElementById('auditLogPrevPageBtn').addEventListener('click', () => changeAuditLogPage(-1));
    document.getElementById('auditLogNextPageBtn').addEventListener('click', () => changeAuditLogPage(1));
}

export async function loadAuditLogPanel(showNotice = true) {
    try {
        const page = normalizeAuditLogPage(await listAuditLogs(currentAuditLogQuery()));
        auditLogPage = page.page;
        auditLogPageSize = page.pageSize;
        renderAuditLogs(page.items, openAuditLogDetail, {
            totalCount: page.totalCount,
            filteredCount: page.filteredCount,
            page: page.page,
            pageSize: page.pageSize
        });
        if (showNotice) notify('加载成功', '审计日志已刷新。');
    } catch (error) {
        onError(error);
    }
}

function currentAuditLogQuery() {
    return {
        page: auditLogPage,
        pageSize: auditLogPageSize,
        keyword: auditLogKeyword,
        targetType: auditLogTargetType,
        success: auditLogSuccess
    };
}

function normalizeAuditLogPage(value) {
    if (Array.isArray(value)) {
        return {
            items: value,
            page: 1,
            pageSize: value.length || auditLogPageSize,
            totalCount: value.length,
            filteredCount: value.length
        };
    }
    return {
        items: value?.items || [],
        page: value?.page || 1,
        pageSize: value?.pageSize || auditLogPageSize,
        totalCount: value?.totalCount || 0,
        filteredCount: value?.filteredCount || 0
    };
}

async function applyAuditLogFilters() {
    auditLogKeyword = document.getElementById('auditLogSearchInput').value;
    auditLogTargetType = document.getElementById('auditLogTargetTypeSelect').value;
    auditLogSuccess = document.getElementById('auditLogSuccessSelect').value;
    auditLogPage = 1;
    await loadAuditLogPanel(false);
}

async function resetAuditLogFilters() {
    auditLogKeyword = '';
    auditLogTargetType = '';
    auditLogSuccess = '';
    auditLogPage = 1;
    document.getElementById('auditLogSearchInput').value = '';
    document.getElementById('auditLogTargetTypeSelect').value = '';
    document.getElementById('auditLogSuccessSelect').value = '';
    await loadAuditLogPanel(false);
}

async function changeAuditLogPageSize(event) {
    auditLogPageSize = Number(event.target.value) || 20;
    auditLogPage = 1;
    await loadAuditLogPanel(false);
}

async function changeAuditLogPage(delta) {
    auditLogPage = Math.max(1, auditLogPage + delta);
    await loadAuditLogPanel(false);
}

async function openAuditLogDetail(log) {
    try {
        const detail = await getAuditLog(log.id);
        showResult(detail, {
            title: `${detail.toolName || detail.targetName || detail.targetId || '调用'} 审计明细`,
            subtitle: `${detail.targetType || '-'} · ${formatAuditTime(detail.createdAt)}`
        });
    } catch (error) {
        onError(error);
    }
}

function formatAuditTime(value) {
    return value ? new Date(value).toLocaleString() : '-';
}
