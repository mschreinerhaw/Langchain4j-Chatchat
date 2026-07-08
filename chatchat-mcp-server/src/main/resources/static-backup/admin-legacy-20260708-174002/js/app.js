import { MCP_ENDPOINT } from './config.js';
import { loadLayout } from './layout.js';
import { getToken, login, logout } from './auth.js';
import { UnauthorizedError } from './http.js';
import {
    deleteService,
    deleteServices,
    getLivedataConfig,
    listLivedataApis,
    listServices,
    rebuildApiServiceIndex,
    registerLivedataApis,
    refreshTools,
    saveLivedataConfig,
    saveService,
    setEnabled,
    testService
} from './apiServices.js';
import { bindMcpServicePanel, loadMcpServicePanel, openNewMcpService, resetMcpServicePanel } from './mcpServicePanel.js';
import { bindAuthorizationPanel, loadAuthorizationPanel } from './authorizationPanel.js';
import { bindAssetCenterPanel, loadAssetCenterPanel } from './assetCenterPanel.js';
import { bindAuditLogPanel, loadAuditLogPanel } from './auditLogPanel.js';
import { bindNotificationPanel, loadNotificationPanel } from './notificationPanel.js';
import { bindCacheSettingsPanel, loadCacheSettingsPanel } from './cacheSettingsPanel.js';
import { listHttpAssets, listSqlAssets } from './assetCenter.js';
import {
    deleteDatabaseQuery,
    deleteDatabaseQueries,
    listDatabaseQueries,
    rebuildDatabaseQueryIndex,
    saveDatabaseQuery,
    getTradingCalendarConfig,
    saveTradingCalendarConfig,
    setDatabaseQueryEnabled,
    testDatabaseQuery,
    testTradingCalendarFunction,
    testTradingCalendarConfig,
    testSavedDatabaseQuery,
    validateDatabaseQueryTemplateDsl,
    importDatabaseQueryTemplateDsl
} from './databaseMcp.js';
import { bindApiParamEditor, fillServiceForm, readServiceForm, readTestArgs, readTestArgsFromSchema, toggleMicroserviceFields } from './form.js';
import {
    confirmDangerAction,
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
let livedataConfig = null;
let apiGatewayAssets = [];
let sqlAssets = [];
let databaseQueries = [];
let selectedDatabaseQueryId = '';
let selectedDatabaseQueryIds = new Set();
let databaseQuerySearchTerm = '';
let databaseQueryPage = 1;
let latestDatabaseQueryDslValidation = null;
let tradingCalendarConfig = null;
let tradingCalendarQueryTestPassed = false;
const SERVICE_PAGE_SIZE = 12;
const DATABASE_DYNAMIC_PARAMS = new Set([
    'today',
    'natural_date',
    'month',
    'month_start',
    'month_end',
    'trade_date'
]);
const DATABASE_DIRECT_DYNAMIC_TOKEN = /^(?:today|natural_date|month|month_start|month_end|trade_date[+-]?\d*)$/;
const DATABASE_PARAM_SOURCE_OPTIONS = [
    ['user_input', '用户输入'],
    ['today', '当天自然日 today'],
    ['month', '当前月份 month'],
    ['month_start', '当月第一天 month_start'],
    ['month_end', '当月最后一天 month_end'],
    ['trade_date', '当前交易日 trade_date'],
    ['trade_date-1', '上一交易日 trade_date-1'],
    ['trade_date+1', '下一交易日 trade_date+1']
];

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
    document.addEventListener('click', event => {
        if (event.target?.id === 'saveLivedataConfigBtn') {
            handleSaveLivedataConfig();
        }
    });
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
    bindCacheSettingsPanel({ onError: handleError });
    bindApiParamEditor();
    document.getElementById('databaseQueryForm').addEventListener('submit', handleDatabaseQueryTest);
    document.getElementById('databaseQuerySaveBtn').addEventListener('click', handleDatabaseQuerySave);
    document.getElementById('databaseQueryClearBtn').addEventListener('click', resetDatabaseQueryForm);
    document.getElementById('databaseSqlInput').addEventListener('input', updateDatabaseQueryStructuredPreview);
    document.getElementById('databaseQuerySyncParamsBtn').addEventListener('click', () => syncDatabaseQueryParametersFromSql({ notifyUser: true }));
    document.getElementById('databaseDatasourceSelect').addEventListener('change', () => updateDatabaseQueryParamSummary());
    document.getElementById('addDatabaseParamBtn').addEventListener('click', () => addDatabaseParamRow());
    document.getElementById('databaseParamRows').addEventListener('click', handleDatabaseParamRowsClick);
    document.getElementById('databaseParamRows').addEventListener('input', () => syncDatabaseParamHiddenFields(false));
    document.getElementById('databaseParamRows').addEventListener('change', event => {
        updateDatabaseParamSourceState(event.target?.closest?.('[data-database-param-row]'));
        syncDatabaseParamHiddenFields(false);
    });
    document.getElementById('databaseQuerySearchInput').addEventListener('input', handleDatabaseQuerySearch);
    document.getElementById('databaseQueryPrevPageBtn').addEventListener('click', () => changeDatabaseQueryPage(-1));
    document.getElementById('databaseQueryNextPageBtn').addEventListener('click', () => changeDatabaseQueryPage(1));
    document.getElementById('databaseQuerySelectVisibleBtn').addEventListener('click', selectVisibleDatabaseQueries);
    document.getElementById('databaseQueryClearSelectionBtn').addEventListener('click', clearDatabaseQuerySelection);
    document.getElementById('databaseQueryBatchDeleteBtn').addEventListener('click', removeSelectedDatabaseQueries);
    document.getElementById('saveTradingCalendarConfigBtn').addEventListener('click', handleSaveTradingCalendarConfig);
    document.getElementById('testTradingCalendarConfigBtn').addEventListener('click', handleTestTradingCalendarConfig);
    document.getElementById('testTradingCalendarFunctionBtn').addEventListener('click', handleTestTradingCalendarFunction);
    document.getElementById('tradingCalendarDatasourceSelect').addEventListener('change', resetTradingCalendarFunctionTestState);
    document.getElementById('tradingCalendarSqlInput').addEventListener('input', resetTradingCalendarFunctionTestState);
    document.getElementById('databaseQueryImportDslBtn').addEventListener('click', openDatabaseQueryDslImport);
    document.getElementById('databaseQueryExportDslBtn').addEventListener('click', handleDatabaseQueryDslExport);
    document.getElementById('databaseQueryDslValidateBtn').addEventListener('click', handleDatabaseQueryDslValidate);
    document.getElementById('databaseQueryDslImportForm').addEventListener('submit', handleDatabaseQueryDslImport);
    document.getElementById('databaseQueryDslDatasourceId').addEventListener('change', resetDatabaseQueryDslValidationState);
    document.getElementById('databaseQueryDslBody').addEventListener('input', resetDatabaseQueryDslValidationState);
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
    ensureApiGatewaySelector();
    hideLegacyApiHttpFields();
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
    if (view === 'cacheSettings') await loadCacheSettingsPanel();
    if (view === 'notificationChannels') await loadNotificationPanel();
    if (view === 'auditLogs') await loadAuditLogPanel();
}

