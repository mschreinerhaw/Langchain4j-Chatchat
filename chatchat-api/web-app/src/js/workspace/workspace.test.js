// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
vi.mock("monaco-editor/esm/vs/editor/editor.api", () => ({ editor: {}, languages: {}, Uri: { parse: vi.fn() } }));
vi.mock("monaco-editor/esm/vs/editor/editor.all", () => ({}));
vi.mock("monaco-editor/esm/vs/basic-languages/python/python.contribution", () => ({}));
vi.mock("monaco-editor/esm/vs/editor/editor.worker?worker", () => ({ default: class {} }));
import { setActiveWorkspace, resetWorkspaceContext, workspaceHeaders, rememberResponseOrigins, workspaceStorageKey, parseWorkspaceRoute, workspaceFamily } from "./workspaceContext";
import { apiRequest, fetchWorkbenchShortcuts, clearAuthSession } from "../../services/api";
import { upsertChatRuntimeState, readChatRuntimeState } from "../utils/chatRuntimeState";
import App from "../App";
import DataScience from "../views/DataScienceView";
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); resetWorkspaceContext();
  localStorage.setItem("chatchat.auth.session", JSON.stringify({ token: "same-token", user: { id: "user-A", tenantId: "tenant" } })); });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe("Workspace boundaries", () => {
  it("does not reuse loading or denied components as a cached business page", () => {
    const ctx = { activeView:"chat", activeWorkspaceId:"zhice", menuAuthorizationReady:false, canAccessView:()=>true };
    const loading = App.computed.activeViewKey.call(ctx);
    ctx.menuAuthorizationReady = true;
    const chat = App.computed.activeViewKey.call(ctx);
    ctx.canAccessView = ()=>false;
    expect(App.computed.activeViewKey.call(ctx)).not.toBe(chat);
    expect(loading).not.toBe(chat);
  });
  it("does not record a previous user's late response in the new user's context", async () => {
    let complete;
    vi.stubGlobal("fetch", vi.fn().mockImplementation(()=>new Promise(resolve=>{complete=resolve;})));
    const pending = apiRequest("/agent/tasks", { workspaceId:"zhice" });
    localStorage.setItem("chatchat.auth.session", JSON.stringify({token:"new-token",user:{id:"user-B",tenantId:"tenant"}}));
    setActiveWorkspace("data-science");
    complete(new Response(JSON.stringify({code:200,data:{taskId:"old-task"}}),{headers:{"Content-Type":"application/json"}}));
    await pending;
    expect(workspaceHeaders("/agent/tasks/old-task")["X-Workspace-Id"]).toBe("data-science");
  });
  it("keeps legacy links in zhice and rejects malformed/external routes", () => {
    expect(parseWorkspaceRoute("#/dataScience")).toMatchObject({workspaceId:"zhice",viewId:"dataScienceEnvironment"});
    expect(parseWorkspaceRoute("#/w/data-science/python/develop?file=old")).toMatchObject({workspaceId:"data-science",route:"python/develop"});
    expect(parseWorkspaceRoute("#/w/../chat").invalid).toBe(true);
    expect(parseWorkspaceRoute("#/%zz").invalid).toBe(true);
    expect(workspaceFamily("dataScienceData")).toBe(workspaceFamily("dataScienceDevelop"));
  });
  it("switches the request workspace without changing identity or Token", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({code:200,data:{taskId:"task-A",sessionId:"session-A"}}),{headers:{"Content-Type":"application/json"}}));
    vi.stubGlobal("fetch",fetch);
    await apiRequest("/agent/tasks", {method:"POST",workspaceId:"zhice"});
    setActiveWorkspace("data-science"); await apiRequest("/agent/tasks/task-A/result");
    expect(fetch.mock.calls[1][1].headers["X-Workspace-Id"]).toBe("zhice");
    expect(fetch.mock.calls[1][1].headers.Authorization).toBe("Bearer same-token");
    expect(JSON.parse(localStorage.getItem("chatchat.auth.session")).token).toBe("same-token");
  });
  it("does not share workbench response caches across workspace or user", async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({code:200,data:[]}),{headers:{"Content-Type":"application/json"}})));
    vi.stubGlobal("fetch",fetch);
    await fetchWorkbenchShortcuts(); await fetchWorkbenchShortcuts(); expect(fetch).toHaveBeenCalledTimes(1);
    setActiveWorkspace("data-science"); await fetchWorkbenchShortcuts(); expect(fetch).toHaveBeenCalledTimes(2);
    localStorage.setItem("chatchat.auth.session",JSON.stringify({token:"other",user:{id:"user-B",tenantId:"tenant"}}));
    await fetchWorkbenchShortcuts(); expect(fetch).toHaveBeenCalledTimes(3);
    clearAuthSession(); expect(workspaceHeaders()["X-Workspace-Id"]).toBe("zhice");
  });
  it("keeps background runtime snapshots in their origin space", () => {
    upsertChatRuntimeState({conversationId:"s",taskId:"t",workspaceId:"zhice",status:"running"});
    setActiveWorkspace("data-science"); expect(Object.keys(readChatRuntimeState().entries)).toHaveLength(0);
    expect(Object.keys(readChatRuntimeState("zhice").entries)).toHaveLength(1);
    localStorage.setItem("chatchat.auth.session", JSON.stringify({user:{id:"user-B",tenantId:"tenant"}}));
    expect(Object.keys(readChatRuntimeState("zhice").entries)).toHaveLength(0);
  });
  it("ignores a completed background conversation from another space", () => {
    const ctx={activeWorkspaceId:"data-science",activeHistoryId:"current",conversationHistory:[],upsertLocalConversation:vi.fn()};
    App.methods.handleConversationActive.call(ctx,{workspaceId:"zhice",id:"background",question:"old"});
    App.methods.handleHistorySaved.call(ctx,{workspaceId:"zhice",activeHistoryId:"background"});
    expect(ctx.activeHistoryId).toBe("current"); expect(ctx.upsertLocalConversation).not.toHaveBeenCalled();
    App.methods.handleConversationActive.call(ctx,{workspaceId:"data-science",originIdentity:"tenant:user-old",id:"background",question:"old"});
    expect(ctx.activeHistoryId).toBe("current");
  });
  it("restores IDE drafts only for current authorized assets and workspace", () => {
    const key=workspaceStorageKey("chatchat.python-studio.draft.v1","data-science");
    sessionStorage.setItem(key,JSON.stringify({version:1,updatedAt:Date.now(),editorTabs:[{key:"bad",form:{assetId:"foreign",sourceCode:"private"}}],aiPrompt:"prompt"}));
    const ctx={workspaceId:"data-science",assets:[{id:"owned"}],scripts:[],dataFiles:[],editorTabs:[],disposeEditor:vi.fn(),initializeEditor:vi.fn()};
    DataScience.methods.restoreWorkspaceDraft.call(ctx); expect(ctx.editorTabs).toHaveLength(0);
    expect(workspaceStorageKey("layout","zhice")).not.toBe(workspaceStorageKey("layout","data-science"));
  });
  it("resource origin recorded before a switch remains usable for SSE reconnect", () => {
    rememberResponseOrigins({taskId:"t",artifactId:"a"},"/agent/tasks","zhice"); setActiveWorkspace("data-science");
    expect(workspaceHeaders("/agent/tasks/t/events/stream")["X-Workspace-Id"]).toBe("zhice");
    expect(workspaceHeaders("/ui-artifacts/a/resources/answer")["X-Workspace-Id"]).toBe("zhice");
  });
});
