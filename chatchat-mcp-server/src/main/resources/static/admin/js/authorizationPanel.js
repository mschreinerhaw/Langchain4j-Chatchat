import { listHttpAssets, listSqlAssets, listSshAssets } from './assetCenter.js';
import { listDatabaseQueries } from './databaseMcp.js';
import {
    createRolePermission,
    deleteRolePermission,
    getAuthorizationSnapshot,
    listRolePermissions,
    syncAuthorizationSnapshot
} from './mcpAuthorization.js';
import { notify } from './ui.js';

let snapshot = null;
let localAuthorizationTools = [];
let activeSettingsTab = 'connection';
let activeAuthSyncTab = 'users';
let rolePermissionRole = null;
let rolePermissionRules = [];
let selectedRolePermissionToolName = '';
let onError = error => console.error(error);

export function bindAuthorizationPanel(options = {}) {
    onError = options.onError || onError;
    bindOptional('syncMcpAuthorizationBtn', 'click', handleSync);
    document.querySelectorAll('[data-settings-tab]').forEach(button => {
        button.addEventListener('click', () => switchSettingsTab(button.dataset.settingsTab));
    });
    document.querySelectorAll('[data-auth-sync-tab]').forEach(button => {
        button.addEventListener('click', () => switchAuthSyncTab(button.dataset.authSyncTab));
    });
    bindOptional('closeRolePermissionModalBtn', 'click', closeRolePermissionModal);
    bindOptional('rolePermissionModal', 'click', event => {
        if (event.target.id === 'rolePermissionModal') closeRolePermissionModal();
    });
    bindOptional('rolePermissionForm', 'submit', handleRolePermissionSubmit);
    bindOptional('rolePermissionCategorySelect', 'change', () => {
        selectedRolePermissionToolName = '';
        renderRolePermissionWorkspace();
    });
    bindOptional('rolePermissionToolSearchInput', 'input', () => {
        selectedRolePermissionToolName = '';
        renderRolePermissionWorkspace();
    });
}

export async function loadAuthorizationPanel() {
    try {
        const [nextSnapshot] = await Promise.all([
            getAuthorizationSnapshot(),
            loadLocalAuthorizationTools()
        ]);
        snapshot = nextSnapshot;
        renderAuthorization();
    } catch (error) {
        onError(error);
    }
}

function bindOptional(id, event, handler) {
    document.getElementById(id)?.addEventListener(event, handler);
}

