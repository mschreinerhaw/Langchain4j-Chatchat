const form = document.querySelector('#licenseForm');
const message = document.querySelector('#message');
const button = document.querySelector('#submitButton');

const preview = {
  plan: document.querySelector('#previewPlan'),
  product: document.querySelector('#previewProduct'),
  licenseNo: document.querySelector('#previewLicenseNo'),
  moduleCount: document.querySelector('#previewModuleCount'),
  mac: document.querySelector('#previewMac'),
  modules: document.querySelector('#previewModules'),
  users: document.querySelector('#previewUsers'),
  agents: document.querySelector('#previewAgents'),
  expiry: document.querySelector('#previewExpiry')
};

const moduleLabels = {};
let availableModules = [];
let auditRecords = [];
let auditPage = { page: 0, size: 20, totalElements: 0, totalPages: 0, summary: {} };

const planCatalog = {
  standard: {
    label: '标准版', users: 25, agents: 5,
    modules: ['apiServices', 'mcpServices', 'templateQueryPublications', 'auditLogs']
  },
  professional: {
    label: '专业版', users: 100, agents: 20,
    modules: ['apiServices', 'mcpServices', 'templateQueryPublications', 'businessCategories',
      'databaseMcp', 'cacheSettings', 'notificationChannels', 'auditLogs', 'commandAuditLogs',
      'assetSsh', 'assetSql', 'assetHttp', 'assetJmx']
  },
  enterprise: { label: '企业版', users: 500, agents: 100, modules: '*' }
};

const dateText = value => {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};
const issued = new Date();
const expires = new Date();
expires.setFullYear(expires.getFullYear() + 1);
form.elements.issuedTime.value = dateText(issued);
form.elements.expireTime.value = dateText(expires);
form.elements.licenseNo.value = `LIC-${Date.now()}`;

function selectedValues(name) {
  return [...form.querySelectorAll(`input[name="${name}"]:checked`)].map(item => item.value);
}

function normalizeMac(value) {
  const hex = (value || '').replace(/^MAC[-:]?/i, '').replace(/[^0-9a-f]/gi, '').toUpperCase();
  return hex.length === 12 ? `MAC-${hex}` : value || '尚未填写';
}

function updatePreview() {
  const modules = selectedValues('modules');
  const edition = form.elements.edition.value || 'enterprise';
  const plan = planCatalog[edition] || planCatalog.enterprise;
  const moduleNames = modules.map(item => moduleLabels[item] || item);
  preview.plan.textContent = `LiveMCP ${plan.label}`;
  preview.product.textContent = `${edition.charAt(0).toUpperCase() + edition.slice(1)} Commercial License`;
  preview.licenseNo.textContent = form.elements.licenseNo.value || '-';
  preview.moduleCount.textContent = modules.length;
  preview.mac.textContent = normalizeMac(form.elements.serverId.value.trim());
  preview.modules.textContent = moduleNames.length
    ? `${moduleNames.slice(0, 2).join('、')}${moduleNames.length > 2 ? ` 等 ${moduleNames.length} 项` : ''}`
    : '尚未选择';
  preview.users.textContent = form.elements.maxUsers.value || '-';
  preview.agents.textContent = form.elements.maxAgents.value || '-';
  preview.expiry.textContent = form.elements.expireTime.value || '-';
}

const editionText = value => ({ standard: 'Standard 标准版', professional: 'Professional 专业版', enterprise: 'Enterprise 企业版' }[value] || value || '-');
const formatTimestamp = value => value ? new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
}).format(new Date(value)) : '-';

function setView(view) {
  const auditMode = view === 'audit';
  document.querySelector('#issuerView').hidden = auditMode;
  document.querySelector('#auditView').hidden = !auditMode;
  document.querySelector('#showIssuer').classList.toggle('active', !auditMode);
  document.querySelector('#showAudit').classList.toggle('active', auditMode);
  if (auditMode) loadAudits();
}

function applyPlan(edition, updateEntitlements = true) {
  const plan = planCatalog[edition];
  if (!plan) return;
  form.elements.maxUsers.value = plan.users;
  form.elements.maxAgents.value = plan.agents;
  if (updateEntitlements && availableModules.length) {
    const included = plan.modules === '*' ? null : new Set(plan.modules);
    form.querySelectorAll('input[name="modules"]').forEach(input => {
      input.checked = included === null || included.has(input.value);
    });
  }
  updatePreview();
}

