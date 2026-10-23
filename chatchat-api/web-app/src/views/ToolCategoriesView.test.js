// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import { it, expect, vi, beforeEach, afterEach } from "vitest";
import ToolCategoriesView from "./ToolCategoriesView.vue";
import { apiRequest } from "../services/api.js";
vi.mock("../services/api.js", () => ({
  apiRequest: vi.fn(),
  getStoredAuthSession: () => ({ user: { permissionCodes: ["mcp:publication:edit"] } }),
}));
let app, root, rows;
const flush = async () => {
  await new Promise((r) => setTimeout(r, 0));
  await nextTick();
};
beforeEach(() => {
  vi.resetAllMocks();
  rows = [
    { id: "a", name: "客户分析", version: 3, toolCount: 2 },
    { id: "b", name: "数据核验", version: 0, toolCount: 0 },
  ];
  apiRequest.mockImplementation(async (path, options) => (options ? true : rows));
});
afterEach(() => {
  app?.unmount();
  root?.remove();
});
async function mount() {
  root = document.createElement("div");
  document.body.append(root);
  app = createApp(ToolCategoriesView);
  const vm = app.mount(root);
  await flush();
  return vm;
}
it("searches and paginates categories with tool counts", async () => {
  rows = Array.from({ length: 13 }, (_, i) => ({
    id: `${i}`,
    name: `分类${i}`,
    version: 0,
    toolCount: i,
  }));
  const vm = await mount();
  expect(vm.pages).toBe(2);
  expect(vm.visible).toHaveLength(10);
  vm.page = 2;
  await flush();
  expect(vm.visible).toHaveLength(3);
  vm.query = "分类12";
  await flush();
  expect(vm.page).toBe(1);
  expect(vm.filtered).toHaveLength(1);
});
it("cancels deletion without writes and explains how related tools will be retained", async () => {
  const vm = await mount();
  await vm.open("delete", rows[0]);
  await flush();
  expect(root.querySelector("dialog").textContent).toContain("2 个工具");
  expect(root.querySelector("dialog").textContent).toContain("工具不会被删除");
  expect(vm.targets.map((row) => row.id)).toEqual(["b"]);
  vm.close();
  await flush();
  expect(root.querySelector("dialog")).toBeNull();
  expect(apiRequest).toHaveBeenCalledTimes(1);
});
it("submits an explicit transfer and current version and count before deleting", async () => {
  const vm = await mount();
  await vm.open("delete", rows[0]);
  vm.targetId = "b";
  await vm.submit();
  expect(apiRequest).toHaveBeenCalledWith("/tool-categories/a", {
    method: "DELETE",
    body: JSON.stringify({ expectedVersion: 3, expectedToolCount: 2, targetCategoryId: "b" }),
  });
  expect(vm.notice).toContain("2 个工具已移至“数据核验”");
  expect(vm.mode).toBe("");
});
it("rejects duplicate and reserved names before sending and preserves input on server failure", async () => {
  const vm = await mount();
  await vm.open("create");
  vm.name = " 客户分析 ";
  await vm.submit();
  expect(vm.dialogError).toContain("已存在");
  expect(apiRequest).toHaveBeenCalledTimes(1);
  vm.name = "未分类";
  await vm.submit();
  expect(vm.dialogError).toContain("系统使用");
  vm.name = "经营分析";
  apiRequest.mockRejectedValueOnce(new Error("分类已被修改，请刷新后重试"));
  await vm.submit();
  expect(vm.name).toBe("经营分析");
  expect(vm.mode).toBe("create");
  expect(vm.dialogError).toContain("请刷新");
  expect(vm.saving).toBe(false);
});
it("renames with a version and updates stale deletion details after refresh", async () => {
  const vm = await mount();
  await vm.open("rename", rows[0]);
  vm.name = "客户洞察";
  await vm.submit();
  expect(apiRequest).toHaveBeenCalledWith("/tool-categories/a", {
    method: "PUT",
    body: JSON.stringify({ name: "客户洞察", expectedVersion: 3 }),
  });
  await vm.open("delete", rows[0]);
  rows = [{ ...rows[0], toolCount: 4, version: 4 }, rows[1]];
  await vm.reloadDialog();
  expect(vm.selected.toolCount).toBe(4);
  expect(vm.selected.version).toBe(4);
});
