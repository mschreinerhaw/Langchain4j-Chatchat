import {
    deleteCommandTemplate,
    deleteHttpAsset,
    deleteSqlAsset,
    deleteSqlTemplate,
    deleteSshAsset,
    listCommandTemplates,
    listHttpAssets,
    listSqlAssets,
    listSqlTemplates,
    listSshAssets,
    rebuildAssetIndex,
    rebuildTemplateIndex,
    refreshOpsTools,
    refreshSqlTools,
    saveCommandTemplate,
    saveHttpAsset,
    saveSqlAsset,
    saveSqlTemplate,
    saveSshAsset,
    testHttpAsset,
    testSqlAsset,
    testSshAsset
} from './assetCenter.js';
import {
    hideCommandTemplateModal,
    hideHttpAssetModal,
    hideSqlAssetModal,
    hideSshAssetModal,
    notify,
    showCommandTemplateModal,
    showHttpAssetModal,
    showResult,
    showSqlAssetModal,
    showSshAssetModal
} from './ui.js';

let onError = error => console.error(error);
let sshAssets = [];
let sqlAssets = [];
let httpAssets = [];
let commandTemplates = [];
let sqlTemplates = [];
let selectedSshAssetId = '';
let selectedSqlAssetId = '';
let selectedHttpAssetId = '';
let selectedCommandTemplateId = '';
let editingTemplateScope = 'ssh';
let activeAssetTab = 'ssh';
let sshAssetSearchTerm = '';
let sshAssetTemplateSearchTerm = '';
let sshAssetTemplatePage = 1;
let sshAssetEnvironmentFilter = '';
let sshAssetStatusFilter = '';
let sshAssetCategoryFilter = '';
let sshAssetPage = 1;
let sqlAssetSearchTerm = '';
let sqlAssetTemplateSearchTerm = '';
let sqlAssetTemplatePage = 1;
let sqlAssetEnvironmentFilter = '';
let sqlAssetStatusFilter = '';
let sqlAssetCategoryFilter = '';
let sqlAssetPage = 1;
let httpAssetSearchTerm = '';
let httpAssetEnvironmentFilter = '';
let httpAssetStatusFilter = '';
let httpAssetMethodFilter = '';
let httpAssetCategoryFilter = '';
let httpAssetPage = 1;
let commandTemplateSearchTerm = '';
let commandTemplateTypeFilter = '';
let commandTemplateCategoryFilter = '';
let commandTemplatePage = 1;

const ASSET_PAGE_SIZE = 12;
const COMMAND_TEMPLATE_PAGE_SIZE = 12;
const GOVERNANCE_MASK_FIELDS = ['phone', 'id_card', 'account_no'];
const DEFAULT_SSH_COMMAND_TEMPLATE_CODES = [];

export function bindAssetCenterPanel(options = {}) {
    onError = options.onError || onError;
    document.getElementById('newSshAssetBtn').addEventListener('click', openNewSshAsset);
    document.getElementById('newSqlAssetBtn').addEventListener('click', openNewSqlAsset);
    bindOptional('newHttpAssetBtn', 'click', openNewHttpAsset);
    bindOptional('newCommandTemplateBtn', 'click', openNewCommandTemplate);
    bindOptional('newSqlTemplateBtn', 'click', openNewSqlTemplate);
    bindOptional('newHttpTemplateBtn', 'click', openNewHttpTemplate);
    document.getElementById('sshAssetForm').addEventListener('submit', handleSshAssetSave);
    document.getElementById('sqlAssetForm').addEventListener('submit', handleSqlAssetSave);
    bindOptional('httpAssetForm', 'submit', handleHttpAssetSave);
    bindOptional('commandTemplateForm', 'submit', handleCommandTemplateSave);
    document.getElementById('testSshAssetBtn').addEventListener('click', handleSshAssetTest);
    document.getElementById('testSqlAssetBtn').addEventListener('click', handleSqlAssetTest);
    bindOptional('testHttpAssetBtn', 'click', handleHttpAssetTest);
    document.getElementById('refreshOpsAssetToolsBtn').addEventListener('click', handleOpsAssetRefresh);
    document.getElementById('refreshSqlAssetToolsBtn').addEventListener('click', handleSqlAssetRefresh);
    bindOptional('rebuildAssetIndexBtn', 'click', handleAssetIndexRebuild);
    bindOptional('rebuildTemplateIndexBtn', 'click', handleTemplateIndexRebuild);
    document.querySelectorAll('[data-asset-tab]').forEach(button => {
        button.addEventListener('click', () => switchAssetTab(button.dataset.assetTab));
    });
    document.getElementById('sshAssetSearchInput').addEventListener('input', handleSshAssetFilter);
    document.getElementById('sshAssetEnvironmentFilter').addEventListener('change', handleSshAssetFilter);
    document.getElementById('sshAssetStatusFilter').addEventListener('change', handleSshAssetFilter);
    document.getElementById('sshAssetCategoryFilter').addEventListener('change', handleSshAssetFilter);
    bindOptional('sshAssetPrevPageBtn', 'click', () => changeAssetPage('ssh', -1));
    bindOptional('sshAssetNextPageBtn', 'click', () => changeAssetPage('ssh', 1));
    bindOptional('sshAssetAllowedCommandsJson', 'input', syncSshCommandTemplateSelectionFromJson);
    bindOptional('sshAssetTemplateSearchInput', 'input', handleSshAssetTemplateSearch);
    bindOptional('sshAssetTemplatePrevPageBtn', 'click', () => changeTemplatePickerPage('ssh', -1));
    bindOptional('sshAssetTemplateNextPageBtn', 'click', () => changeTemplatePickerPage('ssh', 1));
    bindOptional('sshAssetTemplateSelectVisibleBtn', 'click', () => setVisibleSshTemplatesChecked(true));
    bindOptional('sshAssetTemplateClearVisibleBtn', 'click', () => setVisibleSshTemplatesChecked(false));
    document.getElementById('sqlAssetSearchInput').addEventListener('input', handleSqlAssetFilter);
    document.getElementById('sqlAssetEnvironmentFilter').addEventListener('change', handleSqlAssetFilter);
    document.getElementById('sqlAssetStatusFilter').addEventListener('change', handleSqlAssetFilter);
    document.getElementById('sqlAssetCategoryFilter').addEventListener('change', handleSqlAssetFilter);
    bindOptional('sqlAssetPrevPageBtn', 'click', () => changeAssetPage('sql', -1));
    bindOptional('sqlAssetNextPageBtn', 'click', () => changeAssetPage('sql', 1));
    bindOptional('sqlAssetAllowedTemplatesJson', 'input', syncSqlTemplateSelectionFromJson);
    bindOptional('sqlAssetTemplateSearchInput', 'input', handleSqlAssetTemplateSearch);
    bindOptional('sqlAssetDatabaseType', 'change', renderSqlTemplateOptions);
    bindOptional('sqlAssetTemplatePrevPageBtn', 'click', () => changeTemplatePickerPage('sql', -1));
    bindOptional('sqlAssetTemplateNextPageBtn', 'click', () => changeTemplatePickerPage('sql', 1));
    bindOptional('sqlAssetTemplateSelectVisibleBtn', 'click', () => setVisibleSqlTemplatesChecked(true));
    bindOptional('sqlAssetTemplateClearVisibleBtn', 'click', () => setVisibleSqlTemplatesChecked(false));
    bindOptional('httpAssetSearchInput', 'input', handleHttpAssetFilter);
    bindOptional('httpAssetEnvironmentFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetStatusFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetMethodFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetCategoryFilter', 'change', handleHttpAssetFilter);
    bindOptional('httpAssetPrevPageBtn', 'click', () => changeAssetPage('http', -1));
    bindOptional('httpAssetNextPageBtn', 'click', () => changeAssetPage('http', 1));
    bindOptional('commandTemplateSearchInput', 'input', handleCommandTemplateFilter);
    bindOptional('commandTemplateTypeFilter', 'change', handleCommandTemplateFilter);
    bindOptional('commandTemplateCategoryFilter', 'change', handleCommandTemplateFilter);
    bindOptional('commandTemplatePrevPageBtn', 'click', () => changeCommandTemplatePage(-1));
    bindOptional('commandTemplateNextPageBtn', 'click', () => changeCommandTemplatePage(1));
}

