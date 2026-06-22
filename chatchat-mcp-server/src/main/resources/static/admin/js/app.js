import { MCP_ENDPOINT } from './config.js';
import { loadLayout } from './layout.js';
import { getToken, login, logout } from './auth.js';
import { UnauthorizedError } from './http.js';
import {
    deleteService,
    deleteServices,
    listLivedataApis,
    listServices,
    registerLivedataApis,
    refreshTools,
    saveService,
    setEnabled,
    testService
} from './apiServices.js';
import {
    deleteMcpService,
    generateMcpToken,
    listMcpServices,
    regenerateMcpToken,
    saveMcpService,
    setMcpEnabled
} from './mcpServices.js';
import {
    listHttpAssets,
    listSqlAssets,
    listSshAssets,
    refreshOpsTools,
    refreshSqlTools,
    saveHttpAsset,
    saveSqlAsset,
    saveSshAsset
} from './assetCenter.js';
import { getAuditLog, listAuditLogs } from './auditLogs.js';
import {
    deleteDatabaseQuery,
    deleteDatabaseQueries,
    listDatabaseQueries,
    saveDatabaseQuery,
    setDatabaseQueryEnabled,
    testDatabaseQuery,
    testSavedDatabaseQuery
} from './databaseMcp.js';
import {
    listNotificationChannels,
    refreshNotificationTools,
    saveNotificationChannel,
    setNotificationEnabled,
    setNotificationRuntimeAction,
    testNotificationChannel
} from './notificationChannels.js';
import { fillServiceForm, readServiceForm, readTestArgs, readTestArgsFromSchema, toggleMicroserviceFields } from './form.js';
import {
    hideLoginError,
    hideApiServiceModal,
    hideDatabaseQueryModal,
    hideLivedataImportModal,
    hideMcpServiceModal,
    hideNotificationChannelModal,
    hideHttpAssetModal,
    hideSqlAssetModal,
    hideSshAssetModal,
    initUi,
    notify,
    renderNotificationChannels,
    renderDatabaseQueries,
    renderDatabaseQueryPreview,
    renderAuditLogs,
    renderMcpServices,
    renderServices,
    showApp,
    showApiServiceModal,
    showDatabaseQueryModal,
    showLivedataImportModal,
    showLogin,
    showLoginError,
    showMcpServiceModal,
    showNotificationChannelModal,
    showHttpAssetModal,
    showSqlAssetModal,
    showSshAssetModal,
    showResult,
    switchView
} from './ui.js';

let services = [];
let selectedId = '';
let selectedServiceIds = new Set();
let serviceSearchTerm = '';
let servicePage = 1;
let livedataApis = [];
let livedataSearchTerm = '';
let selectedLivedataApiIds = new Set();
let mcpServices = [];
let selectedMcpId = '';
let mcpServiceSearchTerm = '';
let mcpServicePage = 1;
let sshAssets = [];
let sqlAssets = [];
let httpAssets = [];
let selectedSshAssetId = '';
let selectedSqlAssetId = '';
let selectedHttpAssetId = '';
let activeAssetTab = 'ssh';
let sshAssetSearchTerm = '';
let sshAssetEnvironmentFilter = '';
let sshAssetStatusFilter = '';
let sshAssetCategoryFilter = '';
let sqlAssetSearchTerm = '';
let sqlAssetEnvironmentFilter = '';
let sqlAssetStatusFilter = '';
let sqlAssetCategoryFilter = '';
let httpAssetSearchTerm = '';
let httpAssetEnvironmentFilter = '';
let httpAssetStatusFilter = '';
let httpAssetMethodFilter = '';
let httpAssetCategoryFilter = '';
let databaseQueries = [];
let selectedDatabaseQueryId = '';
let selectedDatabaseQueryIds = new Set();
let databaseQuerySearchTerm = '';
let databaseQueryPage = 1;
let notificationChannels = [];
let selectedNotificationChannelId = '';
let notificationSearchTerm = '';
let auditLogKeyword = '';
let auditLogTargetType = '';
let auditLogSuccess = '';
let auditLogPage = 1;
let auditLogPageSize = 20;

const SERVICE_PAGE_SIZE = 12;
const GOVERNANCE_MASK_FIELDS = ['phone', 'id_card', 'account_no'];

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await loadLayout();
        initUi();
        document.getElementById('mcpEndpointText').textContent = MCP_ENDPOINT;
        bindEvents();
        if (getToken()) {
            await enterApp();
        } else {
            showLogin();
        }
    } catch (error) {
        document.getElementById('adminRoot').innerHTML = `
            <main class="login-shell">
                <section class="login-panel">
                    <h1>页面加载失败</h1>
                    <p>${error.message || '管理台资源加载失败。'}</p>
                </section>
            </main>
        `;
    }
});

function bindEvents() {
    document.getElementById('loginForm').addEventListener('submit', handleLogin);
    const togglePasswordBtn = document.getElementById('togglePasswordBtn');
    if (togglePasswordBtn) {
        togglePasswordBtn.addEventListener('click', togglePasswordVisibility);
    }
    document.getElementById('logoutBtn').addEventListener('click', handleLogout);
    document.getElementById('serviceForm').addEventListener('submit', handleSave);
    document.getElementById('newServiceBtn').addEventListener('click', openNewService);
    document.getElementById('resetFormBtn').addEventListener('click', () => resetForm());
    document.getElementById('serviceSearchInput').addEventListener('input', handleServiceSearch);
    document.getElementById('servicePrevPageBtn').addEventListener('click', () => changeServicePage(-1));
    document.getElementById('serviceNextPageBtn').addEventListener('click', () => changeServicePage(1));
    document.getElementById('serviceSelectVisibleBtn').addEventListener('click', selectVisibleServices);
    document.getElementById('serviceClearSelectionBtn').addEventListener('click', clearServiceSelection);
    document.getElementById('serviceBatchDeleteBtn').addEventListener('click', removeSelectedServices);
    document.getElementById('importLivedataBtn').addEventListener('click', openLivedataImport);
    document.getElementById('reloadLivedataApisBtn').addEventListener('click', reloadLivedataApis);
    document.getElementById('selectAllLivedataApisBtn').addEventListener('click', selectVisibleLivedataApis);
    document.getElementById('clearLivedataSelectionBtn').addEventListener('click', clearLivedataSelection);
    document.getElementById('registerLivedataApisBtn').addEventListener('click', handleLivedataRegister);
    document.getElementById('livedataApiSearchInput').addEventListener('input', handleLivedataSearch);
    document.getElementById('livedataApiBody').addEventListener('change', handleLivedataSelectionChange);
    document.getElementById('microserviceMode').addEventListener('change', toggleMicroserviceFields);
    document.getElementById('testServiceBtn').addEventListener('click', handleTest);
    document.getElementById('refreshBtn').addEventListener('click', handleRefresh);
    document.getElementById('mcpServiceForm').addEventListener('submit', handleMcpSave);
    document.getElementById('newMcpServiceBtn').addEventListener('click', openNewMcpService);
    document.getElementById('resetMcpFormBtn').addEventListener('click', resetMcpForm);
    document.getElementById('mcpServiceSearchInput').addEventListener('input', handleMcpServiceSearch);
    document.getElementById('mcpServicePrevPageBtn').addEventListener('click', () => changeMcpServicePage(-1));
    document.getElementById('mcpServiceNextPageBtn').addEventListener('click', () => changeMcpServicePage(1));
    document.getElementById('generateMcpTokenBtn').addEventListener('click', handleGenerateMcpToken);
    document.getElementById('regenSavedMcpTokenBtn').addEventListener('click', handleRegenerateSavedMcpToken);
    document.getElementById('newSshAssetBtn').addEventListener('click', openNewSshAsset);
    document.getElementById('newSqlAssetBtn').addEventListener('click', openNewSqlAsset);
    bindOptional('newHttpAssetBtn', 'click', openNewHttpAsset);
    document.getElementById('sshAssetForm').addEventListener('submit', handleSshAssetSave);
    document.getElementById('sqlAssetForm').addEventListener('submit', handleSqlAssetSave);
    bindOptional('httpAssetForm', 'submit', handleHttpAssetSave);
    document.getElementById('refreshOpsAssetToolsBtn').addEventListener('click', handleOpsAssetRefresh);
    document.getElementById('refreshSqlAssetToolsBtn').addEventListener('click', handleSqlAssetRefresh);
    document.querySelectorAll('[data-asset-tab]').forEach(button => {
        button.addEventListener('click', () => switchAssetTab(button.dataset.assetTab));
    });
    document.getElementById('sshAssetSearchInput').addEventListener('input', handleSshAssetFilter);
    document.getElementById('sshAssetEnvironmentFilter').addEventListener('change', handleSshAssetFilter);
    document.getElementById('sshAssetStatusFilter').addEventListener('change', handleSshAssetFilter);
    document.getElementById('sshAssetCategoryFilter').addEventListener('change', handleSshAssetFilter);
    document.getElementById('sqlAssetSearchInput').addEventListener('input', handleSqlAssetFilter);
    document.getElementById('sqlAssetEnvironmentFilter').addEventListener('change', handleSqlAssetFilter);
    document.getElementById('sqlAssetStatusFilter').addEventListener('change', handleSqlAssetFilter);
    document.getElementById('sqlAssetCategoryFilter').addEventListener('change', handleSqlAssetFilter);
    bindOptional('httpAssetSearchInput', 'input', handleHttpAssetFilter);
    bindOptional('httpAssetEnvironmentFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetStatusFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetMethodFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetCategoryFilter', 'change', handleHttpAssetFilter);
    document.getElementById('databaseQueryForm').addEventListener('submit', handleDatabaseQueryTest);
    document.getElementById('databaseQuerySaveBtn').addEventListener('click', handleDatabaseQuerySave);
    document.getElementById('databaseQueryClearBtn').addEventListener('click', resetDatabaseQueryForm);
    document.getElementById('newDatabaseQueryBtn').addEventListener('click', openNewDatabaseQuery);
    document.getElementById('databaseQuerySearchInput').addEventListener('input', handleDatabaseQuerySearch);
    document.getElementById('databaseQueryPrevPageBtn').addEventListener('click', () => changeDatabaseQueryPage(-1));
    document.getElementById('databaseQueryNextPageBtn').addEventListener('click', () => changeDatabaseQueryPage(1));
    document.getElementById('databaseQuerySelectVisibleBtn').addEventListener('click', selectVisibleDatabaseQueries);
    document.getElementById('databaseQueryClearSelectionBtn').addEventListener('click', clearDatabaseQuerySelection);
    document.getElementById('databaseQueryBatchDeleteBtn').addEventListener('click', removeSelectedDatabaseQueries);
    document.getElementById('databaseDatasourceSelect').addEventListener('change', toggleDatabaseExternalFields);
    document.getElementById('notificationSearchInput').addEventListener('input', handleNotificationSearch);
    document.getElementById('refreshNotificationToolsBtn').addEventListener('click', handleNotificationRefresh);
    document.getElementById('notificationChannelForm').addEventListener('submit', handleNotificationSave);
    document.getElementById('notificationTestBtn').addEventListener('click', handleNotificationTest);
    document.getElementById('notificationDeliveryMode').addEventListener('change', toggleNotificationDeliveryFields);
    document.getElementById('reloadAuditBtn').addEventListener('click', loadAuditLogs);
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
    document.querySelectorAll('.sidebar [data-view]').forEach(button => {
        button.addEventListener('click', () => handleViewSwitch(button.dataset.view));
    });
}

