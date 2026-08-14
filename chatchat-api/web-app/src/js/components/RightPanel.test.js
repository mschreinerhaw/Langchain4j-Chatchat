import { describe, expect, it } from "vitest";
import RightPanel from "./RightPanel.js";

describe("RightPanel personal todos", () => {
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
});
