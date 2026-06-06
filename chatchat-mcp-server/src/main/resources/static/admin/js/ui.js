const toastElement = document.getElementById('appToast');
const toast = new bootstrap.Toast(toastElement, { delay: 2800 });
const resultModal = new bootstrap.Modal(document.getElementById('resultModal'));

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

export function renderServices(services, selectedId, handlers) {
    document.getElementById('serviceCount').textContent = services.length;
    const list = document.getElementById('serviceList');
    list.innerHTML = '';
    if (services.length === 0) {
        list.innerHTML = '<div class="text-secondary small">No API services yet.</div>';
        return;
    }
    for (const service of services) {
        const item = document.createElement('article');
        item.className = `service-card ${service.id === selectedId ? 'active' : ''}`;
        item.innerHTML = `
            <h3>${escapeHtml(service.title || service.toolName)}</h3>
            <p>${escapeHtml(service.description || service.urlTemplate)}</p>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(service.method)}</span>
                <span class="badge ${service.enabled ? 'text-bg-success' : 'text-bg-secondary'}">
                    ${service.enabled ? 'Enabled' : 'Disabled'}
                </span>
                <span class="badge text-bg-light">${escapeHtml(service.toolName)}</span>
                <span class="badge ${service.cacheEnabled ? 'text-bg-info' : 'text-bg-light'}">
                    ${service.cacheEnabled ? `Cache ${escapeHtml(service.cacheTtlSeconds || 300)}s` : 'No cache'}
                </span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">Edit</button>
                <button class="btn btn-outline-secondary" data-action="toggle">${service.enabled ? 'Disable' : 'Enable'}</button>
                <button class="btn btn-outline-danger" data-action="delete">Delete</button>
            </div>
        `;
        item.addEventListener('click', event => {
            const action = event.target?.dataset?.action || 'edit';
            if (action === 'edit') handlers.edit(service);
            if (action === 'toggle') handlers.toggle(service);
            if (action === 'delete') handlers.delete(service);
        });
        list.appendChild(item);
    }
}

export function renderMcpServices(services, selectedId, handlers) {
    document.getElementById('mcpServiceCount').textContent = services.length;
    const list = document.getElementById('mcpServiceList');
    list.innerHTML = '';
    if (services.length === 0) {
        list.innerHTML = '<div class="text-secondary small">No MCP services yet.</div>';
        return;
    }
    for (const service of services) {
        const item = document.createElement('article');
        item.className = `service-card ${service.id === selectedId ? 'active' : ''}`;
        item.innerHTML = `
            <h3>${escapeHtml(service.name)}</h3>
            <p>${escapeHtml(service.endpoint)}</p>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(service.serviceType || 'DATA')}</span>
                <span class="badge ${service.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${service.enabled ? 'Enabled' : 'Disabled'}</span>
                <span class="badge text-bg-light">${escapeHtml(service.permissionGroup || 'default')}</span>
            </div>
            <div class="small text-secondary mt-2">Heartbeat: ${formatTime(service.lastHeartbeatAt)}</div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">Edit</button>
                <button class="btn btn-outline-secondary" data-action="toggle">${service.enabled ? 'Disable' : 'Enable'}</button>
                <button class="btn btn-outline-danger" data-action="delete">Delete</button>
            </div>
        `;
        item.addEventListener('click', event => {
            const action = event.target?.dataset?.action || 'edit';
            if (action === 'edit') handlers.edit(service);
            if (action === 'toggle') handlers.toggle(service);
            if (action === 'delete') handlers.delete(service);
        });
        list.appendChild(item);
    }
}

export function renderAuditLogs(logs) {
    const body = document.getElementById('auditLogBody');
    body.innerHTML = '';
    if (!logs.length) {
        body.innerHTML = '<tr><td colspan="6" class="text-secondary">No audit logs yet.</td></tr>';
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
            <td><span class="badge ${log.success ? 'text-bg-success' : 'text-bg-danger'}">${log.success ? 'OK' : 'FAIL'}</span></td>
            <td>${log.statusCode ?? '-'}</td>
            <td>${log.durationMs ?? '-'} ms</td>
            <td class="text-truncate" style="max-width: 260px;">${escapeHtml(log.errorMessage || '')}</td>
        `;
        row.addEventListener('click', () => showResult(log));
        body.appendChild(row);
    }
}

export function showResult(value) {
    document.getElementById('resultOutput').textContent = JSON.stringify(value, null, 2);
    resultModal.show();
}

export function switchView(name) {
    const viewIds = ['apiServicesView', 'mcpServicesView', 'auditLogsView', 'settingsView'];
    for (const id of viewIds) {
        document.getElementById(id).classList.toggle('d-none', id !== `${name}View`);
    }
    document.querySelectorAll('.sidebar .nav-link').forEach(button => {
        button.classList.toggle('active', button.dataset.view === name);
    });
    const newApi = document.getElementById('newServiceBtn');
    const newMcp = document.getElementById('newMcpServiceBtn');
    const refresh = document.getElementById('refreshBtn');
    newApi.classList.toggle('d-none', name !== 'apiServices');
    newMcp.classList.toggle('d-none', name !== 'mcpServices');
    refresh.classList.toggle('d-none', name === 'auditLogs' || name === 'settings');
    const titles = {
        apiServices: ['API Services', 'Register standard HTTP APIs and expose them as MCP tools.'],
        mcpServices: ['MCP Services', 'Register MCP services, manage service tokens, and track heartbeat status.'],
        auditLogs: ['Audit Logs', 'Review recent MCP/API invocation records.'],
        settings: ['Settings', 'Connection endpoints and registry API paths.']
    };
    document.getElementById('pageTitle').textContent = titles[name]?.[0] || 'MCP Admin';
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
