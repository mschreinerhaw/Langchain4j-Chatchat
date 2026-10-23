// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import { it, expect, vi, beforeEach, afterEach } from "vitest";
import ToolPublicationsView from "./ToolPublicationsView.vue";
import { apiRequest } from "../services/api.js";
vi.mock("../services/api.js", () => ({
  apiRequest: vi.fn(),
  getStoredAuthSession: () => ({
    user: { permissionCodes: ["mcp:publication:edit", "mcp:publication:publish"] },
  }),
}));
const definition = {
  domainCode: "business",
  parentToolName: "ssh_template_query",
  roleId: "role",
  subjectType: "ROLE",
  templateKeys: ["authorized"],
  enabled: true,
};
const release = (id, version, state) => ({
  id,
  logicalId: "logical",
  title: "业务工具",
  description: "受控分析",
  releaseNumber: version,
  state,
  definitionJson: JSON.stringify(definition),
  inputSchemaJson: '{"type":"object"}',
  outputSchemaJson: '{"type":"object"}',
  publishedAt: state === "RUNNING" ? "2026-10-23T00:00:00Z" : null,
});
let app, root;
const flush = async () => {
  await new Promise((r) => setTimeout(r, 0));
  await nextTick();
};
beforeEach(() => {
  vi.resetAllMocks();
  apiRequest.mockImplementation(async (path, options) => {
    if (path === "/tool-publications")
      return options?.method === "POST"
        ? release("new", 3, "DRAFT")
        : [release("draft", 2, "DRAFT"), release("live", 1, "RUNNING")];
    if (path.endsWith("/parents")) return [{ toolName: "ssh_template_query", title: "主机模板" }];
    if (path.endsWith("/roles")) return [{ id: "role", roleName: "业务角色" }];
    if (path.includes("/templates?")) return [{ key: "authorized", title: "已授权模板" }];
    return {};
  });
});
afterEach(() => {
  app?.unmount();
  root?.remove();
});
async function mount() {
  root = document.createElement("div");
  document.body.append(root);
  app = createApp(ToolPublicationsView);
  const vm = app.mount(root);
  await flush();
  return vm;
}
it("groups versions into one business tool while preserving version selection and state filters", async () => {
  const vm = await mount();
  expect(vm.stateFilter).toBe("RUNNING");
  expect(vm.tools).toHaveLength(1);
  expect(vm.selectedId).toBe("live");
  expect(vm.selected.state).toBe("RUNNING");
  expect(vm.versions.map((r) => r.id)).toEqual(["draft", "live"]);
  vm.stateFilter = "";
  expect(vm.tools[0].id).toBe("draft");
  vm.stateFilter = "RUNNING";
  expect(vm.tools[0].id).toBe("live");
  vm.query = "不存在";
  await nextTick();
  expect(vm.visibleTools).toHaveLength(0);
  expect(root.textContent).toContain("没有匹配的工具");
});
it("shows an empty running list without silently selecting a draft", async () => {
  const original = apiRequest.getMockImplementation();
  apiRequest.mockImplementation((path, options) =>
    path === "/tool-publications"
      ? Promise.resolve([release("draft", 2, "DRAFT")])
      : original(path, options)
  );
  const vm = await mount();
  expect(vm.tools).toHaveLength(0);
  expect(vm.selected).toBeNull();
  vm.stateFilter = "DRAFT";
  expect(vm.tools[0].id).toBe("draft");
});
it("reveals the saved draft even when the initial filter is running", async () => {
  const vm = await mount();
  const original = apiRequest.getMockImplementation();
  apiRequest.mockImplementation((path, options) =>
    path === "/tool-publications" && !options?.method
      ? Promise.resolve([release("new", 3, "DRAFT"), ...vm.rows])
      : original(path, options)
  );
  vm.reset();
  vm.form.title = "新工具";
  vm.definition = { ...definition, enabled: false };
  vm.templates = [{ key: "authorized", title: "已授权模板" }];
  await vm.save();
  expect(vm.failed).toBe(false);
  expect(vm.stateFilter).toBe("DRAFT");
  expect(vm.selectedId).toBe("new");
  expect(vm.tools.some((row) => row.id === "new")).toBe(true);
});
it("keeps the draft when schema validation fails without issuing a write", async () => {
  const vm = await mount();
  vm.reset();
  vm.form.title = "新工具";
  vm.definition = { ...definition, enabled: false };
  vm.templates = [{ key: "authorized", title: "已授权模板" }];
  vm.inputSchema = "invalid";
  await vm.save();
  expect(vm.failed).toBe(true);
  expect(vm.notice).toContain("有效 JSON");
  expect(vm.editing).toBe(true);
  expect(apiRequest.mock.calls.some((c) => c[1]?.method === "POST")).toBe(false);
});
it("creates a new revision without modifying the immutable source and fully resets a fresh tool", async () => {
  const vm = await mount();
  vm.choose(vm.rows[1]);
  await vm.startRevision();
  vm.form.title = "新版";
  vm.definition.templateKeys.push("other");
  expect(JSON.parse(vm.rows[1].definitionJson).templateKeys).toEqual(["authorized"]);
  vi.spyOn(window, "confirm").mockReturnValue(true);
  vm.inputSample = '{"old":true}';
  vm.inputSchema = '{"old":true}';
  vm.reset();
  expect(vm.form.logicalId).toBeNull();
  expect(vm.inputSample).toBe("{}");
  expect(JSON.parse(vm.inputSchema)).toEqual({ type: "object", properties: {} });
  vi.restoreAllMocks();
});
it("uses the selected immutable release id and actual test input for lifecycle commands", async () => {
  const vm = await mount();
  vm.choose(vm.rows[1]);
  vm.inputSample = '{"limit":1}';
  await vm.test();
  const call = apiRequest.mock.calls.find((c) => c[0] === "/tool-publications/live/test");
  expect(JSON.parse(call[1].body)).toEqual({ input: { limit: 1 } });
  expect(vm.selectedId).toBe("live");
});
it("ignores stale template responses when the role changes", async () => {
  const vm = await mount();
  let finish;
  apiRequest
    .mockImplementationOnce(
      () =>
        new Promise((r) => {
          finish = r;
        })
    )
    .mockResolvedValueOnce([{ key: "new", title: "新角色模板" }]);
  vm.definition = { ...definition, templateKeys: ["authorized"] };
  const first = vm.loadTemplates();
  vm.definition.roleId = "next";
  await vm.loadTemplates();
  finish([{ key: "old", title: "旧角色模板" }]);
  await first;
  expect(vm.templates.map((t) => t.key)).toEqual(["new"]);
  expect(vm.definition.templateKeys).toEqual([]);
});
it("preserves a multi-template collection across categories and pages and saves it as one query tool", async () => {
  const vm = await mount();
  vm.reset();
  vm.form.title = "客户查询集合";
  vm.definition = {
    ...definition,
    domainCode: "collection_example",
    templateKeys: [],
    enabled: false,
  };
  vm.templates = Array.from({ length: 23 }, (_, i) => ({
    key: `template:${i}`,
    title: `模板${i}`,
    businessCategoryName: i < 21 ? "客户分析" : "交易分析",
  }));
  expect(vm.visibleTemplates).toHaveLength(10);
  vm.selectTemplatePage();
  vm.templatePage = 3;
  await nextTick();
  expect(vm.visibleTemplates).toHaveLength(3);
  vm.selectTemplatePage();
  expect(vm.definition.templateKeys).toHaveLength(13);
  vm.templateCategory = "交易分析";
  await nextTick();
  expect(vm.templatePage).toBe(1);
  expect(vm.visibleTemplates).toHaveLength(2);
  vm.templateQuery = "不存在";
  await nextTick();
  expect(vm.visibleTemplates).toHaveLength(0);
  expect(vm.definition.templateKeys).toHaveLength(13);
  await vm.save();
  const call = apiRequest.mock.calls.find(
    (c) => c[0] === "/tool-publications" && c[1]?.method === "POST"
  );
  const body = JSON.parse(call[1].body);
  expect(body.kind).toBe("CONTROLLED_TEMPLATE");
  expect(body.definition.templateKeys).toHaveLength(13);
  expect(body.definition.roleId).toBe("role");
  expect(body.definition.parentToolName).toBe("ssh_template_query");
  expect(apiRequest.mock.calls.some((c) => c[0].includes("/capabilities/"))).toBe(false);
});
it("resets the template picker and its selection when switching parent or role", async () => {
  const vm = await mount();
  vm.reset();
  vm.definition = { ...definition, templateKeys: ["authorized"] };
  vm.templateCategory = "客户分析";
  vm.templatePage = 4;
  await vm.loadTemplates();
  expect(vm.templateCategory).toBe("");
  expect(vm.templatePage).toBe(1);
  expect(vm.definition.templateKeys).toEqual([]);
});
it("takes validated tools to contract testing and tested tools back to the publish action", async () => {
  const vm = await mount();
  await vm.action(vm.selected, "validate");
  expect(vm.activeTab).toBe("testing");
  await vm.test();
  expect(vm.activeTab).toBe("overview");
});
it("returns a failed first draft to the editor and saves corrections to the same tool version", async () => {
  let current = {
    ...release("bad", 1, "DRAFT"),
    logicalId: "bad-tool",
    version: 0,
    definitionJson: JSON.stringify({ ...definition, domainCode: "中文错误标识" }),
  };
  const other = { ...release("other", 8, "RUNNING"), logicalId: "other-tool" };
  const original = apiRequest.getMockImplementation();
  apiRequest.mockImplementation(async (path, options) => {
    if (path === "/tool-publications" && !options?.method) return [current, other];
    if (path === "/tool-publications/bad/validate")
      throw new Error("domainCode must start with a lowercase letter");
    if (path === "/tool-publications/bad" && options?.method === "PUT") {
      const body = JSON.parse(options.body);
      current = {
        ...current,
        title: body.title,
        definitionJson: JSON.stringify(body.definition),
        version: 1,
        state: "DRAFT",
        testEvidenceJson: null,
      };
      return current;
    }
    return original(path, options);
  });
  const vm = await mount();
  vm.stateFilter = "DRAFT";
  await flush();
  await vm.action(vm.selected, "validate");
  expect(vm.editing).toBe(true);
  expect(vm.editingId).toBe("bad");
  expect(vm.failed).toBe(true);
  expect(vm.notice).toContain("工具标识");
  expect(vm.identityLocked).toBe(false);
  vm.definition.domainCode = "mysql_query";
  vm.form.title = "MySQL查询";
  await vm.save();
  const call = apiRequest.mock.calls.find(
    (c) => c[0] === "/tool-publications/bad" && c[1]?.method === "PUT"
  );
  expect(JSON.parse(call[1].body).expectedVersion).toBe(0);
  expect(vm.selectedId).toBe("bad");
  expect(vm.selected.logicalId).toBe("bad-tool");
  expect(vm.selected.releaseNumber).toBe(1);
  expect(vm.editing).toBe(false);
  expect(vm.versions.map((r) => r.id)).toEqual(["bad"]);
  expect(vm.selected.title).toBe("MySQL查询");
  expect(
    apiRequest.mock.calls.some((c) => c[0] === "/tool-publications" && c[1]?.method === "POST")
  ).toBe(false);
});
it("restricts version selection to the current tool and keeps revision ownership", async () => {
  const vm = await mount();
  const other = { ...release("foreign", 99, "RUNNING"), logicalId: "foreign-tool" };
  vm.rows.push(other);
  const event = { target: { value: "foreign" } };
  vm.chooseVersion(event);
  expect(vm.selectedId).toBe("live");
  expect(event.target.value).toBe("live");
  await vm.startRevision();
  expect(vm.form.logicalId).toBe("logical");
  expect(vm.identityLocked).toBe(true);
  expect(vm.editingId).toBe("");
  await vm.save();
  const call = apiRequest.mock.calls.find(
    (c) => c[0] === "/tool-publications" && c[1]?.method === "POST"
  );
  expect(JSON.parse(call[1].body).logicalId).toBe("logical");
});
it("retains edits after a concurrent draft update is rejected", async () => {
  const vm = await mount();
  vm.choose(vm.rows.find((r) => r.id === "draft"));
  await vm.editDraft();
  vm.form.title = "保留编辑";
  apiRequest.mockImplementation(async (path, options) => {
    if (options?.method === "PUT") throw Error("草稿已被修改，请刷新后重试");
    return [];
  });
  await vm.save();
  expect(vm.failed).toBe(true);
  expect(vm.editing).toBe(true);
  expect(vm.form.title).toBe("保留编辑");
  expect(vm.notice).toContain("草稿已被修改");
});
it("returns failed tests to the same editable draft while preserving the test input", async () => {
  const vm = await mount();
  const draft = vm.rows.find((r) => r.id === "draft");
  draft.state = "VALIDATED";
  vm.choose(draft);
  vm.inputSample = '{"limit":5}';
  const original = apiRequest.getMockImplementation();
  apiRequest.mockImplementation(async (path, options) => {
    if (path.endsWith("/test")) throw Error("测试结构不匹配");
    return original(path, options);
  });
  await vm.test();
  expect(vm.editingId).toBe("draft");
  expect(vm.editing).toBe(true);
  expect(vm.inputSample).toBe('{"limit":5}');
  expect(vm.notice).toContain("测试结构不匹配");
});
it("selects the matching version of the same tool when changing publication state filters", async () => {
  const vm = await mount();
  expect(vm.selectedId).toBe("live");
  vm.stateFilter = "PENDING";
  await nextTick();
  expect(vm.selectedId).toBe("draft");
  expect(vm.selected.logicalId).toBe("logical");
  vm.stateFilter = "RUNNING";
  await nextTick();
  expect(vm.selectedId).toBe("live");
});