export async function loadAssetCenterPanel() {
    await loadAssets();
}
async function loadAssets() {
    try {
        [sshAssets, sqlAssets, httpAssets, commandTemplates, sqlTemplates] = await Promise.all([
            listSshAssets(),
            listSqlAssets(),
            listHttpAssets(),
            listCommandTemplates(),
            listSqlTemplates()
        ]);
        if (selectedSshAssetId && !sshAssets.some(asset => asset.id === selectedSshAssetId)) selectedSshAssetId = '';
        if (selectedSqlAssetId && !sqlAssets.some(asset => asset.id === selectedSqlAssetId)) selectedSqlAssetId = '';
        if (selectedHttpAssetId && !httpAssets.some(asset => asset.id === selectedHttpAssetId)) selectedHttpAssetId = '';
        if (selectedCommandTemplateId && !templateCatalogRows().some(template => template.catalogId === selectedCommandTemplateId)) selectedCommandTemplateId = '';
        renderAssetCenter();
    } catch (error) {
        onError(error);
    }
}

function renderAssetCenter() {
    renderAssetTabs();
    renderSshAssets();
    renderSqlAssets();
    renderHttpAssets();
    renderCommandTemplates();
}

function renderAssetTabs() {
    document.getElementById('sshAssetTabBtn').classList.toggle('active', activeAssetTab === 'ssh');
    document.getElementById('sqlAssetTabBtn').classList.toggle('active', activeAssetTab === 'sql');
    document.getElementById('httpAssetTabBtn')?.classList.toggle('active', activeAssetTab === 'http');
    document.getElementById('commandTemplateTabBtn')?.classList.toggle('active', activeAssetTab === 'template');
    document.getElementById('sshAssetPane').classList.toggle('d-none', activeAssetTab !== 'ssh');
    document.getElementById('sqlAssetPane').classList.toggle('d-none', activeAssetTab !== 'sql');
    document.getElementById('httpAssetPane')?.classList.toggle('d-none', activeAssetTab !== 'http');
    document.getElementById('commandTemplatePane')?.classList.toggle('d-none', activeAssetTab !== 'template');
}

function switchAssetTab(tab) {
    activeAssetTab = ['ssh', 'sql', 'http', 'template'].includes(tab) ? tab : 'ssh';
    renderAssetCenter();
}

function handleSshAssetFilter() {
    sshAssetSearchTerm = value('sshAssetSearchInput');
    sshAssetEnvironmentFilter = value('sshAssetEnvironmentFilter');
    sshAssetStatusFilter = value('sshAssetStatusFilter');
    sshAssetCategoryFilter = value('sshAssetCategoryFilter');
    sshAssetPage = 1;
    renderSshAssets();
}

function handleSqlAssetFilter() {
    sqlAssetSearchTerm = value('sqlAssetSearchInput');
    sqlAssetEnvironmentFilter = value('sqlAssetEnvironmentFilter');
    sqlAssetStatusFilter = value('sqlAssetStatusFilter');
    sqlAssetCategoryFilter = value('sqlAssetCategoryFilter');
    sqlAssetPage = 1;
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
    httpAssetPage = 1;
    renderHttpAssets();
}

function handleCommandTemplateFilter() {
    commandTemplateSearchTerm = value('commandTemplateSearchInput');
    commandTemplateTypeFilter = value('commandTemplateTypeFilter');
    commandTemplateCategoryFilter = value('commandTemplateCategoryFilter');
    commandTemplatePage = 1;
    renderCommandTemplates();
}

function handleSshAssetTemplateSearch() {
    sshAssetTemplateSearchTerm = value('sshAssetTemplateSearchInput');
    sshAssetTemplatePage = 1;
    renderSshCommandTemplateOptions();
}

function handleSqlAssetTemplateSearch() {
    sqlAssetTemplateSearchTerm = value('sqlAssetTemplateSearchInput');
    sqlAssetTemplatePage = 1;
    renderSqlTemplateOptions();
}

function renderSshAssets() {
    document.getElementById('sshAssetCount').textContent = sshAssets.length;
    const filteredAssets = filterSshAssets();
    document.getElementById('sshAssetFilteredCount').textContent = filteredAssets.length;
    const visibleAssets = pageAssetItems(filteredAssets, 'ssh');
    updateAssetPager('ssh', filteredAssets.length);
    const list = document.getElementById('sshAssetList');
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的服务器资产。</div>';
        return;
    }
    for (const asset of visibleAssets) {
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
                <button class="btn btn-outline-secondary" data-action="test">测试</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectSshAsset(asset));
        item.querySelector('[data-action="test"]').addEventListener('click', () => testSshAssetFromCard(asset));
        item.querySelector('[data-action="delete"]').addEventListener('click', () => removeSshAsset(asset));
        list.appendChild(item);
    }
}

