// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ResourceAuthorizationPanel from './ResourceAuthorizationPanel.vue';
const api = vi.hoisted(() => ({
  fetchResearchLibrary: vi.fn(), fetchDomainSkills: vi.fn(), fetchMcpRegisteredTools: vi.fn(),
  fetchResourceGrants: vi.fn(), fetchRoleAuthorization: vi.fn(),
  createResourceGrant: vi.fn(), deleteResourceGrant: vi.fn()
}));
vi.mock('../services/api', () => api);
let app;
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); };
beforeEach(() => {
  vi.resetAllMocks();
  api.fetchResearchLibrary.mockResolvedValue({ categories: ['Finance'], documents: [], totalPages: 1 });
  api.fetchDomainSkills.mockResolvedValue({ skills: [{ id: 'skill-a', name: '固收文档查看' }], totalPages: 1 });
  api.fetchMcpRegisteredTools.mockResolvedValue([{ localToolName: 'query', chineseAlias: 'API 服务查询' }]);
  api.fetchResourceGrants.mockResolvedValue([]);
  api.fetchRoleAuthorization.mockImplementation(async role => ({ agentIds: role === 'role-1' ? ['agent-a', 'agent-b'] : ['agent-b'] }));
});
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; });
async function mount() {
  const root = document.createElement('div'); document.body.append(root);
  app = createApp(ResourceAuthorizationPanel, {
    tenantId: 'tenant-1', roles: [{ id: 'role-1', roleName: '业务管理员' }, { id: 'role-2', roleName: '分析师' }],
    agents: [{ id: 'agent-a', name: '金融助手' }, { id: 'agent-b', name: '指标助手' }, { id: 'agent-c', name: '未绑定助手' }]
  });
  app.mount(root); await settle(); return root;
}
it('shows bound agents on the left and resource types on the right', async () => {
  const root = await mount();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(2);
  expect(root.textContent).not.toContain('未绑定助手');
  expect(root.querySelector('.resource-auth-card:first-child input[type=checkbox]')).toBeNull();
  expect([...root.querySelectorAll('.resource-auth-kinds button')].map(el => el.textContent))
    .toEqual(['领域技能', '文档', 'MCP 工具']);
  expect(api.fetchResourceGrants).toHaveBeenCalledWith('tenant-1', 'SKILL', 'agent-a');
  expect(api.createResourceGrant).not.toHaveBeenCalled();
});
it('saves the current role and selected agent independently', async () => {
  const root = await mount();
  root.querySelectorAll('.resource-auth-agent')[1].click(); await settle();
  root.querySelector('.resource-auth-resource input').click(); await settle();
  expect(api.createResourceGrant).toHaveBeenCalledWith(expect.objectContaining({
    tenantId: 'tenant-1', principalType: 'ROLE', principalId: 'role-1', agentId: 'agent-b',
    resourceType: 'SKILL', resourceId: 'skill-a', effect: 'ALLOW'
  }));
  const select = root.querySelector('select'); select.value = 'role-2'; select.dispatchEvent(new Event('change')); await settle();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(1);
  root.querySelector('.resource-auth-resource input').click(); await settle();
  expect(api.createResourceGrant).toHaveBeenLastCalledWith(expect.objectContaining({ principalId: 'role-2', agentId: 'agent-b' }));
});
it('revokes only the selected pair with an explicit deny', async () => {
  const grant = { resourceId: 'skill-a', principalType: 'ROLE', principalId: 'role-1', agentId: 'agent-a', effect: 'ALLOW', enabled: true };
  api.fetchResourceGrants.mockResolvedValue([
    { ...grant, id: 'mine' }, { ...grant, id: 'other-agent', agentId: 'agent-b' },
    { ...grant, id: 'other-role', principalId: 'role-2' }
  ]);
  const root = await mount();
  expect(root.querySelector('.resource-auth-resource input').checked).toBe(true);
  root.querySelector('.resource-auth-resource input').click(); await settle();
  expect(api.createResourceGrant).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
    principalId: 'role-1', agentId: 'agent-a', resourceId: 'skill-a', effect: 'DENY'
  }));
  expect(api.deleteResourceGrant).not.toHaveBeenCalled();
});
it('ignores delayed grants from the previously selected agent', async () => {
  let resolveOld;
  api.fetchResourceGrants.mockImplementation((_tenant, _kind, agent) => agent === 'agent-a'
    ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve([]));
  const root = await mount();
  root.querySelectorAll('.resource-auth-agent')[1].click(); await settle();
  resolveOld([{ resourceId: 'skill-a', principalType: 'ROLE', principalId: 'role-1', agentId: 'agent-a', effect: 'ALLOW', enabled: true }]);
  await settle();
  expect(root.querySelector('.resource-auth-resource input').checked).toBe(false);
});
it('shows no editable resources when the role has no bound agent', async () => {
  api.fetchRoleAuthorization.mockResolvedValue({ agentIds: [] });
  const root = await mount();
  expect(root.textContent).toContain('该角色尚未绑定 Agent');
  expect(root.querySelector('.resource-auth-resource')).toBeNull();
});
it('uses Chinese MCP labels and supports name filtering', async () => {
  api.fetchMcpRegisteredTools.mockResolvedValue([
    { localToolName: 'mcp_api_service_query', remoteToolName: 'api_service_query', chineseAlias: 'API 服务查询' },
    { localToolName: 'mcp_legacy', remoteToolName: 'legacy' }
  ]);
  const root = await mount();
  [...root.querySelectorAll('.resource-auth-kinds button')].find(el => el.textContent === 'MCP 工具').click(); await settle();
  expect(root.querySelector('.resource-auth-resource strong').textContent).toBe('API 服务查询');
  expect(root.querySelectorAll('.resource-auth-resource strong')[1].textContent).toBe('legacy');
  const input = root.querySelectorAll('input[type=search]')[1];
  for (const keyword of ['服务查询', 'api_service_query']) {
    input.value = keyword; input.dispatchEvent(new Event('input')); await nextTick();
    expect(root.querySelectorAll('.resource-auth-resource')).toHaveLength(1);
    expect(root.querySelector('.resource-auth-resource strong').textContent).toBe('API 服务查询');
  }
  root.querySelector('.resource-auth-resource input').click(); await settle();
  expect(api.createResourceGrant).toHaveBeenCalledWith(expect.objectContaining({
    resourceType: 'MCP_TOOL', resourceId: 'mcp_api_service_query'
  }));
  input.value = '不存在'; input.dispatchEvent(new Event('input')); await nextTick();
  expect(root.querySelector('.resource-auth-resource')).toBeNull();
});

