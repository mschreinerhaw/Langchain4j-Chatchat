import { MCP_ENDPOINT } from './config.js';
import { loadLayout } from './layout.js';
import { getToken, login, logout } from './auth.js';
import { UnauthorizedError } from './http.js';
import {
    deleteService,
    deleteServices,
    listLivedataApis,
    listServices,
    rebuildApiServiceIndex,
    registerLivedataApis,
    refreshTools,
    saveService,
    setEnabled,
    testService
} from './apiServices.js';
import { bindMcpServicePanel, loadMcpServicePanel, openNewMcpService, resetMcpServicePanel } from './mcpServicePanel.js';
import { bindAuthorizationPanel, loadAuthorizationPanel } from './authorizationPanel.js';
import { bindAssetCenterPanel, loadAssetCenterPanel } from './assetCenterPanel.js';
import { bindAuditLogPanel, loadAuditLogPanel } from './auditLogPanel.js';
import { bindNotificationPanel, loadNotificationPanel } from './notificationPanel.js';
import { listSqlAssets } from './assetCenter.js';
import {
    deleteDatabaseQuery,
    deleteDatabaseQueries,
    listDatabaseQueries,
    rebuildDatabaseQueryIndex,
    saveDatabaseQuery,
    setDatabaseQueryEnabled,
    testDatabaseQuery,
    testSavedDatabaseQuery
} from './databaseMcp.js';
import { fillServiceForm, readServiceForm, readTestArgs, readTestArgsFromSchema, toggleMicroserviceFields } from './form.js';
import {
    hideLoginError,
    hideApiServiceModal,
    hideDatabaseQueryModal,
    hideLivedataImportModal,
    initUi,
    notify,
    renderDatabaseQueries,
    renderDatabaseQueryPreview,
    renderServices,
    showApp,
    showApiServiceModal,
    showDatabaseQueryModal,
    showLivedataImportModal,
    showLogin,
    showLoginError,
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
let sqlAssets = [];
let databaseQueries = [];
let selectedDatabaseQueryId = '';
let selectedDatabaseQueryIds = new Set();
let databaseQuerySearchTerm = '';
let databaseQueryPage = 1;
const SERVICE_PAGE_SIZE = 12;

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
    document.getElementById('apiServiceRebuildIndexBtn').addEventListener('click', handleApiServiceRebuildIndex);
    document.getElementById('newMcpServiceBtn').addEventListener('click', openNewMcpService);
    document.getElementById('newDatabaseQueryBtn').addEventListener('click', openNewDatabaseQuery);
    document.getElementById('databaseQueryRebuildIndexBtn').addEventListener('click', handleDatabaseQueryRebuildIndex);
    bindMcpServicePanel({ onError: handleError });
    bindAuthorizationPanel({ onError: handleError, onPasswordChanged: showLogin });
    bindAssetCenterPanel({ onError: handleError });
    bindAuditLogPanel({ onError: handleError });
    bindNotificationPanel({ onError: handleError });
    document.getElementById('databaseQueryForm').addEventListener('submit', handleDatabaseQueryTest);
    document.getElementById('databaseQuerySaveBtn').addEventListener('click', handleDatabaseQuerySave);
    document.getElementById('databaseQueryClearBtn').addEventListener('click', resetDatabaseQueryForm);
    document.getElementById('databaseQuerySearchInput').addEventListener('input', handleDatabaseQuerySearch);
    document.getElementById('databaseQueryPrevPageBtn').addEventListener('click', () => changeDatabaseQueryPage(-1));
    document.getElementById('databaseQueryNextPageBtn').addEventListener('click', () => changeDatabaseQueryPage(1));
    document.getElementById('databaseQuerySelectVisibleBtn').addEventListener('click', selectVisibleDatabaseQueries);
    document.getElementById('databaseQueryClearSelectionBtn').addEventListener('click', clearDatabaseQuerySelection);
    document.getElementById('databaseQueryBatchDeleteBtn').addEventListener('click', removeSelectedDatabaseQueries);
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
    resetMcpServicePanel();
    await loadServices();
}

async function handleLogout() {
    await logout();
    showLogin();
}

