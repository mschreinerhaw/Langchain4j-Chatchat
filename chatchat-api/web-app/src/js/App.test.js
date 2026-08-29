import { describe, expect, it, vi } from "vitest";
import App from "./App.js";
import AiSearchView from "./views/AiSearchView.js";
import ChatAssistantView from "./views/ChatAssistantView.js";
import AssistantSidebar from "./components/AssistantSidebar.js";

describe("document Ask AI conversation isolation", () => {
  it("does not emit delete for an in-progress conversation", () => {
    const emit = vi.fn();
    const context = {
      $emit: emit,
      resolveStatus: AssistantSidebar.methods.resolveStatus,
      isConversationInProgress: AssistantSidebar.methods.isConversationInProgress
    };

    AssistantSidebar.methods.deleteConversation.call(context, { id: "running-1", status: "running" });
    expect(emit).not.toHaveBeenCalled();

    AssistantSidebar.methods.deleteConversation.call(context, { id: "completed-1", status: "completed" });
    expect(emit).toHaveBeenCalledWith("delete-conversation", { id: "completed-1", status: "completed" });
  });

  it("requires the styled confirmation dialog before deleting a conversation card", () => {
    const emit = vi.fn();
    const context = {
      $emit: emit,
      $nextTick: (callback) => callback(),
      $refs: {},
      deleteConversationCandidate: null,
      conversationMenuKey: "completed-1",
      resolveStatus: AssistantSidebar.methods.resolveStatus,
      isConversationInProgress: AssistantSidebar.methods.isConversationInProgress,
      closeConversationMenu: AssistantSidebar.methods.closeConversationMenu,
      deleteConversation: AssistantSidebar.methods.deleteConversation
    };
    const conversation = { id: "completed-1", status: "completed" };

    AssistantSidebar.methods.openDeleteConversationDialog.call(context, conversation);
    expect(emit).not.toHaveBeenCalled();
    expect(context.deleteConversationCandidate).toBe(conversation);

    AssistantSidebar.methods.confirmDeleteConversation.call(context);
    expect(context.deleteConversationCandidate).toBeNull();
    expect(emit).toHaveBeenCalledWith("delete-conversation", conversation);
  });

  it("opens rename from the conversation menu and emits the confirmed title", () => {
    const emit = vi.fn();
    const conversation = { id: "completed-1", question: "旧会话名称", status: "completed" };
    const context = {
      $emit: emit,
      $nextTick: (callback) => callback(),
      $refs: {},
      conversationMenuKey: "completed-1",
      renameConversationCandidate: null,
      renameConversationTitle: "",
      conversationTitle: AssistantSidebar.methods.conversationTitle,
      closeConversationMenu: AssistantSidebar.methods.closeConversationMenu,
      closeRenameConversationDialog: AssistantSidebar.methods.closeRenameConversationDialog
    };

    AssistantSidebar.methods.openRenameConversationDialog.call(context, conversation);
    expect(context.conversationMenuKey).toBe("");
    expect(context.renameConversationCandidate).toBe(conversation);
    expect(context.renameConversationTitle).toBe("旧会话名称");

    context.renameConversationTitle = "新的会话名称";
    AssistantSidebar.methods.confirmRenameConversation.call(context);

    expect(context.renameConversationCandidate).toBeNull();
    expect(emit).toHaveBeenCalledWith("rename-conversation", {
      conversation,
      title: "新的会话名称"
    });
  });

  it("opens a styled dialog before bulk-deleting selected conversations", () => {
    const emit = vi.fn();
    const selected = [{ id: "completed-1", status: "completed" }];
    const context = {
      $emit: emit,
      $refs: {},
      $nextTick: (callback) => callback(),
      deleteConfirmOpen: false,
      historyDeleting: false,
      selectedManagerConversations: selected
    };

    AssistantSidebar.methods.deleteSelectedHistory.call(context);
    expect(context.deleteConfirmOpen).toBe(true);
    expect(emit).not.toHaveBeenCalled();

    AssistantSidebar.methods.confirmDeleteSelectedHistory.call(context);
    expect(context.deleteConfirmOpen).toBe(false);
    expect(emit).toHaveBeenCalledWith("delete-conversations", selected);
  });

  it("exposes data science sections as sidebar child routes", () => {
    const state = App.data();
    const capability = state.navItems.find((group) => group.id === "capability");
    const dataScience = capability.items.find((item) => item.id === "dataScience");

    expect(dataScience.permissionCode).toBe("capability:data-science");
    expect(dataScience.children.every((item) => item.permissionCode === "capability:data-science")).toBe(true);

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
