import { describe, it, expect } from "vitest";
import {
  resourceCatalog,
  resourceRules,
  roleResourceState,
  activeGrant,
  mergePermissionSelection,
} from "./permissionWorkspace.js";
import { parseWorkspaceRoute } from "./workspaceContext.js";
describe("unified permission workspace projections", () => {
  it("hides internal communication capabilities without hiding published business tools or external tools", () => {
    const tools=[
      {localToolName:"internal",serviceId:"chatchat-mcp-server",remoteToolName:"sql_query_execute",serviceName:"ChatChat MCP Server"},
      {localToolName:"business",serviceId:"chatchat-mcp-server",remoteToolName:"sales_template_query",serviceName:"ChatChat MCP Server"},
      {localToolName:"external",serviceId:"third-party",remoteToolName:"sql_query_execute"},
      {localToolName:"stale",serviceId:"old",remoteToolName:"old"},
      {localToolName:"old-grant",serviceId:"old",remoteToolName:"old-grant"},
    ];
    const catalog={tools,grants:[{localToolName:"old-grant",targetType:"ROLE",targetId:"role"}],resourceGrants:[{resourceType:"MCP_TOOL",resourceId:"internal"}]};
    const result=resourceCatalog(catalog,{toolsLoaded:true,tools:[{localToolName:"business",chineseAlias:"销售汇总"},{localToolName:"external",displayName:"外部受控查询"}]});
    expect(result.map(r=>r.id)).toEqual(["business","external","old-grant"]);expect(result.some(r=>r.name==="ChatChat MCP Server")).toBe(false);expect(catalog.resourceGrants).toHaveLength(1);
  });
  it("normalizes obsolete routes without creating duplicate pages", () => {
    for (const id of ["permissionTools", "systemSkillRoles"])
      for (const workspace of ["permissions", "zhice"])
        expect(parseWorkspaceRoute(`#/w/${workspace}/${id}`)).toEqual({
          workspaceId: "permissions",
          pageId: "systemResources",
        });
  });
  it("keeps local query-service restrictions separate from tenant and direct grants", () => {
    const resource = { backend: "resource", type: "KNOWLEDGE", id: "doc" };
    const catalog = {
      tenantId: "tenant",
      resourceGrants: [
        {
          id: "shared",
          resourceType: "KNOWLEDGE",
          resourceId: "*",
          principalType: "TENANT",
          principalId: "tenant",
          effect: "ALLOW",
          enabled: true,
        },
        {
          id: "scoped",
          resourceType: "KNOWLEDGE",
          resourceId: "doc",
          principalType: "ROLE",
          principalId: "role",
          effect: "ALLOW",
          enabled: true,
          agentId: "local-service",
        },
      ],
    };
    const state = roleResourceState(resource, catalog, "role");
    expect(state.inherited).toHaveLength(1);
    expect(state.direct[0].agentId).toBe("local-service");
    expect(resourceRules(resource, catalog)).toHaveLength(2);
    expect(activeGrant({ enabled: true, expiresAt: "2000-01-01" })).toBe(false);
  });
  it("does not treat expired denies or another tenant as effective inheritance", () => {
    const resource = { backend: "tool", id: "tool" };
    const catalog = {
      tenantId: "tenant",
      grants: [
        { targetType: "TENANT", targetId: "other", localToolName: "tool", effect: "deny" },
        {
          targetType: "ROLE",
          targetId: "role",
          localToolName: "tool",
          effect: "deny",
          expiresAt: "2000-01-01",
        },
      ],
    };
    expect(roleResourceState(resource, catalog, "role").rules).toEqual([]);
  });
  it("preserves concurrent permission additions, members, organization scopes and service bindings", () => {
    const latest = {
      permissionIds: ["old", "concurrent"],
      userIds: ["user"],
      agentIds: ["local-service"],
      orgScopes: [{ id: "scope", scopeType: "org_and_children", orgId: "org" }],
    };
    const merged = mergePermissionSelection(latest, ["old"], ["new"]);
    expect(merged).toEqual({
      ...latest,
      permissionIds: ["concurrent", "new"],
      orgScopes: [{ scopeType: "org_and_children", orgId: "org" }],
    });
  });
  it("presents existing tool and resource policy layers together without duplicating the registered tool", () => {
    const catalog = {
      tools: [{ localToolName: "sql", serviceName: "受控查询" }],
      permissions: [
        {
          id: "ws",
          permissionCode: "workspace:permissions:access",
          permissionName: "权限管理",
          permissionType: "menu",
          status: "enabled",
        },
      ],
      grants: [
        {
          id: "tool-grant",
          localToolName: "sql",
          targetType: "ROLE",
          targetId: "role",
          effect: "allow",
        },
      ],
      resourceGrants: [
        {
          id: "resource-grant",
          resourceType: "MCP_TOOL",
          resourceId: "sql",
          principalType: "ROLE",
          principalId: "role",
          effect: "ALLOW",
        },
      ],
    };
    const resources = resourceCatalog(catalog, {
      tools: [
        {
          localToolName: "sql",
          chineseAlias: "业务数据查询",
          backendServiceType: "sql_datasource",
        },
      ],
    });
    expect(resources).toHaveLength(2);
    expect(resources.find((r) => r.id === "sql").group).toBe("datasource");
    expect(resources.find((r) => r.id === "ws").group).toBe("workspace");
    expect(
      resourceRules(
        resources.find((r) => r.id === "sql"),
        catalog
      )
    ).toHaveLength(2);
  });
});
