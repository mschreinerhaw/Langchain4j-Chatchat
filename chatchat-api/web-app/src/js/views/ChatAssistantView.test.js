import { describe, expect, it } from "vitest";

import ChatAssistantView, {
  collapseDuplicateAssistantResults,
  mergeExecutionSteps
} from "./ChatAssistantView";

describe("restored assistant result deduplication", () => {
  it("routes a role Agent to the tool-free role-chat mode", () => {
    expect(ChatAssistantView.methods.agentInteractionMode.call({}, { defaultMode: "role_chat" }))
      .toBe("role_chat");
    expect(ChatAssistantView.methods.agentInteractionMode.call({}, { defaultMode: "llm_chat" }))
      .toBe("role_chat");
  });

  it("keeps tool Agents on the agent Runtime", () => {
    expect(ChatAssistantView.methods.agentInteractionMode.call({}, { defaultMode: "agent_chat" }))
      .toBe("agent_chat");
  });

  it("opens the product confirmation dialog before deleting an answer", () => {
    const message = { id: "answer-1", role: "assistant", content: "回答内容" };
    const context = {
      conversationId: "conversation-1",
      loading: false,
      deleteMessageCandidate: null,
      $refs: {},
      $nextTick: (callback) => callback()
    };

    ChatAssistantView.methods.deleteMessage.call(context, message);

    expect(context.deleteMessageCandidate).toBe(message);
  });

  it("keeps one rich result when two restored messages share a task id", () => {
    const plain = {
      id: "assistant-history",
      role: "assistant",
      content: "Plain persisted answer",
      taskId: "task-1"
    };
    const rich = {
      id: "assistant-runtime",
      role: "assistant",
      content: "Rich runtime answer",
      taskId: "task-1",
      steps: [{ type: "TOOL_RESULT" }]
    };

    expect(collapseDuplicateAssistantResults([plain, rich])).toEqual([
      expect.objectContaining({
        id: "assistant-history",
        content: "Rich runtime answer",
        taskId: "task-1"
      })
    ]);
  });

  it("compares raw content when artifact answers are different", () => {
    const artifact = {
      id: "assistant-artifact",
      role: "assistant",
      content: "## Customer analysis\n\nThe complete answer",
      uiResponse: { answer: "Artifact preview" }
    };
    const runtime = {
      id: "assistant-runtime",
      role: "assistant",
      content: "## Customer analysis The complete answer",
      uiResponse: { answer: "Runtime presentation" },
      steps: [{ type: "MODEL_RESULT" }]
    };

    expect(collapseDuplicateAssistantResults([artifact, runtime])).toHaveLength(1);
    expect(collapseDuplicateAssistantResults([artifact, runtime])[0].content)
      .toBe("## Customer analysis The complete answer");
  });

  it("keeps adjacent results from different tasks", () => {
    const first = { role: "assistant", content: "First", taskId: "task-1" };
    const second = { role: "assistant", content: "Second", taskId: "task-2" };

    expect(collapseDuplicateAssistantResults([first, second])).toHaveLength(2);
  });

  it("appends ordered runtime events incrementally without dropping earlier rows", () => {
    const first = mergeExecutionSteps([], [{
      eventId: "event-1",
      sequence: 1,
      type: "QUESTION",
      status: "RUNNING",
      payload: "{}"
    }]);
    const second = mergeExecutionSteps(first, [{
      eventId: "event-2",
      sequence: 2,
      type: "PLAN",
      status: "RUNNING",
      payload: "{}"
    }]);

    expect(second).toHaveLength(2);
    expect(second.map((step) => step.id)).toEqual(["receive-question", "planning"]);
  });

  it("refreshes the active model step from stream heartbeats without adding duplicate rows", () => {
    const waiting = mergeExecutionSteps([], [{
      eventId: "event-wait-model",
      sequence: 3,
      type: "STATUS",
      status: "WAIT_MODEL",
      createTime: 1_000,
      payload: JSON.stringify({ message: "Agent task is waiting for model inference" })
    }]);
    const refreshed = mergeExecutionSteps(waiting, [{
      sequence: 3,
      type: "HEARTBEAT",
      status: "WAIT_MODEL",
      createTime: 3_000,
      payload: "{}"
    }]);

    expect(refreshed).toHaveLength(1);
    expect(refreshed[0]).toEqual(expect.objectContaining({
      id: "model-inference",
      status: "active",
      timestamp: 3_000
    }));
    expect(refreshed[0].detail).toContain("立即展示后续步骤");
  });

  it("completes a runtime tool when its observation arrives in a later stream batch", () => {
    const running = mergeExecutionSteps([], [{
      eventId: "runtime-step-1", sequence: 10, type: "RUNTIME_STEP", status: "RUNNING",
      payload: JSON.stringify({ payload: { stepId: "step-1", toolName: "template_query", action: "execute" } })
    }]);
    const completed = mergeExecutionSteps(running, [{
      eventId: "runtime-observation-1", sequence: 11, type: "RUNTIME_OBSERVATION", status: "RUNNING",
      payload: JSON.stringify({ payload: { source: "template_query", contentPreview: "completed" } })
    }]);

    expect(completed).toHaveLength(1);
    expect(completed[0]).toEqual(expect.objectContaining({
      id: "runtime-tool:step-1:template_query", toolName: "template_query", status: "done"
    }));
  });

  it("keeps the structured model review phase aligned across start and completion events", () => {
    const event = (eventId, sequence, eventState) => ({
      eventId, sequence, type: "RUNTIME_OBSERVATION", status: "RUNNING",
      payload: JSON.stringify({ payload: { metadata: {
        eventKind: "MODEL_INFERENCE", eventState,
        modelPhase: "tool_result_review", stepId: "step-1"
      } } })
    });
    const started = mergeExecutionSteps([], [event("review-started", 20, "STARTED")]);
    const completed = mergeExecutionSteps(started, [event("review-completed", 21, "COMPLETED")]);

    expect(completed).toHaveLength(1);
    expect(completed[0]).toEqual(expect.objectContaining({
      id: "runtime-observation:model-inference:tool_result_review:step-1",
      title: "模型审查工具结果", status: "done"
    }));
  });
});
