import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const root = new URL('../chatchat-mcp-server/web-app-mcp/src/scripts/components/', import.meta.url);
const syntax = fs.readFileSync(new URL('../../utils/sql-parameter-syntax.js', root), 'utf8').replaceAll('export function ', 'function ');
const dialog = fs.readFileSync(new URL('ApiTestParameterDialog.js', root), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export default {', 'const dialog = {')
  .replace('export function schemaFields', 'function schemaFields');
const source = fs.readFileSync(new URL('CrudCatalog.js', root), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export default {', 'const component = {');
const context = { ModalPanel: {}, ApiTestParameterDialog: {}, defineAsyncComponent: () => ({}) };
vm.createContext(context);
vm.runInContext(syntax + '\n' + dialog + '\n' + source + '\nglobalThis.component = component;', context);
const component = context.component;
const field = { key: 'queryParameters', type: 'databaseParamConfig', schemaKey: 'inputSchema', paramsKey: 'parameters', sqlKey: 'sqlTemplate', sqlStepsKey: 'sqlSteps' };
const editor = {
  ...component.data(), formFields: [{ key: 'sqlTemplate', type: 'databaseSqlEditor' }, field],
  hasSimpleSqlEditor: true, databaseQueryProfile: { type: 'RELATIONAL' },
  form: { sqlTemplate: 'select :id, :trade_date', sqlSteps: [] },
  schemaDraft: { queryParameters: [] },
};
editor.visibleFormFields = editor.formFields;
for (const [key, method] of Object.entries(component.methods)) editor[key] = method.bind(editor);
editor.syncSimpleSqlParams();
assert.equal(editor.schemaDraft.queryParameters.length, 2);
editor.schemaDraft.queryParameters[0].testValue = '7';
editor.syncSimpleSqlParams();
assert.equal(editor.schemaDraft.queryParameters[0].testValue, '7');
let payload = editor.formPayload();
assert.equal(payload.sqlSteps.length, 0);
assert.equal(payload.parameters.id, '7');
assert.equal(payload.inputSchema.properties.trade_date.defaultSource, 'trade_date');
editor.form.sqlTemplate = 'select 1';
editor.syncSimpleSqlParams();
assert.equal(editor.schemaDraft.queryParameters.length, 0);
editor.form.sqlSteps = [{ sqlContent: 'select :id', enabled: true, parameters: { id: 0 } }];
editor.prepareSimpleSqlEditor(field);
assert.equal(editor.schemaDraft.queryParameters[0].testValue, '0');
assert.equal(editor.form.sqlTemplate, 'select {{id}}');

const schema = { type: 'object', properties: {
  min_area: { type: 'number', minimum: 0, default: 0 },
  country: { type: 'string', enum: ['US', 'CN'], default: 'US' },
  nullable: { type: ['string', 'null'], default: null },
  labels: { $ref: '#/$defs/labels' },
}, $defs: { labels: { type: 'array', items: { type: 'string' }, default: ['a'] } }, required: ['min_area', 'nullable'] };
editor.form = { sourceKind: 'SQL_STUDIO', inputSchema: schema, sqlTemplate: 'select {{min_area}}', parameters: { min_area: 294242 } };
editor.prepareSimpleSqlEditor(field);
editor.syncSimpleSqlParams();
assert.equal(editor.schemaDraft.queryParameters.length, 4, 'Studio definitions are preserved even for parameters absent from SQL');
payload = editor.formPayload();
assert.equal(payload.inputSchema, schema, 'Schema metadata, constraints, refs, defaults and nullability remain unchanged');
assert.equal(payload.parameters.min_area, 294242);
assert.equal(payload.parameters.country, 'US');
assert.equal(payload.parameters.nullable, null);
assert.equal(payload.parameters.labels[0], 'a');
assert.equal(payload.sqlSteps, undefined, 'No manual workflow is injected into Studio metadata');
console.log('PASS: manual SQL synchronization/migration and Studio defaults, types, enums, refs, nullable values and unchanged schema');

const validationErrors = [];
editor.$emit = (name, value) => { if (name === 'error') validationErrors.push(value.message); };
editor.formPreviewType = 'databaseQuery';
editor.formFields = [
  { key: 'categoryId', label: '能力分类', required: true, section: 'basic' },
  { key: 'datasourceId', label: '数据源', required: true },
  { key: 'sqlTemplate', label: 'SQL 语句', type: 'databaseSqlEditor', required: true }, field,
];
editor.visibleFormFields = editor.formFields;
editor.form = { sourceKind: 'SQL_STUDIO', categoryId: '', datasourceId: 'controlled-asset', sqlTemplate: 'select {{warehouse_id}}',
  parameters: { warehouse_id: 2 }, inputSchema: { type: 'object', properties: {
    warehouse_id: { type: 'integer' }, min_on_hand: { type: 'integer', default: 0 },
    category: { type: ['string', 'null'], default: null }, enabled: { type: 'boolean', default: false },
  }, required: ['warehouse_id', 'min_on_hand', 'category', 'enabled'] } };
editor.prepareSimpleSqlEditor(field);
assert.equal(editor.validateFormBeforeSubmit(true), true, 'Executing a query does not require publication metadata');
assert.equal(editor.validateFieldsBeforeSubmit(editor.form, false, true), true, 'List test has the same query validation scope');
assert.equal(editor.validateDatabaseParameterCompleteness(true), true, 'Defaults 0, false and null satisfy supplied query parameters');
payload = editor.formPayload();
assert.equal(payload.parameters.warehouse_id, 2);
assert.equal(payload.parameters.min_on_hand, 0);
assert.equal(payload.parameters.category, null);
assert.equal(payload.parameters.enabled, false);
assert.equal(editor.validateFormBeforeSubmit(), false, 'Saving still requires classification');
assert.ok(validationErrors.at(-1).includes('能力分类'));
editor.schemaDraft.queryParameters.find(row => row.name === 'warehouse_id').testValue = '';
assert.equal(editor.validateDatabaseParameterCompleteness(true), false, 'A truly missing required parameter is still rejected');
assert.ok(validationErrors.at(-1).includes('warehouse_id'));
editor.form.sqlTemplate = '';
assert.equal(editor.validateFormBeforeSubmit(true), false, 'Query SQL remains required');
console.log('PASS: query-only validation, strict save validation, supplied/default 0/false/null values and missing required query parameters');

vm.runInContext('globalThis.normalizeSqlParameters = normalizeSqlParameters;', context);
const legacy = "SELECT :id, :id, col::text, ':literal', `:identifier`, [col:name], $$ :body $$ -- :comment\n/* :block */ WHERE x = :value";
const canonical = context.normalizeSqlParameters(legacy);
assert.equal(canonical, "SELECT {{id}}, {{id}}, col::text, ':literal', `:identifier`, [col:name], $$ :body $$ -- :comment\n/* :block */ WHERE x = {{value}}");
assert.equal(context.normalizeSqlParameters(canonical), canonical);
editor.form.sourceKind = 'MANUAL';
editor.form.sqlTemplate = canonical;
assert.equal(editor.extractQueryParameters(canonical).map(item => item.name).join(','), 'id,value');
editor.form.sqlTemplate = "SELECT '{{id}}'";
assert.equal(context.normalizeSqlParameters(editor.form.sqlTemplate), 'SELECT {{id}}');
console.log('PASS: canonical template syntax, legacy conversion, literal/comment/cast preservation and parameter recognition');
