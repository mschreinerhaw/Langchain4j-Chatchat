import { MCP_ENDPOINT } from './config.js';
import { getToken, login, logout } from './auth.js';
import { UnauthorizedError } from './http.js';
import {
    deleteService,
    listServices,
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
import { listAuditLogs } from './auditLogs.js';
import { fillServiceForm, readServiceForm, readTestArgs, toggleMicroserviceFields } from './form.js';
import {
    hideLoginError,
    notify,
    renderAuditLogs,
    renderMcpServices,
    renderServices,
    showApp,
    showLogin,
    showLoginError,
    showResult,
    switchView
} from './ui.js';

let services = [];
let selectedId = '';
let mcpServices = [];
let selectedMcpId = '';

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('mcpEndpointText').textContent = MCP_ENDPOINT;
    bindEvents();
    if (getToken()) {
        enterApp();
    } else {
        showLogin();
    }
});

function bindEvents() {
    document.getElementById('loginForm').addEventListener('submit', handleLogin);
    document.getElementById('logoutBtn').addEventListener('click', handleLogout);
    document.getElementById('serviceForm').addEventListener('submit', handleSave);
    document.getElementById('newServiceBtn').addEventListener('click', resetForm);
    document.getElementById('resetFormBtn').addEventListener('click', resetForm);
    document.getElementById('microserviceMode').addEventListener('change', toggleMicroserviceFields);
    document.getElementById('testServiceBtn').addEventListener('click', handleTest);
    document.getElementById('refreshBtn').addEventListener('click', handleRefresh);
    document.getElementById('mcpServiceForm').addEventListener('submit', handleMcpSave);
    document.getElementById('newMcpServiceBtn').addEventListener('click', resetMcpForm);
    document.getElementById('resetMcpFormBtn').addEventListener('click', resetMcpForm);
    document.getElementById('generateMcpTokenBtn').addEventListener('click', handleGenerateMcpToken);
    document.getElementById('regenSavedMcpTokenBtn').addEventListener('click', handleRegenerateSavedMcpToken);
    document.getElementById('reloadAuditBtn').addEventListener('click', loadAuditLogs);
    document.querySelectorAll('.sidebar .nav-link').forEach(button => {
        button.addEventListener('click', () => handleViewSwitch(button.dataset.view));
    });
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
    if (view === 'auditLogs') await loadAuditLogs();
}

async function loadServices() {
    try {
        services = await listServices();
        if (selectedId && !services.some(service => service.id === selectedId)) selectedId = '';
        renderServices(services, selectedId, {
            edit: selectService,
            toggle: toggleService,
            delete: removeService
        });
    } catch (error) {
        handleError(error);
    }
}

function selectService(service) {
    selectedId = service.id;
    document.getElementById('formTitle').textContent = 'Edit API Service';
    fillServiceForm(service);
    renderServices(services, selectedId, {
        edit: selectService,
        toggle: toggleService,
        delete: removeService
    });
}

function resetForm() {
    selectedId = '';
    document.getElementById('formTitle').textContent = 'New API Service';
    fillServiceForm(null);
}

async function handleSave(event) {
    event.preventDefault();
    try {
        const saved = await saveService(readServiceForm());
        selectedId = saved.id;
        notify('Saved', `${saved.toolName} has been published to MCP tools.`);
        await loadServices();
        selectService(saved);
    } catch (error) {
        handleError(error);
    }
}

async function handleTest() {
    const id = document.getElementById('serviceId').value;
    if (!id) {
        notify('Cannot test', 'Save the API service first.');
        return;
    }
    try {
        const result = await testService(id, readTestArgs());
        showResult(result);
        await loadAuditLogs(false);
    } catch (error) {
        handleError(error);
    }
}

async function handleRefresh() {
    try {
        await refreshTools();
        notify('Refreshed', 'MCP tool list has been refreshed.');
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function toggleService(service) {
    try {
        await setEnabled(service.id, !service.enabled);
        notify('Updated', `${service.toolName} is now ${service.enabled ? 'disabled' : 'enabled'}.`);
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function removeService(service) {
    if (!window.confirm(`Delete ${service.toolName}?`)) return;
    try {
        await deleteService(service.id);
        notify('Deleted', `${service.toolName} has been removed.`);
        resetForm();
        await loadServices();
    } catch (error) {
        handleError(error);
    }
}

async function loadMcpServices() {
    try {
        mcpServices = await listMcpServices();
        if (selectedMcpId && !mcpServices.some(service => service.id === selectedMcpId)) selectedMcpId = '';
        renderMcpServices(mcpServices, selectedMcpId, {
            edit: selectMcpService,
            toggle: toggleMcpService,
            delete: removeMcpService
        });
    } catch (error) {
        handleError(error);
    }
}

function selectMcpService(service) {
    selectedMcpId = service.id;
    document.getElementById('mcpFormTitle').textContent = 'Edit MCP Service';
    setValue('mcpServiceId', service.id);
    setValue('mcpName', service.name);
    setValue('mcpEndpoint', service.endpoint);
    setValue('mcpServiceToken', service.serviceToken);
    setValue('mcpServiceType', service.serviceType || 'DATA');
    setValue('mcpPermissionGroup', service.permissionGroup || 'default');
    setValue('mcpEnabled', String(service.enabled));
    setValue('mcpStatus', service.status || 'ACTIVE');
    renderMcpServices(mcpServices, selectedMcpId, {
        edit: selectMcpService,
        toggle: toggleMcpService,
        delete: removeMcpService
    });
}

function resetMcpForm() {
    selectedMcpId = '';
    document.getElementById('mcpFormTitle').textContent = 'New MCP Service';
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
        notify('Saved', `${saved.name} has been registered.`);
        await loadMcpServices();
        selectMcpService(saved);
    } catch (error) {
        handleError(error);
    }
}

async function handleGenerateMcpToken() {
    try {
        const result = await generateMcpToken();
        setValue('mcpServiceToken', result.token);
        notify('Token generated', 'Copy this token into the service config.');
    } catch (error) {
        handleError(error);
    }
}

async function handleRegenerateSavedMcpToken() {
    const id = value('mcpServiceId');
    if (!id) {
        notify('Cannot regenerate', 'Save the MCP service first.');
        return;
    }
    try {
        const updated = await regenerateMcpToken(id);
        selectMcpService(updated);
        notify('Token regenerated', `${updated.name} now has a new service token.`);
    } catch (error) {
        handleError(error);
    }
}

async function toggleMcpService(service) {
    try {
        await setMcpEnabled(service.id, !service.enabled);
        notify('Updated', `${service.name} is now ${service.enabled ? 'disabled' : 'enabled'}.`);
        await loadMcpServices();
    } catch (error) {
        handleError(error);
    }
}

async function removeMcpService(service) {
    if (!window.confirm(`Delete ${service.name}?`)) return;
    try {
        await deleteMcpService(service.id);
        notify('Deleted', `${service.name} has been removed.`);
        resetMcpForm();
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
        const logs = await listAuditLogs();
        renderAuditLogs(logs);
        if (showNotice) notify('Loaded', 'Audit logs refreshed.');
    } catch (error) {
        handleError(error);
    }
}

function handleError(error) {
    if (error instanceof UnauthorizedError) {
        showLogin();
        notify('Login required', error.message);
        return;
    }
    notify('Operation failed', error.message || 'Unknown error');
}

function value(id) {
    return document.getElementById(id).value.trim();
}

function setValue(id, nextValue) {
    document.getElementById(id).value = nextValue ?? '';
}
