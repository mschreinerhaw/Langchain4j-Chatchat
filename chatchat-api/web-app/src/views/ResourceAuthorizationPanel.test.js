// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ResourceAuthorizationPanel from './ResourceAuthorizationPanel.vue';

const api = vi.hoisted(() => ({
  fetchResearchLibrary: vi.fn(async () => ({ categories: ['Finance'], documents: [], totalPages: 1 })),
  fetchDomainSkills: vi.fn(async () => ({ skills: [], totalPages: 1 })),
  fetchMcpRegisteredTools: vi.fn(async () => []),
  fetchResourceGrants: vi.fn(async (_tenant, type) => type === 'AGENT_SKILL'
    ? [{ resourceId: 'agent-a', principalType: 'ROLE', principalId: 'role-1', effect: 'ALLOW', enabled: true }]
    : []),
  fetchRoleAuthorization: vi.fn(async () => ({ agentIds: ['agent-a'] })),
  fetchSkillResourceScopes: vi.fn(async () => [{ resourceType: 'KNOWLEDGE_BASE', resourceId: 'finance', enabled: true }]),
  createResourceGrant: vi.fn(), deleteResourceGrant: vi.fn(),
  createSkillResourceScope: vi.fn(), deleteSkillResourceScope: vi.fn()
}));
vi.mock('../services/api', () => api);

let app;
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks(); });
const settle = async () => { await new Promise((resolve) => setTimeout(resolve, 0)); await nextTick(); };

it('uses one selected Agent for role grant and document scope without changing grant on inspection', async () => {
  const root = document.createElement('div');
  document.body.append(root);
  app = createApp(ResourceAuthorizationPanel, {
    tenantId: 'tenant-1', roles: [{ id: 'role-1', roleName: '业务管理员', roleCode: 'BUSINESS_ADMIN' }],
    agents: [{ id: 'agent-a', name: '金融文档分析助手' }, { id: 'agent-b', name: '指标助手' }],
    initialRoleId: 'role-1'
  });
  app.mount(root);
  await settle();

  const inspect = root.querySelector('[aria-label="配置 金融文档分析助手 的运行白名单"]');
  expect(inspect).toBeTruthy();
  inspect.click();
  await settle();

  expect(root.querySelectorAll('.resource-auth-context select')[1].value).toBe('agent-a');
  expect(root.querySelector('.resource-auth-item.selected strong').textContent).toBe('金融文档分析助手');
  expect(root.textContent).toContain('Agent 入口：已允许（角色绑定 + 显式授权）');
  expect(root.textContent).toContain('已配置 1 项白名单');
  expect(api.fetchSkillResourceScopes).toHaveBeenCalledWith('tenant-1', 'agent-a');
  expect(api.createResourceGrant).not.toHaveBeenCalled();

  const agentSelect = root.querySelectorAll('.resource-auth-context select')[1];
  agentSelect.value = 'agent-b';
  agentSelect.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
  expect(root.textContent).toContain('Agent 入口：未允许');
  expect(root.querySelector('.resource-auth-item.selected strong').textContent).toBe('指标助手');
  expect(api.fetchSkillResourceScopes).toHaveBeenCalledWith('tenant-1', 'agent-b');
});

it('shows the MCP Chinese alias instead of using the English description as its name', async () => {
  api.fetchMcpRegisteredTools.mockResolvedValueOnce([{
    localToolName: 'mcp_chatchat_mcp_server_api_service_query',
    remoteToolName: 'api_service_query',
    chineseAlias: 'API 服务查询',
    displayName: 'API Service Query',
    description: 'Query redacted API service assets and their routing metadata.'
  }]);
  const root = document.createElement('div');
  document.body.append(root);
  app = createApp(ResourceAuthorizationPanel, {
    tenantId: 'tenant-1', roles: [{ id: 'role-1', roleName: '业务管理员', roleCode: 'BUSINESS_ADMIN' }],
    agents: [{ id: 'agent-a', name: '金融文档分析助手' }], initialRoleId: 'role-1'
  });
  app.mount(root);
  await settle();

  [...root.querySelectorAll('.resource-auth-card:first-of-type .resource-auth-kinds button')]
    .find((button) => button.textContent.includes('MCP 工具')).click();
  await settle();

  expect(root.querySelector('.resource-auth-card:first-of-type .resource-auth-item strong').textContent).toBe('API 服务查询');
  expect(root.querySelector('.resource-auth-card:first-of-type .resource-auth-item small').textContent)
    .toBe('mcp_chatchat_mcp_server_api_service_query');
  expect(root.textContent).not.toContain('Query redacted API service assets');
});
