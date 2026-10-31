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
  getStoredAuthSession: () => ({
    user: {
      permissionCodes: [
        "system:role",
        "system:role:authorize",
        "system:permission-center:manage",
        "system:resource-grants:manage",
      ],
    },
  }),
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
function mount(component, props) {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(component, props);
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
  it("keeps templates out of the default directory while global search and six-item pages retain access", async () => {
    const data = catalog();
    const templates = Array.from({ length: 19 }, (_, i) => ({
      templateKey: `sql-${i}`,
      title: `SQL_客户分析_${i}`,
    }));
    api.apiRequest.mockImplementation(async (path) =>
      path.endsWith("/permission-catalog") ? templates : data
    );
    const wrapper = mount(PermissionConsoleView, { mode: "resources" });
    await flushPromises();
    expect(wrapper.vm.masterItems).toHaveLength(1);
    expect(wrapper.vm.selectedResource.id).toBe("p1");
    expect(wrapper.text()).not.toContain("SQL_客户分析_0");
    wrapper.vm.masterQuery = "SQL_客户分析";
    await flushPromises();
    expect(wrapper.vm.masterItems).toHaveLength(19);
    expect(wrapper.vm.masterPage).toHaveLength(6);
    expect(wrapper.vm.masterPages).toBe(4);
    expect(wrapper.vm.masterRange).toBe("1–6 / 19");
    expect(wrapper.vm.selectedResource.id).toBe("sql-0");
    wrapper.vm.masterPageNumber = 2;
    await flushPromises();
    expect(wrapper.vm.selectedResource.id).toBe("sql-6");
    expect(wrapper.vm.masterRange).toBe("7–12 / 19");
    wrapper.vm.masterQuery = "not-found";
    await flushPromises();
    expect(wrapper.vm.masterPageNumber).toBe(1);
    expect(wrapper.vm.selectedResource).toBeUndefined();
    wrapper.vm.masterQuery = "";
    wrapper.vm.resourceGroup = "template";
    await flushPromises();
    expect(wrapper.vm.selectedResource.id).toBe("sql-0");
    expect(wrapper.vm.masterPage).toHaveLength(6);
    expect(wrapper.find(".permission-master-item small").exists()).toBe(false);
    expect(wrapper.find(".permission-master-item strong").element.title).toBe("SQL_客户分析_0");
    wrapper.vm.resourceGroup = "all";
    await flushPromises();
    expect(wrapper.vm.masterItems).toHaveLength(20);
    expect(
      api.apiRequest.mock.calls.every(([, options]) => !options?.method || options.method === "GET")
    ).toBe(true);
    wrapper.unmount();
  });
  it("dismisses the refreshed catalog success message automatically", async () => {
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    vi.useFakeTimers();
    try {
      await wrapper.vm.reloadAfterChange();
      expect(wrapper.vm.notice).toBe("授权目录已刷新");
      await vi.advanceTimersByTimeAsync(3500);
      expect(wrapper.vm.notice).toBe("");
      api.apiRequest.mockRejectedValueOnce(new Error("目录不可用"));
      await wrapper.vm.reloadAfterChange();
      await vi.advanceTimersByTimeAsync(10000);
      expect(wrapper.vm.notice).toContain("刷新失败");
    } finally {
      wrapper.unmount();
      vi.useRealTimers();
    }
  });
  it("switches between separate skill and Agent tabs with matching resource types", async () => {
    api.fetchDomainSkills.mockResolvedValue({
      skills: [{ id: "skill-a", name: "投资技能" }],
      totalPages: 1,
    });
    api.fetchAgentOptions.mockResolvedValue([{ id: "agent-a", name: "日志分析助手" }]);
    const wrapper = mount(PermissionConsoleView);
    await flushPromises();
    const tabs = wrapper.findAll(".permission-category-tabs button");
    await tabs.find((b) => b.text() === "领域技能").trigger("click");
    expect(wrapper.find(".permission-table").text()).toContain("投资技能");
    expect(wrapper.find(".permission-table").text()).not.toContain("日志分析助手");
    await tabs.find((b) => b.text() === "Agent").trigger("click");
    expect(wrapper.find(".permission-table").text()).toContain("日志分析助手");
    expect(wrapper.find(".permission-table").text()).not.toContain("投资技能");
    expect(wrapper.find(".permission-table").text()).toContain("Agent");
    wrapper.unmount();
  });
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
    expect(api.saveRoleAuthorization).not.toHaveBeenCalled();
    expect(document.querySelector('.app-confirm-dialog').textContent).toContain('确认撤销');
    document.querySelector('.app-confirm-dialog .app-confirm-submit').click();
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
    document.querySelector('.app-confirm-dialog .app-confirm-submit').click();
    await flushPromises();
    expect(wrapper.find(".permission-notice.error").text()).toContain("授权保存失败");
    expect(wrapper.find('[aria-label="授权 权限管理"]').element.checked).toBe(false);
    wrapper.unmount();
  });
});
