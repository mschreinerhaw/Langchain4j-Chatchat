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
  skills: document.querySelector('#previewSkills'),
  expiry: document.querySelector('#previewExpiry')
};

const moduleLabels = {};
let availableModules = [];
let auditRecords = [];
let auditPage = { page: 0, size: 20, totalElements: 0, totalPages: 0, summary: {} };

const moduleGroups = [
  {
    key: 'core', title: '核心接入与能力',
    description: '服务接入、模板发布、业务查询和知识能力',
    modules: new Set(['apiServices', 'mcpServices', 'templateQueryPublications', 'databaseMcp',
      'businessCategories', 'newsCollection', 'pythonManagement'])
  },
  {
    key: 'assets', title: '资产与数据能力',
    description: '主机、数据库、HTTP、JMX、索引和元数据治理',
    modules: new Set(['assetSsh', 'assetSql', 'assetHttp', 'assetJmx', 'assetSearchIndex', 'enterpriseMetadata'])
  },
  {
    key: 'governance', title: '治理、安全与运营',
    description: '权限、审计、通知和运行策略',
    modules: new Set(['authorizationManagement', 'auditLogs', 'commandAuditLogs',
      'notificationChannels', 'cacheSettings'])
  },
  {
    key: 'system', title: '系统配置',
    description: '用户、登录审计、执行目标及平台参数',
    modules: new Set(['settings'])
  }
];

const planCatalog = {
  standard: {
    label: '标准版', users: 25, agents: 5, skills: 5,
    modules: ['apiServices', 'mcpServices', 'templateQueryPublications', 'auditLogs']
  },
  professional: {
    label: '专业版', users: 100, agents: 20, skills: 20,
    modules: ['apiServices', 'mcpServices', 'templateQueryPublications', 'businessCategories',
      'databaseMcp', 'cacheSettings', 'notificationChannels', 'auditLogs', 'commandAuditLogs',
      'assetSsh', 'assetSql', 'assetHttp', 'assetJmx']
  },
  enterprise: { label: '企业版', users: 500, agents: 100, skills: 50, modules: '*' }
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
  preview.plan.textContent = `LingDong Nexus ${plan.label}`;
  preview.product.textContent = `${edition.charAt(0).toUpperCase() + edition.slice(1)} Commercial License`;
  preview.licenseNo.textContent = form.elements.licenseNo.value || '-';
  preview.moduleCount.textContent = modules.length;
  preview.mac.textContent = normalizeMac(form.elements.serverId.value.trim());
  preview.modules.textContent = moduleNames.length
    ? `${moduleNames.slice(0, 2).join('、')}${moduleNames.length > 2 ? ` 等 ${moduleNames.length} 项` : ''}`
    : '尚未选择';
  preview.users.textContent = form.elements.maxUsers.value || '-';
  preview.agents.textContent = form.elements.maxAgents.value || '-';
  preview.skills.textContent = form.elements.maxSkills.value || '-';
  preview.expiry.textContent = form.elements.expireTime.value || '-';
  updateModuleSelectionUi();
}

function groupForModule(module) {
  return moduleGroups.find(group => group.modules.has(module.key)) || {
    key: 'other', title: '其他功能', description: '由目标服务动态发布的扩展模块', modules: new Set()
  };
}

function updateModuleSelectionUi() {
  const inputs = [...form.querySelectorAll('input[name="modules"]')];
  const selected = inputs.filter(input => input.checked).length;
  const selectedCount = document.querySelector('#selectedModuleCount');
  const availableCount = document.querySelector('#availableModuleCount');
  if (selectedCount) selectedCount.textContent = selected;
  if (availableCount) availableCount.textContent = `共 ${inputs.length} 项`;
  document.querySelectorAll('.module-group').forEach(group => {
    const groupInputs = [...group.querySelectorAll('input[name="modules"]')];
    const groupSelected = groupInputs.filter(input => input.checked).length;
    const count = group.querySelector('.module-group-count');
    const button = group.querySelector('.module-group-action');
    if (count) count.textContent = `${groupSelected}/${groupInputs.length}`;
    if (button) button.textContent = groupInputs.length && groupSelected === groupInputs.length ? '取消本组' : '选择本组';
  });
}

