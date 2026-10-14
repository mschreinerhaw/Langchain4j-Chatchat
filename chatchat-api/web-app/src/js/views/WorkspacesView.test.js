// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import WorkspacesView from "../../views/WorkspacesView.vue";

const cleanup = [];
afterEach(() => cleanup.splice(0).forEach(fn => fn()));
function mount(workspaces = catalog(14), extra = {}) {
  const host = document.createElement("div"); document.body.append(host);
  const props = reactive({ workspaces, defaultWorkspaceId: "space-0", ...extra });
  const events = { onSwitchWorkspace: vi.fn(), onDefaultWorkspace: vi.fn(), onReloadWorkspaces: vi.fn() };
  const app = createApp({ render: () => h(WorkspacesView, { ...props, ...events }) });
  app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); });
  const buttons = () => [...host.querySelectorAll("button")];
  return { host, props, events, cards: () => [...host.querySelectorAll(".workspace-card")],
    button: text => buttons().find(button => button.textContent.trim().startsWith(text)),
    async search(query) {
      const input = host.querySelector('input[type="search"]');
      input.value = query; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
    },
    async size(value) {
      const select = host.querySelector("select"); select.value = value;
      select.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    }
  };
}
function catalog(length) {
  return Array.from({ length }, (_, index) => ({ workspaceId: `space-${index}`, name: `空间 ${index}`,
    description: index % 2 ? "Python SQL 数据开发" : "智能协作与知识检索", layoutType: index % 2 ? "studio" : "assistant" }));
}
describe("workspace directory", () => {
  it("paginates authorized spaces with the correct last page and boundaries", async () => {
    const view = mount();
    expect(view.cards()).toHaveLength(6);
    expect(view.button("上一页").disabled).toBe(true);
    view.button("下一页").click(); await nextTick();
    expect(view.cards()[0].textContent).toContain("空间 6");
    view.button("下一页").click(); await nextTick();
    expect(view.cards()).toHaveLength(2);
    expect(view.host.textContent).toContain("13–14 / 14 条");
    expect(view.button("下一页").disabled).toBe(true);
  });
  it("combines case-insensitive description search and category filters, resetting pagination", async () => {
    const view = mount();
    view.button("下一页").click(); await nextTick();
    await view.search("  sql  ");
    expect(view.cards()).toHaveLength(6);
    expect(view.cards()[0].textContent).toContain("空间 1");
    expect(view.host.textContent).toContain("第 1 / 2 页");
    view.button("智能协作").click(); await nextTick();
    expect(view.cards()).toHaveLength(0);
    expect(view.host.textContent).toContain("没有找到匹配的空间");
    view.button("清除筛选").click(); await nextTick();
    expect(view.cards()).toHaveLength(6);
    view.button("数据开发").click(); await nextTick();
    expect(view.cards().every(card => card.textContent.includes("Python SQL"))).toBe(true);
  });
  it("changes page size and clamps the page when refreshed authorization removes spaces", async () => {
    const view = mount(catalog(25));
    view.button("下一页").click(); await nextTick();
    await view.size("12");
    expect(view.cards()).toHaveLength(12);
    expect(view.host.textContent).toContain("第 1 / 3 页");
    view.button("下一页").click(); await nextTick();
    view.props.workspaces = catalog(2); await nextTick();
    expect(view.cards()).toHaveLength(2);
    expect(view.host.textContent).toContain("第 1 / 1 页");
    view.button("数据开发").click(); await nextTick();
    view.props.workspaces = catalog(1); await nextTick();
    expect(view.cards()).toHaveLength(1);
    expect(view.host.querySelector('.workspace-categories [aria-pressed="true"]').textContent).toContain("全部空间");
  });
  it("emits actions for the visible workspace and keeps the saved default disabled", async () => {
    const view = mount();
    expect(view.cards()[0].querySelector(".workspace-button-default").disabled).toBe(true);
    view.cards()[1].querySelector(".workspace-button-primary").click();
    view.cards()[1].querySelector(".workspace-button-default").click();
    expect(view.events.onSwitchWorkspace).toHaveBeenCalledWith("space-1");
    expect(view.events.onDefaultWorkspace).toHaveBeenCalledWith("space-1");
    view.button("刷新").click();
    expect(view.events.onReloadWorkspaces).toHaveBeenCalledOnce();
    view.props.loading = true; await nextTick();
    expect(view.cards().every(card => [...card.querySelectorAll("button")].every(button => button.disabled))).toBe(true);
  });
  it("distinguishes no authorized workspaces from no search results and groups unknown apps safely", async () => {
    const view = mount([]);
    expect(view.host.textContent).toContain("暂无可用工作空间");
    expect(view.host.textContent).toContain("共 0 个空间");
    view.props.workspaces = [{ workspaceId: "future", name: "未来空间", layoutType: "custom" }]; await nextTick();
    expect(view.button("其他应用")).toBeTruthy();
    expect(view.cards()[0].textContent).toContain("在专属空间中");
    await view.search("不存在");
    expect(view.host.textContent).toContain("没有找到匹配的空间");
  });
});
