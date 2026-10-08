<template>
  <el-card shadow="never" v-loading="busy">
    <h3>批量导入数据能力</h3>
    <p>模板包含查询定义、输入参数、业务分类与发布配置。先校验模板，再导入；每行单独反馈结果。</p>
    <el-select v-model="type"><el-option v-for="option in types" :key="option.value" :label="option.label" :value="option.value" /></el-select>
    <el-button @click="template">下载模板</el-button>
    <input type="file" accept=".json,application/json" @change="readFile" />
    <el-input v-model="text" type="textarea" :rows="14" spellcheck="false" placeholder="粘贴能力定义 JSON 数组" />
    <div class="panel-actions"><el-button @click="submit(true)">校验模板</el-button><el-button type="primary" @click="submit(false)">批量导入</el-button><el-button @click="load">刷新导入记录</el-button></div>
    <el-alert v-if="feedback" :closable="false" :type="feedback.failed ? 'warning' : 'success'" :title="`${feedback.dryRun ? '校验' : '导入'}完成：成功 ${feedback.succeeded}，失败 ${feedback.failed}`" />
    <el-alert v-if="feedback?.publicationError" :closable="false" type="warning" :title="`能力已导入，MCP 发布失败：${feedback.publicationError}`" />
    <el-table v-if="feedback" :data="JSON.parse(feedback.resultsJson)"><el-table-column prop="row" label="行号" /><el-table-column prop="code" label="能力编码" /><el-table-column prop="status" label="状态" /><el-table-column prop="error" label="错误信息" /></el-table>
    <h4>导入与校验记录</h4>
    <el-table :data="batches"><el-table-column prop="createdAt" label="时间" /><el-table-column prop="succeeded" label="成功数" /><el-table-column prop="failed" label="失败数" /><el-table-column label="结果"><template #default="{ row }"><el-button link @click="feedback = row">查看异常与结果</el-button><el-button link @click="downloadJson(JSON.parse(row.resultsJson), `import-${row.id}.json`)">下载反馈</el-button></template></el-table-column></el-table>
  </el-card>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { capabilityRequest, downloadJson } from '../../services/data-capabilities';
const emit = defineEmits(['error', 'notify']);
const type = ref('TRINO'), text = ref(''), busy = ref(false), feedback = ref(null), batches = ref([]);
const types = [{ value: 'TRINO', label: 'Trino 查询' }, { value: 'RELATIONAL', label: '关系库查询' }, { value: 'GRAPH', label: '图数据库查询' }, { value: 'UNSTRUCTURED', label: '非结构化数据查询' }, { value: 'TRADING_CALENDAR', label: '交易日历' }];
async function run(action) { busy.value = true; try { await action(); } catch (error) { emit('error', error); } finally { busy.value = false; } }
async function load() { await run(async () => { batches.value = await capabilityRequest('/imports'); }); }
async function template() { await run(async () => { const value = [await capabilityRequest(`/imports/template?type=${type.value}`)]; text.value = JSON.stringify(value, null, 2); downloadJson(value, `data-capability-${type.value.toLowerCase()}.json`); }); }
async function readFile(event) { await run(async () => { const file = event.target.files?.[0]; if (!file) return; if (file.size > 5 * 1024 * 1024) throw new Error('文件不能超过 5 MB'); text.value = await file.text(); }); }
async function submit(dryRun) { await run(async () => { const definitions = JSON.parse(text.value); if (!Array.isArray(definitions)) throw new Error('导入内容必须是 JSON 数组'); feedback.value = await capabilityRequest('/imports', { definitions, dryRun }, 'POST'); batches.value = await capabilityRequest('/imports'); }); }
onMounted(load);
</script>
