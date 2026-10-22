// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import { describe, it, expect, vi, beforeEach } from "vitest";
import PermissionConsoleView from "./PermissionConsoleView.vue";
import * as api from "../services/api.js";
vi.mock("../services/api.js", () => ({
  apiRequest: vi.fn(),
  fetchResearchLibrary: vi.fn(),
  fetchDomainSkills: vi.fn(),
  fetchMcpRegisteredTools: vi.fn(),
  fetchAgentOptions: vi.fn(),
  fetchOrgs: vi.fn(),
  fetchRoleAuthorization: vi.fn(),
  saveRoleAuthorization: vi.fn(),
  createRole: vi.fn(),
  updateRole: vi.fn(),
  deleteRole: vi.fn(),
  getStoredAuthSession: () => ({ user: { permissionCodes: ["system:role","system:role:authorize","system:permission-center:manage","system:resource-grants:manage"] } }),
}));
const role = {
  id: "role",
  tenantId: "tenant",
  roleName: "业务分析员",
  roleCode: "ANALYST",
  status: "enabled",
};
async function flushPromises() {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
function mount(component) {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(component);
  const vm = app.mount(root);
  const element = (node) => ({
    exists: () => !!node,
    text: () => node?.textContent || "",
    element: node,
    setValue: async (value) => {
      node.checked = value;
      node.dispatchEvent(new Event("change", { bubbles: true }));
      await nextTick();
    },
    trigger: async (event) => {
      node.dispatchEvent(new Event(event, { bubbles: true }));
      await nextTick();
    },
  });
  return {
    vm,
    text: () => root.textContent,
    find: (selector) => element(root.querySelector(selector)),
    findAll: (selector) => [...root.querySelectorAll(selector)].map(element),
    unmount: () => {
      app.unmount();
      root.remove();
    },
  };
}
function catalog() {
  return {
    tenantId: "tenant",
    roles: [role],
    users: [{ id: "user", displayName: "业务用户" }],
    tools: [],
    resourceGrants: [],
    grants: [],
    permissions: [
      {
        id: "p1",
        permissionName: "权限管理",
        permissionCode: "workspace:permissions:access",
        permissionType: "menu",
        status: "enabled",
      },
    ],
    roleAuthorizations: [
      {
        role,
        permissionIds: ["p1", "hidden"],
        userIds: ["user"],
        agentIds: ["local-service"],
        orgScopes: [{ scopeType: "org_and_children", orgId: "org" }],
      },
    ],
  };
}
beforeEach(() => {
  vi.resetAllMocks();
  api.apiRequest.mockResolvedValue(catalog());
  api.fetchResearchLibrary.mockResolvedValue({ documents: [], categories: [], totalPages: 1 });
  api.fetchDomainSkills.mockResolvedValue({ skills: [], totalPages: 1 });
  api.fetchMcpRegisteredTools.mockResolvedValue([]);
  api.fetchAgentOptions.mockResolvedValue([]);
  api.fetchOrgs.mockResolvedValue([]);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
describe("permission console", () => {
  it("refuses bulk writes that would remove an existing data or query-service boundary", async () => {
    const data = catalog();
    data.tools = [{ localToolName: "bounded", serviceName: "范围工具" }];
    data.grants = [
      {
        id: "limited",
        localToolName: "bounded",
        targetType: "ROLE",
        targetId: "role",
        effect: "allow",
        scopeExpression: "mcp:sql:query:read@domain=original",
      },
    ];
    api.apiRequest.mockResolvedValue(data);
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    wrapper.vm.bulkSelected = ["tool:MCP_TOOL:bounded"];
    await wrapper.vm.allowSelectedResources();
    await flushPromises();
    expect(wrapper.text()).toContain("批量操作不会扩大这些范围");
    expect(api.apiRequest.mock.calls.some((call) => call[1]?.method === "POST")).toBe(false);
    wrapper.unmount();
  });
  it("loads roles from the authenticated tenant catalog and exposes a real master-detail editor", async () => {
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    expect(wrapper.text()).toContain("业务分析员");
    expect(wrapper.find('[aria-label="授权 权限管理"]').exists()).toBe(true);
    expect(wrapper.findAll(".system-metrics")).toHaveLength(0);
    expect(api.apiRequest).toHaveBeenCalledWith("/permission-center");
    wrapper.unmount();
  });
  it("saves permission deltas without dropping hidden grants or unrelated concurrent bindings", async () => {
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    await wrapper.find('[aria-label="授权 权限管理"]').setValue(false);
    api.fetchRoleAuthorization.mockResolvedValue({
      ...catalog().roleAuthorizations[0],
      permissionIds: ["p1", "hidden", "concurrent"],
    });
    await wrapper
      .findAll("button")
      .find((b) => b.text() === "保存权限变更")
      .trigger("click");
    await flushPromises();
    expect(api.saveRoleAuthorization).toHaveBeenCalledWith(
      "role",
      expect.objectContaining({
        permissionIds: ["hidden", "concurrent"],
        userIds: ["user"],
        agentIds: ["local-service"],
        orgScopes: [{ scopeType: "org_and_children", orgId: "org" }],
      })
    );
    wrapper.unmount();
  });
  it("keeps failed edits available for retry and reports the failure", async () => {
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    await wrapper.find('[aria-label="授权 权限管理"]').setValue(false);
    api.fetchRoleAuthorization.mockRejectedValue(new Error("授权保存失败"));
    await wrapper
      .findAll("button")
      .find((b) => b.text() === "保存权限变更")
      .trigger("click");
    await flushPromises();
    expect(wrapper.find(".permission-notice.error").text()).toContain("授权保存失败");
    expect(wrapper.find('[aria-label="授权 权限管理"]').element.checked).toBe(false);
    wrapper.unmount();
  });
});
