// @vitest-environment jsdom
import { it, expect, vi, beforeEach } from "vitest";
vi.mock("element-plus", () => ({ ElMessageBox: {}, ElMessage: { warning: vi.fn() } }));
const api = vi.hoisted(() => ({
  studioPublication: vi.fn(),
  actStudioPublication: vi.fn(),
  save: vi.fn(),
}));
vi.mock("../services/api", () => ({ databaseApi: api, assetsApi: {} }));
import View from "../scripts/views/DatabaseMcpView.js";
import Catalog from "../scripts/components/CrudCatalog.js";
import Parameters from "../scripts/components/ApiTestParameterDialog.js";
function vm(options, props = {}) {
  const v = { ...props, $emit: vi.fn(), $refs: {} };
  Object.assign(v, options.data.call(v));
  for (const [k, f] of Object.entries(options.methods || {})) v[k] = f.bind(v);
  for (const [k, f] of Object.entries(options.computed || {}))
    Object.defineProperty(v, k, { get: () => f.call(v) });
  return v;
}
const studio = () => ({
  id: "studio:group",
  templateId: "revision",
  sourceKind: "SQL_STUDIO",
  sourceLabel: "SQL Studio",
  title: "Warehouse",
  description: "Read actual rows",
  toolName: "tpl_original",
  datasourceId: "db",
  categoryId: "category",
  capabilityCategory: "analysis",
  expectedVersion: 3,
  publicationId: "release",
  inputSchema: {
    type: "object",
    properties: { minimum: { type: ["number", "null"], default: null } },
    required: [],
    additionalProperties: false,
  },
  parameters: { minimum: null },
  sqlTemplate: "select {{minimum}}",
});
beforeEach(() => vi.resetAllMocks());
it('opens the same parameter dialog for a manual form test instead of requiring inline test values', async () => {
  const testAction = vi.fn().mockResolvedValue({success:false,message:'query failed'});
  const catalog = vm(Catalog,{formFields:[],formPreviewType:'databaseQuery',formTestAction:testAction});
  catalog.form={sourceKind:'MANUAL'};
  catalog.validateFormBeforeSubmit=()=>true;
  catalog.validateDatabaseParameterCompleteness=vi.fn().mockReturnValue(true);
  catalog.formPayload=()=>({sourceKind:'MANUAL',inputSchema:{type:'object',properties:{minimum:{type:'integer'}}}});
  catalog.$refs.apiTestParameterDialog={open:vi.fn().mockResolvedValue({minimum:3})};
  await catalog.testFormDraft();
  expect(catalog.validateDatabaseParameterCompleteness).toHaveBeenCalledWith(false);
  expect(catalog.$refs.apiTestParameterDialog.open).toHaveBeenCalledOnce();
  expect(testAction).toHaveBeenCalledWith(expect.objectContaining({sourceKind:'MANUAL'}),{minimum:3});
});
it("uses the same form with common business fields and source-specific immutable SQL", () => {
  const view = vm(View, { queryFamily: "relational", section: "queries" });
  const catalog = vm(Catalog, { defaults: {}, formFields: view.sharedFormFields });
  catalog.form = studio();
  expect(catalog.hasDatabaseWorkflowField).toBe(true);
  expect(catalog.visibleFormFields.map((f) => f.key)).toContain("title");
  expect(catalog.visibleFormFields.map((f) => f.key)).not.toContain("implementationSteps");
  expect(catalog.visibleFormFields.map((f) => f.key)).not.toContain("sqlSteps");
  expect(catalog.isFieldReadonly(catalog.formFields.find((f) => f.key === "datasourceId"))).toBe(
    true
  );
  catalog.form = { sourceKind: "MANUAL" };
  expect(catalog.hasDatabaseWorkflowField).toBe(true);
  expect(catalog.visibleFormFields.map((f) => f.key)).not.toContain("sqlTemplate");
});
it("does not normalize a Studio parameter contract through hidden manual flow fields", () => {
  const view = vm(View, { queryFamily: "relational", section: "queries" });
  const catalog = vm(Catalog, { defaults: {}, formFields: view.sharedFormFields });
  catalog.form = studio();
  catalog.prepareJsonDraft();
  const payload = catalog.formPayload();
  expect(payload.inputSchema).toEqual(studio().inputSchema);
  expect(payload.parameters).toEqual({ minimum: null });
  expect(payload.sqlSteps).toBeUndefined();
  expect(catalog.validateDatabaseParameterCompleteness(true)).toBe(true);
  expect(catalog.validateFieldsBeforeSubmit(studio(), false)).toBe(true);
});
it("loads authoritative Studio data and dispatches only metadata to the original revision", async () => {
  const view = vm(View, { queryFamily: "relational", section: "queries" });
  view.categories = [{ id: "category", code: "analysis" }];
  api.studioPublication.mockResolvedValue({
    ...studio(),
    parameterSchema: studio().inputSchema,
    testEvidence: { parameters: { minimum: null } },
  });
  const loaded = await view.loadQueryItem(studio());
  expect(loaded.id).toBe("studio:group");
  view.$refs.catalog = {
    $refs: { apiTestParameterDialog: { open: vi.fn().mockResolvedValue({ minimum: 3 }) } },
  };
  await view.saveQuery(loaded);
  expect(api.actStudioPublication).toHaveBeenCalledWith(
    "revision",
    "metadata",
    expect.objectContaining({
      category: "analysis",
      parameters: { minimum: 3 },
      expectedVersion: 3,
      expectedActiveReleaseId: "release",
    })
  );
  expect(api.save).not.toHaveBeenCalled();
  await view.saveQuery({ sourceKind: "MANUAL", id: "manual" });
  expect(api.save).toHaveBeenCalledWith({ sourceKind: "MANUAL", id: "manual" });
});
it("retains failures and actual returned rows in the common test result contract", async () => {
  const view = vm(View, { queryFamily: "relational", section: "queries" });
  api.actStudioPublication.mockResolvedValue({
    testEvidence: { success: false, diagnostic: "denied", preview: { rows: [] } },
  });
  expect((await view.testStudio(studio(), { minimum: 3 })).success).toBe(false);
  api.actStudioPublication.mockResolvedValue({
    testEvidence: { success: true, rowCount: 1, preview: { columns: ["id"], rows: [{ id: 3 }] } },
  });
  expect((await view.testStudio(studio(), { minimum: 3 })).data.rows).toEqual([{ id: 3 }]);
});
it("uses one parameter dialog for nullable Studio and manual inputs without dropping false or zero", async () => {
  const dialog = vm(Parameters);
  const pending = dialog.open({
    schema: {
      type: "object",
      properties: {
        minimum: { $ref: "#/$defs/minimum" },
        flag: { type: "boolean", default: false },
        count: { type: "integer", default: 0 },
      },
      $defs: { minimum: { type: ["number", "null"], default: null } },
    },
  });
  dialog.submit();
  expect(await pending).toEqual({ minimum: null, flag: false, count: 0 });
});