function ensureApiGatewaySelector() {
    if (document.getElementById('apiGatewaySelect')) {
        return;
    }
    const titleInput = document.getElementById('title');
    const titleColumn = titleInput?.closest('.col-md-6');
    if (!titleColumn) {
        return;
    }
    const wrapper = document.createElement('div');
    wrapper.className = 'col-12';
    wrapper.innerHTML = `
        <label class="form-label" for="apiGatewaySelect">API 网关资产</label>
        <select id="apiGatewaySelect" class="form-select">
            <option value="">请选择 API 网关资产</option>
        </select>
    `;
    titleColumn.insertAdjacentElement('afterend', wrapper);
}

function hideLegacyApiHttpFields() {
    ['method', 'timeoutMs', 'urlTemplate', 'headersJson'].forEach(id => {
        const element = document.getElementById(id);
        element?.closest('[class*="col-"]')?.classList.add('d-none');
        element?.removeAttribute('required');
    });
    const bodyTemplate = document.getElementById('bodyTemplate');
    bodyTemplate?.classList.add('d-none');
    bodyTemplate?.removeAttribute('required');
    document.querySelector('label[for="bodyTemplate"]')?.classList.add('d-none');
    const microserviceBox = document.getElementById('microserviceMode')?.closest('[class*="col-"]');
    microserviceBox?.classList.add('d-none');
}

function renderApiGatewayOptions(selectedGatewayId = document.getElementById('apiGatewaySelect')?.value || '') {
    ensureApiGatewaySelector();
    const select = document.getElementById('apiGatewaySelect');
    if (!select) {
        return;
    }
    const selectedId = selectedGatewayId || '';
    const visibleAssets = apiGatewayAssets
        .filter(asset => asset.enabled || asset.id === selectedId)
        .sort((a, b) => String(a.name || a.toolName || '').localeCompare(String(b.name || b.toolName || '')));
    const options = ['<option value="">请选择 API 网关资产</option>'];
    for (const asset of visibleAssets) {
        const label = [asset.name || asset.title || asset.toolName || asset.id, asset.environment, asset.method]
            .filter(Boolean)
            .join(' · ');
        options.push(`<option value="${escapeHtml(asset.id)}">${escapeHtml(label)}</option>`);
    }
    select.innerHTML = options.join('');
    select.value = selectedId;
}

