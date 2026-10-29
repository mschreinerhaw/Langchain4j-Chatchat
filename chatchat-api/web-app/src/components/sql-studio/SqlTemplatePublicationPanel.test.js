// @vitest-environment jsdom
import { createApp, h, nextTick } from "vue";
import { afterEach, beforeEach, it, expect, vi } from "vitest";
const api = vi.hoisted(() => ({
  sqlTemplateSources: vi.fn(),
  sqlTemplateHistory: vi.fn(),
  writeSqlTemplate: vi.fn(),
  actSqlTemplate: vi.fn(),
}));
vi.mock("../../services/sqlStudioApi", () => api);
import Panel from "./SqlTemplatePublicationPanel.vue";
it('uses an accessible icon close action without changing the close event',async()=>{
 const closed=vi.fn();await mount(file,{onClose:closed});
 const close=document.querySelector('[aria-label="关闭发布模板"]');
 expect(close.title).toBe('关闭');expect(close.querySelector('svg')).not.toBeNull();expect(close.textContent.trim()).toBe('');
 close.click();expect(closed).toHaveBeenCalledOnce();
});
let app, host;
const file = {
  id: "file",
  path: "analysis.sql",
  version: 3,
  content: "select {{value}}",
  draft: "select {{value}}",
};
const row = () => ({
  id: "draft",
  groupId: "group",
  version: 1,
  expectedVersion: 0,
  title: "Sample",
  description: "",
  category: "sql_diagnostic",
  datasourceId: "db",
  state: "DRAFT",
  active: false,
  sqlTemplate: file.content,
  source: { fileVersion: 3 },
  parameterSchema: { type: "object", properties: { value: { type: "integer" } } },
  testEvidence: null,
});
const settle = async () => {
  for (let n = 0; n < 8; n++) await nextTick();
};
const button = (text) =>
  [...document.querySelectorAll("button")].find((b) => b.textContent.includes(text));
