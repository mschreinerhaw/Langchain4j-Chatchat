import { describe, expect, it, vi } from "vitest";
import App from "./App.js";
import AiSearchView from "./views/AiSearchView.js";
import ChatAssistantView from "./views/ChatAssistantView.js";
import AssistantSidebar from "./components/AssistantSidebar.js";
import SystemManagementView from "./views/SystemManagementView.js";
import * as api from "../services/api.js";
import { setWorkspaceContext } from "./utils/workspaceContext";

describe("removed product routes", () => {
  it.each(["systemSkillRoles", "permissionTools"])("replaces the retired URL after checking page access (%s)", (page) => {
    const replaceState = vi.fn();
    vi.stubGlobal("window", { location: { hash: `#/w/permissions/${page}` }, history: { replaceState } });
    const context = { authSession: { token: "test" }, currentWorkspace: { workspaceId: "permissions" }, canAccessView: () => true };
    try {
      App.methods.handleHashChange.call(context);
      expect(context.activeView).toBe("systemResources");
      expect(replaceState).toHaveBeenCalledWith(undefined, "", "#/w/permissions/systemResources");
    } finally { vi.unstubAllGlobals(); }
  });
  it("rejects a removed component even if stale navigation still declares it", () => {
    const context = { workspaceReady: false, navItems: [{ items: [{ id: "domainAnalysis" }, { id: "chat" }] }] };
    expect(App.methods.canAccessView.call(context, "domainAnalysis")).toBe(false);
    expect(App.methods.canAccessView.call(context, "chat")).toBe(true);
  });
});

describe("history selected during workspace startup", () => {
  it.each([false, true])("preserves only an initial selection, without leaking it across workspace switches (%s)", async switching => {
    let resolve;
    const manifest = vi.spyOn(api, "fetchWorkspaceManifest").mockImplementation(() => new Promise(done => { resolve = done; }));
    const preferences = vi.spyOn(api, "saveWorkspacePreferences").mockResolvedValue({});
    vi.stubGlobal("window", { location: { hash: "#/w/zhice/chat" } });
    const context = { authSession: { user: { id: "owner" } }, tenantId: "tenant", workspaceNavigationSerial: 0,
      currentWorkspace: switching ? { workspaceId: "other" } : null, workspaceContexts: {}, setHashRoute: vi.fn() };
    try {
      const pending = App.methods.switchWorkspace.call(context, "zhice", "tasks");
      const selection = { id: "selected-before-ready", messages: [{ role: "user", content: "saved question" }] };
      context.selectedConversation = selection; context.activeHistoryId = selection.id;
      resolve({ manifest: { workspaceId: "zhice", pages: [{ id: "chat", componentKey: "chat" },
        { id: "tasks", componentKey: "tasks" }], entryPage: "chat" }, menus: [] });
      await pending;
      expect(context.workspaceReady).toBe(true);
      expect(context.selectedConversation).toBe(switching ? null : selection);
      expect(context.activeHistoryId).toBe(switching ? "" : selection.id);
      expect(context.activeView).toBe(switching ? "tasks" : "chat");
      if (switching) expect(context.workspaceContexts.other.selectedConversation).toBe(selection);
    } finally { manifest.mockRestore(); preferences.mockRestore(); vi.unstubAllGlobals(); setWorkspaceContext(null); }
  });
});

describe("incomplete history restoration", () => {
  it("reloads missing bodies when reopening a partially hydrated conversation", async () => {
    const fetch = vi.spyOn(api, "fetchConversationDetail").mockResolvedValue({
      id: "session", messages: [{ id: "restored", role: "assistant", content: "saved answer" }],
      missingMessageCount: 0
    });
    const conversation = { id: "session", detailsLoaded: true, missingMessageCount: 1,
      messages: [{ id: "question", role: "user", content: "saved question" }] };
    const context = { tenantId: "tenant", navigateToView: vi.fn(), conversationHistory: [conversation] };
    try {
      App.methods.upsertLocalConversation.call(context, { id: "session", status: "completed", messages: conversation.messages });
      expect(context.conversationHistory[0].missingMessageCount).toBe(1);
      await App.methods.selectConversation.call(context, context.conversationHistory[0]);
      expect(fetch).toHaveBeenCalledWith("session", "tenant");
      expect(context.selectedConversation.missingMessageCount).toBe(0);
      expect(context.selectedConversation.messages[0].id).toBe("restored");
    } finally { fetch.mockRestore(); }
  });
});

