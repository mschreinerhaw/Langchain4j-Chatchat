let toast;
let resultModal;
let apiServiceModal;
let mcpServiceModal;
let databaseQueryModal;
let livedataImportModal;
let latestResultText = '';

export function initUi() {
    toast = new bootstrap.Toast(document.getElementById('appToast'), { delay: 2800 });
    resultModal = new bootstrap.Modal(document.getElementById('resultModal'));
    apiServiceModal = new bootstrap.Modal(document.getElementById('apiServiceModal'));
    mcpServiceModal = new bootstrap.Modal(document.getElementById('mcpServiceModal'));
    databaseQueryModal = new bootstrap.Modal(document.getElementById('databaseQueryModal'));
    livedataImportModal = new bootstrap.Modal(document.getElementById('livedataImportModal'));
    document.getElementById('copyResultBtn').addEventListener('click', copyLatestResult);
}

export function showApp() {
    document.getElementById('loginView').classList.add('d-none');
    document.getElementById('appView').classList.remove('d-none');
}

export function showLogin() {
    document.getElementById('appView').classList.add('d-none');
    document.getElementById('loginView').classList.remove('d-none');
}

export function showLoginError(message) {
    const node = document.getElementById('loginError');
    node.textContent = message;
    node.classList.remove('d-none');
}

export function hideLoginError() {
    document.getElementById('loginError').classList.add('d-none');
}

export function notify(title, message) {
    document.getElementById('toastTitle').textContent = title;
    document.getElementById('toastBody').textContent = message;
    toast.show();
}

export function showApiServiceModal() {
    apiServiceModal.show();
}

export function hideApiServiceModal() {
    apiServiceModal.hide();
}

export function showLivedataImportModal() {
    livedataImportModal.show();
}

export function hideLivedataImportModal() {
    livedataImportModal.hide();
}

export function showMcpServiceModal() {
    mcpServiceModal.show();
}

export function hideMcpServiceModal() {
    mcpServiceModal.hide();
}

export function showDatabaseQueryModal() {
    databaseQueryModal.show();
}

export function hideDatabaseQueryModal() {
    databaseQueryModal.hide();
}

export function renderServices(services, selectedId, handlers, paging = {}) {
    const visibleServices = paging.visible || services;
    const selectedIds = paging.selectedIds || new Set();
    updatePagination('service', {
        totalCount: paging.totalCount ?? services.length,
        filteredCount: paging.filteredCount ?? services.length,
        visibleCount: visibleServices.length,
        page: paging.page ?? 1,
        pageSize: paging.pageSize ?? visibleServices.length
    });
    updateBulkSelection('service', selectedIds.size);
    const list = document.getElementById('serviceList');
    list.innerHTML = '';
    if (services.length === 0) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的 API 服务。</div>';
        return;
    }
    for (const service of visibleServices) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${service.id === selectedId ? 'active' : ''} ${selectedIds.has(service.id) ? 'selected' : ''}`;
        item.innerHTML = `
            <label class="service-card-check" aria-label="选择 ${escapeHtml(service.title || service.toolName)}">
                <input class="form-check-input" type="checkbox" data-select-service="${escapeHtml(service.id)}" ${selectedIds.has(service.id) ? 'checked' : ''}>
            </label>
            <div class="api-card-main">
                <h3>${escapeHtml(service.title || service.toolName)}</h3>
                <p>${escapeHtml(service.description || service.urlTemplate)}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(service.method)}</span>
                <span class="badge ${service.enabled ? 'text-bg-success' : 'text-bg-secondary'}">
                    ${service.enabled ? '启用' : '停用'}
                </span>
                <span class="badge text-bg-light">${escapeHtml(service.toolName)}</span>
                <span class="badge ${service.cacheEnabled ? 'text-bg-info' : 'text-bg-light'}">
                    ${service.cacheEnabled ? `缓存 ${escapeHtml(service.cacheTtlSeconds || 300)} 秒` : '未启用缓存'}
                </span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-primary" data-action="test">测试</button>
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
                <button class="btn btn-outline-secondary" data-action="toggle">${service.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.addEventListener('click', event => {
            if (event.target?.dataset?.selectService != null) {
                handlers.select?.(service, event.target.checked);
                return;
            }
            const action = event.target?.dataset?.action;
            if (!action) return;
            if (action === 'test') handlers.test(service);
            if (action === 'edit') handlers.edit(service);
            if (action === 'toggle') handlers.toggle(service);
            if (action === 'delete') handlers.delete(service);
        });
        list.appendChild(item);
    }
}