async function loadServices() {
    try {
        [services, apiGatewayAssets] = await Promise.all([listServices(), listHttpAssets()]);
        if (selectedId && !services.some(service => service.id === selectedId)) selectedId = '';
        selectedServiceIds = new Set([...selectedServiceIds].filter(id => services.some(service => service.id === id)));
        renderApiGatewayOptions();
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
    renderApiGatewayOptions();
    showApiServiceModal();
}

function selectService(service) {
    selectedId = service.id;
    document.getElementById('formTitle').textContent = '编辑 API 服务';
    renderApiGatewayOptions(service.gatewayId);
    fillServiceForm(service);
    renderApiServices();
    showApiServiceModal();
}

function resetForm() {
    selectedId = '';
    document.getElementById('formTitle').textContent = '新增 API 服务';
    renderApiGatewayOptions();
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
    ensureLivedataConfigPanel();
    document.getElementById('livedataApiSearchInput').value = '';
    document.getElementById('overwriteLivedataExisting').checked = false;
    showLivedataImportModal();
    await loadLivedataConfig();
    await reloadLivedataApis();
}

async function loadLivedataConfig() {
    try {
        [livedataConfig, sqlAssets] = await Promise.all([getLivedataConfig(), listSqlAssets()]);
        renderLivedataDatasourceOptions(livedataConfig?.datasourceId || '');
        fillLivedataConfigForm(livedataConfig);
    } catch (error) {
        handleError(error);
    }
}

async function handleSaveLivedataConfig() {
    const button = document.getElementById('saveLivedataConfigBtn');
    const originalText = button?.textContent || '保存配置';
    if (button) {
        button.disabled = true;
        button.textContent = '保存中...';
    }
    try {
        livedataConfig = await saveLivedataConfig(readLivedataConfigForm());
        notify('保存成功', 'LiveData 配置已更新。');
        renderLivedataDatasourceOptions(livedataConfig?.datasourceId || '');
        fillLivedataConfigForm(livedataConfig);
        await reloadLivedataApis();
    } catch (error) {
        handleError(error);
    } finally {
        if (button) {
            button.disabled = false;
            button.textContent = originalText;
        }
    }
}

async function reloadLivedataApis() {
    ensureLivedataConfigPanel();
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
    const confirmed = await confirmDangerAction({
        title: '删除 API 服务',
        message: '确定删除该 API 服务吗？',
        target: service.toolName || service.title || '未命名 API 服务',
        detail: '删除后该 API 服务将从 MCP 工具列表移除，已绑定的页面配置不会再调用它。',
        confirmText: '确认删除'
    });
    if (!confirmed) return;
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
    const confirmed = await confirmDangerAction({
        title: '批量删除 API 服务',
        message: `确定删除选中的 ${ids.length} 个 API 服务吗？`,
        target: `${ids.length} 个已选 API 服务`,
        detail: '批量删除后这些 API 服务将从 MCP 工具列表移除。',
        confirmText: '确认删除'
    });
    if (!confirmed) return;
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
        [databaseQueries, sqlAssets, tradingCalendarConfig] = await Promise.all([
            listDatabaseQueries(),
            listSqlAssets(),
            getTradingCalendarConfig()
        ]);
        if (selectedDatabaseQueryId && !databaseQueries.some(query => query.id === selectedDatabaseQueryId)) {
            selectedDatabaseQueryId = '';
        }
        renderDatabaseDatasourceOptions();
        renderTradingCalendarConfig();
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
    updateDatabaseQueryDslExportButton();
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

function openDatabaseQueryDslImport() {
    renderDatabaseQueryDslDatasourceOptions();
    resetDatabaseQueryDslValidationState('粘贴模板 JSON 后点击“验证”。');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('databaseQueryDslImportModal')).show();
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
        const params = readDatabasePromptArgsFromSchema(query.inputSchema || emptyParameterSchema());
        selectedDatabaseQueryId = query.id;
        fillDatabaseQueryForm(query);
        renderDatabaseParamEditor(query.inputSchema || emptyParameterSchema(), params);
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
    const confirmed = await confirmDangerAction({
        title: '删除数据库查询',
        message: '确定删除该数据库查询吗？',
        target: query.toolName || query.title || '未命名数据库查询',
        detail: '删除后该查询模板将从 MCP 数据库查询工具中移除。',
        confirmText: '确认删除'
    });
    if (!confirmed) return;
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

function updateDatabaseQueryDslExportButton() {
    const button = document.getElementById('databaseQueryExportDslBtn');
    if (!button) {
        return;
    }
    button.disabled = selectedDatabaseQueryIds.size === 0;
    button.textContent = selectedDatabaseQueryIds.size > 0
        ? `导出模板 (${selectedDatabaseQueryIds.size})`
        : '导出模板';
}

function handleDatabaseQueryDslExport() {
    const selected = databaseQueries.filter(query => selectedDatabaseQueryIds.has(query.id));
    if (!selected.length) {
        notify('请选择查询模板', '勾选一个或多个数据库查询模板后再导出模板。');
        return;
    }
    const exported = selected.map(exportDatabaseQueryAsDsl);
    showResult(selected.length === 1 ? exported[0] : exported, {
        title: selected.length === 1 ? `${selected[0].toolName || selected[0].title || '数据库查询'} 导出结果` : `已导出 ${selected.length} 个数据库查询模板`,
        subtitle: '可使用结果窗口右上角复制完整 JSON。'
    });
}

async function handleDatabaseQueryDslValidate() {
    const submitBtn = document.getElementById('databaseQueryDslImportSubmitBtn');
    const validateBtn = document.getElementById('databaseQueryDslValidateBtn');
    try {
        validateBtn.disabled = true;
        latestDatabaseQueryDslValidation = await validateDatabaseQueryTemplateDsl(readDatabaseQueryDslImportForm());
        renderDatabaseQueryDslValidationResult(latestDatabaseQueryDslValidation);
        submitBtn.disabled = !latestDatabaseQueryDslValidation.valid;
    } catch (error) {
        latestDatabaseQueryDslValidation = null;
        submitBtn.disabled = true;
        renderDatabaseQueryDslImportMessage(error.message || String(error), true);
    } finally {
        validateBtn.disabled = false;
    }
}

async function handleSaveTradingCalendarConfig() {
    const button = document.getElementById('saveTradingCalendarConfigBtn');
    const original = button?.textContent || '保存配置';
    try {
        if (button) {
            button.disabled = true;
            button.textContent = '保存中...';
        }
        tradingCalendarConfig = await saveTradingCalendarConfig(readTradingCalendarConfigForm());
        resetTradingCalendarFunctionTestState();
        notify('保存成功', '交易日数据源模板已更新。');
        renderTradingCalendarConfig();
    } catch (error) {
        handleError(error);
    } finally {
        if (button) {
            button.disabled = false;
            button.textContent = original;
        }
    }
}

async function handleTestTradingCalendarConfig() {
    const button = document.getElementById('testTradingCalendarConfigBtn');
    const original = button?.textContent || '测试查询';
    try {
        if (button) {
            button.disabled = true;
            button.textContent = '测试中...';
        }
        const result = await testTradingCalendarConfig(readTradingCalendarConfigForm());
        tradingCalendarQueryTestPassed = !!result.success;
        updateTradingCalendarFunctionTestState();
        notify(result.success ? '测试通过' : '测试未通过', result.message || result.errorMessage || '交易日查询测试完成。');
        showResult(result, {
            title: '交易日数据源模板测试结果',
            subtitle: '使用当前页面配置执行测试，不保存配置'
        });
    } catch (error) {
        resetTradingCalendarFunctionTestState();
        notify('测试失败', error.message || '交易日查询测试失败。');
        showResult({
            success: false,
            message: '交易日查询测试失败',
            errorMessage: error.message || String(error)
        }, {
            title: '交易日数据源模板测试结果',
            subtitle: '使用当前页面配置执行测试，不保存配置'
        });
    } finally {
        if (button) {
            button.disabled = false;
            button.textContent = original;
        }
    }
}

async function handleTestTradingCalendarFunction() {
    const button = document.getElementById('testTradingCalendarFunctionBtn');
    const original = button?.textContent || '测试函数';
    const functionName = value('tradingCalendarFunctionSelect') || 'trade_date';
    try {
        if (button) {
            button.disabled = true;
            button.textContent = '获取中...';
        }
        const result = await testTradingCalendarFunction({
            ...readTradingCalendarConfigForm(),
            functionName
        });
        notify(result.success ? '获取成功' : '获取失败', result.success ? `${result.functionName} = ${result.value}` : (result.message || '函数数据日获取失败。'));
        showResult(result, {
            title: '交易日函数数据日获取测试',
            subtitle: '使用当前页面配置和已通过查询测试的交易日模板'
        });
    } catch (error) {
        notify('获取失败', error.message || '函数数据日获取失败。');
        showResult({
            success: false,
            functionName,
            message: '函数数据日获取失败',
            errorMessage: error.message || String(error)
        }, {
            title: '交易日函数数据日获取测试',
            subtitle: '使用当前页面配置和已通过查询测试的交易日模板'
        });
    } finally {
        updateTradingCalendarFunctionTestState();
        if (button) {
            button.textContent = original;
        }
    }
}

function resetTradingCalendarFunctionTestState() {
    tradingCalendarQueryTestPassed = false;
    updateTradingCalendarFunctionTestState();
}

function updateTradingCalendarFunctionTestState() {
    const enabled = !!tradingCalendarQueryTestPassed;
    const select = document.getElementById('tradingCalendarFunctionSelect');
    const button = document.getElementById('testTradingCalendarFunctionBtn');
    if (select) {
        select.disabled = !enabled;
    }
    if (button) {
        button.disabled = !enabled;
    }
}

function readDatabasePromptArgsFromSchema(schema = emptyParameterSchema()) {
    const args = {};
    const properties = schema.properties || {};
    for (const [name, definition] of Object.entries(properties)) {
        if (definition?.defaultSource && definition.defaultSource !== 'user_input') {
            continue;
        }
        const promptValue = window.prompt(`请输入参数 ${name}`, definition.default ?? '');
        if (promptValue !== null && promptValue !== '') {
            args[name] = coerceParamValue(promptValue, definition.type || 'string');
        }
    }
    return args;
}

function ensureLivedataConfigPanel() {
    if (document.getElementById('livedataConfigPanel')) {
        return;
    }
    const toolbar = document.querySelector('.livedata-import-toolbar');
    if (!toolbar) {
        return;
    }
    const panel = document.createElement('div');
    panel.id = 'livedataConfigPanel';
    panel.className = 'livedata-config-panel mb-3 border rounded-2';
    panel.innerHTML = `
        <button class="livedata-config-toggle collapsed" type="button" data-bs-toggle="collapse" data-bs-target="#livedataConfigBody" aria-expanded="false" aria-controls="livedataConfigBody">
            <span>
                <strong>API 网关设置</strong>
                <small class="text-secondary">配置 LiveData 导入使用的数据资产、网关地址、登录和缓存策略</small>
            </span>
        </button>
        <div id="livedataConfigBody" class="collapse">
            <div class="row g-3 p-3 pt-0">
                <div class="col-md-3">
                    <label class="form-label small text-secondary" for="livedataEnabled">启用状态</label>
                    <select id="livedataEnabled" class="form-select">
                        <option value="false">停用</option>
                        <option value="true">启用</option>
                    </select>
                </div>
                <div class="col-md-5">
                    <label class="form-label small text-secondary" for="livedataDatasourceId">数据库资产</label>
                    <select id="livedataDatasourceId" class="form-select">
                        <option value="">请选择数据库资产</option>
                    </select>
                </div>
                <div class="col-md-4">
                    <label class="form-label small text-secondary" for="livedataTableName">API 表名</label>
                    <input id="livedataTableName" class="form-control" value="ld_dataservice_api">
                </div>
                <div class="col-md-6">
                    <label class="form-label small text-secondary" for="livedataServiceBaseUrl">服务基础地址</label>
                    <input id="livedataServiceBaseUrl" class="form-control" placeholder="https://livedata.example.com">
                </div>
                <div class="col-md-6">
                    <label class="form-label small text-secondary" for="livedataServicePathTemplate">服务路径模板</label>
                    <input id="livedataServicePathTemplate" class="form-control" value="/service/{serviceName}/call">
                </div>
                <div class="col-md-3">
                    <label class="form-label small text-secondary" for="livedataLoginEnabled">登录开关</label>
                    <select id="livedataLoginEnabled" class="form-select">
                        <option value="true">启用</option>
                        <option value="false">停用</option>
                    </select>
                </div>
                <div class="col-md-3">
                    <label class="form-label small text-secondary" for="livedataLoginPath">登录路径</label>
                    <input id="livedataLoginPath" class="form-control" value="/login">
                </div>
                <div class="col-md-3">
                    <label class="form-label small text-secondary" for="livedataLoginId">登录账号</label>
                    <input id="livedataLoginId" class="form-control">
                </div>
                <div class="col-md-3">
                    <label class="form-label small text-secondary" for="livedataLoginPwd">登录密码</label>
                    <input id="livedataLoginPwd" class="form-control" type="password">
                </div>
                <div class="col-md-4">
                    <label class="form-label small text-secondary" for="livedataAmsToken">AMS 令牌</label>
                    <input id="livedataAmsToken" class="form-control" type="password">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataNamespace">命名空间</label>
                    <input id="livedataNamespace" class="form-control" value="livedata">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataToolPrefix">工具名前缀</label>
                    <input id="livedataToolPrefix" class="form-control" value="livedata_">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataPublishedState">发布状态值</label>
                    <input id="livedataPublishedState" class="form-control" type="number" value="0">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataMaxApis">最大 API 数</label>
                    <input id="livedataMaxApis" class="form-control" type="number" min="1" value="1000">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataTimeoutMs">超时时间 ms</label>
                    <input id="livedataTimeoutMs" class="form-control" type="number" min="1000" value="20000">
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataCacheEnabled">缓存开关</label>
                    <select id="livedataCacheEnabled" class="form-select">
                        <option value="false">停用</option>
                        <option value="true">启用</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small text-secondary" for="livedataCacheTtlSeconds">缓存 TTL 秒</label>
                    <input id="livedataCacheTtlSeconds" class="form-control" type="number" min="1" value="300">
                </div>
                <div class="col-md-3 d-flex align-items-end">
                    <div class="form-check">
                        <input id="livedataIncludeUnpublished" class="form-check-input" type="checkbox">
                        <label class="form-check-label" for="livedataIncludeUnpublished">包含未发布 API</label>
                    </div>
                </div>
                <div class="col-md-3 d-flex align-items-end">
                    <div class="form-check">
                        <input id="livedataExposeAmsToken" class="form-check-input" type="checkbox">
                        <label class="form-check-label" for="livedataExposeAmsToken">暴露令牌参数</label>
                    </div>
                </div>
                <div class="col-12 d-flex justify-content-end">
                    <button id="saveLivedataConfigBtn" class="btn btn-outline-primary btn-sm px-3" type="button">保存配置</button>
                </div>
            </div>
        </div>
    `;
    toolbar.insertAdjacentElement('beforebegin', panel);
}

function renderLivedataDatasourceOptions(selected = '') {
    const select = document.getElementById('livedataDatasourceId');
    if (!select) {
        return;
    }
    const assets = sqlAssets
        .filter(asset => asset.enabled || asset.id === selected)
        .sort((a, b) => String(a.name || '').localeCompare(String(b.name || '')));
    select.innerHTML = [
        '<option value="">请选择数据库资产</option>',
        ...assets.map(asset => `<option value="${escapeHtml(asset.id)}">${escapeHtml(asset.name || asset.toolName || asset.id)} / ${escapeHtml(asset.environment || 'DEV')}</option>`)
    ].join('');
    select.value = selected || '';
}

function fillLivedataConfigForm(config = {}) {
    setValue('livedataEnabled', String(config.enabled ?? false));
    setValue('livedataDatasourceId', config.datasourceId || '');
    setValue('livedataTableName', config.tableName || 'ld_dataservice_api');
    setValue('livedataServiceBaseUrl', config.serviceBaseUrl || '');
    setValue('livedataServicePathTemplate', config.servicePathTemplate || '/service/{serviceName}/call');
    setValue('livedataLoginEnabled', String(config.loginEnabled ?? true));
    setValue('livedataLoginPath', config.loginPath || '/login');
    setValue('livedataLoginId', config.loginId || '');
    setValue('livedataLoginPwd', config.loginPwd || '');
    setValue('livedataAmsToken', config.amsToken || '');
    setValue('livedataNamespace', config.defaultNamespace || 'livedata');
    setValue('livedataToolPrefix', config.toolNamePrefix || 'livedata_');
    setValue('livedataPublishedState', String(config.publishedState ?? 0));
    setValue('livedataMaxApis', String(config.maxApis || 1000));
    setValue('livedataTimeoutMs', String(config.timeoutMs || 20000));
    setValue('livedataCacheEnabled', String(config.cacheEnabled ?? false));
    setValue('livedataCacheTtlSeconds', String(config.cacheTtlSeconds || 300));
    const include = document.getElementById('livedataIncludeUnpublished');
    if (include) include.checked = Boolean(config.includeUnpublishedAsDisabled);
    const expose = document.getElementById('livedataExposeAmsToken');
    if (expose) expose.checked = Boolean(config.exposeAmsTokenParameter);
    const overwrite = document.getElementById('overwriteLivedataExisting');
    if (overwrite) overwrite.checked = Boolean(config.overwriteExisting);
}

function readLivedataConfigForm() {
    return {
        enabled: value('livedataEnabled') === 'true',
        datasourceId: value('livedataDatasourceId') || null,
        tableName: value('livedataTableName') || 'ld_dataservice_api',
        serviceBaseUrl: value('livedataServiceBaseUrl') || null,
        servicePathTemplate: value('livedataServicePathTemplate') || '/service/{serviceName}/call',
        loginEnabled: value('livedataLoginEnabled') === 'true',
        loginPath: value('livedataLoginPath') || '/login',
        loginId: value('livedataLoginId') || null,
        loginPwd: value('livedataLoginPwd') || null,
        loginTimeoutMs: 10000,
        sessionTtlSeconds: 1800,
        amsToken: value('livedataAmsToken') || null,
        defaultNamespace: value('livedataNamespace') || 'livedata',
        toolNamePrefix: value('livedataToolPrefix') || 'livedata_',
        publishedState: Number(value('livedataPublishedState') || 0),
        maxApis: Number(value('livedataMaxApis') || 1000),
        timeoutMs: Number(value('livedataTimeoutMs') || 20000),
        cacheEnabled: value('livedataCacheEnabled') === 'true',
        cacheTtlSeconds: Number(value('livedataCacheTtlSeconds') || 300),
        overwriteExisting: document.getElementById('overwriteLivedataExisting')?.checked || false,
        includeUnpublishedAsDisabled: document.getElementById('livedataIncludeUnpublished')?.checked || false,
        exposeAmsTokenParameter: document.getElementById('livedataExposeAmsToken')?.checked || false
    };
}

async function handleDatabaseQueryDslImport(event) {
    event.preventDefault();
    const submitBtn = document.getElementById('databaseQueryDslImportSubmitBtn');
    const validateBtn = document.getElementById('databaseQueryDslValidateBtn');
    try {
        submitBtn.disabled = true;
        validateBtn.disabled = true;
        const payload = readDatabaseQueryDslImportForm();
        const validation = latestDatabaseQueryDslValidation?.valid
            ? latestDatabaseQueryDslValidation
            : await validateDatabaseQueryTemplateDsl(payload);
        if (!validation.valid) {
            latestDatabaseQueryDslValidation = validation;
            renderDatabaseQueryDslValidationResult(validation);
            submitBtn.disabled = true;
            return;
        }
        const result = await importDatabaseQueryTemplateDsl(payload);
        notify('数据库查询模板已导入', `${result.templateCode || '模板'} 已写入数据库查询模板。`);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('databaseQueryDslImportModal')).hide();
        selectedDatabaseQueryId = result.savedId || '';
        await loadDatabaseQueries();
    } catch (error) {
        handleError(error);
    } finally {
        validateBtn.disabled = false;
        submitBtn.disabled = !(latestDatabaseQueryDslValidation?.valid);
    }
}

async function removeSelectedDatabaseQueries() {
    const ids = [...selectedDatabaseQueryIds];
    if (!ids.length) {
        notify('未选择查询', '请先选择需要删除的数据查询。');
        return;
    }
    const confirmed = await confirmDangerAction({
        title: '批量删除数据库查询',
        message: `确定删除选中的 ${ids.length} 个数据查询吗？`,
        target: `${ids.length} 个已选数据库查询`,
        detail: '批量删除后这些查询模板将从 MCP 数据库查询工具中移除。',
        confirmText: '确认删除'
    });
    if (!confirmed) return;
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

function exportDatabaseQueryAsDsl(query) {
    const existing = existingDatabaseQueryDsl(query);
    if (existing) {
        return existing;
    }
    return {
        templateCode: query.toolName,
        templateName: query.title || query.toolName,
        templateType: 'DATABASE_QUERY',
        targetType: query.dbType || query.databaseType || 'generic',
        databaseType: query.dbType || query.databaseType || 'generic',
        datasourceId: query.datasourceId || null,
        description: query.description || '',
        businessGroup: query.businessGroup || 'default',
        businessGroupName: query.businessGroupName || query.businessGroup || 'default',
        businessGroupDescription: query.businessGroupDescription || '',
        riskLevel: query.riskLevel || 'read_only',
        owner: query.owner || 'admin',
        maxRows: query.maxRows || 50,
        timeoutSeconds: query.timeoutSeconds || 30,
        executionMode: 'SEQUENTIAL',
        continueOnError: true,
        parameterSchema: query.inputSchema || emptyParameterSchema(),
        governance: query.governance || defaultDatabaseQueryGovernance(query),
        routingLabels: query.routingLabels || parseJsonArray(query.routingLabelsJson),
        capabilities: query.capabilities || parseJsonArray(query.capabilitiesJson),
        steps: commandList(query.sqlTemplate).map((sql, index) => ({
            stepCode: `STEP_${index + 1}`,
            stepName: index === 0 ? (query.title || `Step ${index + 1}`) : `Step ${index + 1}`,
            stepType: 'SQL',
            order: index + 1,
            required: true,
            timeoutSeconds: query.timeoutSeconds || null,
            sql,
            analysisHint: query.description || query.businessGroupDescription || ''
        })),
        analysisPolicy: defaultDslAnalysisPolicy()
    };
}

function existingDatabaseQueryDsl(query) {
    const parsed = tryParseJsonObject(query.sqlTemplate);
    if (!parsed || !Array.isArray(parsed.steps)) {
        return null;
    }
    return {
        ...parsed,
        templateCode: parsed.templateCode || query.toolName,
        templateName: parsed.templateName || parsed.name || query.title || query.toolName,
        templateType: parsed.templateType || 'DATABASE_QUERY',
        datasourceId: parsed.datasourceId || query.datasourceId || null,
        description: parsed.description || query.description || '',
        riskLevel: parsed.riskLevel || query.riskLevel || 'read_only'
    };
}

function renderDatabaseQueryDslDatasourceOptions() {
    const select = document.getElementById('databaseQueryDslDatasourceId');
    if (!select) {
        return;
    }
    const selected = select.value;
    select.innerHTML = '<option value="">使用模板内 datasourceId</option>';
    for (const asset of sqlAssets.filter(item => item.enabled)) {
        const option = document.createElement('option');
        option.value = asset.id;
        option.textContent = `${asset.name || asset.title || asset.toolName || asset.id} (${asset.databaseType || 'generic'} / ${asset.environment || 'DEV'})`;
        select.appendChild(option);
    }
    if ([...select.options].some(option => option.value === selected)) {
        select.value = selected;
    }
}

function readDatabaseQueryDslImportForm() {
    return {
        templateType: 'DATABASE_QUERY',
        datasourceId: value('databaseQueryDslDatasourceId'),
        dsl: value('databaseQueryDslBody')
    };
}

function resetDatabaseQueryDslValidationState(message) {
    latestDatabaseQueryDslValidation = null;
    const submitBtn = document.getElementById('databaseQueryDslImportSubmitBtn');
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    renderDatabaseQueryDslImportMessage(typeof message === 'string' ? message : '模板已修改，请重新验证。', false);
}

function renderDatabaseQueryDslValidationResult(result) {
    const node = document.getElementById('databaseQueryDslImportResult');
    if (!node) {
        return;
    }
    const normalized = result?.normalized || {};
    const steps = Array.isArray(normalized.steps) ? normalized.steps : [];
    const errors = Array.isArray(result?.errors) ? result.errors : [];
    const warnings = Array.isArray(result?.warnings) ? result.warnings : [];
    node.classList.remove('text-secondary', 'text-danger');
    node.innerHTML = `
        <div class="d-flex align-items-center gap-2 mb-2">
            <span class="badge ${result?.valid ? 'text-bg-success' : 'text-bg-danger'}">${result?.valid ? '验证通过' : '验证失败'}</span>
            <strong>${escapeHtml(normalized.templateCode || '-')}</strong>
            <span class="text-secondary">DATABASE_QUERY</span>
            <span class="text-secondary">步骤 ${escapeHtml(normalized.stepCount ?? steps.length)}</span>
        </div>
        ${errors.length ? `<div class="alert alert-danger py-2 mb-2">${errors.map(escapeHtml).join('<br>')}</div>` : ''}
        ${warnings.length ? `<div class="alert alert-warning py-2 mb-2">${warnings.map(escapeHtml).join('<br>')}</div>` : ''}
        <div class="table-responsive">
            <table class="table table-sm align-middle mb-0">
                <thead><tr><th>顺序</th><th>步骤编码</th><th>类型</th><th>必需</th><th>分析提示</th></tr></thead>
                <tbody>
                    ${steps.map(step => `
                        <tr>
                            <td>${escapeHtml(step.order ?? '')}</td>
                            <td><code>${escapeHtml(step.stepCode || '')}</code><div class="small text-secondary">${escapeHtml(step.stepName || '')}</div></td>
                            <td>${escapeHtml(step.stepType || '')}</td>
                            <td>${step.required ? '是' : '否'}</td>
                            <td>${escapeHtml(step.analysisHint || '')}</td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

function renderDatabaseQueryDslImportMessage(message, error) {
    const node = document.getElementById('databaseQueryDslImportResult');
    if (!node) {
        return;
    }
    node.classList.toggle('text-danger', !!error);
    node.classList.toggle('text-secondary', !error);
    node.textContent = message || '';
}

function commandList(value) {
    const parsed = tryParseJson(value);
    if (Array.isArray(parsed)) {
        return parsed.map(item => String(item ?? '').trim()).filter(Boolean);
    }
    const text = String(value || '').trim();
    return text ? [text] : [];
}

function tryParseJsonObject(value) {
    const parsed = tryParseJson(value);
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : null;
}

function tryParseJson(value) {
    if (!value || typeof value !== 'string') {
        return null;
    }
    try {
        return JSON.parse(value);
    } catch (error) {
        return null;
    }
}

function parseJsonArray(value) {
    const parsed = tryParseJson(value);
    return Array.isArray(parsed) ? parsed : [];
}

function emptyParameterSchema() {
    return {
        type: 'object',
        properties: {},
        required: [],
        additionalProperties: false
    };
}

function defaultDslAnalysisPolicy() {
    return {
        summaryRequired: true,
        evidenceRequired: true,
        outputSections: ['总体结论', '异常项', '关键证据', '风险等级', '处理建议']
    };
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
    const element = document.getElementById(id);
    return element ? element.value.trim() : '';
}

function setValue(id, nextValue) {
    const element = document.getElementById(id);
    if (element) {
        element.value = nextValue ?? '';
    }
}

function readDatabaseQueryForm() {
    syncDatabaseParamHiddenFields();
    return {
        sql: value('databaseSqlInput'),
        params: readDatabaseTestParams(),
        maxRows: Number(value('databaseMaxRowsInput') || 50),
        timeoutSeconds: Number(value('databaseTimeoutSecondsInput') || 30),
        datasourceId: value('databaseDatasourceSelect')
    };
}

function readDatabaseQueryRegistrationForm() {
    syncDatabaseParamHiddenFields();
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
        inputSchema: readDatabaseInputSchema(),
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
    updateDatabaseQueryStructuredPreview();
    renderDatabaseParamEditor(query?.inputSchema || emptyParameterSchema(), {});
    setValue('databaseMaxRowsInput', String(query?.maxRows || 50));
    setValue('databaseTimeoutSecondsInput', String(query?.timeoutSeconds || 30));
    setValue('databaseGovernanceJson', JSON.stringify(query?.governance || defaultDatabaseQueryGovernance(query), null, 2));
    renderDatabaseQueryPreview(null);
}

function updateDatabaseQueryStructuredPreview() {
    renderDatabaseQueryStructuredPreview(value('databaseSqlInput'));
    updateDatabaseQueryParamSummary();
}

function syncDatabaseQueryParametersFromSql(options = {}) {
    const params = extractDatabaseQueryParameters(value('databaseSqlInput'));
    const schema = readDatabaseInputSchema();
    const testParams = readDatabaseTestParams();
    let added = 0;

    for (const param of params) {
        if (!schema.properties[param.name]) {
            schema.properties[param.name] = databaseQuerySchemaProperty(param);
            added += 1;
        } else if (param.dynamic) {
            schema.properties[param.name] = {
                ...schema.properties[param.name],
                defaultSource: param.defaultSource
            };
        }
        if (param.required && !schema.required.includes(param.name)) {
            schema.required.push(param.name);
        }
        if (!param.dynamic && !Object.prototype.hasOwnProperty.call(testParams, param.name)) {
            testParams[param.name] = param.example;
        }
    }

    renderDatabaseParamEditor(schema, testParams);
    updateDatabaseQueryParamSummary(params);
    if (options.notifyUser) {
        notify('参数已同步', added > 0 ? `新增 ${added} 个参数定义。` : '参数定义已是最新。');
    }
    return params;
}

function updateDatabaseQueryParamSummary(existingParams) {
    const node = document.getElementById('databaseQueryParamSummary');
    if (!node) {
        return;
    }
    const params = existingParams || extractDatabaseQueryParameters(value('databaseSqlInput'));
    if (!params.length) {
        node.textContent = '';
        return;
    }
    const names = params.map(param => param.dynamic ? `${param.name}:自动` : param.name);
    node.textContent = `参数：${names.join(', ')}`;
}

function extractDatabaseQueryParameters(sql) {
    const params = new Map();
    const text = String(sql || '');

    const namedPattern = /(^|[^:]):([A-Za-z_][A-Za-z0-9_]*)\b/g;
    let match;
    while ((match = namedPattern.exec(text)) !== null) {
        const name = match[2];
        params.set(name, databaseQueryParamDescriptor(name, DATABASE_DYNAMIC_PARAMS.has(name), 'named'));
    }

    const dynamicPattern = /\$\{\s*([A-Za-z_][A-Za-z0-9_+-]*)\s*}/g;
    while ((match = dynamicPattern.exec(text)) !== null) {
        const token = match[1];
        if (!DATABASE_DIRECT_DYNAMIC_TOKEN.test(token)) {
            continue;
        }
        const name = token.startsWith('trade_date') ? 'trade_date' : token;
        if (!params.has(name)) {
            params.set(name, databaseQueryParamDescriptor(name, true, 'dynamic_token', token));
        }
    }

    const mustachePattern = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}/g;
    while ((match = mustachePattern.exec(text)) !== null) {
        const name = match[1];
        if (!params.has(name)) {
            params.set(name, databaseQueryParamDescriptor(name, DATABASE_DYNAMIC_PARAMS.has(name), 'template_token'));
        }
    }

    return [...params.values()];
}

function databaseQueryParamDescriptor(name, dynamic, source, token = name) {
    return {
        name,
        dynamic,
        source,
        token,
        defaultSource: dynamic ? token : 'user_input',
        required: !dynamic,
        example: databaseQueryParamExample(name)
    };
}

function databaseQuerySchemaProperty(param) {
    const property = {
        type: 'string',
        description: param.dynamic
            ? `Runtime dynamic parameter: ${param.defaultSource}`
            : `Query parameter: ${param.name}`
    };
    if (param.dynamic) {
        property.defaultSource = param.defaultSource;
    }
    const pattern = databaseQueryParamPattern(param.name);
    if (pattern) {
        property.pattern = pattern;
    }
    if (param.example !== '') {
        property.examples = [param.example];
    }
    return property;
}

function databaseQueryParamPattern(name) {
    if (name === 'month') {
        return '^\\d{6}$';
    }
    if (['today', 'natural_date', 'month_start', 'month_end', 'trade_date'].includes(name)) {
        return '^\\d{8}$';
    }
    return null;
}

function databaseQueryParamExample(name) {
    if (name === 'month') {
        return '202607';
    }
    if (['today', 'natural_date', 'month_start', 'month_end', 'trade_date'].includes(name)) {
        return '20260707';
    }
    if (name.toLowerCase().includes('branch')) {
        return '0001';
    }
    if (name.toLowerCase().includes('customer')) {
        return 'CUST001';
    }
    return '';
}

function handleDatabaseParamRowsClick(event) {
    const button = event.target?.closest?.('[data-database-param-remove]');
    if (!button) {
        return;
    }
    button.closest('[data-database-param-row]')?.remove();
    updateDatabaseParamEmptyState();
    syncDatabaseParamHiddenFields(false);
}

function renderDatabaseParamEditor(schema = emptyParameterSchema(), testParams = {}) {
    const rows = document.getElementById('databaseParamRows');
    if (!rows) {
        setValue('databaseInputSchemaJson', JSON.stringify(schema || emptyParameterSchema(), null, 2));
        setValue('databaseParamsJson', JSON.stringify(testParams || {}, null, 2));
        return;
    }
    rows.querySelectorAll('[data-database-param-row]').forEach(row => row.remove());
    const normalized = normalizeInputSchema(isPlainObject(schema) ? { ...schema } : emptyParameterSchema());
    const required = new Set(normalized.required || []);
    for (const [name, definition] of Object.entries(normalized.properties || {})) {
        const defaultSource = definition.defaultSource || '';
        addDatabaseParamRow({
            name,
            type: definition.type || 'string',
            required: required.has(name),
            defaultSource,
            dynamic: defaultSource && defaultSource !== 'user_input',
            defaultValue: definition.default,
            testValue: testParams?.[name],
            exampleValue: Array.isArray(definition.examples) ? definition.examples[0] : definition.example,
            description: definition.description || ''
        }, false);
    }
    updateDatabaseParamEmptyState();
    syncDatabaseParamHiddenFields(false);
}

function addDatabaseParamRow(param = {}, sync = true) {
    const rows = document.getElementById('databaseParamRows');
    const empty = document.getElementById('databaseParamEmptyRow');
    if (!rows) {
        return;
    }
    const dynamic = Boolean(param.dynamic || (param.defaultSource && param.defaultSource !== 'user_input'));
    const tr = document.createElement('tr');
    tr.dataset.databaseParamRow = 'true';
    tr.innerHTML = `
        <td><input class="form-control form-control-sm api-param-name" data-database-param-name value="${escapeAttribute(param.name || '')}" placeholder="customer_id"></td>
        <td>
            <select class="form-select form-select-sm" data-database-param-type>
                ${databaseParamTypeOptions(param.type || 'string')}
            </select>
        </td>
        <td class="api-param-required-cell"><input class="form-check-input" type="checkbox" data-database-param-required ${param.required ? 'checked' : ''}></td>
        <td>
            <select class="form-select form-select-sm database-param-source" data-database-param-source>
                ${databaseParamSourceOptions(param.defaultSource || (dynamic ? 'trade_date' : 'user_input'))}
            </select>
        </td>
        <td><input class="form-control form-control-sm" data-database-param-test value="${escapeAttribute(formatParamValue(param.testValue))}" ${dynamic ? 'disabled' : ''}></td>
        <td><input class="form-control form-control-sm" data-database-param-example value="${escapeAttribute(formatParamValue(param.exampleValue))}"></td>
        <td><input class="form-control form-control-sm api-param-description" data-database-param-description value="${escapeAttribute(param.description || '')}" placeholder="参数业务含义"></td>
        <td class="text-end"><button class="btn btn-outline-danger btn-sm api-param-remove" type="button" data-database-param-remove aria-label="删除参数">×</button></td>
    `;
    rows.insertBefore(tr, empty || null);
    updateDatabaseParamEmptyState();
    if (sync) {
        syncDatabaseParamHiddenFields(false);
    }
}

function readDatabaseInputSchema() {
    const rows = [...document.querySelectorAll('[data-database-param-row]')];
    if (!rows.length && !document.getElementById('databaseParamRows')) {
        return normalizeInputSchema(readJsonObject('databaseInputSchemaJson'));
    }
    const schema = emptyParameterSchema();
    const names = new Set();
    for (const row of rows) {
        const name = databaseParamFieldValue(row, 'name');
        if (!name) {
            continue;
        }
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
            throw new Error(`查询参数名 ${name} 不合法，只能使用字母、数字和下划线，且不能以数字开头`);
        }
        if (names.has(name)) {
            throw new Error(`查询参数名 ${name} 重复`);
        }
        names.add(name);
        const type = databaseParamFieldValue(row, 'type') || 'string';
        const defaultSource = databaseParamFieldValue(row, 'source') || 'user_input';
        const exampleValue = databaseParamFieldValue(row, 'example');
        const description = databaseParamFieldValue(row, 'description');
        const definition = { type };
        if (description) {
            definition.description = description;
        }
        if (defaultSource && defaultSource !== 'user_input') {
            definition.defaultSource = defaultSource;
        }
        if (exampleValue !== '') {
            definition.examples = [coerceParamValue(exampleValue, type)];
        }
        schema.properties[name] = definition;
        if (row.querySelector('[data-database-param-required]')?.checked) {
            schema.required.push(name);
        }
    }
    setValue('databaseInputSchemaJson', JSON.stringify(schema, null, 2));
    return schema;
}

function readDatabaseTestParams() {
    const params = {};
    const rows = [...document.querySelectorAll('[data-database-param-row]')];
    if (!rows.length && !document.getElementById('databaseParamRows')) {
        return readJsonObject('databaseParamsJson');
    }
    for (const row of rows) {
        const name = databaseParamFieldValue(row, 'name');
        const source = databaseParamFieldValue(row, 'source') || 'user_input';
        const valueText = databaseParamFieldValue(row, 'test');
        if (!name || source !== 'user_input' || valueText === '') {
            continue;
        }
        params[name] = coerceParamValue(valueText, databaseParamFieldValue(row, 'type') || 'string');
    }
    setValue('databaseParamsJson', JSON.stringify(params, null, 2));
    return params;
}

function syncDatabaseParamHiddenFields(strict = true) {
    try {
        readDatabaseInputSchema();
        readDatabaseTestParams();
    } catch (error) {
        if (strict) {
            throw error;
        }
    }
}

function updateDatabaseParamEmptyState() {
    const empty = document.getElementById('databaseParamEmptyRow');
    const hasRows = Boolean(document.querySelector('[data-database-param-row]'));
    empty?.classList.toggle('d-none', hasRows);
}

function updateDatabaseParamSourceState(row) {
    if (!row) {
        return;
    }
    const source = databaseParamFieldValue(row, 'source') || 'user_input';
    const testInput = row.querySelector('[data-database-param-test]');
    if (testInput) {
        testInput.disabled = source !== 'user_input';
        if (source !== 'user_input') {
            testInput.value = '';
        }
    }
}

function databaseParamFieldValue(row, name) {
    return row.querySelector(`[data-database-param-${name}]`)?.value?.trim() || '';
}

function databaseParamTypeOptions(selectedType) {
    return ['string', 'number', 'integer', 'boolean', 'object', 'array']
        .map(type => `<option value="${type}" ${type === selectedType ? 'selected' : ''}>${databaseParamTypeLabel(type)}</option>`)
        .join('');
}

function databaseParamSourceOptions(selectedSource) {
    const source = selectedSource || 'user_input';
    const known = new Set(DATABASE_PARAM_SOURCE_OPTIONS.map(([value]) => value));
    const options = [...DATABASE_PARAM_SOURCE_OPTIONS];
    if (source === 'natural_date') {
        options.push(['natural_date', '当天自然日 natural_date（兼容）']);
    }
    if (source && !known.has(source)) {
        options.push([source, `${source}（自定义）`]);
    }
    return options
        .map(([value, label]) => `<option value="${escapeAttribute(value)}" ${value === source ? 'selected' : ''}>${escapeHtml(label)}</option>`)
        .join('');
}

function databaseParamTypeLabel(type) {
    return {
        string: '文本',
        number: '数字',
        integer: '整数',
        boolean: '布尔',
        object: '对象',
        array: '数组'
    }[type] || type;
}

function coerceParamValue(text, type) {
    if (type === 'number' || type === 'integer') {
        const number = Number(text);
        if (!Number.isNaN(number)) {
            return type === 'integer' ? Math.trunc(number) : number;
        }
    }
    if (type === 'boolean') {
        return text === 'true' || text === '1' || text === 'yes' || text === '是';
    }
    if (type === 'object' || type === 'array') {
        try {
            return JSON.parse(text);
        } catch (error) {
            return text;
        }
    }
    return text;
}

function formatParamValue(value) {
    if (value == null) {
        return '';
    }
    if (typeof value === 'object') {
        return JSON.stringify(value);
    }
    return String(value);
}

function escapeAttribute(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('"', '&quot;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;');
}

function renderDatabaseQueryStructuredPreview(rawTemplate) {
    const wrapper = document.getElementById('databaseQueryStructuredPreviewWrap');
    const container = document.getElementById('databaseQueryStructuredPreview');
    if (!wrapper || !container) {
        return;
    }
    const parsed = tryParseJsonObject(rawTemplate);
    const steps = Array.isArray(parsed?.steps) ? parsed.steps : [];
    if (!steps.length) {
        wrapper.classList.add('d-none');
        container.innerHTML = '';
        return;
    }
    wrapper.classList.remove('d-none');
    const sortedSteps = [...steps].sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
    container.innerHTML = `
        <div class="template-steps-summary">
            <span class="badge text-bg-primary">${escapeHtml(parsed.templateType || 'DATABASE_QUERY')}</span>
            <strong>${escapeHtml(parsed.templateName || parsed.name || parsed.templateCode || '数据库查询模板')}</strong>
            <span class="text-secondary">共 ${sortedSteps.length} 个步骤</span>
            ${parsed.executionMode ? `<span class="text-secondary">${escapeHtml(parsed.executionMode)}</span>` : ''}
            ${parsed.continueOnError !== undefined ? `<span class="text-secondary">失败后${parsed.continueOnError ? '继续' : '停止'}</span>` : ''}
        </div>
        <div class="template-steps-list">
            ${sortedSteps.map((step, index) => databaseQueryStepPreviewItem(step, index)).join('')}
        </div>
    `;
}

function databaseQueryStepPreviewItem(step, index) {
    const order = step.order ?? index + 1;
    const code = step.stepCode || step.code || `STEP_${order}`;
    const name = step.stepName || step.name || `Step ${order}`;
    const type = step.stepType || step.type || 'SQL';
    const command = step.command || step.sql || step.shell || '';
    const required = step.required === true;
    return `
        <section class="template-step-preview">
            <div class="template-step-preview-head">
                <div>
                    <span class="template-step-order">${escapeHtml(order)}</span>
                    <strong>${escapeHtml(name)}</strong>
                    <code>${escapeHtml(code)}</code>
                </div>
                <div class="template-step-preview-meta">
                    <span class="badge text-bg-light">${escapeHtml(type)}</span>
                    <span class="badge ${required ? 'text-bg-success' : 'text-bg-secondary'}">${required ? '必选' : '可选'}</span>
                    ${step.timeoutSeconds ? `<span class="badge text-bg-light">${escapeHtml(step.timeoutSeconds)}s</span>` : ''}
                </div>
            </div>
            ${step.analysisHint ? `<div class="template-step-hint">${escapeHtml(step.analysisHint)}</div>` : ''}
            <pre class="template-step-command"><code>${escapeHtml(command)}</code></pre>
        </section>
    `;
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

function renderTradingCalendarConfig() {
    renderTradingCalendarDatasourceOptions(tradingCalendarConfig?.datasourceId || '');
    setValue('tradingCalendarDatasourceSelect', tradingCalendarConfig?.datasourceId || '');
    setValue('tradingCalendarSqlInput', tradingCalendarConfig?.sqlTemplate || tradingCalendarConfig?.defaultSqlTemplate || 'select ZRR,JYR from dsc_cfg.t_xtjyr order by ZRR');
    updateTradingCalendarFunctionTestState();
}

function renderTradingCalendarDatasourceOptions(selected = '') {
    const select = document.getElementById('tradingCalendarDatasourceSelect');
    if (!select) {
        return;
    }
    const assets = sqlAssets
        .filter(asset => asset.enabled || asset.id === selected)
        .sort((a, b) => String(a.name || a.toolName || '').localeCompare(String(b.name || b.toolName || '')));
    select.innerHTML = [
        '<option value="">请选择交易日数据库资产</option>',
        ...assets.map(asset => `
            <option value="${escapeHtml(asset.id)}" ${asset.id === selected ? 'selected' : ''}>
                ${escapeHtml(asset.toolName || asset.name || asset.id)} / ${escapeHtml(asset.environment || 'DEV')}
            </option>
        `)
    ].join('');
}

function readTradingCalendarConfigForm() {
    return {
        enabled: true,
        datasourceId: value('tradingCalendarDatasourceSelect') || null,
        sqlTemplate: value('tradingCalendarSqlInput') || 'select ZRR,JYR from dsc_cfg.t_xtjyr order by ZRR'
    };
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
        notify('参数添加失败', error.message || '请检查参数配置。');
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
        notify('参数添加失败', error.message || '请检查参数配置。');
    }
}

function appendDatabasePreviewParams(items) {
    const params = readDatabaseTestParams();
    const schema = readDatabaseInputSchema();
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

    renderDatabaseParamEditor(schema, params);
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