function bindOptional(id, event, handler) {
    document.getElementById(id)?.addEventListener(event, handler);
}

function togglePasswordVisibility() {
    const password = document.getElementById('password');
    const nextType = password.type === 'password' ? 'text' : 'password';
    password.type = nextType;
    this.setAttribute('aria-label', nextType === 'password' ? '显示密码' : '隐藏密码');
}

async function handleLogin(event) {
    event.preventDefault();
    hideLoginError();
    try {
        await login(
            document.getElementById('username').value.trim(),
            document.getElementById('password').value
        );
        await enterApp();
    } catch (error) {
        showLoginError(error.message);
    }
}

async function enterApp() {
    showApp();
    resetForm();
    resetMcpForm();
    await loadServices();
}

async function handleLogout() {
    await logout();
    showLogin();
}

async function handleViewSwitch(view) {
    switchView(view);
    if (view === 'apiServices') await loadServices();
    if (view === 'mcpServices') await loadMcpServices();
    if (view === 'assetCenter') await loadAssets();
    if (view === 'databaseMcp') {
        await loadDatabaseQueries();
    }
    if (view === 'notificationChannels') await loadNotificationChannels();
    if (view === 'auditLogs') await loadAuditLogs();
}

async function loadServices() {
    try {
        services = await listServices();
        if (selectedId && !services.some(service => service.id === selectedId)) selectedId = '';
        selectedServiceIds = new Set([...selectedServiceIds].filter(id => services.some(service => service.id === id)));
        renderApiServices();
    } catch (error) {
        handleError(error);
    }
}

function renderApiServices() {
    const filtered = filterServices();
    servicePage = clampPage(servicePage, filtered.length, SERVICE_PAGE_SIZE);
    const visible = paginate(filtered, servicePage, SERVICE_PAGE_SIZE);
    renderServices(filtered, selectedId, {
        edit: selectService,
        test: testServiceFromCard,
        toggle: toggleService,
        delete: removeService,
        select: toggleServiceSelection
    }, {
        totalCount: services.length,
        filteredCount: filtered.length,
        page: servicePage,
        pageSize: SERVICE_PAGE_SIZE,
        visible,
        selectedIds: selectedServiceIds
    });
}

function filterServices() {
    const keyword = serviceSearchTerm.trim().toLowerCase();
    if (!keyword) {
        return services;
    }
    return services.filter(service => [
        service.toolName,
        service.title,
        service.description,
        service.method,
        service.urlTemplate
    ].some(value => String(value || '').toLowerCase().includes(keyword)));
}

function handleServiceSearch(event) {
    serviceSearchTerm = event.target.value;
    servicePage = 1;
    renderApiServices();
}

function openNewService() {
    serviceSearchTerm = '';
    servicePage = 1;
    document.getElementById('serviceSearchInput').value = '';
    renderApiServices();
    resetForm();
    showApiServiceModal();
}

function selectService(service) {
    selectedId = service.id;
    document.getElementById('formTitle').textContent = '编辑 API 服务';
    fillServiceForm(service);
    renderApiServices();
    showApiServiceModal();
}

function resetForm() {
    selectedId = '';
    document.getElementById('formTitle').textContent = '新增 API 服务';
    fillServiceForm(null);
}

async function handleSave(event) {
    event.preventDefault();
    try {
        const saved = await saveService(readServiceForm());
        selectedId = saved.id;
        notify('保存成功', `${saved.toolName} 已发布为 MCP 工具。`);
        await loadServices();
        selectedId = saved.id;
        renderApiServices();
        hideApiServiceModal();
    } catch (error) {
        handleError(error);
    }
}

async function handleTest() {
    const id = document.getElementById('serviceId').value;
    if (!id) {
        notify('无法测试', '请先保存 API 服务。');
        return;
    }
    try {
        const result = await testService(id, readTestArgs());
        const service = services.find(item => item.id === id);
        showResult(result, resultOptions(service));
        await loadAuditLogs(false);
    } catch (error) {
        handleError(error);
    }
}

async function testServiceFromCard(service) {
    selectedId = service.id;
    renderApiServices();
    try {
        const result = await testService(service.id, readTestArgsFromSchema(service.inputSchema));
        showResult(result, resultOptions(service));
        await loadAuditLogs(false);
    } catch (error) {
        handleError(error);
    }
}

function resultOptions(service) {
    if (!service) {
        return {};
    }
    return {
        title: `${service.title || service.toolName} 测试结果`,
        subtitle: service.toolName
    };
}