export function renderMcpServices(services, selectedId, handlers, paging = {}) {
    const visibleServices = paging.visible || services;
    updatePagination('mcpService', {
        totalCount: paging.totalCount ?? services.length,
        filteredCount: paging.filteredCount ?? services.length,
        visibleCount: visibleServices.length,
        page: paging.page ?? 1,
        pageSize: paging.pageSize ?? visibleServices.length
    });
    const list = document.getElementById('mcpServiceList');
    list.innerHTML = '';
    if (services.length === 0) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的 MCP 服务。</div>';
        return;
    }
    for (const service of visibleServices) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${service.id === selectedId ? 'active' : ''}`;
        item.innerHTML = `
            <div class="api-card-main">
                <h3>${escapeHtml(service.name)}</h3>
                <p>${escapeHtml(service.endpoint)}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(service.serviceType || 'DATA')}</span>
                <span class="badge ${service.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${service.enabled ? '启用' : '停用'}</span>
                <span class="badge text-bg-light">${escapeHtml(service.permissionGroup || 'default')}</span>
            </div>
            <div class="small text-secondary mt-2">最后心跳：${formatTime(service.lastHeartbeatAt)}</div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
                <button class="btn btn-outline-secondary" data-action="toggle">${service.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.addEventListener('click', event => {
            const action = event.target?.dataset?.action;
            if (!action) return;
            if (action === 'edit') handlers.edit(service);
            if (action === 'toggle') handlers.toggle(service);
            if (action === 'delete') handlers.delete(service);
        });
        list.appendChild(item);
    }
}

export function renderDatabaseQueryPreview(output, handlers = {}) {
    const node = document.getElementById('databaseQueryResult');
    if (!output) {
        node.innerHTML = '<div class="text-secondary small">暂无查询结果。</div>';
        return;
    }
    if (!output.success) {
        node.innerHTML = `
            <div class="alert alert-danger mb-0" role="alert">
                ${escapeHtml(output.errorMessage || '数据库查询执行失败')}
            </div>
        `;
        return;
    }

    const data = output.data || {};
    const columns = Array.isArray(data.columns) ? data.columns : [];
    const rows = Array.isArray(data.rows) ? data.rows : [];
    node.innerHTML = `
        <div class="database-preview-summary">
            <span class="badge text-bg-success">成功</span>
            <span>${escapeHtml(output.message || '查询完成')}</span>
            <span>返回 ${escapeHtml(data.rowCount ?? rows.length)} 行</span>
            <span>上限 ${escapeHtml(data.maxRows ?? '-')} 行</span>
            ${data.possiblyTruncated ? '<span class="badge text-bg-warning">可能已截断</span>' : ''}
        </div>
        ${renderPreviewFieldPicker(columns)}
        ${renderPreviewTable(columns, rows)}
        <details class="database-preview-json">
            <summary>完整 JSON</summary>
            <pre class="result-output">${escapeHtml(stringifyForDisplay(output))}</pre>
        </details>
    `;
    node.querySelectorAll('[data-database-preview-field]').forEach(button => {
        button.addEventListener('click', () => {
            const index = Number(button.dataset.databasePreviewField);
            const field = columns[index];
            if (!field || typeof handlers.pickField !== 'function') {
                return;
            }
            handlers.pickField(field, rows[0]?.[field]);
        });
    });
    const pickAllButton = node.querySelector('[data-database-preview-pick-all]');
    if (pickAllButton) {
        pickAllButton.addEventListener('click', () => {
            if (typeof handlers.pickFields !== 'function') {
                return;
            }
            handlers.pickFields(columns, rows[0] || {});
        });
    }
}