describe("retired execution trace page", () => {
  it("does not resolve the retired component even with a stale workspace page registration", () => {
    const context = { menuAuthorizationReady: true, activeView: "runtime", canAccessView: () => true,
      workspacePages: [{ id: "runtime", componentKey: "runtime" }] };
    const retired = App.computed.activeComponent.call(context);
    const unavailable = App.computed.activeComponent.call({ ...context, activeView: "not-a-page", workspacePages: [] });
    expect(retired).toBe(unavailable);
  });
});

describe("history pagination request ordering", () => {
  it("keeps the visible page while loading and ignores an older response", async () => {
    let finishFirst, finishSecond;
    const fetch = vi.spyOn(api, "fetchConversationHistoryPage")
      .mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }))
      .mockImplementationOnce(() => new Promise(resolve => { finishSecond = resolve; }));
    const visible = [{ id: "visible" }];
    const context = { authSession: {}, userId: "user", tenantId: "tenant", historyManagerRequestSerial: 0,
      historyManagerPageSize: 10, historyManagerItems: visible };
    try {
      const first = App.methods.loadHistoryManager.call(context, { page: 2 });
      const second = App.methods.loadHistoryManager.call(context, { page: 3 });
      expect(context.historyManagerItems).toBe(visible);
      finishFirst({ items: [{ id: "older" }], page: 2 });
      await first;
      expect(context.historyManagerLoading).toBe(true);
      expect(context.historyManagerItems).toBe(visible);
      finishSecond({ items: [{ id: "latest" }], page: 3, total: 21, totalPages: 3 });
      await second;
      expect(context.historyManagerItems).toEqual([{ id: "latest" }]);
      expect(context.historyManagerPage).toBe(3);
      expect(context.historyManagerLoading).toBe(false);
    } finally { fetch.mockRestore(); }
  });

  it("retains the current records and page when pagination fails", async () => {
    const fetch = vi.spyOn(api, "fetchConversationHistoryPage").mockRejectedValue(new Error("offline"));
    const context = { authSession: {}, userId: "user", tenantId: "tenant", historyManagerRequestSerial: 0,
      historyManagerPageSize: 10, historyManagerPage: 1, historyManagerItems: [{ id: "visible" }] };
    try {
      await App.methods.loadHistoryManager.call(context, { page: 2 });
      expect(context.historyManagerItems).toEqual([{ id: "visible" }]);
      expect(context.historyManagerPage).toBe(1);
      expect(context.historyManagerLoading).toBe(false);
      expect(context.historyError).toBe("offline");
    } finally { fetch.mockRestore(); }
  });
});

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
      loadWorkspaces: vi.fn(async () => sequence.push("authorized-workspace")),
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

    expect(context.loadWorkspaces).toHaveBeenCalledWith({ requestedView: "library" });
    expect(sequence).toEqual(["authorized-workspace"]);
    await App.methods.handleLoginSuccess.call(context, {
      token: "token-2", user: { id: "user-1", username: "admin", tenantId: "tenant-1" }
    }, "data-science");
    expect(context.loadWorkspaces).toHaveBeenLastCalledWith({ selectedWorkspaceId: "data-science", requestedView: "library" });
  });

  it("shows the matching title for each system management page", () => {
    const labels = {
      users: "用户管理",
      organizations: "组织管理",
      logins: "安全审计"
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