async function handleRefresh() {
    try {
        await refreshTools();
        notify('刷新成功', 'MCP 工具列表已刷新。');
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function openLivedataImport() {
    livedataSearchTerm = '';
    selectedLivedataApiIds = new Set();
    document.getElementById('livedataApiSearchInput').value = '';
    document.getElementById('overwriteLivedataExisting').checked = false;
    showLivedataImportModal();
    await reloadLivedataApis();
}

async function reloadLivedataApis() {
    const button = document.getElementById('reloadLivedataApisBtn');
    button.disabled = true;
    try {
        livedataApis = await listLivedataApis();
        selectedLivedataApiIds = new Set([...selectedLivedataApiIds].filter(id =>
            livedataApis.some(api => api.id === id && api.canRegister)
        ));
        renderLivedataImport();
    } catch (error) {
        handleError(error);
    } finally {
        button.disabled = false;
    }
}

function handleLivedataSearch(event) {
    livedataSearchTerm = event.target.value;
    renderLivedataImport();
}

function handleLivedataSelectionChange(event) {
    if (event.target?.dataset?.livedataApiId == null) {
        return;
    }
    const id = event.target.dataset.livedataApiId;
    if (event.target.checked) {
        selectedLivedataApiIds.add(id);
    } else {
        selectedLivedataApiIds.delete(id);
    }
    updateLivedataImportSummary(filterLivedataApis());
}

function selectVisibleLivedataApis() {
    for (const api of filterLivedataApis()) {
        if (api.canRegister) {
            selectedLivedataApiIds.add(api.id);
        }
    }
    renderLivedataImport();
}

function clearLivedataSelection() {
    selectedLivedataApiIds.clear();
    renderLivedataImport();
}

async function handleLivedataRegister() {
    const ids = [...selectedLivedataApiIds];
    if (!ids.length) {
        notify('请选择 API', '勾选需要注册为 MCP 工具的 LiveData API。');
        return;
    }
    const button = document.getElementById('registerLivedataApisBtn');
    button.disabled = true;
    try {
        const result = await registerLivedataApis(ids, document.getElementById('overwriteLivedataExisting').checked);
        notify('注册完成', `成功 ${result.registered} 个，跳过 ${result.skipped} 个，缺失 ${result.missing} 个。`);
        selectedLivedataApiIds.clear();
        await loadServices();
        await reloadLivedataApis();
        if (!result.errors?.length) {
            hideLivedataImportModal();
        }
    } catch (error) {
        handleError(error);
    } finally {
        button.disabled = false;
    }
}

function renderLivedataImport() {
    const filtered = filterLivedataApis();
    const body = document.getElementById('livedataApiBody');
    body.innerHTML = '';
    updateLivedataImportSummary(filtered);

    if (!filtered.length) {
        body.innerHTML = '<tr><td colspan="6" class="text-secondary">暂无匹配的 LiveData API。</td></tr>';
        return;
    }

    for (const api of filtered) {
        const row = document.createElement('tr');
        const disabled = !api.canRegister;
        row.innerHTML = `
            <td>
                <input class="form-check-input" type="checkbox"
                    data-livedata-api-id="${escapeHtml(api.id)}"
                    ${selectedLivedataApiIds.has(api.id) ? 'checked' : ''}
                    ${disabled ? 'disabled' : ''}>
            </td>
            <td>
                <span class="livedata-api-title">${escapeHtml(api.apiName || api.apiId || api.title || '-')}</span>
                <div class="small text-secondary">${escapeHtml(api.apiId || api.id || '')}</div>
                <div class="livedata-api-desc">${escapeHtml(api.description || api.error || '')}</div>
            </td>
            <td>
                <code>${escapeHtml(api.toolName || '-')}</code>
                ${api.registered ? '<div><span class="badge text-bg-info">已注册</span></div>' : ''}
            </td>
            <td>
                <div>${escapeHtml(api.serviceName || '-')}</div>
                <div class="small text-secondary">${escapeHtml(api.methodName || api.namespace || '')}</div>
            </td>
            <td>
                <span class="badge ${api.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${api.enabled ? '启用' : '停用'}</span>
                ${api.canRegister ? '' : '<span class="badge text-bg-danger ms-1">配置异常</span>'}
            </td>
            <td>${escapeHtml(api.releaseVersion || api.version || '-')}</td>
        `;
        body.appendChild(row);
    }
}

function filterLivedataApis() {
    const keyword = livedataSearchTerm.trim().toLowerCase();
    if (!keyword) {
        return livedataApis;
    }
    return livedataApis.filter(api => [
        api.apiId,
        api.apiName,
        api.toolName,
        api.title,
        api.description,
        api.namespace,
        api.serviceName,
        api.methodName,
        api.urlTemplate
    ].some(value => String(value || '').toLowerCase().includes(keyword)));
}

function updateLivedataImportSummary(filtered) {
    document.getElementById('livedataApiTotalCount').textContent = livedataApis.length;
    document.getElementById('livedataApiFilteredCount').textContent = filtered.length;
    document.getElementById('livedataApiSelectedCount').textContent = selectedLivedataApiIds.size;
    document.getElementById('registerLivedataApisBtn').disabled = selectedLivedataApiIds.size === 0;
}

async function toggleService(service) {
    try {
        await setEnabled(service.id, !service.enabled);
        notify('更新成功', `${service.toolName} 已${service.enabled ? '停用' : '启用'}。`);
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function removeService(service) {
    if (!window.confirm(`确定删除 ${service.toolName} 吗？`)) return;
    try {
        await deleteService(service.id);
        notify('删除成功', `${service.toolName} 已删除。`);
        if (selectedId === service.id) {
            resetForm();
            hideApiServiceModal();
        }
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

function toggleServiceSelection(service, selected) {
    if (selected) {
        selectedServiceIds.add(service.id);
    } else {
        selectedServiceIds.delete(service.id);
    }
    renderApiServices();
}

function selectVisibleServices() {
    for (const service of paginate(filterServices(), servicePage, SERVICE_PAGE_SIZE)) {
        selectedServiceIds.add(service.id);
    }
    renderApiServices();
}

function clearServiceSelection() {
    selectedServiceIds.clear();
    renderApiServices();
}

async function removeSelectedServices() {
    const ids = [...selectedServiceIds];
    if (!ids.length) {
        notify('未选择 API', '请先选择需要删除的 API。');
        return;
    }
    if (!window.confirm(`确定删除选中的 ${ids.length} 个 API 吗？`)) return;
    try {
        const result = await deleteServices(ids);
        selectedServiceIds.clear();
        if (ids.includes(selectedId)) {
            resetForm();
            hideApiServiceModal();
        }
        notify('删除成功', `已删除 ${result.deleted ?? ids.length} 个 API。`);
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function loadMcpServices() {
    try {
        mcpServices = await listMcpServices();
        if (selectedMcpId && !mcpServices.some(service => service.id === selectedMcpId)) selectedMcpId = '';
        renderMcpServiceCards();
    } catch (error) {
        handleError(error);
    }
}

function renderMcpServiceCards() {
    const filtered = filterMcpServices();
    mcpServicePage = clampPage(mcpServicePage, filtered.length, SERVICE_PAGE_SIZE);
    const visible = paginate(filtered, mcpServicePage, SERVICE_PAGE_SIZE);
    renderMcpServices(filtered, selectedMcpId, {
        edit: selectMcpService,
        toggle: toggleMcpService,
        delete: removeMcpService
    }, {
        totalCount: mcpServices.length,
        filteredCount: filtered.length,
        page: mcpServicePage,
        pageSize: SERVICE_PAGE_SIZE,
        visible
    });
}

function filterMcpServices() {
    const keyword = mcpServiceSearchTerm.trim().toLowerCase();
    if (!keyword) {
        return mcpServices;
    }
    return mcpServices.filter(service => [
        service.name,
        service.endpoint,
        service.serviceType,
        service.permissionGroup,
        service.status
    ].some(value => String(value || '').toLowerCase().includes(keyword)));
}

function handleMcpServiceSearch(event) {
    mcpServiceSearchTerm = event.target.value;
    mcpServicePage = 1;
    renderMcpServiceCards();
}

function openNewMcpService() {
    mcpServiceSearchTerm = '';
    mcpServicePage = 1;
    document.getElementById('mcpServiceSearchInput').value = '';
    renderMcpServiceCards();
    resetMcpForm();
    showMcpServiceModal();
}

function selectMcpService(service) {
    selectedMcpId = service.id;
    document.getElementById('mcpFormTitle').textContent = '编辑 MCP 服务';
    setValue('mcpServiceId', service.id);
    setValue('mcpName', service.name);
    setValue('mcpEndpoint', service.endpoint);
    setValue('mcpServiceToken', service.serviceToken);
    setValue('mcpServiceType', service.serviceType || 'DATA');
    setValue('mcpPermissionGroup', service.permissionGroup || 'default');
    setValue('mcpEnabled', String(service.enabled));
    setValue('mcpStatus', service.status || 'ACTIVE');
    renderMcpServiceCards();
    showMcpServiceModal();
}

function resetMcpForm() {
    selectedMcpId = '';
    document.getElementById('mcpFormTitle').textContent = '新增 MCP 服务';
    setValue('mcpServiceId', '');
    setValue('mcpName', '');
    setValue('mcpEndpoint', '');
    setValue('mcpServiceToken', '');
    setValue('mcpServiceType', 'DATA');
    setValue('mcpPermissionGroup', 'default');
    setValue('mcpEnabled', 'true');
    setValue('mcpStatus', 'ACTIVE');
}

async function handleMcpSave(event) {
    event.preventDefault();
    try {
        const saved = await saveMcpService(readMcpForm());
        selectedMcpId = saved.id;
        notify('保存成功', `${saved.name} 已注册。`);
        await loadMcpServices();
        selectedMcpId = saved.id;
        renderMcpServiceCards();
        hideMcpServiceModal();
    } catch (error) {
        handleError(error);
    }
}

async function handleGenerateMcpToken() {
    try {
        const result = await generateMcpToken();
        setValue('mcpServiceToken', result.token);
        notify('Token 已生成', '请将该 Token 配置到接入服务中。');
    } catch (error) {
        handleError(error);
    }
}

async function handleRegenerateSavedMcpToken() {
    const id = value('mcpServiceId');
    if (!id) {
        notify('无法重新生成', '请先保存 MCP 服务。');
        return;
    }
    try {
        const updated = await regenerateMcpToken(id);
        selectMcpService(updated);
        notify('Token 已重新生成', `${updated.name} 已使用新的服务 Token。`);
    } catch (error) {
        handleError(error);
    }
}

async function toggleMcpService(service) {
    try {
        await setMcpEnabled(service.id, !service.enabled);
        notify('更新成功', `${service.name} 已${service.enabled ? '停用' : '启用'}。`);
        await loadMcpServices();
    } catch (error) {
        handleError(error);
    }
}

async function removeMcpService(service) {
    if (!window.confirm(`确定删除 ${service.name} 吗？`)) return;
    try {
        await deleteMcpService(service.id);
        notify('删除成功', `${service.name} 已删除。`);
        if (selectedMcpId === service.id) {
            resetMcpForm();
            hideMcpServiceModal();
        }
        await loadMcpServices();
    } catch (error) {
        handleError(error);
    }
}

function readMcpForm() {
    return {
        id: value('mcpServiceId'),
        name: value('mcpName'),
        endpoint: value('mcpEndpoint'),
        serviceToken: value('mcpServiceToken'),
        serviceType: value('mcpServiceType'),
        permissionGroup: value('mcpPermissionGroup'),
        enabled: value('mcpEnabled') === 'true',
        status: value('mcpStatus')
    };
}

async function loadAuditLogs(showNotice = true) {
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
        handleError(error);
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
    await loadAuditLogs(false);
}

async function resetAuditLogFilters() {
    auditLogKeyword = '';
    auditLogTargetType = '';
    auditLogSuccess = '';
    auditLogPage = 1;
    document.getElementById('auditLogSearchInput').value = '';
    document.getElementById('auditLogTargetTypeSelect').value = '';
    document.getElementById('auditLogSuccessSelect').value = '';
    await loadAuditLogs(false);
}

async function changeAuditLogPageSize(event) {
    auditLogPageSize = Number(event.target.value) || 20;
    auditLogPage = 1;
    await loadAuditLogs(false);
}

async function changeAuditLogPage(delta) {
    auditLogPage = Math.max(1, auditLogPage + delta);
    await loadAuditLogs(false);
}

async function openAuditLogDetail(log) {
    try {
        const detail = await getAuditLog(log.id);
        showResult(detail, {
            title: `${detail.toolName || detail.targetName || detail.targetId || '调用'} 审计明细`,
            subtitle: `${detail.targetType || '-'} · ${formatAuditTime(detail.createdAt)}`
        });
    } catch (error) {
        handleError(error);
    }
}

function formatAuditTime(value) {
    return value ? new Date(value).toLocaleString() : '-';
}

async function loadDatabaseQueries() {
    try {
        [databaseQueries, sqlAssets] = await Promise.all([listDatabaseQueries(), listSqlAssets()]);
        if (selectedDatabaseQueryId && !databaseQueries.some(query => query.id === selectedDatabaseQueryId)) {
            selectedDatabaseQueryId = '';
        }
        renderDatabaseDatasourceOptions();
        selectedDatabaseQueryIds = new Set([...selectedDatabaseQueryIds].filter(id =>
            databaseQueries.some(query => query.id === id)
        ));
        renderDatabaseQueryCards();
    } catch (error) {
        handleError(error);
    }
}

function renderDatabaseQueryCards() {
    const filtered = filterDatabaseQueries();
    databaseQueryPage = clampPage(databaseQueryPage, filtered.length, SERVICE_PAGE_SIZE);
    const visible = paginate(filtered, databaseQueryPage, SERVICE_PAGE_SIZE);
    renderDatabaseQueries(filtered, selectedDatabaseQueryId, {
            edit: selectDatabaseQuery,
            test: testDatabaseQueryFromCard,
            toggle: toggleDatabaseQuery,
            delete: removeDatabaseQuery,
            select: toggleDatabaseQuerySelection
    }, {
        totalCount: databaseQueries.length,
        filteredCount: filtered.length,
        page: databaseQueryPage,
        pageSize: SERVICE_PAGE_SIZE,
        visible,
        selectedIds: selectedDatabaseQueryIds
    });
}

function filterDatabaseQueries() {
    const keyword = databaseQuerySearchTerm.trim().toLowerCase();
    if (!keyword) {
        return databaseQueries;
    }
    return databaseQueries.filter(query => [
        query.toolName,
        query.title,
        query.description,
        query.sqlTemplate,
        query.jdbcUrl
    ].some(value => String(value || '').toLowerCase().includes(keyword)));
}

function handleDatabaseQuerySearch(event) {
    databaseQuerySearchTerm = event.target.value;
    databaseQueryPage = 1;
    renderDatabaseQueryCards();
}

function openNewDatabaseQuery() {
    resetDatabaseQueryForm();
    showDatabaseQueryModal();
}

function changeDatabaseQueryPage(delta) {
    databaseQueryPage = clampPage(databaseQueryPage + delta, filterDatabaseQueries().length, SERVICE_PAGE_SIZE);
    renderDatabaseQueryCards();
}

async function handleDatabaseQueryTest(event) {
    event.preventDefault();
    const button = document.getElementById('databaseQueryTestBtn');
    const status = document.getElementById('databaseQueryStatus');
    button.disabled = true;
    status.textContent = '正在执行...';
    try {
        const output = await testDatabaseQuery(readDatabaseQueryForm());
        renderDatabaseQueryOutput(output);
        if (!output.success) {
            notify('查询失败', output.errorMessage || '数据库查询执行失败。');
        }
    } catch (error) {
        renderDatabaseQueryPreview({
            success: false,
            errorMessage: error.message || '数据库查询执行失败'
        });
        notify('查询失败', error.message || '数据库查询执行失败');
    } finally {
        button.disabled = false;
        status.textContent = '';
    }
}

async function handleDatabaseQuerySave() {
    const form = document.getElementById('databaseQueryForm');
    if (!form.reportValidity()) {
        return;
    }
    try {
        const saved = await saveDatabaseQuery(readDatabaseQueryRegistrationForm());
        selectedDatabaseQueryId = saved.id;
        notify('保存成功', `${saved.toolName} 已注册为 MCP 查询工具。`);
        await loadDatabaseQueries();
        fillDatabaseQueryForm(saved);
        hideDatabaseQueryModal();
    } catch (error) {
        handleError(error);
    }
}

function selectDatabaseQuery(query) {
    selectedDatabaseQueryId = query.id;
    fillDatabaseQueryForm(query);
    renderDatabaseQueryCards();
    showDatabaseQueryModal();
}

async function testDatabaseQueryFromCard(query) {
    try {
        const params = promptJsonObject('请输入查询参数 JSON', {});
        if (params == null) return;
        selectedDatabaseQueryId = query.id;
        fillDatabaseQueryForm(query);
        renderDatabaseQueryOutput(await testSavedDatabaseQuery(query.id, params));
        renderDatabaseQueryCards();
        showDatabaseQueryModal();
    } catch (error) {
        handleError(error);
    }
}

async function toggleDatabaseQuery(query) {
    try {
        await setDatabaseQueryEnabled(query.id, !query.enabled);
        notify('更新成功', `${query.toolName} 已${query.enabled ? '停用' : '启用'}。`);
        await loadDatabaseQueries();
    } catch (error) {
        handleError(error);
    }
}

async function removeDatabaseQuery(query) {
    if (!window.confirm(`确定删除 ${query.toolName} 吗？`)) return;
    try {
        await deleteDatabaseQuery(query.id);
        notify('删除成功', `${query.toolName} 已删除。`);
        if (selectedDatabaseQueryId === query.id) {
            resetDatabaseQueryForm();
        }
        await loadDatabaseQueries();
    } catch (error) {
        handleError(error);
    }
}

function toggleDatabaseQuerySelection(query, selected) {
    if (selected) {
        selectedDatabaseQueryIds.add(query.id);
    } else {
        selectedDatabaseQueryIds.delete(query.id);
    }
    renderDatabaseQueryCards();
}

function selectVisibleDatabaseQueries() {
    for (const query of paginate(filterDatabaseQueries(), databaseQueryPage, SERVICE_PAGE_SIZE)) {
        selectedDatabaseQueryIds.add(query.id);
    }
    renderDatabaseQueryCards();
}

function clearDatabaseQuerySelection() {
    selectedDatabaseQueryIds.clear();
    renderDatabaseQueryCards();
}

async function removeSelectedDatabaseQueries() {
    const ids = [...selectedDatabaseQueryIds];
    if (!ids.length) {
        notify('未选择查询', '请先选择需要删除的数据查询。');
        return;
    }
    if (!window.confirm(`确定删除选中的 ${ids.length} 个数据查询吗？`)) return;
    try {
        const result = await deleteDatabaseQueries(ids);
        selectedDatabaseQueryIds.clear();
        if (ids.includes(selectedDatabaseQueryId)) {
            resetDatabaseQueryForm();
            hideDatabaseQueryModal();
        }
        notify('删除成功', `已删除 ${result.deleted ?? ids.length} 个数据查询。`);
        await loadDatabaseQueries();
    } catch (error) {
        handleError(error);
    }
}

async function loadAssets() {
    try {
        [sshAssets, sqlAssets, httpAssets] = await Promise.all([listSshAssets(), listSqlAssets(), listHttpAssets()]);
        if (selectedSshAssetId && !sshAssets.some(asset => asset.id === selectedSshAssetId)) selectedSshAssetId = '';
        if (selectedSqlAssetId && !sqlAssets.some(asset => asset.id === selectedSqlAssetId)) selectedSqlAssetId = '';
        if (selectedHttpAssetId && !httpAssets.some(asset => asset.id === selectedHttpAssetId)) selectedHttpAssetId = '';
        renderAssetCenter();
    } catch (error) {
        handleError(error);
    }
}

function renderAssetCenter() {
    renderAssetTabs();
    renderSshAssets();
    renderSqlAssets();
    renderHttpAssets();
}

function renderAssetTabs() {
    document.getElementById('sshAssetTabBtn').classList.toggle('active', activeAssetTab === 'ssh');
    document.getElementById('sqlAssetTabBtn').classList.toggle('active', activeAssetTab === 'sql');
    document.getElementById('httpAssetTabBtn')?.classList.toggle('active', activeAssetTab === 'http');
    document.getElementById('sshAssetPane').classList.toggle('d-none', activeAssetTab !== 'ssh');
    document.getElementById('sqlAssetPane').classList.toggle('d-none', activeAssetTab !== 'sql');
    document.getElementById('httpAssetPane')?.classList.toggle('d-none', activeAssetTab !== 'http');
}

function switchAssetTab(tab) {
    activeAssetTab = ['ssh', 'sql', 'http'].includes(tab) ? tab : 'ssh';
    renderAssetCenter();
}

function handleSshAssetFilter() {
    sshAssetSearchTerm = value('sshAssetSearchInput');
    sshAssetEnvironmentFilter = value('sshAssetEnvironmentFilter');
    sshAssetStatusFilter = value('sshAssetStatusFilter');
    sshAssetCategoryFilter = value('sshAssetCategoryFilter');
    renderSshAssets();
}

function handleSqlAssetFilter() {
    sqlAssetSearchTerm = value('sqlAssetSearchInput');
    sqlAssetEnvironmentFilter = value('sqlAssetEnvironmentFilter');
    sqlAssetStatusFilter = value('sqlAssetStatusFilter');
    sqlAssetCategoryFilter = value('sqlAssetCategoryFilter');
    renderSqlAssets();
}

function handleHttpAssetFilter() {
    if (!document.getElementById('httpAssetSearchInput')) {
        return;
    }
    httpAssetSearchTerm = value('httpAssetSearchInput');
    httpAssetEnvironmentFilter = value('httpAssetEnvironmentFilter');
    httpAssetStatusFilter = value('httpAssetStatusFilter');
    httpAssetMethodFilter = value('httpAssetMethodFilter');
    httpAssetCategoryFilter = value('httpAssetCategoryFilter');
    renderHttpAssets();
}

function renderSshAssets() {
    document.getElementById('sshAssetCount').textContent = sshAssets.length;
    const filteredAssets = filterSshAssets();
    document.getElementById('sshAssetFilteredCount').textContent = filteredAssets.length;
    const list = document.getElementById('sshAssetList');
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的服务器资产。</div>';
        return;
    }
    for (const asset of filteredAssets) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${asset.id === selectedSshAssetId ? 'active' : ''}`;
        const category = classifySshAsset(asset);
        item.innerHTML = `
            <div class="api-card-main">
                <h3>${escapeHtml(asset.title || asset.name || asset.toolName)}</h3>
                <p>${escapeHtml(asset.description || asset.hostname || '')}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(asset.environment || 'DEV')}</span>
                <span class="badge text-bg-info">${escapeHtml(formatAssetCategory(category))}</span>
                <span class="badge ${asset.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${asset.enabled ? '启用' : '停用'}</span>
                <span class="badge text-bg-light">${escapeHtml(asset.toolName || '-')}</span>
                <span class="badge text-bg-light">${escapeHtml(asset.hostname || '-')}:${escapeHtml(asset.port || 22)}</span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectSshAsset(asset));
        list.appendChild(item);
    }
}

function renderSqlAssets() {
    document.getElementById('sqlAssetCount').textContent = sqlAssets.length;
    const filteredAssets = filterSqlAssets();
    document.getElementById('sqlAssetFilteredCount').textContent = filteredAssets.length;
    const list = document.getElementById('sqlAssetList');
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的数据库资产。</div>';
        return;
    }
    for (const asset of filteredAssets) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${asset.id === selectedSqlAssetId ? 'active' : ''}`;
        const category = classifySqlAsset(asset);
        item.innerHTML = `
            <div class="api-card-main">
                <h3>${escapeHtml(asset.title || asset.name || asset.toolName)}</h3>
                <p>${escapeHtml(asset.description || asset.jdbcUrl || '')}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(asset.environment || 'DEV')}</span>
                <span class="badge text-bg-info">${escapeHtml(formatAssetCategory(category))}</span>
                <span class="badge ${asset.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${asset.enabled ? '启用' : '停用'}</span>
                <span class="badge text-bg-light">${escapeHtml(asset.toolName || '-')}</span>
                <span class="badge text-bg-light">最多 ${escapeHtml(asset.defaultMaxRows || 1000)} 行</span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectSqlAsset(asset));
        list.appendChild(item);
    }
}