export function renderDatabaseQueries(queries, selectedId, handlers, paging = {}) {
    const visibleQueries = paging.visible || queries;
    const selectedIds = paging.selectedIds || new Set();
    updatePagination('databaseQuery', {
        totalCount: paging.totalCount ?? queries.length,
        filteredCount: paging.filteredCount ?? queries.length,
        visibleCount: visibleQueries.length,
        page: paging.page ?? 1,
        pageSize: paging.pageSize ?? visibleQueries.length
    });
    updateBulkSelection('databaseQuery', selectedIds.size);
    const list = document.getElementById('databaseQueryList');
    list.innerHTML = '';
    if (!queries.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无数据库查询注册。</div>';
        return;
    }
    for (const query of visibleQueries) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${query.id === selectedId ? 'active' : ''} ${selectedIds.has(query.id) ? 'selected' : ''}`;
        item.innerHTML = `
            <label class="service-card-check" aria-label="选择 ${escapeHtml(query.title || query.toolName)}">
                <input class="form-check-input" type="checkbox" data-select-database-query="${escapeHtml(query.id)}" ${selectedIds.has(query.id) ? 'checked' : ''}>
            </label>
            <div class="api-card-main">
                <h3>${escapeHtml(query.title || query.toolName)}</h3>
                <p>${escapeHtml(query.description || query.sqlTemplate || '')}</p>
            </div>
            <div class="service-meta">
                <span class="badge ${query.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${query.enabled ? '启用' : '停用'}</span>
                <span class="badge text-bg-light">${escapeHtml(query.toolName)}</span>
                <span class="badge text-bg-light">最多 ${escapeHtml(query.maxRows || 50)} 行</span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-primary" data-action="test">测试</button>
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
                <button class="btn btn-outline-secondary" data-action="toggle">${query.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.addEventListener('click', event => {
            if (event.target?.dataset?.selectDatabaseQuery != null) {
                handlers.select?.(query, event.target.checked);
                return;
            }
            const action = event.target?.dataset?.action;
            if (!action) return;
            if (action === 'test') handlers.test(query);
            if (action === 'edit') handlers.edit(query);
            if (action === 'toggle') handlers.toggle(query);
            if (action === 'delete') handlers.delete(query);
        });
        list.appendChild(item);
    }
}

function renderPreviewTable(columns, rows) {
    if (!columns.length) {
        return '<div class="database-preview-empty text-secondary small">查询成功，但没有返回行。</div>';
    }
    const head = columns.map(column => `<th>${escapeHtml(column)}</th>`).join('');
    const body = rows.map(row => `
        <tr>
            ${columns.map(column => `<td>${escapeHtml(formatCell(row?.[column]))}</td>`).join('')}
        </tr>
    `).join('');
    return `
        <div class="table-responsive database-preview-table">
            <table class="table table-sm align-middle mb-0">
                <thead><tr>${head}</tr></thead>
                <tbody>${body}</tbody>
            </table>
        </div>
    `;
}

function renderPreviewFieldPicker(columns) {
    if (!columns.length) {
        return '';
    }
    return `
        <div class="database-preview-fields">
            <div class="database-preview-fields-head">
                <span class="text-secondary small">从查询结果列名生成参数</span>
                <button class="btn btn-outline-primary btn-sm" type="button" data-database-preview-pick-all>
                    全部字段
                </button>
            </div>
            <div class="database-preview-field-list">
                ${columns.map((column, index) => `
                    <button class="btn btn-outline-secondary btn-sm" type="button" data-database-preview-field="${index}">
                        ${escapeHtml(column)}
                    </button>
                `).join('')}
            </div>
        </div>
    `;
}

function formatCell(value) {
    if (value == null) {
        return '';
    }
    if (typeof value === 'object') {
        return JSON.stringify(value);
    }
    return String(value);
}

function updatePagination(prefix, { totalCount, filteredCount, visibleCount, page, pageSize }) {
    const pageCount = Math.max(1, Math.ceil(filteredCount / Math.max(1, pageSize)));
    document.getElementById(`${prefix}TotalCount`).textContent = totalCount;
    document.getElementById(`${prefix}FilteredCount`).textContent = filteredCount;
    document.getElementById(`${prefix}Count`).textContent = visibleCount;
    document.getElementById(`${prefix}PageInfo`).textContent = `第 ${page} / ${pageCount} 页`;
    document.getElementById(`${prefix}PrevPageBtn`).disabled = page <= 1;
    document.getElementById(`${prefix}NextPageBtn`).disabled = page >= pageCount;
}

function updateBulkSelection(prefix, selectedCount) {
    const count = document.getElementById(`${prefix}SelectedCount`);
    const deleteButton = document.getElementById(`${prefix}BatchDeleteBtn`);
    const clearButton = document.getElementById(`${prefix}ClearSelectionBtn`);
    if (count) {
        count.textContent = selectedCount;
    }
    if (deleteButton) {
        deleteButton.disabled = selectedCount === 0;
    }
    if (clearButton) {
        clearButton.disabled = selectedCount === 0;
    }
}

