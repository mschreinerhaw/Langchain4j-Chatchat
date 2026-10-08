<template>
  <div class="view-stack">
    <el-card shadow="never">
      <h2>数据能力中心</h2>
      <p>按数据源配置查询、输入参数和结果映射，测试后发布为 API 或 MCP 工具。</p>
      <el-tabs v-model="tab">
        <el-tab-pane v-for="module in modules" :key="module.type" :name="module.type" :label="module.label" />
      </el-tabs>
    </el-card>
    <CapabilityImportPanel v-if="tab === 'IMPORT'" v-on="listeners" />
    <template v-else>
      <TradingCalendarPanel v-if="tab === 'TRADING_CALENDAR'" v-on="listeners" />
      <QueryCapabilityPanel :key="tab" :type="tab" :label="modules.find(m => m.type === tab)?.label" v-on="listeners" />
      <el-collapse v-if="tab === 'RELATIONAL'" v-model="legacyPanels">
        <el-collapse-item title="既有 SQL 工作台与历史查询" name="legacy">
          <DatabaseMcpView v-if="legacyPanels.includes('legacy')" v-on="listeners" />
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>
<script setup>
import { ref } from 'vue';
import QueryCapabilityPanel from '../components/data-capability/QueryCapabilityPanel.vue';
import TradingCalendarPanel from '../components/data-capability/TradingCalendarPanel.vue';
import CapabilityImportPanel from '../components/data-capability/CapabilityImportPanel.vue';
import DatabaseMcpView from './DatabaseMcpView.vue';
defineOptions({ name: 'DataCapabilityCenterView' });
const emit = defineEmits(['notify', 'error', 'result']);
const listeners = Object.fromEntries(['notify', 'error', 'result'].map(name => [name, value => emit(name, value)]));
const tab = ref('TRINO');
const legacyPanels = ref([]);
const modules = [
  { type: 'TRINO', label: 'Trino 查询' }, { type: 'RELATIONAL', label: '关系库查询' },
  { type: 'GRAPH', label: '图数据库查询' }, { type: 'UNSTRUCTURED', label: '非结构化数据查询' },
  { type: 'TRADING_CALENDAR', label: '交易日历' }, { type: 'IMPORT', label: '批量导入' }
];
</script>
