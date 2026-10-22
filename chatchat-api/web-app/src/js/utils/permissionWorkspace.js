import { isInternalMcpCapability, hasExplicitToolRule } from "./mcpPermissionPresentation.js";
export const permissionSections = [
  { id: "systemUsers", label: "用户管理", icon: "users" },
  { id: "systemOrganizations", label: "组织管理", icon: "organization" },
  { id: "systemRoles", label: "角色权限", icon: "shield" },
  { id: "systemResources", label: "资源授权", icon: "key" },
  { id: "systemLogins", label: "安全审计", icon: "tasks" },
];
export const resourceGroups = [
  { id: "workspace", label: "工作空间", description: "进入工作空间的权限" },
  { id: "function", label: "功能模块", description: "页面与业务操作权限" },
  { id: "tool", label: "MCP 工具", description: "工具执行规则与资源边界" },
  { id: "datasource", label: "数据源", description: "通过受控工具访问数据，按数据范围生效" },
  { id: "knowledge", label: "知识库", description: "知识库与文档的访问规则" },
  { id: "other", label: "领域技能", description: "领域技能及其现有查询服务范围" },
];
export const permissionRouteAliases = {
  system: "systemUsers",
  systemSkillRoles: "systemResources",
  permissionTools: "systemResources",
};
export const actionLabel = (value) =>
  ({ GET: "读取", POST: "创建 / 执行", PUT: "修改", DELETE: "删除", "*": "管理" })[value] || "访问";
