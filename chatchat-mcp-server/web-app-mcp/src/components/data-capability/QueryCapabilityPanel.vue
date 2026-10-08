<template>
  <el-card shadow="never" v-loading="busy">
    <div class="panel-heading">
      <h3>{{ label }}</h3>
      <div><el-button @click="load">刷新</el-button><el-button type="primary" @click="create">新增查询</el-button></div>
    </div>
    <el-input v-model="search" placeholder="搜索名称、编码、业务分类" clearable />
    <el-table :data="filtered" empty-text="暂无查询，点击新增查询开始配置">
      <el-table-column prop="title" label="名称" />
      <el-table-column prop="code" label="编码" />
      <el-table-column prop="categoryId" label="业务分类" />
      <el-table-column label="状态"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }} · API {{ row.apiPublished ? '已发布' : '未发布' }} · MCP {{ row.mcpPublished ? '已发布' : '未发布' }}</template></el-table-column>
      <el-table-column label="操作" width="300"><template #default="{ row }">
        <el-button link @click="edit(row)">配置</el-button><el-button link @click="test(row)">测试</el-button>
        <el-button link :disabled="!row.enabled || !row.apiPublished" @click="invoke(row)">执行</el-button>
        <el-button link @click="history(row)">执行记录</el-button><el-button link type="danger" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="open" :title="editing ? '编辑查询' : '新增查询'" width="min(900px, 95vw)">
      <el-form label-position="top" v-if="draft">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="能力编码"><el-input v-model="draft.code" :disabled="editing" placeholder="例如 company_relationships" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="显示名称"><el-input v-model="draft.title" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="描述"><el-input v-model="draft.description" type="textarea" /></el-form-item>
        <el-form-item label="业务分类"><el-select v-model="draft.categoryId" clearable filterable><el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item v-if="type !== 'TRADING_CALENDAR'" label="数据源资产">
          <el-select v-model="draft.connectionId" filterable placeholder="选择资产配置中统一维护的数据源" @visible-change="value => value && loadConnections()">
            <el-option v-for="c in connections" :key="c.id" :label="`${c.name}${c.enabled ? '' : '（停用）'}`" :value="c.id" />
          </el-select>
          <p>连接地址、认证信息及启用状态在资产配置中维护，此处只引用资产。</p>
        </el-form-item>
        <el-form-item :label="type === 'TRADING_CALENDAR' ? '查询操作' : '查询语句'">
          <el-select v-if="type === 'TRADING_CALENDAR'" v-model="draft.query" @change="calendarOperation"><el-option v-for="op in calendarOperations" :key="op.value" :label="op.label" :value="op.value" /></el-select>
          <el-input v-else v-model="draft.query" type="textarea" :rows="6" spellcheck="false" />
          <p>{{ queryHelp }}</p>
        </el-form-item>
        <el-form-item :label="optionsLabel"><el-input v-model="optionsText" type="textarea" :rows="3" spellcheck="false" /></el-form-item>
        <el-form-item label="输入参数（JSON Schema）"><el-input v-model="schemaText" type="textarea" :rows="5" spellcheck="false" /></el-form-item>
        <el-form-item label='结果映射（JSON，输出字段名 → 原字段名，如 {"company": "name"}）'><el-input v-model="mappingText" type="textarea" :rows="2" spellcheck="false" /></el-form-item>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="超时（秒）"><el-input-number v-model="draft.timeoutSeconds" :min="1" :max="['TRINO', 'RELATIONAL'].includes(type) ? 60 : 300" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="最大返回行数"><el-input-number v-model="draft.maxRows" :min="1" :max="10000" /></el-form-item></el-col>
        </el-row>
        <el-form-item><el-checkbox v-model="draft.enabled">启用</el-checkbox><el-checkbox v-model="draft.apiPublished">发布 API</el-checkbox><el-checkbox v-model="draft.mcpPublished">发布 MCP 工具</el-checkbox></el-form-item>
        <p>API：POST /api/v1/data-capabilities/{{ draft.code }}/invoke · MCP：data_query_{{ draft.code }}</p>
      </el-form>
      <template #footer><el-button @click="testDraft" :disabled="busy">测试草稿</el-button><el-button @click="open = false">取消</el-button><el-button type="primary" :disabled="busy" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="parametersOpen" title="输入查询参数" width="min(650px, 95vw)">
      <el-input v-model="parametersText" type="textarea" :rows="8" spellcheck="false" />
      <template #footer><el-button @click="parametersOpen = false">取消</el-button><el-button type="primary" @click="submitParameters">执行</el-button></template>
    </el-dialog>
    <el-dialog v-model="historyOpen" :title="`${historyCode} 执行记录`" width="min(900px, 95vw)">
      <el-button @click="refreshHistory">刷新状态</el-button>
      <el-table :data="executionRows"><el-table-column prop="id" label="执行 ID" /><el-table-column prop="status" label="状态" /><el-table-column prop="startedAt" label="开始时间" /><el-table-column prop="durationMs" label="耗时（ms）" /><el-table-column label="结果"><template #default="{ row }"><el-button link @click="showExecution(row)">查看</el-button></template></el-table-column></el-table>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue';
