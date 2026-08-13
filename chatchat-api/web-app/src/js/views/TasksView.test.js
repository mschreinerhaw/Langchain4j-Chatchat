import { describe, expect, it, vi } from "vitest";
import TasksView from "./TasksView.js";

describe("TasksView persisted plan restoration", () => {
  it("loads the selected task DAG even when the current tab is not the plan tab", async () => {
    const task = { taskId: "task-1", tenantId: "tenant-1" };
    const context = {
      activeTab: "tasks",
      selectedTask: null,
      selectedEventId: "event-old",
      selectedPlanDag: { nodes: [{ id: "old" }] },
      selectedPlanVersions: [{ version: 1 }],
      planLoadedTaskId: "old-task",
      selectedPlanNodeId: "old-node",
      planTaskDetailsOpen: true,
      syncFeedbackDraft: vi.fn(),
      resetRuntimePage: vi.fn(),
      reloadEvents: vi.fn().mockResolvedValue(undefined),
      loadPlanDag: vi.fn().mockResolvedValue(undefined)
    };

    await TasksView.methods.selectTask.call(context, task);

    expect(context.selectedTask).toBe(task);
    expect(context.planLoadedTaskId).toBe("");
    expect(context.reloadEvents).toHaveBeenCalledOnce();
    expect(context.loadPlanDag).toHaveBeenCalledWith({ silent: true });
  });
});
