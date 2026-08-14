import { describe, expect, it, vi } from "vitest";
import TasksView from "./TasksView.js";

describe("TasksView persisted plan restoration", () => {
  it("localizes long terminal task statuses for the compact task table", () => {
    expect(TasksView.methods.formatTaskStatus("NO_PRESENTABLE_RESULT")).toBe("无可展示结果");
    expect(TasksView.methods.formatTaskStatus("TIME_BUDGET_EXHAUSTED")).toBe("执行超时");
    expect(TasksView.methods.formatTaskStatus("PARTIAL_SUCCESS")).toBe("部分完成");
    expect(TasksView.methods.statusClass("NO_PRESENTABLE_RESULT")).toMatchObject({ failed: true });
    expect(TasksView.methods.statusClass("PARTIAL_SUCCESS")).toMatchObject({ partial: true });
  });

  it("displays the tenant name while preserving the internal tenant id", () => {
    const context = {
      tenantName: "星河科技",
      runtimeTenantId: "9001fee4-482b-4851-9eb"
    };
    context.runtimeTenantName = TasksView.computed.runtimeTenantName.call(context);

    expect(context.runtimeTenantName).toBe("星河科技");
    expect(TasksView.methods.tenantLabel.call(context, context.runtimeTenantId)).toBe("星河科技");
    expect(context.runtimeTenantId).toBe("9001fee4-482b-4851-9eb");
  });

  it("sizes the tenant field from the visible tenant name", () => {
    expect(TasksView.computed.runtimeTenantFieldWidth.call({ runtimeTenantName: "默认租户" })).toBe("96px");
    expect(TasksView.computed.runtimeTenantFieldWidth.call({ runtimeTenantName: "华东区域生产环境数据智能中心" }))
      .toBe("220px");
    expect(TasksView.computed.runtimeTenantFieldWidth.call({ runtimeTenantName: "超长租户名称".repeat(20) }))
      .toBe("280px");
  });

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

  it("focuses the matching DAG node when a detail card is activated", async () => {
    const selectNode = vi.fn();
    const scrollIntoView = vi.fn();
    const context = {
      selectedPlanNodeId: "",
      $refs: { planDagGraph: { selectNode }, planDagCanvas: { scrollIntoView } },
      $nextTick: (callback) => callback()
    };

    TasksView.methods.focusPlanNode.call(context, "step-2");

    expect(context.selectedPlanNodeId).toBe("step-2");
    expect(selectNode).toHaveBeenCalledWith("step-2");
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "nearest" });
  });

  it("scrolls the matching detail card into view when a DAG node is selected", async () => {
    const scrollIntoView = vi.fn();
    const context = {
      selectedPlanNodeId: "",
      $refs: {
        planNodeList: {
          children: [
            { dataset: { planNodeId: "step-1" } },
            { dataset: { planNodeId: "step-2" }, scrollIntoView }
          ]
        }
      },
      $nextTick: (callback) => callback()
    };

    TasksView.methods.handlePlanNodeSelect.call(context, "step-2");

    expect(context.selectedPlanNodeId).toBe("step-2");
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "nearest", inline: "nearest" });
  });

  it("normalizes stale running DAG nodes to completed after the task ends", () => {
    const context = {
      selectedTask: { status: "SUCCESS" },
      isActiveTask: TasksView.methods.isActiveTask
    };

    expect(TasksView.methods.resolvePlanNodeStatus.call(context, { status: "running" })).toBe("COMPLETED");
    expect(TasksView.methods.resolvePlanNodeStatus.call(context, { status: "failed" })).toBe("FAILED");
  });

  it("uses the latest workflow event while an active task is still running", () => {
    const context = {
      selectedTask: { status: "RUNNING" },
      selectedEvents: [
        {
          eventId: "call",
          type: "TOOL_CALL",
          status: "WAIT_TOOL",
          toolName: "asset_query",
          createTime: 100,
          payload: "{}"
        },
        {
          eventId: "result",
          type: "TOOL_RESULT",
          status: "RUNNING",
          toolName: "asset_query",
          createTime: 200,
          payload: "{\"success\":true}"
        }
      ],
      isActiveTask: TasksView.methods.isActiveTask,
      parseEventPayload: TasksView.methods.parseEventPayload,
      latestPlanNodeEvent: TasksView.methods.latestPlanNodeEvent,
      planStatusFromEvent: TasksView.methods.planStatusFromEvent
    };

    expect(TasksView.methods.resolvePlanNodeStatus.call(context, { toolName: "asset_query", status: "running" }))
      .toBe("COMPLETED");
  });
});
