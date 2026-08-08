const form = document.querySelector('#licenseForm');
const message = document.querySelector('#message');
const button = document.querySelector('#submitButton');

const preview = {
  avatar: document.querySelector('#customerAvatar'),
  customer: document.querySelector('#previewCustomer'),
  product: document.querySelector('#previewProduct'),
  mac: document.querySelector('#previewMac'),
  modules: document.querySelector('#previewModules'),
  users: document.querySelector('#previewUsers'),
  agents: document.querySelector('#previewAgents'),
  expiry: document.querySelector('#previewExpiry')
};

const moduleLabels = {};

const dateText = value => value.toISOString().slice(0, 10);
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
  const customer = form.elements.customer.value.trim();
  const modules = selectedValues('modules');
  preview.customer.textContent = customer || '未填写客户';
  preview.avatar.textContent = (customer || 'L').slice(0, 1).toUpperCase();
  preview.product.textContent = `${form.elements.product.value || 'LiveMCP'} · ${form.elements.edition.value}`;
  preview.mac.textContent = normalizeMac(form.elements.serverId.value.trim());
  preview.modules.textContent = modules.length ? modules.map(item => moduleLabels[item]).join('、') : '未选择';
  preview.users.textContent = form.elements.maxUsers.value || '-';
  preview.agents.textContent = form.elements.maxAgents.value || '-';
  preview.expiry.textContent = form.elements.expireTime.value || '-';
}

form.addEventListener('input', updatePreview);
form.addEventListener('change', updatePreview);
updatePreview();

async function loadMcpMenus() {
  const container = document.querySelector('#mcpMenuModules');
  try {
    const response = await fetch('/api/licenses/mcp-menus');
    const menus = await response.json();
    if (!response.ok) throw new Error(menus.message || '同步 MCP 菜单失败');
    container.replaceChildren();
    menus.forEach((menu, index) => {
      moduleLabels[menu.key] = menu.label;
      const label = document.createElement('label');
      label.className = 'check-card';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.name = 'modules';
      input.value = menu.key;
      input.checked = index === 0;
      const text = document.createElement('span');
      const title = document.createElement('b');
      title.textContent = menu.label;
      const detail = document.createElement('small');
      detail.textContent = `MCP 菜单 ID：${menu.key}`;
      text.append(title, detail);
      label.append(input, text);
      container.append(label);
    });
    updatePreview();
  } catch (error) {
    container.textContent = error.message;
    message.style.color = '#d14956';
    message.textContent = error.message;
  }
}
loadMcpMenus();

form.addEventListener('submit', async event => {
  event.preventDefault();
  message.textContent = '';
  button.disabled = true;
  button.querySelector('span').textContent = '正在签发…';

  const data = new FormData(form);
  const payload = {
    licenseNo: data.get('licenseNo'),
    customer: data.get('customer'),
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
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = result.fileName || 'license.dat';
    link.click();
    URL.revokeObjectURL(url);
    message.style.color = '#15875a';
    message.textContent = '授权文件已生成并开始下载';
  } catch (error) {
    message.style.color = '#d14956';
    message.textContent = error.message;
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = '生成并下载 License';
  }
});
