import { describe, expect, it, vi } from "vitest";
import App from "./App.js";
import AiSearchView from "./views/AiSearchView.js";
import ChatAssistantView from "./views/ChatAssistantView.js";

describe("document Ask AI conversation isolation", () => {
  it("exposes data science sections as sidebar child routes", () => {
    const state = App.data();
    const capability = state.navItems.find((group) => group.id === "capability");
    const dataScience = capability.items.find((item) => item.id === "dataScience");

    expect(dataScience.children.map((item) => item.id)).toEqual([
      "dataScienceEnvironment",
      "dataScienceDevelop",
      "dataScienceData",
      "dataScienceScripts"
    ]);

    const props = App.computed.activeComponentProps.call({
      activeView: "dataScienceData",
      userId: "user-1",
      tenantId: "tenant-1"
    });
    expect(props.initialTab).toBe("data");
  });

  it("marks document result questions as new-session drafts", () => {
    const emit = vi.fn();
    const result = { docId: "doc-1", title: "Spark document", summary: "Document summary" };
    const context = {
      searchedKeyword: "spark",
      keyword: "",
      $emit: emit,
      buildAskAiPrompt: vi.fn(() => "请分析这份文档"),
      recordDocumentActivity: vi.fn()
    };

    AiSearchView.methods.askAiAboutResult.call(context, result);

    expect(emit).toHaveBeenCalledWith("ask-ai", expect.objectContaining({
      documentId: "doc-1",
      newSession: true,
      prompt: "请分析这份文档"
    }));
  });

  it("routes a document question into a new conversation draft", () => {
    const context = {
      selectedConversation: { id: "conversation-old" },
      activeHistoryId: "conversation-old",
      pendingChatDraft: null,
      navigateToView: vi.fn()
    };

    App.methods.handleAskAiFromSearch.call(context, {
      id: "document-draft-1",
      title: "Spark document",
      prompt: "请分析这份文档",
      newSession: true
    });

    expect(context.selectedConversation).toBeNull();
    expect(context.activeHistoryId).toBe("");
    expect(context.pendingChatDraft).toMatchObject({
      id: "document-draft-1",
      prompt: "请分析这份文档",
      newSession: true
    });
    expect(context.navigateToView).toHaveBeenCalledWith("chat");
  });

  it("clears the active chat before applying a new-session document prompt", () => {
    const clearChat = vi.fn();
    const focusComposer = vi.fn();
    const context = {
      appliedDraftId: "",
      selectedAgentId: "",
      question: "旧问题",
      uploadNotice: "",
      clearChat,
      $refs: { promptComposer: { focusComposer } },
      $nextTick: (callback) => callback()
    };

    ChatAssistantView.methods.applyPendingDraft.call(context, {
      id: "document-draft-2",
      title: "Spark document",
      prompt: "请总结这份文档",
      newSession: true
    });

    expect(clearChat).toHaveBeenCalledOnce();
    expect(context.question).toBe("请总结这份文档");
    expect(context.uploadNotice).toContain("Spark document");
    expect(focusComposer).toHaveBeenCalledOnce();
  });
});
