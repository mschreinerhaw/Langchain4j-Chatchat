import { describe, expect, it } from "vitest";

import ChatAssistantView, { collapseDuplicateAssistantResults } from "./ChatAssistantView";

describe("restored assistant result deduplication", () => {
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
});
