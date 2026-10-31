// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { setWorkspaceContext, workspaceStorageKey, workspaceHeaders, parseWorkspaceRoute, chooseWorkspace } from "./workspaceContext";
import { saveWorkspaceDraft, readWorkspaceDraft, ownedPythonDraftTabs } from "./workspaceDrafts";
import { upsertChatRuntimeState, mergeChatRuntimeState } from "./chatRuntimeState";
import App from "../App";
import * as api from "../../services/api";

const identity = { tenantId: "t1", userId: "u1", workspaceId: "zhice" };
beforeEach(() => {
  const values = new Map();
  const storage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
  vi.stubGlobal("localStorage", storage);
  Object.defineProperty(window, "localStorage", { configurable: true, value: storage });
  setWorkspaceContext(identity); window.location.hash = "";
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); setWorkspaceContext(null); });
describe("Workspace identity and state isolation", () => {
  it("scopes browser state by tenant, stable principal and workspace", () => {
    const keys = [identity, { ...identity, tenantId: "t2" }, { ...identity, userId: "u2" }, { ...identity, workspaceId: "data-science" }]
      .map(context => workspaceStorageKey("draft", context));
    expect(new Set(keys).size).toBe(4);
    expect(workspaceStorageKey("draft", null)).toBeNull();
    expect(workspaceHeaders(identity)).toEqual({ "X-Workspace-Id": "zhice" });
  });
  it("reads only the current owner's workspace draft and tolerates corrupt storage", () => {
    saveWorkspaceDraft("draft", identity, { question: "private" });
    expect(readWorkspaceDraft("draft", { ...identity, userId: "other" })).toBeNull();
    expect(readWorkspaceDraft("draft", { ...identity, workspaceId: "data-science" })).toBeNull();
    expect(readWorkspaceDraft("draft", identity)).toEqual({ question: "private" });
    localStorage.setItem(workspaceStorageKey("draft", identity), "corrupt");
    expect(readWorkspaceDraft("draft", identity)).toBeNull();
  });
  it("restores editor drafts only against freshly authorized script and environment lists", () => {
    const tabs = [{ key: "ok", form: { id: "script", assetId: "env", sourceCode: "unsaved" } },
      { key: "foreign", form: { id: "foreign-script", assetId: "env", sourceCode: "private" } }];
    const restored = ownedPythonDraftTabs(tabs, [{ id: "script", assetId: "env", sourceCode: "saved" }], [{ id: "env" }]);
    expect(restored).toHaveLength(1); expect(restored[0].form.sourceCode).toBe("unsaved");
    expect(restored[0].savedSource).toBe("saved"); expect(restored[0].runState).toBe("idle");
    expect(ownedPythonDraftTabs(tabs, [], [])).toEqual([]);
  });
  it("background events keep writing into their captured workspace after a switch", () => {
    setWorkspaceContext({ ...identity, workspaceId: "data-science" });
    upsertChatRuntimeState({ conversationId: "conversation", taskId: "task", status: "running", workspaceContext: identity });
    expect(mergeChatRuntimeState({ id: "conversation" }).messages).toBeUndefined();
    expect(mergeChatRuntimeState({ id: "conversation" }, identity).messages[0].taskId).toBe("task");
  });
  it("validates explicit routes and restores only still-authorized defaults", () => {
    expect(parseWorkspaceRoute('#/w/data-science/dataExploration?exploration=owned')).toEqual({workspaceId:'data-exploration',pageId:'dataExploration'});
    expect(chooseWorkspace([{workspaceId:'data-science'}],{legacyView:'dataExploration'})).toBeNull();
    expect(chooseWorkspace([{workspaceId:'data-exploration'}],{legacyView:'dataExploration'}).workspaceId).toBe('data-exploration');
    expect(parseWorkspaceRoute("#/w/data-science/dataScienceDevelop")).toEqual({ workspaceId: "data-science", pageId: "dataScienceDevelop" });
    expect(parseWorkspaceRoute("#/%broken").invalid).toBe(true);
    expect(parseWorkspaceRoute("#/dataScience").legacyView).toBe("dataScienceEnvironment");
    const items = [{ workspaceId: "zhice", defaultWorkspace: true }];
    expect(chooseWorkspace(items, { workspaceId: "forbidden" })).toBeNull();
    expect(chooseWorkspace(items, {}, { lastWorkspaceId: "revoked" }).workspaceId).toBe("zhice");
    expect(chooseWorkspace([...items, { workspaceId: "data-science" }], { legacyView: "library" },
      { lastWorkspaceId: "data-science" }).workspaceId).toBe("zhice");
    expect(chooseWorkspace([...items, { workspaceId: "data-science" }],
      { legacyView: "dataScienceDevelop" }).workspaceId).toBe("data-science");
    expect(chooseWorkspace([...items, { workspaceId: "data-science" }],
      { legacyView: "dataScienceSkills" }).workspaceId).toBe("zhice");
  });
  it("opens the platform default at login instead of the last visited workspace", () => {
    const items = [{ workspaceId: "zhice", defaultWorkspace: true }, { workspaceId: "data-science" }];
    expect(chooseWorkspace(items, {}, { lastWorkspaceId: "data-science" }).workspaceId).toBe("zhice");
    expect(chooseWorkspace(items, {}, { defaultWorkspaceId: "zhice", lastWorkspaceId: "data-science" }).workspaceId).toBe("zhice");
    expect(chooseWorkspace(items, {}, { defaultWorkspaceId: "data-science", lastWorkspaceId: "zhice" }).workspaceId).toBe("data-science");
    expect(parseWorkspaceRoute("#/workspaces")).toEqual({ workspaceId: "zhice", pageId: "workspaces" });
  });
});
describe("Workspace Shell", () => {
  it("logs in directly without opening a separate selection screen", async () => {
    const state = { authSession: { token: "token", user: { id: "u1" } }, workspaceBootstrapSerial: 0,
      switchWorkspace: vi.fn(), openWorkspaceSelection: vi.fn(), showWorkspaceSelection: false };
    vi.spyOn(api, "fetchWorkspaces").mockResolvedValue([
      { workspaceId: "zhice", defaultWorkspace: true }, { workspaceId: "data-science" }
    ]);
    vi.spyOn(api, "fetchWorkspacePreferences").mockResolvedValue({ lastWorkspaceId: "data-science", defaultWorkspaceId: "zhice" });
    await App.methods.loadWorkspaces.call(state);
    expect(state.switchWorkspace).toHaveBeenCalledWith("zhice", "");
    expect(state.openWorkspaceSelection).not.toHaveBeenCalled();
    expect(state.showWorkspaceSelection).toBe(false);
  });
  it("saves the personal default without leaving the management page and reports failures", async () => {
    const state = { authSession: { user: { id: "u1" } }, workspacePreference: { defaultWorkspaceId: "zhice" },
      switchWorkspace: vi.fn() };
    const save = vi.spyOn(api, "saveWorkspacePreferences").mockResolvedValue({ defaultWorkspaceId: "data-science" });
    await App.methods.useDefaultWorkspace.call(state, "data-science");
    expect(save).toHaveBeenCalledWith({ defaultWorkspaceId: "data-science" });
    expect(state.workspacePreference.defaultWorkspaceId).toBe("data-science");
    expect(state.switchWorkspace).not.toHaveBeenCalled();
    save.mockRejectedValueOnce(new Error("offline"));
    await App.methods.useDefaultWorkspace.call(state, "zhice");
    expect(state.workspacePreference.defaultWorkspaceId).toBe("data-science");
    expect(state.workspaceError).toBe("offline");
    expect(state.workspaceSaving).toBe(false);
  });
  function shell() {
    return { authSession: { token: "same-token", user: { id: "u1" } }, tenantId: "t1", workspaceNavigationSerial: 0,
      workspaceContexts: {}, currentWorkspace: null, workspacePages: [], activeView: "", workspaceReady: false,
      setHashRoute: vi.fn() };
  }
  function loaded(id, page = "chat") {
    return { manifest: { workspaceId: id, configVersion: 1, entryPage: page, pages: [{ id: page, componentKey: page, menuCode: page }] },
      menus: [{ id: "work", title: "Work", children: [{ id: page, title: page, children: [] }] }] };
  }
  it("ignores a late manifest response after a newer switch", async () => {
    let finishFirst; const state = shell();
    vi.spyOn(api, "fetchWorkspaceManifest").mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }))
      .mockResolvedValueOnce(loaded("data-science", "dataScienceDevelop"));
    vi.spyOn(api, "saveWorkspacePreferences").mockResolvedValue({});
    const first = App.methods.switchWorkspace.call(state, "zhice");
    await App.methods.switchWorkspace.call(state, "data-science");
    finishFirst(loaded("zhice")); await first;
    expect(state.currentWorkspace.workspaceId).toBe("data-science"); expect(state.authSession.token).toBe("same-token");
  });
  it("keeps current state and identity when the server denies another workspace", async () => {
    const state = { ...shell(), currentWorkspace: { workspaceId: "zhice" }, workspaceReady: true, activeView: "chat" };
    vi.spyOn(api, "fetchWorkspaceManifest").mockRejectedValue(new Error("403"));
    await App.methods.switchWorkspace.call(state, "forbidden");
    expect(state.currentWorkspace.workspaceId).toBe("zhice"); expect(state.activeView).toBe("chat"); expect(state.workspaceError).toBe("403");
  });
  it("shows studio pages directly under the workspace name", async () => {
    const state = shell();
    const studio = loaded("data-science", "dataScienceDevelop");
    studio.manifest.layoutType = "studio";
    studio.manifest.name = "数据科学";
    studio.menus = [{ id: "capability", title: "能力管理", children: [
      { id: "dataScience", title: "数据科学", children: [
        { id: "dataScienceDevelop", title: "Python 开发", children: [] }
      ] }
    ] }];
    vi.spyOn(api, "fetchWorkspaceManifest").mockResolvedValue(studio);
    vi.spyOn(api, "saveWorkspacePreferences").mockResolvedValue({});
    await App.methods.switchWorkspace.call(state, "data-science");
    expect(state.navItems[0].label).toBe("数据科学");
    expect(state.navItems[0].items.map(item => item.id)).toEqual(["dataScienceDevelop"]);
  });
  it("shares one Python editor cache inside a workspace and isolates caches across spaces", () => {
    const state = { tenantId: "t1", authSession: { user: { id: "u1" } }, currentWorkspace: { workspaceId: "zhice" }, workspacePages: [] };
    const key = view => App.computed.workspaceComponentKey.call({ ...state, activeView: view });
    expect(key("dataScienceDevelop")).toBe(key("dataScienceScripts"));
    expect(App.computed.workspaceComponentKey.call({ ...state, activeView: "dataScienceDevelop", currentWorkspace: { workspaceId: "data-science" } }))
      .not.toBe(key("dataScienceDevelop"));
  });
});