import { ElMessageBox } from 'element-plus';
import { capabilityRequest } from '../../services/data-capabilities';
import { apiFetch } from '../../services/http';
import { API_BASE } from '../../services/config';
const props = defineProps({ type: String, label: String });
const emit = defineEmits(['notify', 'error', 'result']);
const rows = ref([]), search = ref(''), busy = ref(false), open = ref(false), editing = ref(false), draft = ref(null);
const schemaText = ref(''), optionsText = ref(''), mappingText = ref(''), connections = ref([]), categories = ref([]);
const parametersOpen = ref(false), parametersText = ref('{}'), historyOpen = ref(false), historyCode = ref(''), executionRows = ref([]);
let parameterAction;
const calendarOperations = [{ value: 'isTradingDay', label: '是否交易日' }, { value: 'previousTradingDay', label: '上一交易日' }, { value: 'nextTradingDay', label: '下一交易日' }, { value: 'tradingDays', label: '交易日区间' }];
const filtered = computed(() => rows.value.filter(r => `${r.title} ${r.code} ${r.categoryId || ''}`.toLowerCase().includes(search.value.toLowerCase())));
const queryHelp = computed(() => ({ TRINO: '支持跨 Catalog 联合查询。参数使用 {{name}}，无需添加引号。', RELATIONAL: '输入参数使用 {{name}}，无需添加引号。', GRAPH: '使用只读 Cypher 与 $name 参数，返回实体、关系和路径。', UNSTRUCTURED: '使用 OpenSearch 查询 DSL；参数写为完整 JSON 字符串 "{{name}}"，支持关键词、过滤和 k-NN 向量查询。', TRADING_CALENDAR: '单日操作输入 date；区间操作输入 start、end，日期格式为 YYYY-MM-DD。' }[props.type]));
const optionsLabel = computed(() => ({ TRINO: 'Catalog / Schema（JSON）', GRAPH: '图数据库名称（JSON）', UNSTRUCTURED: '索引配置（JSON）', TRADING_CALENDAR: '市场配置（JSON）' }[props.type] || '扩展配置（JSON）'));
async function run(action) { busy.value = true; try { return await action(); } catch (error) { emit('error', error); } finally { busy.value = false; } }
async function load() { await run(async () => { rows.value = await capabilityRequest(`?type=${props.type}`); }); }
async function loadConnections() {
  await run(async () => {
    const sql = ['TRINO', 'RELATIONAL'].includes(props.type);
    const available = await capabilityRequest(sql ? '/connections/sql' : '/connections');
    connections.value = available.filter(c => sql ? (props.type !== 'TRINO' || c.trino) : c.type === props.type);
  });
}
function edit(row) { draft.value = JSON.parse(JSON.stringify(row)); editing.value = true; prepare(); }
function prepare() { schemaText.value = JSON.stringify(draft.value.inputSchema, null, 2); optionsText.value = JSON.stringify(draft.value.options, null, 2); mappingText.value = JSON.stringify(draft.value.resultMapping, null, 2); open.value = true; loadConnections(); }
async function create() { await run(async () => { draft.value = await capabilityRequest(`/imports/template?type=${props.type}`); editing.value = false; prepare(); }); }
function definition() { return { ...draft.value, inputSchema: JSON.parse(schemaText.value), options: JSON.parse(optionsText.value), resultMapping: JSON.parse(mappingText.value) }; }
function calendarOperation(operation) {
  const names = operation === 'tradingDays' ? ['start', 'end'] : ['date'];
  schemaText.value = JSON.stringify({ type: 'object', properties: Object.fromEntries(names.map(name => [name, { type: 'string', format: 'date' }])), required: names }, null, 2);
}
async function save() { await run(async () => { const value = definition(); await capabilityRequest(editing.value ? `/${value.code}` : '', value, editing.value ? 'PUT' : 'POST'); open.value = false; rows.value = await capabilityRequest(`?type=${props.type}`); emit('notify', { title: '查询已保存' }); }); }
async function remove(row) {
  try { await ElMessageBox.confirm(`删除查询“${row.title}”？`, '删除查询', { type: 'warning' }); }
  catch { return; }
  await run(async () => { await capabilityRequest(`/${row.code}`, undefined, 'DELETE'); rows.value = await capabilityRequest(`?type=${props.type}`); });
}
function ask(action, schema) { parametersText.value = JSON.stringify(Object.fromEntries(Object.entries(schema?.properties || {}).map(([name, property]) => [name, property.default ?? ''])), null, 2); parameterAction = action; parametersOpen.value = true; }
function test(row) { ask(p => capabilityRequest(`/${row.code}/test`, p, 'POST'), row.inputSchema); }
function invoke(row) { ask(p => capabilityRequest(`/${row.code}/invoke?async=true`, p, 'POST'), row.inputSchema); }
function testDraft() { try { const d = definition(); ask(p => capabilityRequest('/test', { definition: d, parameters: p }, 'POST'), d.inputSchema); } catch (error) { emit('error', error); } }
async function submitParameters() { await run(async () => { const result = await parameterAction(JSON.parse(parametersText.value)); parametersOpen.value = false; showExecution(result); }); }
function showExecution(row) { emit('result', { title: `查询执行：${row.status}`, value: { ...row, result: row.resultJson ? JSON.parse(row.resultJson) : null } }); }
async function history(row) { historyCode.value = row.code; await refreshHistory(); historyOpen.value = true; }
async function refreshHistory() { await run(async () => { executionRows.value = await capabilityRequest(`/${historyCode.value}/executions`); }); }
onMounted(async () => { await load(); await run(async () => { categories.value = await apiFetch(`${API_BASE}/database-query/categories`); }); });
</script>
