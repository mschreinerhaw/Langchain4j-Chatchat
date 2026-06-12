const PARTIALS = {
    login: '../partials/login.html',
    sidebar: '../partials/sidebar.html',
    topbar: '../partials/topbar.html',
    apiServices: '../partials/api-services.html',
    mcpServices: '../partials/mcp-services.html',
    assetCenter: '../partials/asset-center.html',
    databaseMcp: '../partials/database-mcp.html',
    notificationChannels: '../partials/notification-channels.html',
    auditLogs: '../partials/audit-logs.html',
    settings: '../partials/settings.html',
    feedback: '../partials/feedback.html'
};

export async function loadLayout() {
    const root = document.getElementById('adminRoot');
    const partials = await loadPartials(PARTIALS);
    root.innerHTML = `
        ${partials.login}
        <main id="appView" class="app-shell d-none">
            ${partials.sidebar}
            <section class="workspace">
                ${partials.topbar}
                ${partials.apiServices}
                ${partials.mcpServices}
                ${partials.assetCenter}
                ${partials.databaseMcp}
                ${partials.notificationChannels}
                ${partials.auditLogs}
                ${partials.settings}
            </section>
        </main>
        ${partials.feedback}
    `;
}

async function loadPartials(partialMap) {
    const entries = await Promise.all(
        Object.entries(partialMap).map(async ([name, path]) => [name, await loadPartial(path)])
    );
    return Object.fromEntries(entries);
}

async function loadPartial(path) {
    const url = new URL(path, import.meta.url);
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`页面片段加载失败：${url.pathname}`);
    }
    return response.text();
}