function switchSettingsTab(tab) {
    activeSettingsTab = tab || 'connection';
    document.querySelectorAll('[data-settings-tab]').forEach(button => {
        const active = button.dataset.settingsTab === activeSettingsTab;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    document.getElementById('settingsConnectionPanel')?.classList.toggle('d-none', activeSettingsTab !== 'connection');
    document.getElementById('settingsAuthorizationPanel')?.classList.toggle('d-none', activeSettingsTab !== 'authorization');
    if (activeSettingsTab === 'authorization') {
        loadAuthorizationPanel();
    }
}

function switchAuthSyncTab(tab) {
    activeAuthSyncTab = tab || 'users';
    document.querySelectorAll('[data-auth-sync-tab]').forEach(button => {
        const active = button.dataset.authSyncTab === activeAuthSyncTab;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    document.getElementById('authSyncUsersPanel')?.classList.toggle('d-none', activeAuthSyncTab !== 'users');
    document.getElementById('authSyncRolesPanel')?.classList.toggle('d-none', activeAuthSyncTab !== 'roles');
    document.getElementById('authSyncRulesPanel')?.classList.toggle('d-none', activeAuthSyncTab !== 'rules');
}

async function handleSync() {
    try {
        const [nextSnapshot] = await Promise.all([
            syncAuthorizationSnapshot(),
            loadLocalAuthorizationTools()
        ]);
        snapshot = nextSnapshot;
        renderAuthorization();
        notify('同步完成', '已从 API 控制面刷新 MCP 权限快照。');
    } catch (error) {
        onError(error);
    }
}

function renderAuthorization() {
    const current = snapshot || {};
    const users = nonAdminUsers(Array.isArray(current.users) ? current.users : []);
    const roles = nonAdminRoles(Array.isArray(current.roles) ? current.roles : []);
    const permissions = Array.isArray(current.permissions) ? current.permissions : [];
    const rolesById = new Map(roles.map(role => [String(role.id || '').toLowerCase(), role]));
    const roleLabel = roleId => {
        const role = rolesById.get(String(roleId || '').toLowerCase());
        return role ? (role.roleName || role.roleCode || role.id || '-') : (roleId || '-');
    };

    setText('mcpAuthStatusText', current.snapshotAvailable
        ? (current.stale ? 'Stale' : 'Ready')
        : (current.enabled ? 'Waiting' : 'Disabled'));
    setText('mcpAuthUserCount', users.length);
    setText('mcpAuthRoleCount', roles.length);
    setText('mcpAuthPermissionCount', permissions.length);

    document.getElementById('mcpAuthUserBody').innerHTML = users.length
        ? users.map(user => `
            <tr>
                <td>
                    <strong>${escapeHtml(user.username || user.id || '-')}</strong>
                    <div class="small text-secondary">${escapeHtml(user.displayName || user.id || '')}</div>
                </td>
                <td><code>${escapeHtml(user.tenantId || '-')}</code></td>
                <td>${(Array.isArray(user.roleIds) && user.roleIds.length)
                    ? user.roleIds.map(roleId => `<span class="badge text-bg-light">${escapeHtml(roleLabel(roleId))}</span>`).join(' ')
                    : '<span class="text-secondary">未分配</span>'}</td>
            </tr>
        `).join('')
        : '<tr><td colspan="3" class="text-secondary">暂无同步的非 admin 用户。</td></tr>';

    document.getElementById('mcpAuthRoleBody').innerHTML = roles.length
        ? roles.map(role => `
            <tr>
                <td>
                    <strong>${escapeHtml(role.roleName || role.roleCode || role.id || '-')}</strong>
                    <div class="small text-secondary">${escapeHtml(role.roleCode || role.id || '')}</div>
                </td>
                <td>${escapeHtml(role.roleType || '-')}</td>
                <td><span class="badge ${role.status === 'enabled' ? 'text-bg-success' : 'text-bg-secondary'}">${escapeHtml(role.status || '-')}</span></td>
                <td><code>${escapeHtml(role.tenantId || '-')}</code></td>
                <td>
                    <button class="btn btn-outline-primary btn-sm" type="button" data-role-permission-role="${escapeHtml(role.id || '')}">
                        授权管理
                    </button>
                </td>
            </tr>
        `).join('')
        : '<tr><td colspan="5" class="text-secondary">暂无同步的角色信息。</td></tr>';
    document.querySelectorAll('[data-role-permission-role]').forEach(button => {
        button.addEventListener('click', () => {
            const role = roles.find(item => item.id === button.dataset.rolePermissionRole);
            openRolePermissionModal(role);
        });
    });

    document.getElementById('mcpAuthPermissionBody').innerHTML = permissions.length
        ? permissions.map(permission => `
            <tr>
                <td>${escapeHtml(permission.targetType || '-')}:${escapeHtml(permission.targetId || '-')}</td>
                <td><code>${escapeHtml(permission.localToolName || '*')}</code></td>
                <td><span class="badge ${permission.effect === 'deny' ? 'text-bg-danger' : 'text-bg-success'}">${escapeHtml(permission.effect || 'allow')}</span></td>
                <td><code>${escapeHtml(permission.tenantId || '*')}</code></td>
            </tr>
        `).join('')
        : '<tr><td colspan="4" class="text-secondary">暂无同步的 MCP 授权规则。</td></tr>';
    if (rolePermissionRole) {
        rolePermissionRole = roles.find(role => role.id === rolePermissionRole.id) || rolePermissionRole;
        renderRolePermissionWorkspace();
    }
}

async function openRolePermissionModal(role) {
    if (!role?.id) return;
    rolePermissionRole = role;
    rolePermissionRules = [];
    selectedRolePermissionToolName = '';
    setText('rolePermissionTitle', `${role.roleName || role.roleCode || role.id} 授权`);
    document.getElementById('rolePermissionRemarkInput').value = '';
    document.getElementById('rolePermissionCategorySelect').value = 'all';
    document.getElementById('rolePermissionToolSearchInput').value = '';
    document.getElementById('rolePermissionModal').classList.remove('d-none');
    if (!localAuthorizationTools.length) {
        await loadLocalAuthorizationTools();
    }
    renderRolePermissionWorkspace();
    await loadRolePermissionRules();
}

function closeRolePermissionModal() {
    document.getElementById('rolePermissionModal').classList.add('d-none');
    rolePermissionRole = null;
    rolePermissionRules = [];
    selectedRolePermissionToolName = '';
}

function renderRolePermissionWorkspace() {
    renderRolePermissionToolBrowser();
    renderRolePermissionToolDetail();
}

function renderRolePermissionToolBrowser() {
    const list = document.getElementById('rolePermissionToolList');
    const tools = filteredRolePermissionTools();
    setText('rolePermissionToolCount', `${tools.length} 个可授权对象`);
    if (!list) return;
    if (!tools.length) {
        selectedRolePermissionToolName = '';
        list.innerHTML = '<div class="empty-state compact">当前分类或检索条件下没有可授权对象。</div>';
        return;
    }
    if (!tools.some(tool => tool.localToolName === selectedRolePermissionToolName)) {
        selectedRolePermissionToolName = tools[0].localToolName || '';
    }
    list.innerHTML = tools.map(tool => {
        const active = tool.localToolName === selectedRolePermissionToolName;
        return `
            <button class="role-tool-option ${active ? 'active' : ''}" type="button" data-role-tool-name="${escapeHtml(tool.localToolName || '')}">
                <span class="role-tool-option-main">
                    <span class="role-tool-option-title">${escapeHtml(tool.localToolName || tool.remoteToolName || tool.id || '-')}</span>
                    <span class="badge text-bg-light">${escapeHtml(toolCategoryLabel(toolCategory(tool)))}</span>
                </span>
                <span class="role-tool-option-meta">${escapeHtml(tool.serviceName || tool.serviceId || '默认服务')}</span>
                <span class="role-tool-option-desc">${escapeHtml(tool.description || tool.remoteToolName || '无描述')}</span>
            </button>
        `;
    }).join('');
    list.querySelectorAll('[data-role-tool-name]').forEach(button => {
        button.addEventListener('click', () => {
            selectedRolePermissionToolName = button.dataset.roleToolName || '';
            renderRolePermissionWorkspace();
        });
    });
}

function renderRolePermissionToolDetail() {
    const tool = selectedRolePermissionTool();
    const existingRule = tool
        ? rolePermissionRules.find(rule => rule.localToolName === tool.localToolName)
        : null;
    setText('rolePermissionSelectedToolName', tool?.localToolName || '请选择授权对象');
    setText('rolePermissionSelectedToolMeta', tool
        ? `${toolCategoryLabel(toolCategory(tool))} / ${tool.serviceName || tool.serviceId || '默认服务'}`
        : '左侧选择资产、数据库查询或 API 后再新增授权。');
    setText('rolePermissionSelectedToolDescription', tool?.description || '暂无描述');
    setText('rolePermissionSelectedToolStatus', existingRule
        ? `当前角色已授权：${existingRule.effect === 'deny' ? '拒绝' : '允许'}`
        : '当前角色未授权');
}

function selectedRolePermissionTool() {
    return authorizationTools().find(tool => tool.localToolName === selectedRolePermissionToolName) || null;
}

function filteredRolePermissionTools() {
    const category = document.getElementById('rolePermissionCategorySelect')?.value || 'all';
    const keyword = (document.getElementById('rolePermissionToolSearchInput')?.value || '').trim().toLowerCase();
    return authorizationTools()
        .filter(tool => tool.enabled !== false)
        .filter(tool => category === 'all' || toolCategory(tool) === category)
        .filter(tool => {
            if (!keyword) return true;
            return [
                tool.localToolName,
                tool.remoteToolName,
                tool.serviceName,
                tool.serviceId,
                tool.resourceType,
                tool.description
            ].some(value => String(value || '').toLowerCase().includes(keyword));
        });
}

async function loadRolePermissionRules() {
    if (!rolePermissionRole?.id) return;
    try {
        rolePermissionRules = await listRolePermissions(rolePermissionRole.id, rolePermissionRole.tenantId || '');
        renderRolePermissionRules();
        renderRolePermissionToolDetail();
    } catch (error) {
        onError(error);
    }
}

function renderRolePermissionRules() {
    const body = document.getElementById('rolePermissionBody');
    body.innerHTML = rolePermissionRules.length
        ? rolePermissionRules.map(rule => `
            <tr>
                <td><code>${escapeHtml(rule.localToolName || '*')}</code></td>
                <td><span class="badge ${rule.effect === 'deny' ? 'text-bg-danger' : 'text-bg-success'}">${rule.effect === 'deny' ? '拒绝' : '允许'}</span></td>
                <td><span class="badge ${rule.enabled === false ? 'text-bg-secondary' : 'text-bg-success'}">${rule.enabled === false ? '停用' : '启用'}</span></td>
                <td>
                    <button class="btn btn-outline-danger btn-sm" type="button" data-delete-role-permission="${escapeHtml(rule.id || '')}">
                        删除
                    </button>
                </td>
            </tr>
        `).join('')
        : '<tr><td colspan="4" class="text-secondary">暂无针对该角色的工具授权。</td></tr>';
    body.querySelectorAll('[data-delete-role-permission]').forEach(button => {
        button.addEventListener('click', () => handleRolePermissionDelete(button.dataset.deleteRolePermission));
    });
}

async function handleRolePermissionSubmit(event) {
    event.preventDefault();
    if (!rolePermissionRole?.id) return;
    const tool = selectedRolePermissionTool();
    if (!tool?.localToolName) {
        notify('请选择授权对象', '左侧没有选中的资产、数据库查询或 API。');
        return;
    }
    try {
        await createRolePermission({
            tenantId: rolePermissionRole.tenantId || '',
            roleId: rolePermissionRole.id,
            toolId: tool.id || '',
            localToolName: tool.localToolName,
            effect: document.getElementById('rolePermissionEffectSelect').value,
            enabled: true,
            remark: document.getElementById('rolePermissionRemarkInput').value.trim()
        });
        document.getElementById('rolePermissionRemarkInput').value = '';
        await loadRolePermissionRules();
        await loadAuthorizationPanel();
        notify('授权已保存', '角色工具授权已写入 API 控制面。');
    } catch (error) {
        onError(error);
    }
}

async function handleRolePermissionDelete(permissionId) {
    if (!permissionId || !window.confirm('确认删除该角色授权？')) return;
    try {
        await deleteRolePermission(permissionId);
        await loadRolePermissionRules();
        await loadAuthorizationPanel();
        notify('授权已删除', '角色工具授权已从 API 控制面删除。');
    } catch (error) {
        onError(error);
    }
}

async function loadLocalAuthorizationTools() {
    const results = await Promise.allSettled([
        listDatabaseQueries(),
        listSshAssets(),
        listSqlAssets(),
        listHttpAssets()
    ]);
    const [databaseQueries, sshAssets, sqlAssets, httpAssets] = results.map(result =>
        result.status === 'fulfilled' && Array.isArray(result.value) ? result.value : []
    );
    localAuthorizationTools = [
        toLocalTool({ id: 'api_asset_query', toolName: 'api_asset_query', enabled: true }, 'api', 'API 资产检索', '检索 API 服务资产元数据，不返回 URL、Header 或 Body 原始执行模板'),
        toLocalTool({ id: 'database_asset_search', toolName: 'database_asset_search', enabled: true }, 'asset', '数据库资产检索', '确认数据库数据源资产元数据'),
        toLocalTool({ id: 'document_search', toolName: 'document_search', enabled: true }, 'document', '文档检索', '检索系统管理的全部文档，访问范围由 document_search 工具权限控制'),
        toLocalTool({ id: 'api_template_query', toolName: 'api_template_query', enabled: true }, 'api', 'API 模板检索', '检索 API 服务模板，不返回 URL、Header 或 Body 原始执行模板'),
        toLocalTool({ id: 'ssh_template_query', toolName: 'ssh_template_query', enabled: true }, 'asset', 'SSH 模板检索', '检索 SSH 主机命令模板'),
        toLocalTool({ id: 'database_ops_template_search', toolName: 'database_ops_template_search', enabled: true }, 'asset', '数据库运维模板检索', '检索数据库维护、元数据和诊断模板'),
        toLocalTool({ id: 'http_endpoint_template_query', toolName: 'http_endpoint_template_query', enabled: true }, 'asset', 'HTTP 端点模板检索', '检索 HTTP 端点请求模板'),
        toLocalTool({ id: 'business_query_template_search', toolName: 'business_query_template_search', enabled: true }, 'database_query', '业务查询模板检索', '检索业务数据库查询模板，不返回原始 SQL'),
        toLocalTool({ id: 'ssh_asset_query', toolName: 'ssh_asset_query', enabled: true }, 'asset', 'SSH 资产检索', '检索 SSH 主机资产元数据'),
        toLocalTool({ id: 'http_endpoint_asset_query', toolName: 'http_endpoint_asset_query', enabled: true }, 'asset', 'HTTP 端点资产检索', '检索 HTTP 端点资产元数据'),
        ...databaseQueries.map(item => toLocalTool(item, 'database_query', '数据库查询', item.title || item.description || item.sqlTemplate)),
        ...sshAssets.map(item => toLocalTool(item, 'asset', '服务器资产', item.title || item.description || item.hostname)),
        ...sqlAssets.map(item => toLocalTool(item, 'asset', '数据库资产', item.title || item.description || item.jdbcUrl)),
        ...httpAssets.map(item => toLocalTool(item, 'asset', 'HTTP 请求资产', item.title || item.description || item.urlTemplate))
    ].filter(tool => tool.localToolName);
}

function toLocalTool(item, resourceType, serviceName, description) {
    return {
        id: item.id || item.toolName || '',
        localToolName: item.toolName || '',
        remoteToolName: item.toolName || '',
        serviceId: resourceType,
        serviceName,
        resourceType,
        description: description || '',
        enabled: item.enabled !== false,
        status: item.enabled === false ? 'disabled' : 'online'
    };
}

function authorizationTools() {
    const merged = new Map();
    for (const tool of Array.isArray(snapshot?.tools) ? snapshot.tools : []) {
        if (tool.localToolName) {
            merged.set(tool.localToolName, tool);
        }
    }
    for (const tool of localAuthorizationTools) {
        if (!tool.localToolName) continue;
        merged.set(tool.localToolName, {
            ...(merged.get(tool.localToolName) || {}),
            ...tool
        });
    }
    return [...merged.values()].sort((left, right) =>
        String(left.localToolName || '').localeCompare(String(right.localToolName || ''))
    );
}

function nonAdminUsers(users) {
    return users.filter(user => !isAdminValue(user.username) && !isAdminValue(user.id));
}

function nonAdminRoles(roles) {
    return roles.filter(role => !isAdminValue(role.roleCode) && !isAdminValue(role.roleName) && !isAdminValue(role.id));
}

function isAdminValue(value) {
    return String(value || '').trim().toLowerCase() === 'admin';
}

function toolCategory(tool) {
    const explicit = String(tool.resourceType || '').toLowerCase();
    if (['api', 'database_query', 'document', 'asset', 'other'].includes(explicit)) {
        return explicit;
    }
    const joined = [
        tool.localToolName,
        tool.remoteToolName,
        tool.serviceName,
        tool.serviceId,
        tool.description
    ].map(value => String(value || '').toLowerCase()).join(' ');
    if (joined.includes('database_query') || joined.includes('database query') || joined.includes('query_mcp') || joined.includes('business database')) {
        return 'database_query';
    }
    if (joined.includes('document_search') || (joined.includes('document') && joined.includes('search'))) {
        return 'document';
    }
    if (joined.includes('api') || joined.includes('livedata')) {
        return 'api';
    }
    if (joined.includes('asset') || joined.includes('ssh') || joined.includes('sql') || joined.includes('http') || joined.includes('linux_command') || joined.includes('template_query')) {
        return 'asset';
    }
    return 'other';
}

function toolCategoryLabel(category) {
    return {
        api: 'API 服务',
        database_query: '数据库查询',
        document: '文档检索',
        asset: '资产工具',
        other: '其他工具'
    }[category] || '其他工具';
}

function setText(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.textContent = value ?? '';
    }
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

