<template>
  <section v-if="blocks.length" class="analytical-report">
    <header>
      <p>数据分析报告</p>
      <h2>{{ report.decisionQuestion || '分析判断与依据' }}</h2>
    </header>
    <section v-if="primaryBlocks.length" class="insight-executive">
      <h3>核心业务判断</h3>
      <ol><li v-for="block in primaryBlocks" :key="block.id">{{ block.observation }}</li></ol>
    </section>
    <p v-else class="insight-state">当前尚无同时绑定计算数据与证据的核心结论，以下说明保留供核对。</p>
    <article v-for="block in blocks" :key="block.id" class="analytical-insight" :data-finding-id="block.id">
      <header><span>{{ sectionLabel(block.section) }}</span><h3>{{ block.question || '分析发现' }}</h3></header>
      <p class="insight-observation">{{ block.observation }}</p>
      <p v-if="block.presentation?.validationStatus !== 'VERIFIED_DATA_BOUND'" class="insight-state">数据状态：待补充可验证数据</p>
      <template v-else>
        <VisualizationRenderer v-if="block.visualization?.type === 'chart'" :spec="block.visualization"
          :style="{ '--insight-chart-height': `${Math.max(300, Math.min(1000, (block.data.rows?.length || 0) * 26 + 100))}px` }"
          @drill-down="$emit('drill-down', { ...$event, findingId: block.id })" />
        <dl v-if="block.presentation?.showKeyMetrics" class="insight-metric">
          <dt>{{ block.data.title }}</dt><dd>{{ block.data.metric }} <small>{{ block.data.metricUnit }}</small></dd>
        </dl>
        <div v-if="block.presentation?.showDataTable" class="insight-data">
          <table><caption>支撑数据 · {{ block.data.unit }}</caption>
            <thead><tr><th>对象</th><th>数值（{{ block.data.unit }}）</th></tr></thead>
            <tbody><tr v-for="(row, index) in block.data.rows" :key="index"><td>{{ row.entity }}</td><td>{{ row.value }}</td></tr></tbody>
          </table>
        </div>
        <p class="insight-scope">{{ block.data.scope }}</p>
        <p v-if="block.data.calculation" class="insight-scope">计算口径：{{ block.data.calculation }}</p>
      </template>
      <section v-if="block.interpretation"><h4>解释与判断</h4><p>{{ block.interpretation }}</p></section>
      <section v-if="block.implication"><h4>业务含义</h4><p>{{ block.implication }}</p></section>
      <p v-if="block.confidence">判断可信度：{{ block.confidence }}</p>
      <ul v-if="block.caveats?.length" class="insight-caveats"><li v-for="caveat in block.caveats" :key="caveat">{{ caveat }}</li></ul>
      <details v-if="block.evidence?.length" class="insight-evidence"><summary>数据来源与证据（{{ block.evidence.length }}）</summary>
        <div v-for="evidence in block.evidence" :key="evidence.artifactId">
          <p>{{ evidence.text }}</p><p>来源：{{ evidence.sourceScope }}</p>
          <p>记录：{{ (evidence.recordRefs || []).join('、') }}</p>
          <p>原始支持值：{{ (evidence.supportingValues || []).join('、') }}</p>
        </div>
      </details>
    </article>
  </section>
</template>

<script setup>
import { computed } from 'vue';
import VisualizationRenderer from './VisualizationRenderer.vue';
const props = defineProps({ report: { type: Object, default: () => ({}) } });
defineEmits(['drill-down']);
const blocks = computed(() => props.report?.schemaVersion === 'analytical_report.v1'
  && Array.isArray(props.report.blocks) ? props.report.blocks : []);
const primaryBlocks = computed(() => blocks.value.filter(block => block.presentation?.primaryConclusion
  && block.presentation?.validationStatus === 'VERIFIED_DATA_BOUND').slice(0, 5));
const sectionLabel = (section) => ({ CORE: '核心发现', DEEP_DIVE: '关键发现', OVERALL: '结构拆解',
  KEY_DRIVER: '驱动因素', RISK_OPPORTUNITY: '业务含义', LIMITATION: '数据边界', ACTION: '下一步' }[section] || '分析发现');
</script>

<style scoped>
.analytical-report { color: #1e293b; line-height: 1.7; min-width: 0; }
.analytical-report h2, .analytical-report h3 { margin: .4rem 0 1rem; }
.analytical-insight { margin: 1.5rem 0; padding: 1.25rem; border: 1px solid #dbe3ef; border-radius: 12px; background: #fff; }
.analytical-insight header span, .insight-scope { color: #64748b; font-size: .85rem; }
.insight-observation, .analytical-insight section p { white-space: pre-wrap; }
.insight-executive { padding: 1rem 1.25rem; border-left: 4px solid #2563eb; background: #eff6ff; }
.insight-state, .insight-caveats { background: #fffbeb; color: #854d0e; padding: .75rem 1.25rem; }
.insight-data { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
th, td { text-align: left; padding: .5rem .75rem; border-bottom: 1px solid #e2e8f0; }
td:last-child { text-align: right; }
caption { text-align: left; font-weight: 600; margin: .75rem 0; }
.insight-metric dd { font-size: 1.35rem; font-weight: 600; margin: 0; }
.insight-evidence { font-size: .85rem; overflow-wrap: anywhere; color: #64748b; }
.insight-evidence summary { cursor: pointer; }
:deep(.visualization-echart) { min-width: 0; height: var(--insight-chart-height, 300px); }
:deep(.visualization-tabs button.active) { background: #2563eb; color: #fff; }
@media (max-width: 640px) {
  .analytical-insight { padding: .75rem; }
  :deep(.visualization-header) { flex-direction: column; align-items: flex-start; gap: .75rem; }
  :deep(.visualization-actions) { flex-wrap: wrap; max-width: 100%; }
}
</style>
