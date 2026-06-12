import { API_BASE } from './config.js';
import { apiFetch } from './http.js';

const BASE_URL = `${API_BASE}/notifications`;

export function listNotificationChannels() {
    return apiFetch(BASE_URL);
}

export function saveNotificationChannel(channel) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(channel.id)}`, {
        method: 'PUT',
        body: JSON.stringify(toPayload(channel))
    });
}

export function setNotificationEnabled(id, enabled) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/enabled?enabled=${enabled}`, { method: 'POST' });
}

export function setNotificationRuntimeAction(id, runtimeAction) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/runtime-action?runtimeAction=${encodeURIComponent(runtimeAction)}`, {
        method: 'POST'
    });
}

export function testNotificationChannel(id, payload) {
    return apiFetch(`${BASE_URL}/${encodeURIComponent(id)}/test`, {
        method: 'POST',
        body: JSON.stringify(payload || {})
    });
}

export function refreshNotificationTools() {
    return apiFetch(`${BASE_URL}/refresh`, { method: 'POST' });
}

function toPayload(channel) {
    return {
        channel: channel.channel,
        toolName: channel.toolName,
        title: channel.title,
        description: channel.description,
        enabled: channel.enabled,
        runtimeAction: channel.runtimeAction,
        deliveryMode: channel.deliveryMode,
        method: channel.method,
        endpointUrl: channel.endpointUrl,
        headers: channel.headers,
        bodyTemplate: channel.bodyTemplate,
        secret: channel.secret,
        defaultReceiver: channel.defaultReceiver,
        ccReceiver: channel.ccReceiver,
        smtpHost: channel.smtpHost,
        smtpPort: channel.smtpPort,
        smtpUsername: channel.smtpUsername,
        smtpPassword: channel.smtpPassword,
        smtpFrom: channel.smtpFrom,
        smtpAuthEnabled: channel.smtpAuthEnabled,
        smtpStarttlsEnabled: channel.smtpStarttlsEnabled,
        smtpSslEnabled: channel.smtpSslEnabled,
        smtpSslTrust: channel.smtpSslTrust,
        smsAccount: channel.smsAccount,
        smsToken: channel.smsToken,
        smsPlainPassword: channel.smsPlainPassword,
        smsMd5Password: channel.smsMd5Password,
        smsPasswordMd5: channel.smsPasswordMd5,
        smsReturnType: channel.smsReturnType,
        smsExtendCode: channel.smsExtendCode,
        timeoutMs: channel.timeoutMs,
        maxRetries: channel.maxRetries
    };
}
