<template>
  <el-card shadow="never" v-loading="busy">
    <h3>交易日期维护</h3>
    <p>按市场维护自然日、交易日和节假日。查询范围内的每个自然日都需要登记。</p>
    <el-form inline>
      <el-form-item label="市场"><el-input v-model="market" placeholder="SSE / SZSE / HKEX" /></el-form-item>
      <el-form-item label="起始日"><el-date-picker v-model="start" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="结束日"><el-date-picker v-model="end" value-format="YYYY-MM-DD" /></el-form-item>
      <el-button @click="load">查询</el-button><el-button type="primary" @click="open = true">维护日期</el-button>
    </el-form>
    <el-table :data="days"><el-table-column prop="market" label="市场" /><el-table-column prop="date" label="日期" /><el-table-column label="交易日"><template #default="{ row }">{{ row.trading ? '是' : '否' }}</template></el-table-column><el-table-column prop="holiday" label="节假日／说明" /></el-table>
    <el-dialog v-model="open" title="维护交易日期" width="min(700px, 95vw)">
      <p>上传或粘贴日期数组；同一市场、日期重复保存会更新原记录。</p>
      <input type="file" accept=".json,application/json" @change="readFile" />
      <el-input v-model="daysText" type="textarea" :rows="12" spellcheck="false" />
      <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" :disabled="busy" @click="save">保存日期</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { ref } from 'vue';
import { capabilityRequest } from '../../services/data-capabilities';
const emit = defineEmits(['notify', 'error']);
const market = ref('SSE'), start = ref(''), end = ref(''), days = ref([]), busy = ref(false), open = ref(false);
const daysText = ref(JSON.stringify([{ market: 'SSE', date: '2026-01-01', trading: false, holiday: '元旦' }], null, 2));
async function run(action) { busy.value = true; try { await action(); } catch (error) { emit('error', error); } finally { busy.value = false; } }
async function load() { await run(async () => { if (!start.value || !end.value) throw new Error('请选择起始日和结束日'); days.value = await capabilityRequest(`/calendar/days?${new URLSearchParams({ market: market.value, start: start.value, end: end.value })}`); }); }
async function save() { await run(async () => { const result = await capabilityRequest('/calendar/days', JSON.parse(daysText.value), 'POST'); days.value = result; open.value = false; emit('notify', { title: `已保存 ${result.length} 个日期` }); }); }
async function readFile(event) { await run(async () => { const file = event.target.files?.[0]; if (!file) return; if (file.size > 5 * 1024 * 1024) throw new Error('文件不能超过 5 MB'); daysText.value = await file.text(); JSON.parse(daysText.value); }); }
</script>
