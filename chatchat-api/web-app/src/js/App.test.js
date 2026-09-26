import { describe, expect, it, vi } from "vitest";
import App from "./App.js";
import AiSearchView from "./views/AiSearchView.js";
import ChatAssistantView from "./views/ChatAssistantView.js";
import AssistantSidebar from "./components/AssistantSidebar.js";
import SystemManagementView from "./views/SystemManagementView.js";

describe("document Ask AI conversation isolation", () => {
  it("opens joint analysis with the Agent selected in management", () => {
    const context = { pendingAnalysisProvider: null, analysisSelectionSerial: 0,
      canAccessView: () => true, navigateToView: vi.fn() };
    App.methods.handleAnalyzeWithAgent.call(context, "group.risk");
    expect(context.pendingAnalysisProvider).toEqual({ providerId: "group.risk", requestId: 1 });
    expect(context.navigateToView).toHaveBeenCalledWith("domainAnalysis");
    expect(App.computed.activeComponentProps.call({ activeView: "domainAnalysis",
      userId: "user-1", tenantId: "tenant-1", pendingAnalysisProvider: context.pendingAnalysisProvider
    }).initialProviderSelection).toEqual(context.pendingAnalysisProvider);
    expect(App.computed.activeComponentProps.call({ activeView: "agents",
      userId: "user-1", tenantId: "tenant-1",
      canAccessView: (view) => view === "domainAnalysis"
    })).toMatchObject({ analysisAvailable: true, chatAvailable: false });
  });
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

  it("does not package a hard-coded menu catalog", () => {
    const state = App.data();
    expect(state.navItems).toEqual([]);

    const props = App.computed.activeComponentProps.call({
      activeView: "dataScienceData",
      userId: "user-1",
      tenantId: "tenant-1"
    });
    expect(props.initialTab).toBe("data");
  });

  it("routes the database-backed system menu to its default child view", () => {
    const context = {
      activeView: "",
      canAccessView: () => true,
      setHashRoute: vi.fn()
    };
    App.methods.navigateToView.call(context, "system");
    expect(context.activeView).toBe("systemUsers");
    expect(context.setHashRoute).toHaveBeenCalledWith("systemUsers");
  });

  it("reconciles an empty post-login view after database menus become available", () => {
    const context = {
      activeView: "",
      navigateToView: vi.fn()
    };

    App.methods.reconcileAuthorizedRoute.call(context, "chat");

    expect(context.navigateToView).toHaveBeenCalledWith("chat");
  });

  it("waits for database menus before selecting the post-login route", async () => {
    const sequence = [];
    const context = {
      authSession: null,
      userId: "",
      tenantId: "",
      tenantName: "",
      loadTrendSemanticConfig: vi.fn(),
      consumeRedirectView: vi.fn(() => "library"),
      loadEnterpriseMenus: vi.fn(async () => sequence.push("menus")),
      reconcileAuthorizedRoute: vi.fn((view) => sequence.push(`route:${view}`)),
      stopIdleLogoutWatcher: vi.fn(),
      startIdleLogoutWatcher: vi.fn(),
      loadConversationHistory: vi.fn(),
      loadFavoriteConversationIds: vi.fn(),
      loadRuntimeTodos: vi.fn(),
      startTodoRefresh: vi.fn(),
      handleUnauthenticated: vi.fn()
    };

    await App.methods.handleLoginSuccess.call(context, {
      token: "token-1",
      user: { id: "user-1", username: "admin", tenantId: "tenant-1" }
    });

    expect(context.loadEnterpriseMenus).toHaveBeenCalledWith({ reconcileRoute: false });
    expect(sequence).toEqual(["menus", "route:library"]);
  });

  it("shows the matching title for each system management page", () => {
    const labels = {
      users: "用户管理",
      organizations: "组织管理",
      roles: "角色管理",
      logins: "登录审计",
      resources: "资源授权"
    };
    for (const [section, title] of Object.entries(labels)) {
      const context = { section };
      expect(SystemManagementView.data.call(context).activeManagementTab).toBe(section);
      expect(SystemManagementView.computed.sectionTitle.call(context)).toBe(title);
      expect(SystemManagementView.computed.sectionDescription.call(context)).toBeTruthy();
    }
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