function renderHttpAssets() {
    const count = document.getElementById('httpAssetCount');
    const filteredCount = document.getElementById('httpAssetFilteredCount');
    const list = document.getElementById('httpAssetList');
    if (!count || !filteredCount || !list) {
        return;
    }
    count.textContent = httpAssets.length;
    const filteredAssets = filterHttpAssets();
    filteredCount.textContent = filteredAssets.length;
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的 HTTP 请求资产。</div>';
        return;
    }
    for (const asset of filteredAssets) {
        const item = document.createElement('article');
        item.className = `service-card api-card ${asset.id === selectedHttpAssetId ? 'active' : ''}`;
        item.innerHTML = `
            <div class="api-card-main">
                <h3>${escapeHtml(asset.title || asset.name || asset.toolName)}</h3>
                <p>${escapeHtml(asset.description || asset.urlTemplate || '')}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-primary">${escapeHtml(asset.environment || 'DEV')}</span>
                <span class="badge text-bg-info">${escapeHtml(formatHttpCategory(asset.category))}</span>
                <span class="badge ${asset.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${asset.enabled ? '启用' : '停用'}</span>
                <span class="badge text-bg-light">${escapeHtml(asset.method || 'GET')}</span>
                <span class="badge text-bg-light">${escapeHtml(asset.toolName || '-')}</span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectHttpAsset(asset));
        list.appendChild(item);
    }
}

function filterSshAssets() {
    const keyword = sshAssetSearchTerm.trim().toLowerCase();
    return sshAssets.filter(asset => {
        if (sshAssetEnvironmentFilter && String(asset.environment || 'DEV').toUpperCase() !== sshAssetEnvironmentFilter.toUpperCase()) {
            return false;
        }
        if (sshAssetStatusFilter === 'enabled' && !asset.enabled) {
            return false;
        }
        if (sshAssetStatusFilter === 'disabled' && asset.enabled) {
            return false;
        }
        if (sshAssetCategoryFilter && classifySshAsset(asset) !== sshAssetCategoryFilter) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return [
            asset.name,
            asset.toolName,
            asset.title,
            asset.description,
            asset.hostname,
            asset.username,
            asset.environment,
            asset.tags,
            asset.allowedCommandsJson
        ].some(value => String(value || '').toLowerCase().includes(keyword));
    });
}

function filterSqlAssets() {
    const keyword = sqlAssetSearchTerm.trim().toLowerCase();
    return sqlAssets.filter(asset => {
        if (sqlAssetEnvironmentFilter && String(asset.environment || 'DEV').toUpperCase() !== sqlAssetEnvironmentFilter.toUpperCase()) {
            return false;
        }
        if (sqlAssetStatusFilter === 'enabled' && !asset.enabled) {
            return false;
        }
        if (sqlAssetStatusFilter === 'disabled' && asset.enabled) {
            return false;
        }
        if (sqlAssetCategoryFilter && classifySqlAsset(asset) !== sqlAssetCategoryFilter) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return [
            asset.name,
            asset.toolName,
            asset.title,
            asset.description,
            asset.jdbcUrl,
            asset.driverClass,
            asset.username,
            asset.environment,
            asset.allowedTablesJson
        ].some(value => String(value || '').toLowerCase().includes(keyword));
    });
}

function filterHttpAssets() {
    const keyword = httpAssetSearchTerm.trim().toLowerCase();
    return httpAssets.filter(asset => {
        if (httpAssetEnvironmentFilter && String(asset.environment || 'DEV').toUpperCase() !== httpAssetEnvironmentFilter.toUpperCase()) {
            return false;
        }
        if (httpAssetStatusFilter === 'enabled' && !asset.enabled) {
            return false;
        }
        if (httpAssetStatusFilter === 'disabled' && asset.enabled) {
            return false;
        }
        if (httpAssetMethodFilter && String(asset.method || 'GET').toUpperCase() !== httpAssetMethodFilter.toUpperCase()) {
            return false;
        }
        if (httpAssetCategoryFilter && String(asset.category || 'business_api') !== httpAssetCategoryFilter) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return [
            asset.name,
            asset.toolName,
            asset.title,
            asset.description,
            asset.method,
            asset.urlTemplate,
            asset.environment,
            asset.category,
            asset.tags,
            asset.headersJson
        ].some(value => String(value || '').toLowerCase().includes(keyword));
    });
}

function classifySshAsset(asset) {
    const text = [
        asset.name,
        asset.toolName,
        asset.title,
        asset.description,
        asset.hostname,
        asset.tags,
        asset.allowedCommandsJson
    ].map(value => String(value || '').toLowerCase()).join(' ');
    if (text.includes('goldendb')) return 'goldendb';
    if (text.includes('inceptor')) return 'inceptor';
    if (text.includes('hive')) return 'hive';
    if (text.includes('nginx')) return 'nginx';
    if (text.includes('k8s') || text.includes('kubernetes') || text.includes('kubectl')) return 'k8s';
    if (text.includes('java') || text.includes('jps') || text.includes('jvm')) return 'java';
    return 'other';
}

function classifySqlAsset(asset) {
    const text = [
        asset.name,
        asset.toolName,
        asset.title,
        asset.description,
        asset.jdbcUrl,
        asset.driverClass
    ].map(value => String(value || '').toLowerCase()).join(' ');
    if (text.includes('goldendb')) return 'goldendb';
    if (text.includes('inceptor')) return 'inceptor';
    if (text.includes('jdbc:hive') || text.includes('hive')) return 'hive';
    if (text.includes('jdbc:dm') || text.includes('dm.jdbc') || text.includes('dameng')) return 'dm';
    if (text.includes('jdbc:mysql') || text.includes('mysql')) return 'mysql';
    if (text.includes('jdbc:oracle') || text.includes('oracle')) return 'oracle';
    if (text.includes('jdbc:postgresql') || text.includes('postgresql') || text.includes('postgres')) return 'postgresql';
    return 'other';
}

function formatAssetCategory(category) {
    const names = {
        mysql: 'MySQL',
        dm: '达梦 DM',
        hive: 'Hive',
        inceptor: 'Inceptor',
        goldendb: 'GoldenDB',
        nginx: 'Nginx',
        java: 'Java',
        k8s: 'K8S',
        oracle: 'Oracle',
        postgresql: 'PostgreSQL',
        other: '其他'
    };
    return names[category] || '其他';
}

function formatHttpCategory(category) {
    const names = {
        business_api: '业务接口',
        monitoring: '监控接口',
        third_party: '第三方接口',
        webhook: 'Webhook',
        ops: '运维接口',
        other: '其他'
    };
    return names[category] || '其他';
}

function openNewSshAsset() {
    fillSshAssetForm(null);
    showSshAssetModal();
}

function openNewSqlAsset() {
    fillSqlAssetForm(null);
    showSqlAssetModal();
}

function openNewHttpAsset() {
    fillHttpAssetForm(null);
    showHttpAssetModal();
}

function selectSshAsset(asset) {
    selectedSshAssetId = asset.id;
    fillSshAssetForm(asset);
    renderSshAssets();
    showSshAssetModal();
}

function selectSqlAsset(asset) {
    selectedSqlAssetId = asset.id;
    fillSqlAssetForm(asset);
    renderSqlAssets();
    showSqlAssetModal();
}

function selectHttpAsset(asset) {
    selectedHttpAssetId = asset.id;
    fillHttpAssetForm(asset);
    renderHttpAssets();
    showHttpAssetModal();
}

async function handleSshAssetSave(event) {
    event.preventDefault();
    try {
        const saved = await saveSshAsset(readSshAssetForm());
        selectedSshAssetId = saved.id;
        notify('保存成功', `${saved.toolName} 已保存为服务器资产。`);
        hideSshAssetModal();
        await loadAssets();
    } catch (error) {
        handleError(error);
    }
}

async function handleSqlAssetSave(event) {
    event.preventDefault();
    try {
        const saved = await saveSqlAsset(readSqlAssetForm());
        selectedSqlAssetId = saved.id;
        notify('保存成功', `${saved.toolName} 已保存为数据库资产。`);
        hideSqlAssetModal();
        await loadAssets();
    } catch (error) {
        handleError(error);
    }
}

async function handleHttpAssetSave(event) {
    event.preventDefault();
    try {
        const saved = await saveHttpAsset(readHttpAssetForm());
        selectedHttpAssetId = saved.id;
        notify('保存成功', `${saved.toolName} 已保存为 HTTP 请求资产。`);
        hideHttpAssetModal();
        await loadAssets();
    } catch (error) {
        handleError(error);
    }
}

async function handleOpsAssetRefresh() {
    try {
        await refreshOpsTools();
        notify('刷新完成', 'SSH 与 HTTP 请求资产工具已重新发布。');
    } catch (error) {
        handleError(error);
    }
}

async function handleSqlAssetRefresh() {
    try {
        await refreshSqlTools();
        notify('刷新完成', '数据库资产工具已重新发布。');
    } catch (error) {
        handleError(error);
    }
}

async function loadNotificationChannels() {
    try {
        notificationChannels = await listNotificationChannels();
        if (selectedNotificationChannelId && !notificationChannels.some(channel => channel.id === selectedNotificationChannelId)) {
            selectedNotificationChannelId = '';
        }
        renderNotificationChannelCards();
    } catch (error) {
        handleError(error);
    }
}

function renderNotificationChannelCards() {
    const filtered = filterNotificationChannels();
    renderNotificationChannels(filtered, selectedNotificationChannelId, {
        edit: selectNotificationChannel,
        test: testNotificationFromCard,
        toggle: toggleNotificationChannel,
        policy: toggleNotificationRuntimeAction
    }, {
        totalCount: notificationChannels.length,
        filteredCount: filtered.length
    });
}

function filterNotificationChannels() {
    const keyword = notificationSearchTerm.trim().toLowerCase();
    if (!keyword) {
        return notificationChannels;
    }
    return notificationChannels.filter(channel => [
        channel.channel,
        channel.toolName,
        channel.title,
        channel.description,
        channel.endpointUrl,
        channel.smtpHost,
        channel.smtpFrom
    ].some(value => String(value || '').toLowerCase().includes(keyword)));
}

function handleNotificationSearch(event) {
    notificationSearchTerm = event.target.value;
    renderNotificationChannelCards();
}

function selectNotificationChannel(channel) {
    selectedNotificationChannelId = channel.id;
    fillNotificationChannelForm(channel);
    renderNotificationChannelCards();
    showNotificationChannelModal();
}

async function toggleNotificationChannel(channel) {
    try {
        await setNotificationEnabled(channel.id, !channel.enabled);
        notify('更新成功', `${channel.toolName} 已${channel.enabled ? '下线' : '启用'}。`);
        await loadNotificationChannels();
    } catch (error) {
        handleError(error);
    }
}

async function toggleNotificationRuntimeAction(channel) {
    const nextAction = channel.runtimeAction === 'forbidden' ? 'confirm_required' : 'forbidden';
    try {
        await setNotificationRuntimeAction(channel.id, nextAction);
        notify('策略已更新', `${channel.toolName} 已切换为 ${nextAction}。`);
        await loadNotificationChannels();
    } catch (error) {
        handleError(error);
    }
}

async function handleNotificationRefresh() {
    try {
        const result = await refreshNotificationTools();
        notify('刷新完成', result.refreshed ? '通知 MCP 工具已重新发布。' : '通知 MCP 工具刷新完成。');
        await loadNotificationChannels();
    } catch (error) {
        handleError(error);
    }
}

async function handleNotificationSave(event) {
    event.preventDefault();
    const form = document.getElementById('notificationChannelForm');
    if (!form.reportValidity()) {
        return;
    }
    try {
        const saved = await saveNotificationChannel(readNotificationChannelForm());
        selectedNotificationChannelId = saved.id;
        notify('保存成功', `${saved.toolName} 配置已生效。`);
        await loadNotificationChannels();
        fillNotificationChannelForm(saved);
        hideNotificationChannelModal();
    } catch (error) {
        handleError(error);
    }
}

async function handleNotificationTest() {
    const id = value('notificationId');
    if (!id) {
        notify('无法测试', '请先选择通知渠道。');
        return;
    }
    try {
        const result = await testNotificationChannel(id, readJsonObject('notificationTestPayloadJson'));
        showResult(result, {
            title: `${value('notificationToolName')} 测试结果`,
            subtitle: '通知发送结果会写入调用审计'
        });
    } catch (error) {
        handleError(error);
    }
}

async function testNotificationFromCard(channel) {
    selectedNotificationChannelId = channel.id;
    fillNotificationChannelForm(channel);
    renderNotificationChannelCards();
    showNotificationChannelModal();
}

function readNotificationChannelForm() {
    return {
        id: value('notificationId'),
        channel: value('notificationChannel'),
        toolName: value('notificationToolName'),
        title: value('notificationTitle'),
        description: value('notificationDescription'),
        enabled: document.getElementById('notificationEnabled').value === 'true',
        runtimeAction: value('notificationRuntimeAction'),
        deliveryMode: value('notificationDeliveryMode'),
        method: value('notificationMethod'),
        endpointUrl: value('notificationEndpointUrl'),
        headers: readJsonObject('notificationHeadersJson'),
        bodyTemplate: document.getElementById('notificationBodyTemplate').value,
        secret: document.getElementById('notificationSecret').value,
        defaultReceiver: notificationDefaultReceiverValue(),
        ccReceiver: value('notificationCcReceiver'),
        smtpHost: value('notificationSmtpHost'),
        smtpPort: Number(value('notificationSmtpPort') || 0),
        smtpUsername: value('notificationSmtpUsername'),
        smtpPassword: document.getElementById('notificationSmtpPassword').value,
        smtpFrom: value('notificationSmtpFrom'),
        smtpAuthEnabled: document.getElementById('notificationSmtpAuthEnabled').checked,
        smtpStarttlsEnabled: document.getElementById('notificationSmtpStarttlsEnabled').checked,
        smtpSslEnabled: document.getElementById('notificationSmtpSslEnabled').checked,
        smtpSslTrust: value('notificationSmtpSslTrust'),
        smsAccount: value('notificationSmsAccount'),
        smsToken: document.getElementById('notificationSmsToken').value,
        smsPlainPassword: document.getElementById('notificationSmsPlainPassword').value,
        smsMd5Password: value('notificationSmsMd5Password'),
        smsPasswordMd5: document.getElementById('notificationSmsPasswordMd5').checked,
        smsReturnType: value('notificationSmsReturnType'),
        smsExtendCode: value('notificationSmsExtendCode'),
        timeoutMs: Number(value('notificationTimeoutMs') || 5000),
        maxRetries: Number(value('notificationMaxRetries') || 0)
    };
}

function fillNotificationChannelForm(channel) {
    document.getElementById('notificationFormTitle').textContent = channel ? `配置 ${channel.toolName}` : '通知渠道配置';
    setValue('notificationId', channel?.id || '');
    setValue('notificationChannel', channel?.channel || '');
    setValue('notificationToolName', channel?.toolName || '');
    setValue('notificationTitle', channel?.title || '');
    setValue('notificationDescription', channel?.description || '');
    setValue('notificationEnabled', String(channel?.enabled ?? true));
    setValue('notificationRuntimeAction', channel?.runtimeAction || 'confirm_required');
    setValue('notificationDeliveryMode', channel?.deliveryMode || 'HTTP');
    setValue('notificationMethod', channel?.method || 'POST');
    setValue('notificationEndpointUrl', channel?.endpointUrl || '');
    setValue('notificationHeadersJson', JSON.stringify(channel?.headers || { 'Content-Type': 'application/json' }, null, 2));
    setValue('notificationBodyTemplate', channel?.bodyTemplate || '');
    document.getElementById('notificationSecret').value = channel?.secret || '';
    setValue('notificationDefaultReceiver', channel?.defaultReceiver || '');
    setValue('notificationEmailDefaultReceiver', channel?.defaultReceiver || '');
    setValue('notificationCcReceiver', channel?.ccReceiver || '');
    setValue('notificationSmtpHost', channel?.smtpHost || '');
    setValue('notificationSmtpPort', channel?.smtpPort ? String(channel.smtpPort) : '');
    setValue('notificationSmtpUsername', channel?.smtpUsername || '');
    document.getElementById('notificationSmtpPassword').value = channel?.smtpPassword || '';
    setValue('notificationSmtpFrom', channel?.smtpFrom || '');
    document.getElementById('notificationSmtpAuthEnabled').checked = channel?.smtpAuthEnabled ?? true;
    document.getElementById('notificationSmtpStarttlsEnabled').checked = channel?.smtpStarttlsEnabled ?? true;
    document.getElementById('notificationSmtpSslEnabled').checked = channel?.smtpSslEnabled ?? false;
    setValue('notificationSmtpSslTrust', channel?.smtpSslTrust || '');
    setValue('notificationSmsAccount', channel?.smsAccount || '');
    document.getElementById('notificationSmsToken').value = channel?.smsToken || '';
    document.getElementById('notificationSmsPlainPassword').value = channel?.smsPlainPassword || '';
    setValue('notificationSmsMd5Password', channel?.smsMd5Password || '');
    document.getElementById('notificationSmsPasswordMd5').checked = channel?.smsPasswordMd5 ?? true;
    setValue('notificationSmsReturnType', channel?.smsReturnType || 'text');
    setValue('notificationSmsExtendCode', channel?.smsExtendCode || '');
    setValue('notificationTimeoutMs', String(channel?.timeoutMs || 5000));
    setValue('notificationMaxRetries', String(channel?.maxRetries ?? 1));
    setValue('notificationTestPayloadJson', JSON.stringify(channel?.defaultTestPayload || {
        receiver: 'ops@example.com',
        title: 'Agent 任务通知',
        content: '这是一条测试通知。',
        level: 'INFO',
        sourceTaskId: 'manual-test'
    }, null, 2));
    toggleNotificationDeliveryFields();
}

function toggleNotificationDeliveryFields() {
    const mode = value('notificationDeliveryMode') || 'HTTP';
    const channel = value('notificationChannel');
    document.querySelectorAll('.notification-http-field').forEach(node => {
        node.classList.toggle('d-none', mode === 'SMTP');
    });
    document.querySelectorAll('.notification-smtp-field').forEach(node => {
        node.classList.toggle('d-none', mode !== 'SMTP');
    });
    document.querySelectorAll('.notification-sms-field').forEach(node => {
        node.classList.toggle('d-none', channel !== 'SMS');
    });
}

function notificationDefaultReceiverValue() {
    return value('notificationChannel') === 'SMS'
        ? value('notificationDefaultReceiver')
        : value('notificationEmailDefaultReceiver');
}

function readSshAssetForm() {
    return {
        id: value('sshAssetId'),
        name: value('sshAssetName'),
        toolName: value('sshAssetToolName'),
        title: value('sshAssetTitle'),
        description: value('sshAssetDescription'),
        hostname: value('sshAssetHostname'),
        port: Number(value('sshAssetPort') || 22),
        username: value('sshAssetUsername'),
        authType: value('sshAssetAuthType'),
        password: document.getElementById('sshAssetPassword').value,
        privateKey: document.getElementById('sshAssetPrivateKey').value,
        passphrase: document.getElementById('sshAssetPassphrase').value,
        hostKeyFingerprint: value('sshAssetHostKeyFingerprint'),
        enabled: document.getElementById('sshAssetEnabled').value === 'true',
        environment: value('sshAssetEnvironment'),
        tags: value('sshAssetTags'),
        allowedCommandsJson: stringifyJsonArray('sshAssetAllowedCommandsJson'),
        governanceJson: stringifyJsonObject('sshAssetGovernanceJson'),
        connectTimeoutMs: Number(value('sshAssetConnectTimeoutMs') || 10000),
        commandTimeoutMs: Number(value('sshAssetCommandTimeoutMs') || 30000)
    };
}

function fillSshAssetForm(asset) {
    document.getElementById('sshAssetFormTitle').textContent = asset ? `编辑 ${asset.toolName || asset.name}` : '新增服务器资产';
    setValue('sshAssetId', asset?.id || '');
    setValue('sshAssetName', asset?.name || '');
    setValue('sshAssetToolName', asset?.toolName || '');
    setValue('sshAssetTitle', asset?.title || '');
    setValue('sshAssetDescription', asset?.description || '');
    setValue('sshAssetHostname', asset?.hostname || '');
    setValue('sshAssetPort', String(asset?.port || 22));
    setValue('sshAssetUsername', asset?.username || '');
    setValue('sshAssetAuthType', asset?.authType || 'PASSWORD');
    document.getElementById('sshAssetPassword').value = asset?.password || '';
    document.getElementById('sshAssetPrivateKey').value = asset?.privateKey || '';
    document.getElementById('sshAssetPassphrase').value = asset?.passphrase || '';
    setValue('sshAssetHostKeyFingerprint', asset?.hostKeyFingerprint || '');
    setValue('sshAssetEnabled', String(asset?.enabled ?? false));
    setValue('sshAssetEnvironment', asset?.environment || 'DEV');
    setValue('sshAssetTags', asset?.tags || '');
    setValue('sshAssetAllowedCommandsJson', prettyJsonArray(asset?.allowedCommandsJson));
    setValue('sshAssetGovernanceJson', prettyJsonObject(asset?.governanceJson, defaultSshGovernance(asset)));
    setValue('sshAssetConnectTimeoutMs', String(asset?.connectTimeoutMs || 10000));
    setValue('sshAssetCommandTimeoutMs', String(asset?.commandTimeoutMs || 30000));
}

function readSqlAssetForm() {
    return {
        id: value('sqlAssetId'),
        name: value('sqlAssetName'),
        toolName: value('sqlAssetToolName'),
        title: value('sqlAssetTitle'),
        description: value('sqlAssetDescription'),
        jdbcUrl: value('sqlAssetJdbcUrl'),
        driverClass: value('sqlAssetDriverClass'),
        username: value('sqlAssetUsername'),
        password: document.getElementById('sqlAssetPassword').value,
        enabled: document.getElementById('sqlAssetEnabled').value === 'true',
        environment: value('sqlAssetEnvironment'),
        defaultTimeoutSeconds: Number(value('sqlAssetDefaultTimeoutSeconds') || 30),
        defaultMaxRows: Number(value('sqlAssetDefaultMaxRows') || 1000),
        allowedTablesJson: stringifyJsonArray('sqlAssetAllowedTablesJson'),
        sensitiveTablesJson: stringifyJsonArray('sqlAssetSensitiveTablesJson'),
        sensitiveFieldsJson: stringifyJsonArray('sqlAssetSensitiveFieldsJson'),
        governanceJson: stringifyJsonObject('sqlAssetGovernanceJson')
    };
}

function fillSqlAssetForm(asset) {
    document.getElementById('sqlAssetFormTitle').textContent = asset ? `编辑 ${asset.toolName || asset.name}` : '新增数据库资产';
    setValue('sqlAssetId', asset?.id || '');
    setValue('sqlAssetName', asset?.name || '');
    setValue('sqlAssetToolName', asset?.toolName || '');
    setValue('sqlAssetTitle', asset?.title || '');
    setValue('sqlAssetDescription', asset?.description || '');
    setValue('sqlAssetJdbcUrl', asset?.jdbcUrl || '');
    setValue('sqlAssetDriverClass', asset?.driverClass || '');
    setValue('sqlAssetUsername', asset?.username || '');
    document.getElementById('sqlAssetPassword').value = asset?.password || '';
    setValue('sqlAssetEnabled', String(asset?.enabled ?? false));
    setValue('sqlAssetEnvironment', asset?.environment || 'DEV');
    setValue('sqlAssetDefaultTimeoutSeconds', String(asset?.defaultTimeoutSeconds || 30));
    setValue('sqlAssetDefaultMaxRows', String(asset?.defaultMaxRows || 1000));
    setValue('sqlAssetAllowedTablesJson', prettyJsonArray(asset?.allowedTablesJson));
    setValue('sqlAssetSensitiveTablesJson', prettyJsonArray(asset?.sensitiveTablesJson));
    setValue('sqlAssetSensitiveFieldsJson', prettyJsonArray(asset?.sensitiveFieldsJson));
    setValue('sqlAssetGovernanceJson', prettyJsonObject(asset?.governanceJson, defaultSqlDatasourceGovernance(asset)));
}

function readHttpAssetForm() {
    return {
        id: value('httpAssetId'),
        name: value('httpAssetName'),
        toolName: value('httpAssetToolName'),
        title: value('httpAssetTitle'),
        description: value('httpAssetDescription'),
        method: value('httpAssetMethod'),
        urlTemplate: value('httpAssetUrlTemplate'),
        headersJson: stringifyJsonObject('httpAssetHeadersJson'),
        bodyTemplate: document.getElementById('httpAssetBodyTemplate').value.trim(),
        inputSchemaJson: stringifyJsonObject('httpAssetInputSchemaJson'),
        governanceJson: stringifyJsonObject('httpAssetGovernanceJson'),
        enabled: document.getElementById('httpAssetEnabled').value === 'true',
        environment: value('httpAssetEnvironment'),
        category: value('httpAssetCategory'),
        tags: value('httpAssetTags'),
        timeoutMs: Number(value('httpAssetTimeoutMs') || 10000)
    };
}

function fillHttpAssetForm(asset) {
    document.getElementById('httpAssetFormTitle').textContent = asset ? `编辑 ${asset.toolName || asset.name}` : '新增 HTTP 请求资产';
    setValue('httpAssetId', asset?.id || '');
    setValue('httpAssetName', asset?.name || '');
    setValue('httpAssetToolName', asset?.toolName || '');
    setValue('httpAssetTitle', asset?.title || '');
    setValue('httpAssetDescription', asset?.description || '');
    setValue('httpAssetMethod', asset?.method || 'GET');
    setValue('httpAssetUrlTemplate', asset?.urlTemplate || '');
    setValue('httpAssetHeadersJson', prettyJsonObject(asset?.headersJson, {}));
    setValue('httpAssetBodyTemplate', asset?.bodyTemplate || '');
    setValue('httpAssetInputSchemaJson', prettyJsonObject(asset?.inputSchemaJson, {
        type: 'object',
        properties: {},
        required: [],
        additionalProperties: true
    }));
    setValue('httpAssetGovernanceJson', prettyJsonObject(asset?.governanceJson, defaultHttpGovernance(asset)));
    setValue('httpAssetEnabled', String(asset?.enabled ?? false));
    setValue('httpAssetEnvironment', asset?.environment || 'DEV');
    setValue('httpAssetCategory', asset?.category || 'business_api');
    setValue('httpAssetTags', asset?.tags || '');
    setValue('httpAssetTimeoutMs', String(asset?.timeoutMs || 10000));
}

function defaultDatabaseQueryGovernance(query = {}) {
    const governance = baseGovernance('database_query', 'read', 'medium', dataScope('database_query', query?.title || query?.toolName));
    governance.output_policy.max_rows_without_confirm = 100;
    if ((query?.maxRows || 50) > 100) {
        governance.confirmation.default = 'ask_before_execute';
    }
    return governance;
}

function defaultSshGovernance(asset = {}) {
    const governance = baseGovernance('host_asset', 'execute_template', 'high', dataScope('host', asset?.name), 'ask_before_execute', false);
    governance.input_policy.required_preview_params = ['template', 'parameters', 'reason', 'sourceTaskId'];
    return governance;
}

function defaultSqlDatasourceGovernance(asset = {}) {
    const governance = baseGovernance('sql_query', 'read_sql', 'high', dataScope('database', asset?.name), 'ask_before_execute', false);
    governance.input_policy.required_preview_params = ['sql', 'template', 'parameters', 'timeoutSeconds', 'maxRows', 'purpose', 'sourceTaskId'];
    return governance;
}

function defaultHttpGovernance(asset = {}) {
    const operationType = methodOperationType(asset?.method || valueOr('httpAssetMethod', 'GET'));
    const riskLevel = operationType === 'read' ? 'low' : 'medium';
    const confirmation = riskLevel === 'low' ? 'auto_execute' : 'ask_before_execute';
    return baseGovernance('http_asset', operationType === 'read' ? 'request' : operationType, riskLevel,
        dataScope('http', asset?.name), confirmation, true);
}

function baseGovernance(category, operationType, riskLevel, dataScopeValue, confirmationDefault, allowUserOverride = true) {
    const confirmation = confirmationDefault || (riskLevel === 'low' ? 'auto_execute' : 'ask_before_execute');
    return {
        category,
        operation_type: operationType,
        risk_level: riskLevel,
        data_scope: dataScopeValue,
        user_visible: true,
        confirmation: {
            default: confirmation,
            allow_user_override: allowUserOverride
        },
        permission: {
            roles: []
        },
        input_policy: {
            must_show_parameters: true,
            sensitive_params: [],
            parameter_rules: {}
        },
        output_policy: {
            mask_fields: GOVERNANCE_MASK_FIELDS
        },
        audit: {
            enabled: true,
            log_params: true,
            log_result_summary: true
        }
    };
}

function methodOperationType(method) {
    const value = String(method || 'GET').toUpperCase();
    if (value === 'GET') {
        return 'read';
    }
    if (value === 'DELETE') {
        return 'delete';
    }
    return 'write';
}

function dataScope(prefix, name) {
    const normalized = String(name || '').trim();
    return `${prefix}:${normalized || 'asset'}`;
}

function valueOr(id, fallback) {
    const element = document.getElementById(id);
    return element ? element.value.trim() || fallback : fallback;
}

function prettyJsonArray(value) {
    if (!value) {
        return '[]';
    }
    try {
        return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2);
    } catch (error) {
        return String(value);
    }
}

function prettyJsonObject(value, fallback = {}) {
    if (!value) {
        return JSON.stringify(fallback, null, 2);
    }
    try {
        return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2);
    } catch (error) {
        return String(value);
    }
}

function stringifyJsonObject(id) {
    const text = document.getElementById(id).value.trim();
    if (!text) {
        return null;
    }
    const value = JSON.parse(text);
    if (Array.isArray(value) || value === null || typeof value !== 'object') {
        throw new Error('该字段必须是 JSON 对象。');
    }
    return JSON.stringify(value);
}

function stringifyJsonArray(id) {
    const text = document.getElementById(id).value.trim();
    if (!text) {
        return null;
    }
    const value = JSON.parse(text);
    if (!Array.isArray(value)) {
        throw new Error('该字段必须是 JSON 数组。');
    }
    return JSON.stringify(value);
}

function handleError(error) {
    if (error instanceof UnauthorizedError) {
        showLogin();
        notify('需要登录', error.message);
        return;
    }
    notify('操作失败', error.message || '未知错误');
}

function value(id) {
    return document.getElementById(id).value.trim();
}

function setValue(id, nextValue) {
    document.getElementById(id).value = nextValue ?? '';
}

function readDatabaseQueryForm() {
    return {
        sql: value('databaseSqlInput'),
        params: readJsonObject('databaseParamsJson'),
        maxRows: Number(value('databaseMaxRowsInput') || 50),
        datasourceId: value('databaseDatasourceSelect'),
        jdbcUrl: value('databaseJdbcUrlInput'),
        driverClass: value('databaseDriverClassInput'),
        username: value('databaseUsernameInput'),
        password: document.getElementById('databasePasswordInput').value,
        reloadDrivers: document.getElementById('databaseReloadDrivers').checked
    };
}

function readDatabaseQueryRegistrationForm() {
    const id = value('databaseQueryId');
    const current = databaseQueries.find(query => query.id === id);
    return {
        id,
        toolName: value('databaseToolNameInput'),
        title: value('databaseTitleInput'),
        datasourceId: value('databaseDatasourceSelect'),
        description: value('databaseDescriptionInput'),
        sqlTemplate: value('databaseSqlInput'),
        inputSchema: readJsonObject('databaseInputSchemaJson'),
        governance: readJsonObject('databaseGovernanceJson'),
        maxRows: Number(value('databaseMaxRowsInput') || 50),
        jdbcUrl: value('databaseJdbcUrlInput'),
        driverClass: value('databaseDriverClassInput'),
        username: value('databaseUsernameInput'),
        password: document.getElementById('databasePasswordInput').value,
        reloadDrivers: document.getElementById('databaseReloadDrivers').checked,
        enabled: current?.enabled ?? true
    };
}

function fillDatabaseQueryForm(query) {
    selectedDatabaseQueryId = query?.id || '';
    document.getElementById('databaseQueryFormTitle').textContent = query ? '编辑查询注册' : '新增查询注册';
    setValue('databaseQueryId', query?.id || '');
    setValue('databaseToolNameInput', query?.toolName || '');
    setValue('databaseTitleInput', query?.title || '');
    renderDatabaseDatasourceOptions(query?.datasourceId || '');
    setValue('databaseDescriptionInput', query?.description || '');
    setValue('databaseSqlInput', query?.sqlTemplate || '');
    setValue('databaseParamsJson', '{}');
    setValue('databaseMaxRowsInput', String(query?.maxRows || 50));
    setValue('databaseInputSchemaJson', JSON.stringify(query?.inputSchema || {
        type: 'object',
        properties: {},
        required: [],
        additionalProperties: false
    }, null, 2));
    setValue('databaseGovernanceJson', JSON.stringify(query?.governance || defaultDatabaseQueryGovernance(query), null, 2));
    setValue('databaseJdbcUrlInput', query?.jdbcUrl || '');
    setValue('databaseDriverClassInput', query?.driverClass || '');
    setValue('databaseUsernameInput', query?.username || '');
    document.getElementById('databasePasswordInput').value = query?.password || '';
    document.getElementById('databaseReloadDrivers').checked = Boolean(query?.reloadDrivers);
    toggleDatabaseExternalFields();
    renderDatabaseQueryPreview(null);
}

function renderDatabaseDatasourceOptions(selected = value('databaseDatasourceSelect')) {
    const select = document.getElementById('databaseDatasourceSelect');
    if (!select) {
        return;
    }
    const enabledAssets = sqlAssets.filter(asset => asset.enabled);
    select.innerHTML = [
        '<option value="">不使用资产，手动填写 JDBC</option>',
        ...enabledAssets.map(asset => `
            <option value="${escapeHtml(asset.id)}" ${asset.id === selected ? 'selected' : ''}>
                ${escapeHtml(asset.toolName || asset.name)} / ${escapeHtml(asset.environment || 'DEV')}
            </option>
        `)
    ].join('');
}

function toggleDatabaseExternalFields() {
    const useAsset = Boolean(value('databaseDatasourceSelect'));
    document.getElementById('databaseExternalFields').classList.toggle('opacity-50', useAsset);
    document.getElementById('databaseJdbcUrlInput').required = !useAsset;
}

function resetDatabaseQueryForm() {
    fillDatabaseQueryForm(null);
}

function renderDatabaseQueryOutput(output) {
    renderDatabaseQueryPreview(output, {
        pickField: addDatabasePreviewFieldParam,
        pickFields: addDatabasePreviewFieldParams
    });
}

function addDatabasePreviewFieldParam(field, sampleValue) {
    try {
        const result = appendDatabasePreviewParams([{ field, sampleValue }]);
        notify('参数已添加', `${field} 已拾取为参数 ${result.names[0]}。`);
    } catch (error) {
        notify('参数添加失败', error.message || '请检查参数 JSON 与 Schema JSON。');
    }
}

function addDatabasePreviewFieldParams(fields, sampleRow) {
    try {
        const result = appendDatabasePreviewParams(fields.map(field => ({
            field,
            sampleValue: sampleRow?.[field]
        })));
        notify('参数已添加', `已从查询结果提取 ${result.addedCount} 个列名参数。`);
    } catch (error) {
        notify('参数添加失败', error.message || '请检查参数 JSON 与 Schema JSON。');
    }
}

function appendDatabasePreviewParams(items) {
    const params = readJsonObject('databaseParamsJson');
    const schema = normalizeInputSchema(readJsonObject('databaseInputSchemaJson'));
    const names = [];
    let addedCount = 0;

    for (const item of items) {
        const paramName = uniqueDatabaseParamName(normalizeDatabaseParamName(item.field), names);
        const existed = Object.prototype.hasOwnProperty.call(params, paramName)
            || Object.prototype.hasOwnProperty.call(schema.properties, paramName);
        if (!Object.prototype.hasOwnProperty.call(params, paramName)) {
            params[paramName] = item.sampleValue ?? '';
        }
        if (!schema.properties[paramName]) {
            schema.properties[paramName] = {
                type: inferJsonSchemaType(item.sampleValue),
                description: item.field === paramName ? `预览字段 ${item.field}` : `预览字段 ${item.field}，参数名已转换`
            };
        }
        if (!schema.required.includes(paramName)) {
            schema.required.push(paramName);
        }
        names.push(paramName);
        if (!existed) {
            addedCount += 1;
        }
    }

    setValue('databaseParamsJson', JSON.stringify(params, null, 2));
    setValue('databaseInputSchemaJson', JSON.stringify(schema, null, 2));
    return { names, addedCount };
}

function normalizeInputSchema(schema) {
    schema.type = schema.type || 'object';
    schema.properties = isPlainObject(schema.properties) ? schema.properties : {};
    schema.required = Array.isArray(schema.required) ? schema.required : [];
    schema.additionalProperties = false;
    return schema;
}

function normalizeDatabaseParamName(field) {
    const value = String(field || '').trim();
    if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(value)) {
        return value;
    }
    const normalized = value.replace(/[^A-Za-z0-9_]/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '');
    if (!normalized) {
        return 'param';
    }
    return /^[A-Za-z_]/.test(normalized) ? normalized : `p_${normalized}`;
}

function uniqueDatabaseParamName(baseName, reservedNames) {
    let name = baseName;
    let index = 2;
    while (reservedNames.includes(name)) {
        name = `${baseName}_${index}`;
        index += 1;
    }
    return name;
}

function inferJsonSchemaType(value) {
    if (typeof value === 'boolean') {
        return 'boolean';
    }
    if (typeof value === 'number') {
        return Number.isInteger(value) ? 'integer' : 'number';
    }
    if (Array.isArray(value)) {
        return 'array';
    }
    if (value && typeof value === 'object') {
        return 'object';
    }
    return 'string';
}

function isPlainObject(value) {
    return value && !Array.isArray(value) && typeof value === 'object';
}

function readJsonObject(id) {
    const text = document.getElementById(id).value.trim();
    if (!text) {
        return {};
    }
    const value = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== 'object') {
        throw new Error('命名参数必须是 JSON 对象。');
    }
    return value;
}

function promptJsonObject(message, fallback) {
    const text = window.prompt(message, JSON.stringify(fallback, null, 2));
    if (text == null) {
        return null;
    }
    const value = JSON.parse(text || '{}');
    if (!value || Array.isArray(value) || typeof value !== 'object') {
        throw new Error('查询参数必须是 JSON 对象。');
    }
    return value;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function changeServicePage(delta) {
    servicePage = clampPage(servicePage + delta, filterServices().length, SERVICE_PAGE_SIZE);
    renderApiServices();
}

function changeMcpServicePage(delta) {
    mcpServicePage = clampPage(mcpServicePage + delta, filterMcpServices().length, SERVICE_PAGE_SIZE);
    renderMcpServiceCards();
}

function paginate(items, page, pageSize) {
    const start = (page - 1) * pageSize;
    return items.slice(start, start + pageSize);
}

function clampPage(page, itemCount, pageSize) {
    const pageCount = Math.max(1, Math.ceil(itemCount / pageSize));
    return Math.min(Math.max(1, page), pageCount);
}