function applyTerm(months) {
  const start = new Date(`${form.elements.issuedTime.value || dateText(new Date())}T00:00:00`);
  const expiry = new Date(start);
  expiry.setFullYear(start.getFullYear(), start.getMonth() + Number(months), start.getDate());
  expiry.setDate(expiry.getDate() - 1);
  form.elements.expireTime.value = dateText(expiry);
  document.querySelectorAll('.term-selector button').forEach(item => {
    item.classList.toggle('active', item.dataset.months === String(months));
  });
  updatePreview();
}

form.addEventListener('input', updatePreview);
form.addEventListener('change', updatePreview);
form.querySelectorAll('input[name="edition"]').forEach(input => {
  input.addEventListener('change', () => applyPlan(input.value));
});
document.querySelectorAll('.term-selector button').forEach(item => {
  item.addEventListener('click', () => applyTerm(item.dataset.months));
});
form.elements.issuedTime.addEventListener('change', () => {
  const active = document.querySelector('.term-selector button.active');
  if (active) applyTerm(active.dataset.months);
});
updatePreview();

async function loadMcpMenus() {
  const menuContainer = document.querySelector('#mcpMenuModules');
  const capabilityContainer = document.querySelector('#mcpCapabilityModules');
  try {
    const response = await fetch('/api/licenses/mcp-menus');
    const menus = await response.json();
    if (!response.ok) throw new Error(menus.message || '同步 MCP 菜单失败');
    menuContainer.replaceChildren();
    capabilityContainer.replaceChildren();
    availableModules = menus;
    const auditModule = document.querySelector('#auditModule');
    auditModule.querySelectorAll('option:not(:first-child)').forEach(option => option.remove());
    menus.forEach(menu => {
      moduleLabels[menu.key] = menu.label;
      const label = document.createElement('label');
      label.className = 'check-card';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.name = 'modules';
      input.value = menu.key;
      input.checked = false;
      const text = document.createElement('span');
      const title = document.createElement('b');
      title.textContent = menu.label;
      const detail = document.createElement('small');
      detail.textContent = menu.description || `授权模块 ID：${menu.key}`;
      text.append(title, detail);
      label.append(input, text);
      (menu.navigation === false ? capabilityContainer : menuContainer).append(label);
      const option = document.createElement('option');
      option.value = menu.key;
      option.textContent = menu.label;
      auditModule.append(option);
    });
    if (!menuContainer.children.length) menuContainer.textContent = '目标服务未发布管理菜单';
    if (!capabilityContainer.children.length) capabilityContainer.textContent = '目标服务未发布功能模块';
    applyPlan(form.elements.edition.value || 'enterprise');
  } catch (error) {
    menuContainer.textContent = error.message;
    capabilityContainer.textContent = error.message;
    message.style.color = '#d14956';
    message.textContent = error.message;
  }
}
loadMcpMenus();
applyTerm(12);

document.querySelector('#selectAllModules').addEventListener('click', () => {
  form.querySelectorAll('input[name="modules"]').forEach(input => { input.checked = true; });
  updatePreview();
});

document.querySelector('#clearAllModules').addEventListener('click', () => {
  form.querySelectorAll('input[name="modules"]').forEach(input => { input.checked = false; });
  updatePreview();
});

document.querySelector('#showIssuer').addEventListener('click', () => setView('issuer'));
document.querySelector('#showAudit').addEventListener('click', () => setView('audit'));
document.querySelector('#refreshAudits').addEventListener('click', loadAudits);
document.querySelector('#searchAudits').addEventListener('click', () => { auditPage.page = 0; loadAudits(); });
['auditStatus', 'auditEdition', 'auditModule', 'auditDateFrom', 'auditDateTo'].forEach(id => {
  document.querySelector(`#${id}`).addEventListener('change', () => { auditPage.page = 0; loadAudits(); });
});
document.querySelector('#auditKeyword').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); auditPage.page = 0; loadAudits(); }
});
document.querySelector('#auditPageSize').addEventListener('change', event => {
  auditPage.size = Number(event.target.value || 20);
  auditPage.page = 0;
  loadAudits();
});
document.querySelector('#auditPrevious').addEventListener('click', () => {
  if (auditPage.page > 0) { auditPage.page -= 1; loadAudits(); }
});
document.querySelector('#auditNext').addEventListener('click', () => {
  if (auditPage.page + 1 < auditPage.totalPages) { auditPage.page += 1; loadAudits(); }
});
document.querySelector('#closeAuditDetail').addEventListener('click', () => document.querySelector('#auditDetail').close());

