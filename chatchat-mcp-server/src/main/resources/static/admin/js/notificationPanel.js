import {
    listNotificationChannels,
    refreshNotificationTools,
    saveNotificationChannel,
    setNotificationEnabled,
    setNotificationRuntimeAction,
    testNotificationChannel
} from './notificationChannels.js';
import {
    hideNotificationChannelModal,
    notify,
    renderNotificationChannels,
    showNotificationChannelModal,
    showResult
} from './ui.js';

let onError = error => console.error(error);
let notificationChannels = [];
let selectedNotificationChannelId = '';
let notificationSearchTerm = '';

export function bindNotificationPanel(options = {}) {
    onError = options.onError || onError;
    document.getElementById('notificationSearchInput').addEventListener('input', handleNotificationSearch);
    document.getElementById('newNotificationChannelBtn').addEventListener('click', handleNewNotificationChannel);
    document.getElementById('refreshNotificationToolsBtn').addEventListener('click', handleNotificationRefresh);
    document.getElementById('notificationChannelForm').addEventListener('submit', handleNotificationSave);
    document.getElementById('notificationTestBtn').addEventListener('click', handleNotificationTest);
    document.getElementById('notificationDeliveryMode').addEventListener('change', toggleNotificationDeliveryFields);
    document.getElementById('notificationChannel').addEventListener('change', handleNotificationChannelTypeChange);
}

export async function loadNotificationPanel() {
    await loadNotificationChannels();
}
async function loadNotificationChannels() {
    try {
        notificationChannels = await listNotificationChannels();
        if (selectedNotificationChannelId && !notificationChannels.some(channel => channel.id === selectedNotificationChannelId)) {
            selectedNotificationChannelId = '';
        }
        renderNotificationChannelCards();
    } catch (error) {
        onError(error);
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

function handleNewNotificationChannel() {
    selectedNotificationChannelId = '';
    fillNotificationChannelForm(newNotificationDraft());
    renderNotificationChannelCards();
    showNotificationChannelModal();
}

async function toggleNotificationChannel(channel) {
    try {
        await setNotificationEnabled(channel.id, !channel.enabled);
        notify('更新成功', `${channel.toolName} 已${channel.enabled ? '下线' : '启用'}。`);
        await loadNotificationChannels();
    } catch (error) {
        onError(error);
    }
}

async function toggleNotificationRuntimeAction(channel) {
    const nextAction = channel.runtimeAction === 'forbidden' ? 'confirm_required' : 'forbidden';
    try {
        await setNotificationRuntimeAction(channel.id, nextAction);
        notify('策略已更新', `${channel.toolName} 已切换为 ${nextAction}。`);
        await loadNotificationChannels();
    } catch (error) {
        onError(error);
    }
}

async function handleNotificationRefresh() {
    try {
        const result = await refreshNotificationTools();
        notify('刷新完成', result.refreshed ? '通知 MCP 工具已重新发布。' : '通知 MCP 工具刷新完成。');
        await loadNotificationChannels();
    } catch (error) {
        onError(error);
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
        onError(error);
    }
}

async function handleNotificationTest() {
    const id = value('notificationId');
    if (!id) {
        notify('无法测试', '请先保存通知工具。');
        return;
    }
    try {
        const result = await testNotificationChannel(id, readJsonObject('notificationTestPayloadJson'));
        showResult(result, {
            title: `${value('notificationToolName')} 测试结果`,
            subtitle: '通知发送结果会写入调用审计'
        });
    } catch (error) {
        onError(error);
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
    document.getElementById('notificationFormTitle').textContent = channel?.id ? `配置 ${channel.toolName}` : '新增通知工具';
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
        title: 'Agent 浠诲姟閫氱煡',
        content: '这是一条测试通知。',
        level: 'INFO',
        sourceTaskId: 'manual-test'
    }, null, 2));
    toggleNotificationDeliveryFields();
}

function handleNotificationChannelTypeChange() {
    const draft = readNotificationChannelForm();
    const defaults = notificationDefaultsFor(value('notificationChannel'));
    if (!value('notificationId') && (!draft.toolName || draft.toolName.startsWith('notify_'))) {
        setValue('notificationToolName', defaults.toolName);
    }
    if (!draft.title || draft.title === '通知工具') {
        setValue('notificationTitle', defaults.title);
    }
    if (!draft.deliveryMode) {
        setValue('notificationDeliveryMode', defaults.deliveryMode);
    }
    toggleNotificationDeliveryFields();
}

function newNotificationDraft() {
    const defaults = notificationDefaultsFor('EMAIL');
    return {
        id: '',
        channel: 'EMAIL',
        toolName: defaults.toolName,
        title: defaults.title,
        description: defaults.description,
        enabled: false,
        runtimeAction: 'confirm_required',
        deliveryMode: defaults.deliveryMode,
        method: 'POST',
        endpointUrl: '',
        headers: { 'Content-Type': 'application/json' },
        bodyTemplate: '',
        secret: '',
        defaultReceiver: 'ops@example.com',
        ccReceiver: '',
        timeoutMs: 10000,
        maxRetries: 1,
        defaultTestPayload: {
            receiver: 'ops@example.com',
            title: 'Agent 任务通知',
            content: '这是一条测试通知。',
            level: 'INFO',
            sourceTaskId: 'manual-test'
        }
    };
}

function notificationDefaultsFor(channel) {
    const suffix = Date.now().toString(36);
    if (channel === 'SMS') {
        return {
            toolName: `notify_sms_${suffix}`,
            title: '短信通知工具',
            description: '通过短信网关发送 Agent 分析结果或告警通知。',
            deliveryMode: 'HTTP'
        };
    }
    if (channel === 'WECHAT_WORK') {
        return {
            toolName: `notify_wechat_work_${suffix}`,
            title: '企业微信通知工具',
            description: '通过企业微信机器人或消息接口发送 Agent 告警通知。',
            deliveryMode: 'HTTP'
        };
    }
    if (channel === 'DINGTALK') {
        return {
            toolName: `notify_dingtalk_${suffix}`,
            title: '钉钉通知工具',
            description: '通过钉钉机器人或消息接口发送 Agent 告警通知。',
            deliveryMode: 'HTTP'
        };
    }
    return {
        toolName: `notify_email_${suffix}`,
        title: '邮件通知工具',
        description: '向指定邮箱发送 Agent 分析结果或告警通知。',
        deliveryMode: 'SMTP'
    };
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

function value(id) {
    return document.getElementById(id).value.trim();
}

function setValue(id, nextValue) {
    document.getElementById(id).value = nextValue ?? '';
}

function readJsonObject(id) {
    const raw = document.getElementById(id).value.trim();
    if (!raw) {
        return {};
    }
    try {
        return JSON.parse(raw);
    } catch (error) {
        throw new Error(`${id} JSON 格式不正确`);
    }
}

function notificationDefaultReceiverValue() {
    return value('notificationDeliveryMode') === 'SMTP'
        ? value('notificationEmailDefaultReceiver')
        : value('notificationDefaultReceiver');
}