it('shows all agents and inherited wildcard grants without creating explicit bindings', async () => {
  api.fetchResearchLibrary.mockResolvedValue({ documents: [{ docId: 'doc-a', title: '资产说明' }], totalPages: 1 });
  api.fetchRoleAuthorization.mockResolvedValue({ agentIds: ['agent-a'], allAgentAccess: true });
  api.fetchResourceGrants.mockImplementation(async (_tenant, kind, agent) => agent ? [] : [{
    principalType: 'ROLE', principalId: 'role-1', resourceId: '*', resourceType: kind,
    effect: 'ALLOW', enabled: true
  }]);
  const root = await mount();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(3);
  expect(root.textContent).toContain('拥有全部 Agent 访问权限');
  expect(root.textContent).toContain('新增 Agent 自动包含');
  for (const kind of ['领域技能', '文档', 'MCP 工具']) {
    [...root.querySelectorAll('.resource-auth-kinds button')].find(el => el.textContent === kind).click(); await settle();
    const checkbox = root.querySelector('.resource-auth-resource input');
    expect(checkbox.checked).toBe(true);
    expect(checkbox.disabled).toBe(true);
    checkbox.click(); await settle();
    expect(root.textContent).toContain('继承全量授权');
  }
  expect(api.createResourceGrant).not.toHaveBeenCalled();
});

it('does not infer admin authority from a role name or ignore explicit deny', async () => {
  api.fetchRoleAuthorization.mockResolvedValue({ agentIds: ['agent-a'], allAgentAccess: false });
  api.fetchResourceGrants.mockImplementation(async (_tenant, _kind, agent) => agent ? [{
    principalType: 'ROLE', principalId: 'role-1', agentId: 'agent-a', resourceId: 'skill-a', effect: 'DENY', enabled: true
  }] : [{ principalType: 'ROLE', principalId: 'role-1', resourceId: '*', effect: 'ALLOW', enabled: true }]);
  const root = await mount();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(1);
  expect(root.querySelector('.resource-auth-resource input').checked).toBe(false);
  expect(root.querySelector('.resource-auth-resource input').disabled).toBe(false);
});

it('ignores expired wildcard grants and grants belonging to other roles', async () => {
  api.fetchRoleAuthorization.mockResolvedValue({ agentIds: ['agent-a'], allAgentAccess: true });
  api.fetchResourceGrants.mockResolvedValue([
    { principalType: 'ROLE', principalId: 'role-1', resourceId: '*', effect: 'ALLOW', enabled: true, expiresAt: '2000-01-01T00:00:00Z' },
    { principalType: 'ROLE', principalId: 'role-2', resourceId: '*', effect: 'ALLOW', enabled: true }
  ]);
  const root = await mount();
  expect(root.querySelector('.resource-auth-resource input').checked).toBe(false);
  expect(root.textContent).not.toContain('继承全量授权');
});

it('clears unrestricted access when switching to an ordinary role', async () => {
  api.fetchRoleAuthorization.mockImplementation(async role => ({ agentIds: ['agent-b'], allAgentAccess: role === 'role-1' }));
  const root = await mount();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(3);
  const select = root.querySelector('select'); select.value = 'role-2'; select.dispatchEvent(new Event('change')); await settle();
  expect(root.querySelectorAll('.resource-auth-agent')).toHaveLength(1);
  expect(root.textContent).not.toContain('拥有全部 Agent 访问权限');
});

it('keeps list containers and scroll positions while grants reload', async () => {
  const root = await mount();
  const lists = [...root.querySelectorAll('.resource-auth-list')];
  lists[0].scrollTop = 120; lists[1].scrollTop = 80;
  let resolveGrants;
  api.fetchResourceGrants.mockImplementation((_tenant, _kind, agent) => agent
    ? new Promise(resolve => { resolveGrants = resolve; }) : Promise.resolve([]));
  root.querySelectorAll('.resource-auth-agent')[1].click(); await settle();
  expect(root.querySelectorAll('.resource-auth-list')[1]).toBe(lists[1]);
  expect(lists[1].getAttribute('aria-busy')).toBe('true');
  expect(lists[0].scrollTop).toBe(120);
  expect(lists[1].scrollTop).toBe(80);
  expect(root.querySelector('.resource-auth-resource input').disabled).toBe(true);
  resolveGrants([]); await settle();
  expect(root.querySelectorAll('.resource-auth-list')[1]).toBe(lists[1]);
  expect(lists[1].scrollTop).toBe(80);
});