async function loadAudits() {
  const rows = document.querySelector('#auditRows');
  rows.replaceChildren();
  const loading = document.createElement('tr');
  const cell = document.createElement('td');
  cell.colSpan = 8;
  cell.className = 'audit-loading';
  cell.textContent = '正在读取授权审计记录…';
  loading.append(cell);
  rows.append(loading);
  try {
    const params = new URLSearchParams({
      keyword: document.querySelector('#auditKeyword').value.trim(),
      status: document.querySelector('#auditStatus').value,
      edition: document.querySelector('#auditEdition').value,
      module: document.querySelector('#auditModule').value,
      page: String(auditPage.page),
      size: String(auditPage.size)
    });
    const dateFrom = document.querySelector('#auditDateFrom').value;
    const dateTo = document.querySelector('#auditDateTo').value;
    if (dateFrom) params.set('dateFrom', dateFrom);
    if (dateTo) params.set('dateTo', dateTo);
    const response = await fetch(`/api/licenses/audits?${params}`);
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '读取授权审计失败');
    auditRecords = Array.isArray(result.content) ? result.content : [];
    auditPage = {
      page: Number(result.page || 0), size: Number(result.size || 20),
      totalElements: Number(result.totalElements || 0), totalPages: Number(result.totalPages || 0),
      summary: result.summary || {}
    };
    renderAudits();
  } catch (error) {
    rows.replaceChildren();
    const failed = document.createElement('tr');
    const failedCell = document.createElement('td');
    failedCell.colSpan = 8;
    failedCell.className = 'audit-loading error';
    failedCell.textContent = error.message;
    failed.append(failedCell);
    rows.append(failed);
  }
}

function renderAudits() {
  const summary = auditPage.summary || {};
  document.querySelector('#auditTotal').textContent = Number(summary.total || 0);
  document.querySelector('#auditDelivered').textContent = Number(summary.delivered || 0);
  document.querySelector('#auditPending').textContent = Number(summary.pending || 0);
  document.querySelector('#auditDownloads').textContent = Number(summary.downloads || 0);

  const rows = document.querySelector('#auditRows');
  rows.replaceChildren();
  document.querySelector('#auditEmpty').hidden = auditRecords.length > 0;
  auditRecords.forEach(item => rows.append(auditRow(item)));
  const displayPages = Math.max(1, auditPage.totalPages);
  document.querySelector('#auditPageSummary').textContent = `共 ${auditPage.totalElements} 条，第 ${auditPage.page + 1} 页`;
  document.querySelector('#auditPageNumber').textContent = `第 ${auditPage.page + 1} / ${displayPages} 页`;
  document.querySelector('#auditPrevious').disabled = auditPage.page <= 0;
  document.querySelector('#auditNext').disabled = auditPage.page + 1 >= auditPage.totalPages;
}

function auditRow(item) {
  const row = document.createElement('tr');
  row.append(
    auditCell(item.licenseNo, item.documentSha256 ? item.documentSha256.slice(0, 12) + '…' : '-'),
    auditCell(editionText(item.edition), item.product || 'LiveMCP'),
    auditCell(item.customerCode || '未设置', item.serverId || '-'),
    auditCell(`${item.maxUsers ?? '-'} 用户`, `${item.maxAgents ?? '-'} Agent`),
    auditCell(item.expireDate || '-', `签发 ${item.issuedDate || '-'}`),
    auditCell(item.issuedBy || '-', formatTimestamp(item.issuedAt)),
    auditStatusCell(item),
    auditActionCell(item)
  );
  return row;
}

function auditCell(primary, secondary) {
  const cell = document.createElement('td');
  const strong = document.createElement('strong');
  strong.textContent = primary;
  const small = document.createElement('small');
  small.textContent = secondary;
  cell.append(strong, small);
  return cell;
}