function renderSqlAssets() {
    document.getElementById('sqlAssetCount').textContent = sqlAssets.length;
    const filteredAssets = filterSqlAssets();
    document.getElementById('sqlAssetFilteredCount').textContent = filteredAssets.length;
    const visibleAssets = pageAssetItems(filteredAssets, 'sql');
    updateAssetPager('sql', filteredAssets.length);
    const list = document.getElementById('sqlAssetList');
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的数据库资产。</div>';
        return;
    }
    for (const asset of visibleAssets) {
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
                <span class="badge text-bg-light">最大 ${escapeHtml(asset.defaultMaxRows || 1000)} 行</span>
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">编辑</button>
                <button class="btn btn-outline-secondary" data-action="test">测试</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectSqlAsset(asset));
        item.querySelector('[data-action="test"]').addEventListener('click', () => testSqlAssetFromCard(asset));
        item.querySelector('[data-action="delete"]').addEventListener('click', () => removeSqlAsset(asset));
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
    const visibleAssets = pageAssetItems(filteredAssets, 'http');
    updateAssetPager('http', filteredAssets.length);
    list.innerHTML = '';
    if (!filteredAssets.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">暂无匹配的 HTTP 请求资产。</div>';
        return;
    }
    for (const asset of visibleAssets) {
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
                <button class="btn btn-outline-secondary" data-action="test">测试</button>
                <button class="btn btn-outline-danger" data-action="delete">删除</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectHttpAsset(asset));
        item.querySelector('[data-action="test"]').addEventListener('click', () => testHttpAssetFromCard(asset));
        item.querySelector('[data-action="delete"]').addEventListener('click', () => removeHttpAsset(asset));
        list.appendChild(item);
    }
}

function pageAssetItems(items, type) {
    const page = normalizeAssetPage(type, items.length);
    const start = (page - 1) * ASSET_PAGE_SIZE;
    return items.slice(start, start + ASSET_PAGE_SIZE);
}

function normalizeAssetPage(type, totalItems) {
    const totalPages = Math.max(1, Math.ceil(totalItems / ASSET_PAGE_SIZE));
    const current = Math.max(1, Math.min(totalPages, getAssetPage(type)));
    setAssetPage(type, current);
    return current;
}

function updateAssetPager(type, totalItems) {
    const page = getAssetPage(type);
    const totalPages = Math.max(1, Math.ceil(totalItems / ASSET_PAGE_SIZE));
    const text = document.getElementById(`${type}AssetPageText`);
    const prev = document.getElementById(`${type}AssetPrevPageBtn`);
    const next = document.getElementById(`${type}AssetNextPageBtn`);
    if (text) {
        text.textContent = totalItems ? `第 ${page} / ${totalPages} 页（共 ${totalItems} 条）` : '第 0 / 0 页（共 0 条）';
    }
    if (prev) {
        prev.disabled = page <= 1 || totalItems === 0;
    }
    if (next) {
        next.disabled = page >= totalPages || totalItems === 0;
    }
}

function changeAssetPage(type, delta) {
    const totalItems = assetFilteredCount(type);
    const totalPages = Math.max(1, Math.ceil(totalItems / ASSET_PAGE_SIZE));
    setAssetPage(type, Math.max(1, Math.min(totalPages, getAssetPage(type) + delta)));
    if (type === 'ssh') {
        renderSshAssets();
    } else if (type === 'sql') {
        renderSqlAssets();
    } else if (type === 'http') {
        renderHttpAssets();
    }
}

function assetFilteredCount(type) {
    if (type === 'ssh') {
        return filterSshAssets().length;
    }
    if (type === 'sql') {
        return filterSqlAssets().length;
    }
    if (type === 'http') {
        return filterHttpAssets().length;
    }
    return 0;
}

function getAssetPage(type) {
    if (type === 'ssh') {
        return sshAssetPage;
    }
    if (type === 'sql') {
        return sqlAssetPage;
    }
    if (type === 'http') {
        return httpAssetPage;
    }
    return 1;
}

function setAssetPage(type, page) {
    if (type === 'ssh') {
        sshAssetPage = page;
    } else if (type === 'sql') {
        sqlAssetPage = page;
    } else if (type === 'http') {
        httpAssetPage = page;
    }
}

function renderCommandTemplates() {
    const count = document.getElementById('commandTemplateCount');
    const filteredCount = document.getElementById('commandTemplateFilteredCount');
    const list = document.getElementById('commandTemplateList');
    if (!count || !filteredCount || !list) {
        return;
    }
    const catalog = templateCatalogRows();
    syncCommandTemplateCategoryFilterOptions(catalog);
    count.textContent = catalog.length;
    const filteredTemplates = filterCommandTemplates();
    filteredCount.textContent = filteredTemplates.length;
    const totalPages = Math.max(1, Math.ceil(filteredTemplates.length / COMMAND_TEMPLATE_PAGE_SIZE));
    if (commandTemplatePage > totalPages) {
        commandTemplatePage = totalPages;
    }
    if (commandTemplatePage < 1) {
        commandTemplatePage = 1;
    }
    const pageStart = (commandTemplatePage - 1) * COMMAND_TEMPLATE_PAGE_SIZE;
    const visibleTemplates = filteredTemplates.slice(pageStart, pageStart + COMMAND_TEMPLATE_PAGE_SIZE);
    updateCommandTemplatePager(totalPages, filteredTemplates.length);
    list.innerHTML = '';
    if (!filteredTemplates.length) {
        list.innerHTML = '<div class="api-empty text-secondary small">No matching execution templates.</div>';
        return;
    }
    for (const template of visibleTemplates) {
        const item = document.createElement('article');
        item.className = `service-card api-card command-template-card ${template.catalogId === selectedCommandTemplateId ? 'active' : ''}`;
        const code = String(template.code || '').trim();
        const signals = parseJsonArrayValue(template.intentSignalsJson)
            .map(signal => String(signal || '').trim())
            .filter(Boolean)
            .slice(0, 4);
        item.innerHTML = `
            <div class="api-card-main">
                <h3>${escapeHtml(template.title || code)}</h3>
                <p>${escapeHtml(template.description || '')}</p>
            </div>
            <div class="service-meta">
                <span class="badge text-bg-light">${escapeHtml(code)}</span>
                <span class="badge text-bg-primary">${escapeHtml(template.scopeLabel)}</span>
                <span class="badge ${template.enabled ? 'text-bg-success' : 'text-bg-secondary'}">${template.enabled ? 'Enabled' : 'Disabled'}</span>
                <span class="badge ${template.riskLevel === 'LOW' ? 'text-bg-success' : 'text-bg-warning'}">${escapeHtml(template.riskLevel || 'LOW')}</span>
                <span class="badge text-bg-info">${escapeHtml(template.category || 'system_diagnostic')}</span>
                ${template.scope === 'sql' ? `<span class="badge text-bg-light">${escapeHtml(template.databaseType || 'generic')}</span>` : ''}
                ${template.scope === 'sql' && template.datasourceId ? '<span class="badge text-bg-secondary">bound asset</span>' : ''}
                ${signals.map(signal => `<span class="badge text-bg-light">${escapeHtml(signal)}</span>`).join('')}
            </div>
            <div class="btn-group btn-group-sm mt-3" role="group">
                <button class="btn btn-outline-secondary" data-action="edit">Edit</button>
                <button class="btn btn-outline-danger" data-action="delete">Delete</button>
            </div>
        `;
        item.querySelector('[data-action="edit"]').addEventListener('click', () => selectTemplateCatalogRow(template));
        item.querySelector('[data-action="delete"]').addEventListener('click', () => removeTemplateCatalogRow(template));
        list.appendChild(item);
    }
}