async function handleViewSwitch(view) {
    switchView(view);
    if (view === 'apiServices') await loadServices();
    if (view === 'mcpServices') await loadMcpServicePanel();
    if (view === 'settings') await loadAuthorizationPanel();
    if (view === 'assetCenter') await loadAssetCenterPanel();
    if (view === 'databaseMcp') {
        await loadDatabaseQueries();
    }
    if (view === 'notificationChannels') await loadNotificationPanel();
    if (view === 'auditLogs') await loadAuditLogPanel();
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
        service.businessGroup,
        service.businessGroupName,
        service.businessGroupDescription,
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
        await loadAuditLogPanel(false);
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
        await loadAuditLogPanel(false);
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

async function handleApiServiceRebuildIndex() {
    const button = document.getElementById('apiServiceRebuildIndexBtn');
    const originalText = button.textContent;
    button.disabled = true;
    button.textContent = '重建中...';
    try {
        await rebuildApiServiceIndex();
        notify('索引已重建', 'API 服务发现索引已刷新。');
        await loadServices();
    } catch (error) {
        handleError(error);
    } finally {
        button.disabled = false;
        button.textContent = originalText;
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
        query.businessGroup,
        query.businessGroupName,
        query.businessGroupDescription,
        query.sqlTemplate
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
            errorMessage: error.message || '数据库查询执行失败。'
        });
        notify('查询失败', error.message || '数据库查询执行失败。');
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

async function handleDatabaseQueryRebuildIndex() {
    const button = document.getElementById('databaseQueryRebuildIndexBtn');
    const originalText = button.textContent;
    button.disabled = true;
    button.textContent = '重建中...';
    try {
        await rebuildDatabaseQueryIndex();
        notify('索引已重建', '数据库查询模板索引已刷新。');
    } catch (error) {
        handleError(error);
    } finally {
        button.disabled = false;
        button.textContent = originalText;
    }
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
        timeoutSeconds: Number(value('databaseTimeoutSecondsInput') || 30),
        datasourceId: value('databaseDatasourceSelect')
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
        businessGroup: value('databaseBusinessGroupInput'),
        businessGroupName: value('databaseBusinessGroupNameInput'),
        businessGroupDescription: value('databaseBusinessGroupDescriptionInput'),
        sqlTemplate: value('databaseSqlInput'),
        inputSchema: readJsonObject('databaseInputSchemaJson'),
        governance: readJsonObject('databaseGovernanceJson'),
        routingLabelsJson: current?.routingLabelsJson,
        routingLabels: current?.routingLabels,
        capabilitiesJson: current?.capabilitiesJson,
        capabilities: current?.capabilities,
        maxRows: Number(value('databaseMaxRowsInput') || 50),
        timeoutSeconds: Number(value('databaseTimeoutSecondsInput') || 30),
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
    setValue('databaseBusinessGroupInput', query?.businessGroup || 'default');
    setValue('databaseBusinessGroupNameInput', query?.businessGroupName || '');
    setValue('databaseBusinessGroupDescriptionInput', query?.businessGroupDescription || '');
    setValue('databaseSqlInput', query?.sqlTemplate || '');
    setValue('databaseParamsJson', '{}');
    setValue('databaseMaxRowsInput', String(query?.maxRows || 50));
    setValue('databaseTimeoutSecondsInput', String(query?.timeoutSeconds || 30));
    setValue('databaseInputSchemaJson', JSON.stringify(query?.inputSchema || {
        type: 'object',
        properties: {},
        required: [],
        additionalProperties: false
    }, null, 2));
    setValue('databaseGovernanceJson', JSON.stringify(query?.governance || defaultDatabaseQueryGovernance(query), null, 2));
    renderDatabaseQueryPreview(null);
}

function renderDatabaseDatasourceOptions(selected = value('databaseDatasourceSelect')) {
    const select = document.getElementById('databaseDatasourceSelect');
    if (!select) {
        return;
    }
    const enabledAssets = sqlAssets.filter(asset => asset.enabled);
    select.innerHTML = [
        '<option value="">请选择数据库资产</option>',
        ...enabledAssets.map(asset => `
            <option value="${escapeHtml(asset.id)}" ${asset.id === selected ? 'selected' : ''}>
                ${escapeHtml(asset.toolName || asset.name)} / ${escapeHtml(asset.environment || 'DEV')}
            </option>
        `)
    ].join('');
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
        notify('参数已添加', `${field} 已抽取为参数 ${result.names[0]}。`);
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

function defaultDatabaseQueryGovernance(query = {}) {
    const maxRows = Number(query?.maxRows || 50);
    return {
        category: 'database_query',
        operation_type: 'read',
        risk_level: 'medium',
        data_scope: `database_query:${String(query?.title || query?.toolName || 'asset').trim() || 'asset'}`,
        user_visible: true,
        confirmation: {
            default: maxRows > 100 ? 'ask_before_execute' : 'auto_execute',
            allow_user_override: true
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
            mask_fields: ['phone', 'id_card', 'account_no'],
            max_rows_without_confirm: 100
        },
        audit: {
            enabled: true,
            log_params: true,
            log_result_summary: true
        }
    };
}

function changeServicePage(delta) {
    servicePage = clampPage(servicePage + delta, filterServices().length, SERVICE_PAGE_SIZE);
    renderApiServices();
}

function paginate(items, page, pageSize) {
    const start = (page - 1) * pageSize;
    return items.slice(start, start + pageSize);
}

function clampPage(page, itemCount, pageSize) {
    const pageCount = Math.max(1, Math.ceil(itemCount / pageSize));
    return Math.min(Math.max(1, page), pageCount);
}