export function renderAuditLogs(logs, openDetail) {
    const body = document.getElementById('auditLogBody');
    body.innerHTML = '';
    if (!logs.length) {
        body.innerHTML = '<tr><td colspan="7" class="text-secondary">暂无审计日志。</td></tr>';
        return;
    }
    for (const log of logs) {
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${formatTime(log.createdAt)}</td>
            <td>
                <strong>${escapeHtml(log.targetName || '-')}</strong>
                <div class="small text-secondary">${escapeHtml(log.targetType || '')}</div>
            </td>
            <td><code>${escapeHtml(log.toolName || '-')}</code></td>
            <td><span class="badge ${log.success ? 'text-bg-success' : 'text-bg-danger'}">${log.success ? '成功' : '失败'}</span></td>
            <td>${log.statusCode ?? '-'}</td>
            <td>${log.durationMs ?? '-'} ms</td>
            <td class="text-truncate" style="max-width: 260px;">${escapeHtml(log.errorMessage || '')}</td>
        `;
        row.addEventListener('click', () => {
            if (typeof openDetail === 'function') {
                openDetail(log);
            } else {
                showResult(log);
            }
        });
        body.appendChild(row);
    }
}

export function showResult(value, options = {}) {
    const title = options.title || '执行结果';
    const subtitle = options.subtitle || '';
    const body = value?.body ?? value?.rawBody ?? value;
    const bodyText = stringifyForDisplay(body);
    latestResultText = stringifyForDisplay(value);

    document.getElementById('resultTitle').textContent = title;
    document.getElementById('resultSubtitle').textContent = subtitle;
    document.getElementById('resultBodyOutput').textContent = bodyText;
    document.getElementById('resultOutput').textContent = latestResultText;
    updateResultSummary(value);
    resultModal.show();
}

function updateResultSummary(value) {
    const summary = document.getElementById('resultSummary');
    const statusBadge = document.getElementById('resultStatusBadge');
    const httpStatus = document.getElementById('resultHttpStatus');
    const cacheBadge = document.getElementById('resultCacheBadge');
    const hasInvokeShape = value && Object.prototype.hasOwnProperty.call(value, 'success');

    summary.classList.toggle('d-none', !hasInvokeShape);
    if (!hasInvokeShape) {
        return;
    }

    statusBadge.textContent = value.success ? '成功' : '失败';
    statusBadge.className = `badge ${value.success ? 'text-bg-success' : 'text-bg-danger'}`;
    httpStatus.textContent = `HTTP ${value.statusCode ?? '-'}`;
    cacheBadge.classList.toggle('d-none', !value.cacheHit);
}

async function copyLatestResult() {
    if (!latestResultText) {
        return;
    }
    try {
        await navigator.clipboard.writeText(latestResultText);
        notify('已复制', '完整执行结果已复制到剪贴板。');
    } catch (error) {
        notify('复制失败', '浏览器不允许访问剪贴板。');
    }
}

function stringifyForDisplay(value) {
    if (value == null) {
        return '';
    }
    if (typeof value === 'string') {
        return value;
    }
    try {
        return JSON.stringify(value, null, 2);
    } catch (error) {
        return String(value);
    }
}

export function switchView(name) {
    const viewIds = ['apiServicesView', 'mcpServicesView', 'databaseMcpView', 'auditLogsView', 'settingsView'];
    for (const id of viewIds) {
        document.getElementById(id).classList.toggle('d-none', id !== `${name}View`);
    }
    document.querySelectorAll('.sidebar [data-view]').forEach(button => {
        button.classList.toggle('active', button.dataset.view === name);
    });
    const newApi = document.getElementById('newServiceBtn');
    const importLivedata = document.getElementById('importLivedataBtn');
    const newMcp = document.getElementById('newMcpServiceBtn');
    const newDatabaseQuery = document.getElementById('newDatabaseQueryBtn');
    const refresh = document.getElementById('refreshBtn');
    newApi.classList.toggle('d-none', name !== 'apiServices');
    importLivedata.classList.toggle('d-none', name !== 'apiServices');
    newMcp.classList.toggle('d-none', name !== 'mcpServices');
    newDatabaseQuery.classList.toggle('d-none', name !== 'databaseMcp');
    refresh.classList.toggle('d-none', name === 'databaseMcp' || name === 'auditLogs' || name === 'settings');
    const titles = {
        apiServices: ['API 服务', '注册标准 HTTP API，并将其发布为 MCP 工具。'],
        mcpServices: ['MCP 服务', '注册 MCP 服务、管理服务 Token，并查看心跳状态。'],
        databaseMcp: ['数据库查询', '注册只读 SQL 查询，并将其发布为 MCP 工具。'],
        auditLogs: ['调用审计', '查看最近的 MCP 与 API 调用记录。'],
        settings: ['系统设置', '查看连接端点和注册接口路径。']
    };
    document.getElementById('pageTitle').textContent = titles[name]?.[0] || 'MCP 管理台';
    document.getElementById('pageSubtitle').textContent = titles[name]?.[1] || '';
}

function formatTime(value) {
    if (!value) {
        return '-';
    }
    return new Date(value).toLocaleString();
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}