function auditStatusCell(item) {
  const cell = document.createElement('td');
  const badge = document.createElement('span');
  badge.className = `audit-status ${item.status === 'DELIVERED' ? 'delivered' : 'issued'}`;
  badge.textContent = item.status === 'DELIVERED' ? '已交付' : '待交付';
  const small = document.createElement('small');
  small.textContent = `下载 ${item.downloadCount || 0} 次`;
  cell.append(badge, small);
  return cell;
}

function auditActionCell(item) {
  const cell = document.createElement('td');
  const action = document.createElement('button');
  action.type = 'button';
  action.className = 'audit-detail-button';
  action.textContent = '查看详情';
  action.addEventListener('click', () => openAuditDetail(item));
  cell.append(action);
  return cell;
}

function openAuditDetail(item) {
  document.querySelector('#detailLicenseNo').textContent = item.licenseNo || '-';
  document.querySelector('#detailStatus').textContent = item.status === 'DELIVERED' ? '已完成交付' : '已签发，待交付';
  document.querySelector('#detailIssuedAt').textContent = `签发于 ${formatTimestamp(item.issuedAt)}`;
  document.querySelector('#detailEdition').textContent = editionText(item.edition);
  document.querySelector('#detailCustomerCode').textContent = item.customerCode || '未设置';
  document.querySelector('#detailServerId').textContent = item.serverId || '-';
  document.querySelector('#detailQuota').textContent = `${item.maxUsers ?? '-'} 用户 / ${item.maxAgents ?? '-'} Agent`;
  document.querySelector('#detailTerm').textContent = `${item.issuedDate || '-'} 至 ${item.expireDate || '-'}`;
  document.querySelector('#detailOperator').textContent = item.issuedBy || '-';
  document.querySelector('#detailDownloads').textContent = `${item.downloadCount || 0} 次，最后下载 ${formatTimestamp(item.lastDownloadedAt)}`;
  document.querySelector('#detailKeyId').textContent = item.keyId || '-';
  document.querySelector('#detailHash').textContent = item.documentSha256 || '-';
  const modules = document.querySelector('#detailModules');
  modules.replaceChildren();
  (item.modules || []).forEach(key => {
    const tag = document.createElement('span');
    tag.textContent = moduleLabels[key] || key;
    modules.append(tag);
  });
  document.querySelector('#auditDetail').showModal();
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  message.textContent = '';
  if (!selectedValues('modules').length) {
    message.style.color = '#d14956';
    message.textContent = '请至少选择一项产品功能权益';
    document.querySelector('.permission-heading').scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }
  button.disabled = true;
  button.querySelector('span').textContent = '正在签发…';

  const data = new FormData(form);
  const payload = {
    licenseNo: data.get('licenseNo'),
    customer: null,
    customerCode: data.get('customerCode'),
    product: data.get('product'),
    edition: data.get('edition'),
    modules: data.getAll('modules'),
    maxUsers: Number(data.get('maxUsers')),
    maxAgents: Number(data.get('maxAgents')),
    serverId: data.get('serverId'),
    issuedTime: data.get('issuedTime'),
    expireTime: data.get('expireTime'),
    features: {}
  };

  try {
    const response = await fetch('/api/licenses/issue', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '生成授权失败');

    const bytes = Uint8Array.from(atob(result.contentBase64), char => char.charCodeAt(0));
    const url = URL.createObjectURL(new Blob([bytes], { type: result.contentType || 'application/zip' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = result.fileName || 'LiveMCP-license-package.zip';
    link.click();
    URL.revokeObjectURL(url);
    let auditUpdated = true;
    if (result.recordId) {
      try {
        const auditResponse = await fetch(`/api/licenses/audits/${encodeURIComponent(result.recordId)}/downloaded`, { method: 'POST' });
        auditUpdated = auditResponse.ok;
      } catch (error) {
        auditUpdated = false;
      }
    }
    message.style.color = '#15875a';
    message.textContent = auditUpdated ? '授权交付包已下载，内含 license.dat 与 license-public.pem' : '授权包已下载，但下载审计状态更新失败，请在审计页面核查';
  } catch (error) {
    message.style.color = '#d14956';
    message.textContent = error.message;
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = '签发并下载授权交付包';
  }
});
