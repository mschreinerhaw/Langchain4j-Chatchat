import { describe, expect, it, vi } from "vitest";
import RightPanel from "./RightPanel.js";

describe("RightPanel personal todos", () => {
  it("opens the plus button directly in a blank sticky-note editor", () => {
    const focus = vi.fn();
    const context = {
      todoManagerOpen: false,
      editingTodo: { id: "old" },
      todoEditorMode: "list",
      todoError: "old error",
      todoDraft: { title: "old" },
      $refs: { todoTitleInput: { focus } },
      $nextTick: (callback) => callback()
    };

    RightPanel.methods.openNewTodoEditor.call(context);

    expect(context.todoManagerOpen).toBe(true);
    expect(context.todoEditorMode).toBe("create");
    expect(context.editingTodo).toBeNull();
    expect(context.todoDraft).toEqual({ title: "", notes: "", dueAt: "", important: false });
    expect(focus).toHaveBeenCalledOnce();
  });

  it("starts all business modules collapsed and toggles them independently", () => {
    const context = {
      collapsedModules: RightPanel.data().collapsedModules
    };

    expect(Object.values(context.collapsedModules).every(Boolean)).toBe(true);
    RightPanel.methods.toggleModule.call(context, "reports");
    expect(context.collapsedModules).toEqual({
      todos: true,
      reports: false,
      favorites: true,
      agents: true
    });
  });

  it("shows only unfinished todos in the compact sidebar", () => {
    const context = {
      personalTodos: [
        { id: "1", title: "整理周报", completed: false },
        { id: "2", title: "阅读文档", completed: true }
      ]
    };

    expect(RightPanel.computed.activeTodoCount.call(context)).toBe(1);
    expect(RightPanel.computed.visibleTodos.call(context)).toEqual([context.personalTodos[0]]);
  });

  it("uses the real tenant id for personal workbench data", () => {
    expect(RightPanel.computed.effectiveTenantId.call({
      tenantId: "tenant-1",
      displayUserId: "alice"
    })).toBe("tenant-1");
  });

  it("does not invent a tenant from the user id", () => {
    expect(RightPanel.computed.effectiveTenantId.call({
      tenantId: "",
      displayUserId: "alice"
    })).toBe("");
  });

  it("drops shortcut entries returned for another tenant", () => {
    const context = {
      effectiveTenantId: "tenant-1"
    };
    const items = [
      { targetId: "doc-1", tenantId: "tenant-1" },
      { targetId: "doc-2", tenantId: "tenant-2" }
    ];

    expect(RightPanel.methods.tenantScopedItems.call(context, items)).toEqual([items[0]]);
  });
});