function updateCommandTemplatePager(totalPages, totalItems) {
    const text = document.getElementById('commandTemplatePageText');
    const prev = document.getElementById('commandTemplatePrevPageBtn');
    const next = document.getElementById('commandTemplateNextPageBtn');
    if (text) {
        text.textContent = totalItems
            ? `第 ${commandTemplatePage} / ${totalPages} 页（共 ${totalItems} 条）`
            : '第 0 / 0 页（共 0 条）';
    }
    if (prev) {
        prev.disabled = commandTemplatePage <= 1 || totalItems === 0;
    }
    if (next) {
        next.disabled = commandTemplatePage >= totalPages || totalItems === 0;
    }
}

function changeCommandTemplatePage(delta) {
    const totalPages = Math.max(1, Math.ceil(filterCommandTemplates().length / COMMAND_TEMPLATE_PAGE_SIZE));
    commandTemplatePage = Math.max(1, Math.min(totalPages, commandTemplatePage + delta));
    renderCommandTemplates();
}

function templateCatalogRows() {
    return [
        ...commandTemplates.map(template => ({
            ...template,
            scope: 'ssh',
            scopeLabel: 'SSH Command',
            catalogId: `ssh:${template.id}`
        })),
        ...sqlTemplates.map(template => ({
            ...template,
            scope: 'sql',
            scopeLabel: 'SQL Ops Query',
            catalogId: `sql:${template.id}`,
            commandTemplate: template.sqlTemplate
        })),
        ...httpAssets.map(asset => ({
            ...asset,
            scope: 'http',
            scopeLabel: 'HTTP Asset',
            catalogId: `http:${asset.id}`,
            code: asset.toolName || asset.name,
            riskLevel: String(asset.method || 'GET').toUpperCase() === 'GET' ? 'LOW' : 'MEDIUM',
            intentSignalsJson: JSON.stringify([asset.toolName, asset.name, asset.category, asset.tags].filter(Boolean)),
            commandTemplate: ''
        }))
    ];
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
            asset.databaseType,
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

function filterCommandTemplates() {
    const keyword = commandTemplateSearchTerm.trim().toLowerCase();
    return templateCatalogRows().filter(template => {
        if (commandTemplateTypeFilter && template.scope !== commandTemplateTypeFilter) {
            return false;
        }
        const category = String(template.category || '').trim();
        if (commandTemplateCategoryFilter && category !== commandTemplateCategoryFilter) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        const searchable = [
            template.code,
            template.title,
            template.description,
            template.category,
            template.riskLevel,
            template.scopeLabel,
            template.databaseType,
            template.intentSignalsJson
        ].join(' ').toLowerCase();
        return searchable.includes(keyword);
    });
}

function syncCommandTemplateCategoryFilterOptions(catalog) {
    const select = document.getElementById('commandTemplateCategoryFilter');
    if (!select) {
        return;
    }
    const selected = commandTemplateCategoryFilter || select.value || '';
    const categories = [...new Set((catalog || [])
        .map(template => String(template.category || '').trim())
        .filter(Boolean))]
        .sort((a, b) => a.localeCompare(b));
    select.innerHTML = [
        '<option value="">鍏ㄩ儴鍒嗙被</option>',
        ...categories.map(category => `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`)
    ].join('');
    if (selected && categories.includes(selected)) {
        select.value = selected;
    } else {
        select.value = '';
        commandTemplateCategoryFilter = '';
    }
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

function openNewCommandTemplate() {
    editingTemplateScope = 'ssh';
    fillCommandTemplateForm(null);
    showCommandTemplateModal();
}

function openNewSqlTemplate() {
    editingTemplateScope = 'sql';
    fillCommandTemplateForm(null);
    showCommandTemplateModal();
}

function openNewHttpTemplate() {
    activeAssetTab = 'template';
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

function selectTemplateCatalogRow(template) {
    selectedCommandTemplateId = template.catalogId;
    if (template.scope === 'http') {
        selectHttpAsset(template);
        return;
    }
    editingTemplateScope = template.scope;
    fillCommandTemplateForm(template);
    renderCommandTemplates();
    showCommandTemplateModal();
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
        onError(error);
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
        onError(error);
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
        onError(error);
    }
}

async function handleCommandTemplateSave(event) {
    event.preventDefault();
    try {
        const payload = readCommandTemplateForm();
        const saved = editingTemplateScope === 'sql'
            ? await saveSqlTemplate({
                id: payload.id,
                code: payload.code,
                title: payload.title,
                description: payload.description,
                sqlTemplate: payload.commandTemplate,
                parameterSchemaJson: payload.parameterSchemaJson,
                riskLevel: payload.riskLevel,
                category: payload.category,
                databaseType: payload.databaseType,
                datasourceId: payload.datasourceId,
                routingLabelsJson: payload.routingLabelsJson,
                intentSignalsJson: payload.intentSignalsJson,
                enabled: payload.enabled
            })
            : await saveCommandTemplate(payload);
        selectedCommandTemplateId = `${editingTemplateScope}:${saved.id}`;
        notify('Template saved', `${saved.code} is available through template_query metadata.`);
        hideCommandTemplateModal();
        await loadAssets();
    } catch (error) {
        onError(error);
    }
}

async function handleSshAssetTest() {
    const form = document.getElementById('sshAssetForm');
    if (!form.reportValidity()) {
        return;
    }
    try {
        const result = await testSshAsset(readSshAssetForm());
        notify(result.success ? '测试成功' : '测试失败', result.success ? 'SSH 连接认证通过。' : (result.errorMessage || 'SSH 连接失败。'));
        showResult(result, {
            title: `${value('sshAssetToolName') || value('sshAssetName') || '服务器资产'} 测试结果`,
            subtitle: '使用当前表单内容测试 SSH 连接与认证，不保存配置'
        });
    } catch (error) {
        onError(error);
    }
}

async function handleSqlAssetTest() {
    const form = document.getElementById('sqlAssetForm');
    if (!form.reportValidity()) {
        return;
    }
    try {
        const result = await testSqlAsset(readSqlAssetForm());
        notify(result.success ? '测试成功' : '测试失败', result.success ? '数据库连接有效。' : (result.errorMessage || '数据库连接失败。'));
        showResult(result, {
            title: `${value('sqlAssetToolName') || value('sqlAssetName') || '数据库资产'} 测试结果`,
            subtitle: '使用当前表单内容测试 JDBC 连接，不保存配置'
        });
    } catch (error) {
        onError(error);
    }
}

async function handleHttpAssetTest() {
    const form = document.getElementById('httpAssetForm');
    if (!form?.reportValidity()) {
        return;
    }
    try {
        const result = await testHttpAsset(readHttpAssetForm());
        notify(result.success ? '测试成功' : '测试失败', result.success ? `HTTP ${result.statusCode} 请求成功。` : (result.errorMessage || `HTTP ${result.statusCode || '-'} 请求失败。`));
        showResult(result, {
            title: `${value('httpAssetToolName') || value('httpAssetName') || 'HTTP 请求资产'} 测试结果`,
            subtitle: '使用当前表单内容发起一次 HTTP 请求，不保存配置'
        });
    } catch (error) {
        onError(error);
    }
}

async function testSshAssetFromCard(asset) {
    try {
        const result = await testSshAsset(asset);
        notify(result.success ? '测试成功' : '测试失败', result.success ? 'SSH 连接认证通过。' : (result.errorMessage || 'SSH 连接失败。'));
        showResult(result, {
            title: `${asset.toolName || asset.name || '服务器资产'} 测试结果`,
            subtitle: '使用当前保存的资产配置测试 SSH 连接与认证'
        });
    } catch (error) {
        onError(error);
    }
}

async function testSqlAssetFromCard(asset) {
    try {
        const result = await testSqlAsset(asset);
        notify(result.success ? '测试成功' : '测试失败', result.success ? '数据库连接有效。' : (result.errorMessage || '数据库连接失败。'));
        showResult(result, {
            title: `${asset.toolName || asset.name || '数据库资产'} 测试结果`,
            subtitle: '使用当前保存的资产配置测试 JDBC 连接'
        });
    } catch (error) {
        onError(error);
    }
}

async function testHttpAssetFromCard(asset) {
    try {
        const result = await testHttpAsset(asset);
        notify(result.success ? '测试成功' : '测试失败', result.success ? `HTTP ${result.statusCode} 请求成功。` : (result.errorMessage || `HTTP ${result.statusCode || '-'} 请求失败。`));
        showResult(result, {
            title: `${asset.toolName || asset.name || 'HTTP 请求资产'} 测试结果`,
            subtitle: '使用当前保存的资产配置发起一次 HTTP 请求'
        });
    } catch (error) {
        onError(error);
    }
}

async function removeSshAsset(asset) {
    if (!window.confirm(`确定删除 ${asset.toolName || asset.name || '该服务器资产'} 吗？`)) return;
    try {
        await deleteSshAsset(asset.id);
        if (selectedSshAssetId === asset.id) {
            selectedSshAssetId = '';
            hideSshAssetModal();
        }
        notify('删除成功', `${asset.toolName || asset.name} 已删除。`);
        await loadAssets();
    } catch (error) {
        onError(error);
    }
}

async function removeSqlAsset(asset) {
    if (!window.confirm(`确定删除 ${asset.toolName || asset.name || '该数据库资产'} 吗？`)) return;
    try {
        await deleteSqlAsset(asset.id);
        if (selectedSqlAssetId === asset.id) {
            selectedSqlAssetId = '';
            hideSqlAssetModal();
        }
        notify('删除成功', `${asset.toolName || asset.name} 已删除。`);
        await loadAssets();
    } catch (error) {
        onError(error);
    }
}

async function removeHttpAsset(asset) {
    if (!window.confirm(`确定删除 ${asset.toolName || asset.name || '该 HTTP 请求资产'} 吗？`)) return;
    try {
        await deleteHttpAsset(asset.id);
        if (selectedHttpAssetId === asset.id) {
            selectedHttpAssetId = '';
            hideHttpAssetModal();
        }
        notify('删除成功', `${asset.toolName || asset.name} 已删除。`);
        await loadAssets();
    } catch (error) {
        onError(error);
    }
}

async function removeTemplateCatalogRow(template) {
    if (template.scope === 'http') {
        await removeHttpAsset(template);
        return;
    }
    if (!window.confirm(`Delete command template ${template.code || template.title || ''}?`)) return;
    try {
        if (template.scope === 'sql') {
            await deleteSqlTemplate(template.id);
        } else {
            await deleteCommandTemplate(template.id);
        }
        if (selectedCommandTemplateId === template.catalogId) {
            selectedCommandTemplateId = '';
            hideCommandTemplateModal();
        }
        notify('Template updated', `${template.code || template.title} has been deleted or disabled.`);
        await loadAssets();
    } catch (error) {
        onError(error);
    }
}

async function handleOpsAssetRefresh() {
    try {
        await refreshOpsTools();
        notify('刷新完成', 'SSH 与 HTTP 请求资产工具已重新发布。');
    } catch (error) {
        onError(error);
    }
}

async function handleSqlAssetRefresh() {
    try {
        await refreshSqlTools();
        notify('刷新完成', '数据库资产工具已重新发布。');
    } catch (error) {
        onError(error);
    }
}

async function handleAssetIndexRebuild() {
    try {
        const result = await rebuildAssetIndex();
        notify('Asset index rebuilt', `Indexed ${result.indexed ?? 0} assets.`);
    } catch (error) {
        onError(error);
    }
}

async function handleTemplateIndexRebuild() {
    try {
        await rebuildTemplateIndex();
        notify('Template index rebuilt', 'MCP template Lucene index has been rebuilt.');
    } catch (error) {
        onError(error);
    }
}

function readCommandTemplateForm() {
    const payload = {
        id: value('commandTemplateId'),
        code: value('commandTemplateCode'),
        title: value('commandTemplateTitle'),
        description: value('commandTemplateDescription'),
        commandTemplate: value('commandTemplateCommand'),
        parameterSchemaJson: stringifyJsonObject('commandTemplateParameterSchemaJson'),
        riskLevel: value('commandTemplateRiskLevel') || 'LOW',
        category: value('commandTemplateCategory') || 'system_diagnostic',
        intentSignalsJson: stringifyJsonArray('commandTemplateIntentSignalsJson'),
        enabled: document.getElementById('commandTemplateEnabled').value === 'true',
        runtimeAction: 'confirm_required'
    };
    if (editingTemplateScope === 'sql') {
        payload.databaseType = value('commandTemplateDatabaseType') || 'generic';
        payload.datasourceId = value('commandTemplateDatasourceId');
        payload.routingLabelsJson = stringifyJsonArray('commandTemplateRoutingLabelsJson');
    }
    return payload;
}

function fillCommandTemplateForm(template) {
    const scopeLabel = editingTemplateScope === 'sql' ? 'SQL Ops Template' : 'SSH Command Template';
    document.getElementById('commandTemplateFormTitle').textContent = template ? `Edit ${template.code || template.title}` : `New ${scopeLabel}`;
    const bodyLabel = document.getElementById('commandTemplateBodyLabel');
    if (bodyLabel) {
        bodyLabel.textContent = editingTemplateScope === 'sql'
            ? 'SQL ops query template'
            : 'Internal command / JSON steps';
    }
    setValue('commandTemplateId', template?.id || '');
    setValue('commandTemplateCode', template?.code || '');
    setValue('commandTemplateTitle', template?.title || '');
    setValue('commandTemplateDescription', template?.description || '');
    setValue('commandTemplateRiskLevel', template?.riskLevel || 'LOW');
    setValue('commandTemplateCategory', template?.category || (editingTemplateScope === 'sql' ? 'maintenance_performance' : 'system_diagnostic'));
    setValue('commandTemplateEnabled', String(template?.enabled ?? true));
    setValue('commandTemplateIntentSignalsJson', prettyJsonArray(template?.intentSignalsJson || '[]'));
    setValue('commandTemplateParameterSchemaJson', prettyJsonObject(template?.parameterSchemaJson, defaultTemplateParameterSchema()));
    setValue('commandTemplateCommand', template?.commandTemplate || template?.sqlTemplate || defaultTemplateBody());
    document.querySelectorAll('.sql-template-field').forEach(node => {
        node.classList.toggle('d-none', editingTemplateScope !== 'sql');
    });
    renderSqlTemplateDatasourceOptions(template);
    setValue('commandTemplateDatabaseType', template?.databaseType || 'generic');
    setValue('commandTemplateDatasourceId', template?.datasourceId || '');
    setValue('commandTemplateRoutingLabelsJson', prettyJsonArray(template?.routingLabelsJson || '[]'));
}

function renderSqlTemplateDatasourceOptions(template) {
    const select = document.getElementById('commandTemplateDatasourceId');
    if (!select) {
        return;
    }
    const selected = template?.datasourceId || '';
    const options = ['<option value="">Any compatible datasource</option>'];
    for (const asset of sqlAssets) {
        const label = [
            asset.toolName || asset.name,
            asset.environment || 'DEV',
            asset.databaseType || 'generic'
        ].filter(Boolean).join(' / ');
        options.push(`<option value="${escapeHtml(asset.id || '')}">${escapeHtml(label)}</option>`);
    }
    select.innerHTML = options.join('');
    select.value = selected;
}

function defaultTemplateParameterSchema() {
    if (editingTemplateScope === 'sql') {
        return {
            type: 'object',
            properties: {},
            required: []
        };
    }
    return {
        type: 'object',
        properties: {},
        required: []
    };
}

function defaultTemplateBody() {
    return editingTemplateScope === 'sql' ? 'SELECT 1' : '';
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
    sshAssetTemplateSearchTerm = '';
    sshAssetTemplatePage = 1;
    setValue('sshAssetTemplateSearchInput', '');
    setValue('sshAssetAllowedCommandsJson', prettyJsonArray(asset ? asset.allowedCommandsJson : defaultSshAllowedCommandsJson()));
    renderSshCommandTemplateOptions();
    setValue('sshAssetGovernanceJson', prettyJsonObject(asset?.governanceJson, defaultSshGovernance(asset)));
    setValue('sshAssetConnectTimeoutMs', String(asset?.connectTimeoutMs || 10000));
    setValue('sshAssetCommandTimeoutMs', String(asset?.commandTimeoutMs || 30000));
}

function defaultSshAllowedCommandsJson() {
    const enabledCodes = new Set(commandTemplates
        .filter(template => template.enabled !== false)
        .map(template => String(template.code || '').trim().toUpperCase())
        .filter(Boolean));
    const codes = DEFAULT_SSH_COMMAND_TEMPLATE_CODES.filter(code => enabledCodes.has(code));
    return JSON.stringify(codes, null, 2);
}

function renderSshCommandTemplateOptions() {
    const container = document.getElementById('sshAssetCommandTemplateList');
    if (!container) {
        return;
    }
    const selected = new Set(currentSshAllowedCommandCodes().map(code => code.toUpperCase()));
    const filteredTemplates = filteredSshAssetTemplates();
    sshAssetTemplatePage = clampPage(sshAssetTemplatePage, filteredTemplates.length, COMMAND_TEMPLATE_PAGE_SIZE);
    const visibleTemplates = paginate(filteredTemplates, sshAssetTemplatePage, COMMAND_TEMPLATE_PAGE_SIZE);
    if (!commandTemplates.length) {
        container.innerHTML = '<div class="text-secondary small">No command templates loaded.</div>';
        updateTemplateSelectionSummary('sshAssetTemplateSelectionSummary', 0, 0, 0);
        updateTemplatePickerPager('ssh', 0, 1);
        return;
    }
    if (!filteredTemplates.length) {
        container.innerHTML = '<div class="text-secondary small">No matching command templates.</div>';
        updateTemplateSelectionSummary('sshAssetTemplateSelectionSummary', selected.size, 0, 0);
        updateTemplatePickerPager('ssh', 0, 1);
        return;
    }
    container.innerHTML = templatePickerTable(visibleTemplates.map(template => {
        const code = String(template.code || '').trim().toUpperCase();
        const id = `sshTemplate_${code.replace(/[^A-Z0-9_-]/g, '_')}`;
        const checked = selected.has(code) ? 'checked' : '';
        const disabled = template.enabled === false ? 'disabled' : '';
        return templatePickerRow({
            id,
            code,
            checked,
            disabled,
            checkboxAttr: `data-ssh-template-code="${escapeHtml(code)}"`,
            title: template.title || code,
            category: template.category || 'system_diagnostic',
            meta: template.riskLevel || 'LOW',
            detail: template.commandTemplate || template.description || ''
        });
    }).join(''));
    container.querySelectorAll('[data-ssh-template-code]').forEach(input => {
        input.addEventListener('change', updateSshAllowedCommandsFromTemplateSelection);
    });
    updateTemplateSelectionSummary('sshAssetTemplateSelectionSummary', selected.size, visibleTemplates.length, filteredTemplates.length);
    updateTemplatePickerPager('ssh', filteredTemplates.length, sshAssetTemplatePage);
}

function filteredSshAssetTemplates() {
    return commandTemplates.filter(template => templateMatchesKeyword(template, sshAssetTemplateSearchTerm));
}

function templateMatchesKeyword(template, keyword) {
    const normalized = String(keyword || '').trim().toLowerCase();
    if (!normalized) {
        return true;
    }
    return [
        template?.code,
        template?.title,
        template?.description,
        template?.category,
        template?.riskLevel,
        template?.databaseType,
        template?.intentSignalsJson
    ].some(value => String(value || '').toLowerCase().includes(normalized));
}

function templatePickerTable(rows) {
    return `
        <table class="template-picker-table">
            <thead>
                <tr>
                    <th class="template-picker-check"></th>
                    <th>Code</th>
                    <th>Name</th>
                    <th>Category</th>
                    <th>Meta</th>
                    <th>Detail</th>
                </tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>
    `;
}

function templatePickerRow({ id, code, checked, disabled, checkboxAttr, title, category, meta, detail, rowClass = '' }) {
    return `
        <tr class="${rowClass}">
            <td class="template-picker-check"><input class="form-check-input" type="checkbox" id="${escapeHtml(id)}" ${checkboxAttr} ${checked} ${disabled}></td>
            <td class="template-picker-code" title="${escapeHtml(code)}">${escapeHtml(code)}</td>
            <td class="template-picker-title" title="${escapeHtml(title)}">${escapeHtml(title)}</td>
            <td class="template-picker-category" title="${escapeHtml(category)}">${escapeHtml(category)}</td>
            <td class="template-picker-meta" title="${escapeHtml(meta)}">${escapeHtml(meta)}</td>
            <td class="template-picker-detail" title="${escapeHtml(detail)}">${escapeHtml(detail)}</td>
        </tr>
    `;
}

function updateTemplateSelectionSummary(id, selectedCount, visibleCount, totalCount = visibleCount) {
    const node = document.getElementById(id);
    if (node) {
        node.textContent = totalCount === visibleCount
            ? `已选 ${selectedCount} / 当前页 ${visibleCount}`
            : `已选 ${selectedCount} / 当前页 ${visibleCount} / 共 ${totalCount}`;
    }
}

function updateTemplatePickerPager(type, totalItems, page) {
    const pageText = document.getElementById(`${type}AssetTemplatePageText`);
    const prev = document.getElementById(`${type}AssetTemplatePrevPageBtn`);
    const next = document.getElementById(`${type}AssetTemplateNextPageBtn`);
    const totalPages = Math.max(1, Math.ceil(totalItems / COMMAND_TEMPLATE_PAGE_SIZE));
    if (pageText) {
        pageText.textContent = totalItems ? `第 ${page} / ${totalPages} 页` : '第 0 / 0 页';
    }
    if (prev) {
        prev.disabled = page <= 1 || totalItems === 0;
    }
    if (next) {
        next.disabled = page >= totalPages || totalItems === 0;
    }
}

function changeTemplatePickerPage(type, delta) {
    if (type === 'ssh') {
        sshAssetTemplatePage = clampPage(sshAssetTemplatePage + delta, filteredSshAssetTemplates().length, COMMAND_TEMPLATE_PAGE_SIZE);
        renderSshCommandTemplateOptions();
        return;
    }
    if (type === 'sql') {
        sqlAssetTemplatePage = clampPage(sqlAssetTemplatePage + delta, filteredSqlAssetTemplates().length, COMMAND_TEMPLATE_PAGE_SIZE);
        renderSqlTemplateOptions();
    }
}

function updateSshAllowedCommandsFromTemplateSelection() {
    const knownCodes = new Set(commandTemplates
        .map(template => String(template.code || '').trim().toUpperCase())
        .filter(Boolean));
    const pageCodes = new Set([...document.querySelectorAll('[data-ssh-template-code]')]
        .map(input => String(input.dataset.sshTemplateCode || '').trim().toUpperCase())
        .filter(Boolean));
    const currentCodes = currentSshAllowedCommandCodes()
        .map(code => String(code || '').trim().toUpperCase())
        .filter(Boolean);
    const customCodes = currentCodes.filter(code => !knownCodes.has(code));
    const retainedKnownCodes = currentCodes.filter(code => knownCodes.has(code) && !pageCodes.has(code));
    const checkedCodes = [...document.querySelectorAll('[data-ssh-template-code]:checked')]
        .map(input => input.dataset.sshTemplateCode)
        .filter(Boolean);
    const nextCodes = [...new Set([...customCodes, ...retainedKnownCodes, ...checkedCodes].map(code => code.toUpperCase()))];
    setValue('sshAssetAllowedCommandsJson', JSON.stringify(nextCodes, null, 2));
    updateTemplateSelectionSummary(
        'sshAssetTemplateSelectionSummary',
        nextCodes.length,
        document.querySelectorAll('[data-ssh-template-code]').length,
        filteredSshAssetTemplates().length
    );
}

function setVisibleSshTemplatesChecked(checked) {
    document.querySelectorAll('[data-ssh-template-code]:not(:disabled)').forEach(input => {
        input.checked = checked;
    });
    updateSshAllowedCommandsFromTemplateSelection();
}

function syncSshCommandTemplateSelectionFromJson() {
    if (parseSshAllowedCommandCodes() !== null) {
        renderSshCommandTemplateOptions();
    }
}

function currentSshAllowedCommandCodes() {
    return parseSshAllowedCommandCodes() || [];
}

function parseSshAllowedCommandCodes() {
    const text = value('sshAssetAllowedCommandsJson');
    if (!text) {
        return [];
    }
    try {
        const parsed = JSON.parse(text);
        return Array.isArray(parsed)
            ? parsed.map(code => String(code || '').trim()).filter(Boolean)
            : [];
    } catch (error) {
        return null;
    }
}

function defaultSqlAllowedTemplatesJson(asset = null) {
    const databaseType = String(asset?.databaseType || value('sqlAssetDatabaseType') || 'generic').toLowerCase();
    const datasourceId = asset?.id || value('sqlAssetId');
    const codes = sqlTemplates
        .filter(template => template.enabled !== false)
        .filter(template => sqlTemplateCompatibleWithAsset(template, databaseType, datasourceId))
        .map(template => String(template.code || '').trim().toUpperCase())
        .filter(Boolean);
    return JSON.stringify([...new Set(codes)], null, 2);
}

function renderSqlTemplateOptions() {
    const container = document.getElementById('sqlAssetTemplateList');
    if (!container) {
        return;
    }
    const selected = new Set(currentSqlAllowedTemplateCodes().map(code => code.toUpperCase()));
    const databaseType = String(value('sqlAssetDatabaseType') || 'generic').toLowerCase();
    const datasourceId = value('sqlAssetId');
    const filteredTemplates = filteredSqlAssetTemplates();
    sqlAssetTemplatePage = clampPage(sqlAssetTemplatePage, filteredTemplates.length, COMMAND_TEMPLATE_PAGE_SIZE);
    const visibleTemplates = paginate(filteredTemplates, sqlAssetTemplatePage, COMMAND_TEMPLATE_PAGE_SIZE);
    if (!sqlTemplates.length) {
        container.innerHTML = '<div class="text-secondary small">No SQL templates loaded.</div>';
        updateTemplateSelectionSummary('sqlAssetTemplateSelectionSummary', 0, 0, 0);
        updateTemplatePickerPager('sql', 0, 1);
        return;
    }
    if (!filteredTemplates.length) {
        container.innerHTML = '<div class="text-secondary small">No matching SQL templates.</div>';
        updateTemplateSelectionSummary('sqlAssetTemplateSelectionSummary', selected.size, 0, 0);
        updateTemplatePickerPager('sql', 0, 1);
        return;
    }
    container.innerHTML = templatePickerTable(visibleTemplates.map(template => {
        const code = String(template.code || '').trim().toUpperCase();
        const id = `sqlTemplate_${code.replace(/[^A-Z0-9_-]/g, '_')}`;
        const compatible = sqlTemplateCompatibleWithAsset(template, databaseType, datasourceId);
        const checked = selected.has(code) ? 'checked' : '';
        const disabled = template.enabled === false || !compatible ? 'disabled' : '';
        const type = template.databaseType || 'generic';
        const bound = template.datasourceId ? 'bound asset' : 'any asset';
        return templatePickerRow({
            id,
            code,
            checked,
            disabled,
            rowClass: compatible ? '' : 'opacity-50',
            checkboxAttr: `data-sql-template-code="${escapeHtml(code)}"`,
            title: template.title || code,
            category: template.category || 'maintenance_performance',
            meta: `${type} / ${bound}`,
            detail: template.description || ''
        });
    }).join(''));
    container.querySelectorAll('[data-sql-template-code]').forEach(input => {
        input.addEventListener('change', updateSqlAllowedTemplatesFromTemplateSelection);
    });
    updateTemplateSelectionSummary('sqlAssetTemplateSelectionSummary', selected.size, visibleTemplates.length, filteredTemplates.length);
    updateTemplatePickerPager('sql', filteredTemplates.length, sqlAssetTemplatePage);
}

function filteredSqlAssetTemplates() {
    return sqlTemplates.filter(template => templateMatchesKeyword(template, sqlAssetTemplateSearchTerm));
}

function sqlTemplateCompatibleWithAsset(template, databaseType, datasourceId) {
    const templateType = String(template?.databaseType || 'generic').toLowerCase();
    const assetType = String(databaseType || 'generic').toLowerCase();
    if (templateType !== 'generic' && assetType !== 'generic' && templateType !== assetType) {
        return false;
    }
    if (template?.datasourceId && datasourceId && template.datasourceId !== datasourceId) {
        return false;
    }
    if (template?.datasourceId && !datasourceId) {
        return false;
    }
    return true;
}

function updateSqlAllowedTemplatesFromTemplateSelection() {
    const knownCodes = new Set(sqlTemplates
        .map(template => String(template.code || '').trim().toUpperCase())
        .filter(Boolean));
    const pageCodes = new Set([...document.querySelectorAll('[data-sql-template-code]')]
        .map(input => String(input.dataset.sqlTemplateCode || '').trim().toUpperCase())
        .filter(Boolean));
    const currentCodes = currentSqlAllowedTemplateCodes()
        .map(code => String(code || '').trim().toUpperCase())
        .filter(Boolean);
    const customCodes = currentCodes.filter(code => !knownCodes.has(code));
    const retainedKnownCodes = currentCodes.filter(code => knownCodes.has(code) && !pageCodes.has(code));
    const checkedCodes = [...document.querySelectorAll('[data-sql-template-code]:checked')]
        .map(input => input.dataset.sqlTemplateCode)
        .filter(Boolean);
    const nextCodes = [...new Set([...customCodes, ...retainedKnownCodes, ...checkedCodes].map(code => code.toUpperCase()))];
    setValue('sqlAssetAllowedTemplatesJson', JSON.stringify(nextCodes, null, 2));
    updateTemplateSelectionSummary(
        'sqlAssetTemplateSelectionSummary',
        nextCodes.length,
        document.querySelectorAll('[data-sql-template-code]').length,
        filteredSqlAssetTemplates().length
    );
}

function setVisibleSqlTemplatesChecked(checked) {
    document.querySelectorAll('[data-sql-template-code]:not(:disabled)').forEach(input => {
        input.checked = checked;
    });
    updateSqlAllowedTemplatesFromTemplateSelection();
}

function syncSqlTemplateSelectionFromJson() {
    if (parseSqlAllowedTemplateCodes() !== null) {
        renderSqlTemplateOptions();
    }
}

function currentSqlAllowedTemplateCodes() {
    return parseSqlAllowedTemplateCodes() || [];
}

function parseSqlAllowedTemplateCodes() {
    const text = value('sqlAssetAllowedTemplatesJson');
    if (!text) {
        return [];
    }
    try {
        const parsed = JSON.parse(text);
        return Array.isArray(parsed)
            ? parsed.map(code => String(code || '').trim()).filter(Boolean)
            : [];
    } catch (error) {
        return null;
    }
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
        databaseType: value('sqlAssetDatabaseType') || 'generic',
        username: value('sqlAssetUsername'),
        password: document.getElementById('sqlAssetPassword').value,
        enabled: document.getElementById('sqlAssetEnabled').value === 'true',
        environment: value('sqlAssetEnvironment'),
        defaultTimeoutSeconds: Number(value('sqlAssetDefaultTimeoutSeconds') || 30),
        defaultMaxRows: Number(value('sqlAssetDefaultMaxRows') || 1000),
        allowedTablesJson: stringifyJsonArray('sqlAssetAllowedTablesJson'),
        allowedTemplatesJson: stringifyJsonArray('sqlAssetAllowedTemplatesJson'),
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
    setValue('sqlAssetDatabaseType', asset?.databaseType || 'generic');
    setValue('sqlAssetUsername', asset?.username || '');
    document.getElementById('sqlAssetPassword').value = asset?.password || '';
    setValue('sqlAssetEnabled', String(asset?.enabled ?? false));
    setValue('sqlAssetEnvironment', asset?.environment || 'DEV');
    setValue('sqlAssetDefaultTimeoutSeconds', String(asset?.defaultTimeoutSeconds || 30));
    setValue('sqlAssetDefaultMaxRows', String(asset?.defaultMaxRows || 1000));
    setValue('sqlAssetAllowedTablesJson', prettyJsonArray(asset?.allowedTablesJson));
    sqlAssetTemplateSearchTerm = '';
    sqlAssetTemplatePage = 1;
    setValue('sqlAssetTemplateSearchInput', '');
    setValue('sqlAssetAllowedTemplatesJson', prettyJsonArray(asset ? asset.allowedTemplatesJson : defaultSqlAllowedTemplatesJson(asset)));
    renderSqlTemplateOptions();
    setValue('sqlAssetSensitiveTablesJson', prettyJsonArray(asset?.sensitiveTablesJson));
    setValue('sqlAssetSensitiveFieldsJson', prettyJsonArray(asset?.sensitiveFieldsJson));
    setValue('sqlAssetGovernanceJson', prettyJsonObject(asset?.governanceJson, defaultSqlDatasourceGovernance(asset)));
}

function readHttpAssetForm() {
    const id = value('httpAssetId');
    const existing = id ? httpAssets.find(asset => asset.id === id) || {} : {};
    return {
        id,
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
        routingLabelsJson: existing.routingLabelsJson,
        routingLabels: existing.routingLabels,
        capabilitiesJson: existing.capabilitiesJson,
        capabilities: existing.capabilities,
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

function parseJsonArrayValue(value) {
    if (!value) {
        return [];
    }
    try {
        const parsed = typeof value === 'string' ? JSON.parse(value) : value;
        return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
        return [];
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

function bindOptional(id, event, handler) {
    document.getElementById(id)?.addEventListener(event, handler);
}

function value(id) {
    const node = document.getElementById(id);
    return node?.type === 'checkbox' ? node.checked : (node?.value ?? '');
}

function setValue(id, nextValue) {
    const node = document.getElementById(id);
    if (!node) return;
    if (node.type === 'checkbox') node.checked = !!nextValue;
    else node.value = nextValue ?? '';
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function paginate(items, page, pageSize) {
    const start = (page - 1) * pageSize;
    return items.slice(start, start + pageSize);
}

function clampPage(page, itemCount, pageSize) {
    const total = Math.max(1, Math.ceil(itemCount / pageSize));
    return Math.max(1, Math.min(total, page));
}