async function mount(value = file, extra = {}) {
  host = document.createElement("div");
  document.body.append(host);
  app = createApp({ render: () => h(Panel, { projectId: "project", file: value, ...extra }) });
  app.mount(host);
  await settle();
}
beforeEach(() => {
  api.sqlTemplateSources.mockResolvedValue([
    { id: "db", name: "Controlled DB", databaseType: "h2" },
  ]);
  api.sqlTemplateHistory.mockResolvedValue([]);
});
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.resetAllMocks();
  vi.unstubAllGlobals();
});
it("supports the existing HTTP intranet origin without crypto.randomUUID", async () => {
  const crypto = globalThis.crypto;
  vi.stubGlobal("crypto", { getRandomValues: crypto.getRandomValues.bind(crypto) });
  api.writeSqlTemplate.mockResolvedValue(row());
  await mount();
  button("从当前保存文件创建").click();
  await settle();
  expect(api.writeSqlTemplate).toHaveBeenCalledWith(
    "project",
    "file",
    null,
    expect.objectContaining({ operationId: expect.stringMatching(/^[a-f0-9]{32}$/) })
  );
});
it("opening only reads configuration and does not execute a query", async () => {
  await mount();
  expect(api.actSqlTemplate).not.toHaveBeenCalled();
  expect(api.writeSqlTemplate).not.toHaveBeenCalled();
  expect(document.body.textContent).toContain("保存草稿不会执行 SQL");
});
it('a new revision uses current SQL placeholders while leaving the published contract intact',async()=>{
  const published={...row(),state:'PUBLISHED',active:true};
  api.sqlTemplateHistory.mockResolvedValue([published]);api.writeSqlTemplate.mockResolvedValue(row());
  await mount({...file,content:'select {{amount}}',draft:'select {{amount}}',version:4});
  button('从当前保存文件创建').click();await settle();
  const submitted=api.writeSqlTemplate.mock.calls[0][3];
  expect(submitted.parameterSchema.properties).toEqual({amount:{type:'string'}});
  expect(submitted.parameterSchema.required).toEqual(['amount']);
  expect(published.parameterSchema.properties).toEqual({value:{type:'integer'}});
  expect(api.actSqlTemplate).not.toHaveBeenCalled();
});
it('forking unchanged placeholders preserves defaults, optionality and local Schema definitions',async()=>{
  const schema={type:'object',properties:{value:{$ref:'#/$defs/value'}},$defs:{value:{type:'integer',default:7}},required:[],additionalProperties:true};
  api.sqlTemplateHistory.mockResolvedValue([{...row(),state:'PUBLISHED',parameterSchema:schema}]);api.writeSqlTemplate.mockResolvedValue(row());
  await mount();button('从当前保存文件创建').click();await settle();
  expect(api.writeSqlTemplate.mock.calls[0][3].parameterSchema).toEqual(schema);
});
it("creating a draft binds the saved source version and inferred parameter Schema", async () => {
  api.writeSqlTemplate.mockResolvedValue(row());
  await mount();
  button("从当前保存文件创建").click();
  await settle();
  expect(api.writeSqlTemplate).toHaveBeenCalledWith(
    "project",
    "file",
    null,
    expect.objectContaining({
      expectedSourceVersion: 3,
      parameterSchema: expect.objectContaining({ required: ["value"] }),
    })
  );
  expect(api.actSqlTemplate).not.toHaveBeenCalled();
});
it("unsaved file changes prevent creating or testing a template", async () => {
  api.sqlTemplateHistory.mockResolvedValue([row()]);
  await mount({ ...file, draft: "select 2" });
  expect(button("从当前保存文件创建").disabled).toBe(true);
  expect(button("执行真实 SQL 测试").disabled).toBe(true);
});
it("a returned failed SQL receipt is displayed as failure and cannot be published", async () => {
  const draft = row();
  api.sqlTemplateHistory.mockResolvedValue([draft]);
  api.actSqlTemplate.mockResolvedValue({
    ...draft,
    expectedVersion: 1,
    testEvidence: {
      type: "SQL_DATABASE_EXECUTION",
      success: false,
      diagnostic: "Query failed",
      parameters: {},
    },
  });
  await mount();
  button("执行真实 SQL 测试").click();
  await settle();
  expect(document.body.textContent).toContain("真实 SQL 测试失败");
  expect(button("确认发布 MCP 模板").disabled).toBe(true);
});
it("publication requires a passed test, matching sample and explicit review", async () => {
  const draft = { ...row(), testEvidence: { success: true, parameters: { value: 1 } } };
  api.sqlTemplateHistory.mockResolvedValue([draft]);
  await mount();
  expect(button("确认发布 MCP 模板").disabled).toBe(true);
  const review = document.querySelector("input[type=checkbox]");
  review.checked = true;
  review.dispatchEvent(new Event("change", { bubbles: true }));
  await settle();
  expect(button("确认发布 MCP 模板").disabled).toBe(false);
  const sample = document.querySelector('[aria-label="模板测试参数"]');
  sample.value = '{"value":2}';
  sample.dispatchEvent(new Event("input", { bubbles: true }));
  await settle();
  expect(button("确认发布 MCP 模板").disabled).toBe(true);
});
it("historical rollback calls the existing action and reports a failed retest without success", async () => {
  const snapshot = { ...row(), state: "PUBLISHED" };
  api.sqlTemplateHistory.mockResolvedValue([snapshot]);
  api.actSqlTemplate.mockResolvedValue({ ...snapshot, testEvidence: { success: false } });
  await mount();
  const review = document.querySelector("input[type=checkbox]");
  review.checked = true;
  review.dispatchEvent(new Event("change", { bubbles: true }));
  await settle();
  button("重新测试并回滚").click();
  await settle();
  expect(api.actSqlTemplate).toHaveBeenCalledWith(
    "project",
    "file",
    "draft",
    "rollback",
    expect.objectContaining({ expectedActiveReleaseId: "" })
  );
  expect(document.body.textContent).toContain("线上版本未变更");
});