function filterModules() {
  const keyword = String(document.querySelector('#moduleSearch')?.value || '').trim().toLowerCase();
  let visibleCount = 0;
  document.querySelectorAll('.module-card').forEach(card => {
    const visible = !keyword || card.dataset.search.includes(keyword);
    card.hidden = !visible;
    if (visible) visibleCount += 1;
  });
  document.querySelectorAll('.module-group').forEach(group => {
    group.hidden = !group.querySelector('.module-card:not([hidden])');
  });
  document.querySelector('#moduleSearchEmpty').hidden = visibleCount !== 0;
}

function renderModuleGroups(menus) {
  const container = document.querySelector('#moduleGroups');
  container.replaceChildren();
  const grouped = new Map();
  menus.forEach(module => {
    const group = groupForModule(module);
    if (!grouped.has(group.key)) grouped.set(group.key, { group, modules: [] });
    grouped.get(group.key).modules.push(module);
  });
  const ordered = [...moduleGroups.map(group => grouped.get(group.key)).filter(Boolean)];
  if (grouped.has('other')) ordered.push(grouped.get('other'));
  ordered.forEach(({ group, modules }) => {
    const section = document.createElement('section');
    section.className = 'module-group';
    section.dataset.group = group.key;
    const heading = document.createElement('div');
    heading.className = 'module-group-heading';
    const copy = document.createElement('div');
    const title = document.createElement('h3');
    title.textContent = group.title;
    const description = document.createElement('p');
    description.textContent = group.description;
    copy.append(title, description);
    const controls = document.createElement('div');
    controls.className = 'module-group-controls';
    const count = document.createElement('span');
    count.className = 'module-group-count';
    const action = document.createElement('button');
    action.type = 'button';
    action.className = 'module-group-action';
    action.addEventListener('click', () => {
      const inputs = [...section.querySelectorAll('input[name="modules"]')];
      const shouldSelect = inputs.some(input => !input.checked);
      inputs.forEach(input => { input.checked = shouldSelect; });
      updatePreview();
    });
    controls.append(count, action);
    heading.append(copy, controls);
    const list = document.createElement('div');
    list.className = 'module-list';
    modules.forEach(module => {
      moduleLabels[module.key] = module.label;
      const label = document.createElement('label');
      label.className = 'check-card module-card';
      label.dataset.search = `${module.label} ${module.key} ${module.description || ''}`.toLowerCase();
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.name = 'modules';
      input.value = module.key;
      const text = document.createElement('span');
      text.className = 'module-card-copy';
      const titleRow = document.createElement('span');
      titleRow.className = 'module-card-title';
      const moduleTitle = document.createElement('b');
      moduleTitle.textContent = module.label;
      const type = document.createElement('i');
      type.className = module.navigation === false ? 'capability' : 'navigation';
      type.textContent = module.navigation === false ? '细分能力' : '管理模块';
      titleRow.append(moduleTitle, type);
      const detail = document.createElement('small');
      detail.textContent = module.description || '暂无模块说明';
      const code = document.createElement('code');
      code.textContent = module.key;
      text.append(titleRow, detail, code);
      label.append(input, text);
      list.append(label);
    });
    section.append(heading, list);
    container.append(section);
  });
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
  form.elements.maxSkills.value = plan.skills;
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

async function loadMcpMenus({ preserveSelection = false, failOnError = false } = {}) {
  const moduleContainer = document.querySelector('#moduleGroups');
  const previousSelection = new Set(selectedValues('modules'));
  try {
    const response = await fetch('/api/licenses/mcp-menus', { cache: 'no-store' });
    const menus = await response.json();
    if (!response.ok) throw new Error(menus.message || '同步 MCP 菜单失败');
    availableModules = menus;
    renderModuleGroups(menus);
    const auditModule = document.querySelector('#auditModule');
    auditModule.querySelectorAll('option:not(:first-child)').forEach(option => option.remove());
    menus.forEach(menu => {
      const input = form.querySelector(`input[name="modules"][value="${CSS.escape(menu.key)}"]`);
      if (input) input.checked = preserveSelection && previousSelection.has(menu.key);
      const option = document.createElement('option');
      option.value = menu.key;
      option.textContent = menu.label;
      auditModule.append(option);
    });
    if (!menus.length) moduleContainer.textContent = '目标服务未发布功能模块';
    const publishedKeys = new Set(menus.map(menu => menu.key));
    const removed = [...previousSelection].filter(key => !publishedKeys.has(key));
    const migrated = [];
    if (preserveSelection) {
      removed.forEach(parentKey => {
        const children = menus.filter(menu => menu.parentKey === parentKey);
        children.forEach(child => {
          const input = form.querySelector(`input[name="modules"][value="${CSS.escape(child.key)}"]`);
          if (input) input.checked = true;
        });
        if (children.length) migrated.push({ parentKey, children: children.map(child => child.key) });
      });
    }
    if (preserveSelection) updatePreview();
    else applyPlan(form.elements.edition.value || 'enterprise');
    filterModules();
    return { menus, removed, migrated };
  } catch (error) {
    if (!preserveSelection) {
      moduleContainer.textContent = error.message;
    }
    message.style.color = '#d14956';
    message.textContent = error.message;
    if (failOnError) throw error;
    return { menus: [], removed: [], migrated: [] };
  }
}
loadMcpMenus();
applyTerm(12);

document.querySelector('#selectAllModules').addEventListener('click', () => {
  form.querySelectorAll('input[name="modules"]').forEach(input => { input.checked = true; });
  updatePreview();
});

document.querySelector('#selectVisibleModules').addEventListener('click', () => {
  form.querySelectorAll('.module-card:not([hidden]) input[name="modules"]').forEach(input => { input.checked = true; });
  updatePreview();
});

document.querySelector('#moduleSearch').addEventListener('input', filterModules);

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
    auditCell(editionText(item.edition), item.product || 'LingDong Nexus'),
    auditCell(item.customerCode || '未设置', item.serverId || '-'),
    auditCell(`${item.maxUsers ?? '-'} 用户`, `${item.maxAgents ?? '-'} Agent / ${item.maxSkills ?? '-'} Skill`),
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
  document.querySelector('#detailQuota').textContent = `${item.maxUsers ?? '-'} 用户 / ${item.maxAgents ?? '-'} Agent / ${item.maxSkills ?? '-'} Skill`;
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
  button.querySelector('span').textContent = '正在同步授权目录…';

  try {
    const synchronization = await loadMcpMenus({ preserveSelection: true, failOnError: true });
    const modules = selectedValues('modules');
    if (!modules.length) throw new Error('所选权益均已从目标 MCP 服务下线，请重新选择产品功能权益');
    const migratedParents = new Set(synchronization.migrated.map(item => item.parentKey));
    const removedNames = synchronization.removed
      .filter(key => !migratedParents.has(key))
      .map(key => moduleLabels[key] || key);
    const migrationNames = synchronization.migrated.map(item => {
      const parent = moduleLabels[item.parentKey] || item.parentKey;
      const children = item.children.map(key => moduleLabels[key] || key).join('、');
      return `${parent}已展开为${children}`;
    });
    const data = new FormData(form);
    const payload = {
      licenseNo: data.get('licenseNo'),
      customer: null,
      customerCode: data.get('customerCode'),
      product: data.get('product'),
      edition: data.get('edition'),
      modules,
      maxUsers: Number(data.get('maxUsers')),
      maxAgents: Number(data.get('maxAgents')),
      maxSkills: Number(data.get('maxSkills')),
      serverId: data.get('serverId'),
      issuedTime: data.get('issuedTime'),
      expireTime: data.get('expireTime'),
      features: {}
    };
    button.querySelector('span').textContent = '正在签发…';
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
    link.download = result.fileName || 'LingDong-Nexus-license-package.zip';
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
    const synchronizationNotice = [
      migrationNames.length ? `已迁移旧模块：${migrationNames.join('；')}` : '',
      removedNames.length ? `已移除未发布模块：${removedNames.join('、')}` : ''
    ].filter(Boolean).join('；');
    const noticeSuffix = synchronizationNotice ? `；${synchronizationNotice}` : '';
    message.textContent = auditUpdated
      ? `授权交付包已下载，内含 license.dat 与 license-public.pem${noticeSuffix}`
      : `授权包已下载，但下载审计状态更新失败，请在审计页面核查${noticeSuffix}`;
  } catch (error) {
    message.style.color = '#d14956';
    message.textContent = error.message;
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = '签发并下载授权交付包';
  }
});
