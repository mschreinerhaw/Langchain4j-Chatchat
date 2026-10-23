import { describe, it, expect, vi } from "vitest";
vi.mock("../../services/api", () => ({
  fetchPublishedToolTemplates: vi.fn(),
  fetchMcpCenterStatus: vi.fn(),
  fetchMcpServices: vi.fn(),
  fetchMcpToolCards: vi.fn(),
  syncMcpCenter: vi.fn(),
}));
import { fetchPublishedToolTemplates } from "../../services/api";
import { readFileSync } from "node:fs";
import McpCenterView from "./McpCenterView.js";
describe("published business tool directory", () => {
  it("does not fetch template publications for built-in system tools", async () => {
    fetchPublishedToolTemplates.mockClear();
    const vm = {
      ...McpCenterView.data(),
      activeTool: { systemTool: true, localToolName: "mcp_chatchat_mcp_server_web_search" },
      detailTemplates: [{ key: "previous" }],
    };
    await McpCenterView.methods.loadDetailTemplates.call(vm);
    expect(fetchPublishedToolTemplates).not.toHaveBeenCalled();
    expect(vm.detailTemplates).toEqual([]);
    expect(vm.detailLoading).toBe(false);
  });
  it("keeps call details behind an explicit entry and provides searchable authorized templates", () => {
    const template = readFileSync(
      new URL("../../views/McpCenterView.vue", import.meta.url),
      "utf8"
    );
    expect(McpCenterView.data().detailAdvanced).toBe(false);
    expect(template).toContain('v-if="detailAdvanced"');
    expect(template).toContain("高级信息");
    expect(template).toContain("activeToolSchemaText");
    expect(template).toContain("已授权模板");
    expect(template).toContain("template.content");
    const rows = Array.from({ length: 8 }, (_, i) => ({ title: `模板${i}`, description: "业务" }));
    expect(
      McpCenterView.computed.filteredDetailTemplates.call({
        detailQuery: "模板7",
        detailTemplates: rows,
      })
    ).toEqual([rows[7]]);
    expect(
      McpCenterView.computed.pagedDetailTemplates.call({
        detailPage: 2,
        filteredDetailTemplates: rows,
      })
    ).toEqual(rows.slice(6));
  });
  it("returns to hidden advanced information whenever a tool is reopened", () => {
    const vm = {
      ...McpCenterView.data(),
      detailAdvanced: true,
      loadDetailTemplates: vi.fn(),
      $nextTick: vi.fn(),
    };
    McpCenterView.methods.openToolDetail.call(vm, { localToolName: "a" });
    expect(vm.detailAdvanced).toBe(false);
    vm.detailAdvanced = true;
    McpCenterView.methods.closeToolDetail.call(vm);
    expect(vm.detailAdvanced).toBe(false);
  });
  it("ignores a closed drawer response and never retains previously loaded content on failure", async () => {
    let resolve;
    fetchPublishedToolTemplates.mockReturnValueOnce(new Promise((r) => (resolve = r)));
    const vm = { ...McpCenterView.data(), activeTool: { localToolName: "first" } };
    const pending = McpCenterView.methods.loadDetailTemplates.call(vm);
    McpCenterView.methods.closeToolDetail.call(vm);
    resolve([{ key: "private" }]);
    await pending;
    expect(vm.detailTemplates).toEqual([]);
    vm.activeTool = { localToolName: "second" };
    vm.detailTemplates = [{ key: "previous" }];
    fetchPublishedToolTemplates.mockRejectedValueOnce(new Error("读取失败"));
    await McpCenterView.methods.loadDetailTemplates.call(vm);
    expect(vm.detailTemplates).toEqual([]);
    expect(vm.detailError).toContain("读取失败");
    expect(vm.detailLoading).toBe(false);
  });
  it("defaults to business categories and hides connection and protocol details behind advanced information", () => {
    expect(McpCenterView.data().toolGroupMode).toBe("category");
    const template = readFileSync(
      new URL("../../views/McpCenterView.vue", import.meta.url),
      "utf8"
    );
    expect(template).toContain("已发布工具");
    expect(template).toContain("业务分类");
    expect(template).not.toContain("已注册工具");
    expect(template).not.toContain("后端服务类型");
    expect(template).toContain("连接与运维信息（高级）");
  });
  it("uses the backend business classification consistently for grouping", () => {
    const group = McpCenterView.methods.resolveToolGroup.call(
      { toolGroupMode: "category" },
      { functionalCategory: "客户服务", serviceName: "SQL technical service" }
    );
    expect(group.label).toBe("客户服务");
    expect(group.key).toBe("category:客户服务");
  });
});
