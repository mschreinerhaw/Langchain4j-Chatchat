// @vitest-environment jsdom
import { createApp, nextTick } from 'vue';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import TemplateToolsView from './TemplateToolsView.vue';
import { apiRequest } from '../services/api.js';
vi.mock('../services/api.js', () => ({ apiRequest: vi.fn(), getStoredAuthSession: () => ({ user: { permissionCodes: ['mcp:publication:publish', 'platform:agents:workshop'] } }) }));
const base = '/tool-publications/capabilities';
const template = { key: 'sql:customers@asset', title: '客户资产查询', description: '按客户查询', assetName: '客户库', revision: 'revision', inputSchema: { properties: { customerId: { type: 'string' } } } };
const publication = { id: 'release', title: template.title, assetName: '客户库', state: 'RUNNING', toolName: 'tpl_123', version: 1 };
let app, root;
const flush = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick(); } };
async function mount() { root = document.createElement('div'); document.body.append(root); app = createApp(TemplateToolsView); const vm = app.mount(root); await flush(); return vm; }
beforeEach(() => apiRequest.mockImplementation(path => Promise.resolve(path === base + '/templates' ? [template] : path === base + '/publications' ? [publication] : path === base + '/agents' ? [{ id: 'agent', label: '客户分析', marketStatus: 'published' }] : [])));
afterEach(() => { app?.unmount(); root?.remove(); vi.restoreAllMocks(); apiRequest.mockReset(); });
it('shows business templates without requiring executor or role configuration', async () => {
  const vm = await mount(); expect(root.textContent).toContain('客户资产查询'); expect(root.textContent).toContain('客户库');
  expect(root.textContent).not.toContain('历史版本管理'); expect(vm.templates).toHaveLength(1);
  vm.query = '不存在'; await nextTick(); expect(root.textContent).toContain('暂无可用模板');
});
it('requires confirmation and sends only template identity and expected revision', async () => {
  const vm = await mount(); root.querySelector('.template-capability-card button').click(); await nextTick();
  expect(root.querySelector('[role="dialog"]')).not.toBeNull();
  expect(apiRequest.mock.calls.some(call => call[1]?.method === 'POST')).toBe(false);
  await vm.publish(); expect(apiRequest).toHaveBeenCalledWith(base + '/publications', { method: 'POST', body: JSON.stringify({ key: template.key, revision: template.revision }) });
  expect(vm.selected).toBeNull(); expect(vm.tab).toBe('publications');
});
it('keeps confirmation open after a rejected stale or revoked publication', async () => {
  const vm = await mount(); vm.selected = template;
  apiRequest.mockRejectedValueOnce(new Error('模板授权已撤销')); await vm.publish();
  expect(vm.failed).toBe(true); expect(vm.selected).toEqual(template); expect(root.textContent).toContain('模板授权已撤销');
});
it('persists explicit Agent binding and refreshes after denial', async () => {
  const vm = await mount(); await vm.toggleBinding(publication, true);
  expect(apiRequest).toHaveBeenCalledWith(base + '/agents/agent/tools/tpl_123', { method: 'PUT' });
  apiRequest.mockRejectedValueOnce(new Error('绑定已失效')); await vm.toggleBinding(publication, false);
  expect(vm.failed).toBe(true); expect(vm.isBound(publication)).toBe(false);
});
