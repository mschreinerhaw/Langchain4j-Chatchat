import { beforeEach, describe, expect, it, vi } from "vitest";
import TasksView from "./TasksView.js";
import * as api from "../../services/api";

vi.mock("../../services/api", async (original) => ({
  ...await original(),
  fetchAgentRuntimeSummary: vi.fn(), fetchAgentTaskPage: vi.fn(),
  fetchAgentRuntimeToolAudits: vi.fn(), fetchAgentEffectAnalytics: vi.fn(),
  fetchAgentExperiences: vi.fn(), fetchToolGovernance: vi.fn()
}));

function context() {
  const value = { ...TasksView.data.call({ tenantId: "tenant", userId: "owner" }),
    clampRuntimePages: vi.fn(), selectTask: vi.fn(), syncFeedbackDraft: vi.fn() };
  Object.defineProperty(value, "tasks", { get: () => value.taskPageItems });
  return value;
}

beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  api.fetchAgentRuntimeSummary.mockResolvedValue({ totalTasks: 12 });
  api.fetchAgentTaskPage.mockResolvedValue({ items: [{ taskId: "task-a" }], total: 12, page: 1, totalPages: 2 });
  api.fetchAgentRuntimeToolAudits.mockResolvedValue([{ toolName: "tool" }]);
  api.fetchAgentEffectAnalytics.mockResolvedValue({ feedbackTasks: 5 });
  api.fetchToolGovernance.mockResolvedValue({ totalTools: 3 });
  api.fetchAgentExperiences.mockResolvedValue({ totalExperiences: 2 });
});

describe("TasksView independent monitoring loads", () => {
  it("keeps task counters and rows when experiences return 403, then stops automatic retries", async () => {
    api.fetchAgentExperiences.mockRejectedValue(Object.assign(new Error("Document evidence authorization or version is no longer valid"), { status: 403 }));
    const view = context();
    view.experienceSummary = { private: "previously visible source" };
    await TasksView.methods.loadLegacyRuntime.call(view);
    expect(view.summary.totalTasks).toBe(12);
    expect(view.taskTotal).toBe(12);
    expect(view.taskPageItems[0].taskId).toBe("task-a");
    expect(view.effectAnalytics.feedbackTasks).toBe(5);
    expect(view.experienceSummary).toBeNull();
    expect(view.error).toContain("经验库");
    expect(view.error).toContain("暂停自动重试");
    expect(view.error).not.toContain("Document evidence");
    for (let i = 0; i < 4; i++) await TasksView.methods.loadLegacyRuntime.call(view, { silent: true });
    expect(api.fetchAgentExperiences).toHaveBeenCalledTimes(1);
    expect(api.fetchAgentTaskPage).toHaveBeenCalledTimes(5);
  });

  it("retries a blocked section once on explicit refresh and clears the warning after recovery", async () => {
    api.fetchAgentExperiences.mockRejectedValueOnce(Object.assign(new Error("denied"), { status: 403 }));
    const view = context();
    await TasksView.methods.loadLegacyRuntime.call(view);
    await TasksView.methods.loadLegacyRuntime.call(view, { silent: true });
    await TasksView.methods.loadLegacyRuntime.call(view);
    expect(api.fetchAgentExperiences).toHaveBeenCalledTimes(2);
    expect(view.experienceSummary.totalExperiences).toBe(2);
    expect(view.runtimeBlockedSections).toEqual({});
    expect(view.error).toBe("");
  });

  it("does not block recovery from temporary service errors", async () => {
    api.fetchAgentExperiences.mockRejectedValueOnce(Object.assign(new Error("unavailable"), { status: 503 }));
    const view = context();
    await TasksView.methods.loadLegacyRuntime.call(view);
    await TasksView.methods.loadLegacyRuntime.call(view, { silent: true });
    expect(api.fetchAgentExperiences).toHaveBeenCalledTimes(2);
    expect(view.error).toBe("");
  });

  it("clears a formerly selected task when task authorization is revoked", async () => {
    api.fetchAgentTaskPage.mockRejectedValue(Object.assign(new Error("denied"), { status: 403 }));
    const view = context();
    view.selectedTask = { taskId: "old" };
    view.selectedEvents = [{ payload: "private" }];
    view.selectedPlanDag = { private: true };
    view.explorationTimeline = { observations: [{ private: true }], steps: [] };
    await TasksView.methods.loadLegacyRuntime.call(view);
    expect(view.selectedTask).toBeNull();
    expect(view.selectedEvents).toEqual([]);
    expect(view.selectedPlanDag).toBeNull();
    expect(view.explorationTimeline).toEqual({ observations: [], steps: [] });
    expect(view.experienceSummary.totalExperiences).toBe(2);
  });

  it("resets denied sections when switching tenant", async () => {
    const view = context();
    view.runtimeBlockedSections = { experiences: new Error("denied") };
    view.runtimeLoadErrors = { experiences: "denied" };
    view.loadRuntime = vi.fn();
    TasksView.watch.tenantId.call(view, "new-tenant");
    expect(view.runtimeBlockedSections).toEqual({});
    expect(view.runtimeLoadErrors).toEqual({});
    expect(view.loadRuntime).toHaveBeenCalledWith({ silent: true });
  });
});