export function activeGrant(grant, now = Date.now()) {
  return grant.enabled !== false && (!grant.expiresAt || new Date(grant.expiresAt).getTime() > now);
}
export function resourceCatalog(catalog, extras = {}) {
  const result = [],
    seen = new Set();
  const add = (item) => {
    item.key = `${item.backend}:${item.type}:${item.id}`;
    if (!seen.has(item.key)) {
      seen.add(item.key);
      result.push(item);
    }
  };
  for (const p of catalog.permissions || []) {
    if (p.permissionType === "internal") continue;
    add({
      backend: "permission",
      id: p.id,
      type: "PERMISSION",
      name:
        {
          "Workspace access": "基础工作空间访问",
          "Zhice access": "智策工作空间访问",
          "Workspace management": "工作空间设置管理",
        }[p.permissionName] ||
        p.permissionName ||
        "未命名权限",
      group: /^workspace:[^:]+:access$/.test(p.permissionCode) ? "workspace" : "function",
      action: actionLabel(p.httpMethod),
      code: p.permissionCode,
      enabled: p.status === "enabled",
    });
  }
  const hiddenTools = new Set();
  for (const tool of catalog.tools || []) {
    if (isInternalMcpCapability(tool)) {
      hiddenTools.add(tool.localToolName);
      continue;
    }
    const details =
      (extras.tools || []).find((t) => (t.localToolName || t.toolName) === tool.localToolName) ||
      {};
    if (
      extras.toolsLoaded &&
      !Object.keys(details).length &&
      !hasExplicitToolRule(tool.localToolName, catalog)
    )
      continue;
    const types = [details.backendServiceType, ...(details.backendServiceTypes || [])];
    const datasource = types.some((t) =>
      ["sql_datasource", "database_query", "ssh_host", "http_endpoint", "api_service"].includes(t)
    );
    add({
      backend: "tool",
      type: "MCP_TOOL",
      id: tool.localToolName,
      name:
        details.chineseAlias ||
        details.displayName ||
        details.applicability?.scopeLabel ||
        "业务工具（查看详情）",
      group: datasource ? "datasource" : "tool",
      action: "执行",
      code: tool.localToolName,
      description: tool.description,
      enabled: tool.enabled !== false,
    });
  }
  for (const doc of extras.documents || [])
    add({
      backend: "resource",
      type: "KNOWLEDGE",
      id: doc.docId,
      name: doc.title || doc.fileName || "未命名文档",
      group: "knowledge",
      action: "读取",
    });
  for (const category of extras.categories || []) {
    const name = typeof category === "string" ? category : category.name;
    if (name)
      add({
        backend: "resource",
        type: "KNOWLEDGE_BASE",
        id: name.trim().toLowerCase(),
        name,
        group: "knowledge",
        action: "检索",
      });
  }
  for (const skill of extras.skills || [])
    add({
      backend: "resource",
      type: "SKILL",
      id: skill.id,
      name: skill.name || "领域技能",
      group: "other",
      action: "使用",
    });
  for (const service of extras.services || [])
    add({
      backend: "resource",
      type: "AGENT_SKILL",
      id: service.id,
      name: service.name || "查询服务",
      group: "other",
      action: "使用",
    });
  for (const grant of catalog.resourceGrants || []) {
    if (grant.resourceType === "MCP_TOOL" && hiddenTools.has(grant.resourceId)) continue;
    const group = ["KNOWLEDGE", "KNOWLEDGE_BASE"].includes(grant.resourceType)
      ? "knowledge"
      : grant.resourceType === "MCP_TOOL"
        ? "tool"
        : "other";
    const named = result.find((r) => r.type === grant.resourceType && r.id === grant.resourceId);
    if (!named)
      add({
        backend: "resource",
        type: grant.resourceType,
        id: grant.resourceId,
        name:
          grant.resourceId === "*"
            ? "全部" + (resourceGroups.find((g) => g.id === group)?.label || "资源")
            : "已有资源（查看详情）",
        group,
        action: "访问",
      });
  }
  return result.filter((r) => r.id);
}
export function resourceRules(resource, catalog) {
  if (resource.backend === "permission")
    return (catalog.roleAuthorizations || [])
      .filter((a) => a.permissionIds?.includes(resource.id))
      .map((a) => ({
        id: `role:${a.role.id}`,
        targetType: "ROLE",
        targetId: a.role.id,
        effect: "allow",
        enabled: a.role.status === "enabled",
        backend: "permission",
        source: "角色直接授权",
      }));
  if (resource.backend === "tool")
    return [
      ...(catalog.grants || [])
        .filter((g) => g.localToolName === resource.id || g.localToolName === "*")
        .map((g) => ({ ...g, backend: "tool" })),
      ...(catalog.resourceGrants || [])
        .filter(
          (g) =>
            g.resourceType === "MCP_TOOL" && (g.resourceId === resource.id || g.resourceId === "*")
        )
        .map((g) => ({
          ...g,
          targetType: g.principalType,
          targetId: g.principalId,
          backend: "resource",
        })),
    ];
  return (catalog.resourceGrants || [])
    .filter(
      (g) =>
        g.resourceType === resource.type && (g.resourceId === resource.id || g.resourceId === "*")
    )
    .map((g) => ({
      ...g,
      targetType: g.principalType,
      targetId: g.principalId,
      backend: "resource",
    }));
}
export function roleResourceState(resource, catalog, roleId) {
  const rules = resourceRules(resource, catalog).filter(
    (g) =>
      activeGrant(g) &&
      ((g.targetType === "ROLE" && g.targetId === roleId) ||
        (g.targetType === "TENANT" && g.targetId === catalog.tenantId))
  );
  const direct = rules.filter((g) => g.targetType === "ROLE"),
    inherited = rules.filter((g) => g.targetType === "TENANT");
  const applicable = rules.filter((g) => !g.agentId);
  const denies = applicable.some((g) => g.effect.toLowerCase() === "deny");
  const scope = rules.some((g) => g.scopeExpression || g.agentId || g.resourceId === "*");
  return {
    direct,
    inherited,
    rules,
    status: denies
      ? "存在拒绝规则"
      : applicable.length
        ? scope
          ? "按范围允许"
          : "已授权"
        : rules.length
          ? "限定查询服务"
          : "未直接授权",
  };
}
// Preserve concurrent changes and all unrelated role memberships/scopes/service bindings.
export function mergePermissionSelection(latest, original, selected) {
  const removed = original.filter((id) => !selected.includes(id));
  const added = selected.filter((id) => !original.includes(id));
  return {
    ...latest,
    permissionIds: [
      ...new Set([...latest.permissionIds.filter((id) => !removed.includes(id)), ...added]),
    ],
    orgScopes: (latest.orgScopes || []).map(({ scopeType, orgId }) => ({ scopeType, orgId })),
  };
}
