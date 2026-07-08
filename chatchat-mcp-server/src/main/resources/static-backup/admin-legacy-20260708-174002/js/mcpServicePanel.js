import {
    deleteMcpService,
    generateMcpToken,
    listMcpServices,
    regenerateMcpToken,
    saveMcpService,
    setMcpEnabled
} from './mcpServices.js';
import {
    hideMcpServiceModal,
    notify,
    renderMcpServices,
    showMcpServiceModal
} from './ui.js';

let onError = error => console.error(error);
let mcpServices = [];
let selectedMcpId = '';
let mcpServiceSearchTerm = '';
let mcpServicePage = 1;

const SERVICE_PAGE_SIZE = 12;

export function bindMcpServicePanel(options = {}) {
    onError = options.onError || onError;
    document.getElementById('mcpServiceForm').addEventListener('submit', handleMcpSave);
    document.getElementById('resetMcpFormBtn').addEventListener('click', resetMcpForm);
    document.getElementById('mcpServiceSearchInput').addEventListener('input', handleMcpServiceSearch);
    document.getElementById('mcpServicePrevPageBtn').addEventListener('click', () => changeMcpServicePage(-1));
    document.getElementById('mcpServiceNextPageBtn').addEventListener('click', () => changeMcpServicePage(1));
    document.getElementById('generateMcpTokenBtn').addEventListener('click', handleGenerateMcpToken);
    document.getElementById('regenSavedMcpTokenBtn').addEventListener('click', handleRegenerateSavedMcpToken);
}

export async function loadMcpServicePanel() {
    await loadMcpServices();
}

export function resetMcpServicePanel() {
    resetMcpForm();
}
async function loadMcpServices() {
    try {
        mcpServices = await listMcpServices();
        if (selectedMcpId && !mcpServices.some(service => service.id === selectedMcpId)) selectedMcpId = '';
        renderMcpServiceCards();
    } catch (error) {
        onError(error);
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

export function openNewMcpService() {
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
        onError(error);
    }
}

async function handleGenerateMcpToken() {
    try {
        const result = await generateMcpToken();
        setValue('mcpServiceToken', result.token);
        notify('Token 已生成', '请将该 Token 配置到接入服务中。');
    } catch (error) {
        onError(error);
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
        onError(error);
    }
}

async function toggleMcpService(service) {
    try {
        await setMcpEnabled(service.id, !service.enabled);
        notify('更新成功', `${service.name} 已${service.enabled ? '停用' : '启用'}。`);
        await loadMcpServices();
    } catch (error) {
        onError(error);
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
        onError(error);
    }
}

function readMcpForm() {
    const id = value('mcpServiceId');
    const existing = id ? mcpServices.find(service => service.id === id) || {} : {};
    return {
        id,
        name: value('mcpName'),
        endpoint: value('mcpEndpoint'),
        serviceToken: value('mcpServiceToken'),
        serviceType: value('mcpServiceType'),
        permissionGroup: value('mcpPermissionGroup'),
        environment: existing.environment,
        routingLabelsJson: existing.routingLabelsJson,
        routingLabels: existing.routingLabels,
        capabilitiesJson: existing.capabilitiesJson,
        capabilities: existing.capabilities,
        enabled: value('mcpEnabled') === 'true',
        status: value('mcpStatus')
    };
}

function value(id) {
    return document.getElementById(id).value.trim();
}

function setValue(id, nextValue) {
    document.getElementById(id).value = nextValue ?? '';
}

function changeMcpServicePage(delta) {
    mcpServicePage = clampPage(mcpServicePage + delta, filterMcpServices().length, SERVICE_PAGE_SIZE);
    renderMcpServiceCards();
}

function paginate(items, page, pageSize) {
    const start = (page - 1) * pageSize;
    return (items || []).slice(start, start + pageSize);
}

function clampPage(page, itemCount, pageSize) {
    const total = Math.max(1, Math.ceil(itemCount / pageSize));
    return Math.max(1, Math.min(total, page));
}
