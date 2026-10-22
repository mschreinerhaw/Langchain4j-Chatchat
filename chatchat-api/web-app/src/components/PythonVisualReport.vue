<template>
  <section class="python-visual-report" aria-label="图文分析报告" :data-status="deliveryStatus">
    <p v-if="!report" role="status">正在整理图文报告…</p>
    <template v-else>
      <header><small>图文报告 · {{ statusLabel }}</small><h2>{{ report.title || '分析报告' }}</h2><p v-if="report.summary">{{ report.summary }}</p></header>
      <p v-if="deliveryStatus!=='SUCCESS'" class="report-notice" role="status">{{ statusLabel }}。已取得的分析内容会继续保留。</p>
      <dl v-if="report.metrics?.length" class="python-report-metrics"><div v-for="metric in report.metrics" :key="metric.id"><dt>{{ metric.label }}</dt><dd>{{ metric.value }} <small>{{ metric.unit }}</small></dd><p v-if="metric.calculation">{{ metric.calculation }}</p></div></dl>
      <article v-for="(section,index) in report.sections || []" :key="index"><template v-if="showSectionText(section)"><h3>{{ section.title }}</h3><p class="report-narrative">{{ section.narrative }}</p></template><PythonReportChart v-for="chart in chartsFor(section.chartIds)" :key="chart.id" :chart="chart" @status="chartStatus" /><ReportTable v-for="table in tablesFor(section.tableIds)" :key="table.id" :table="table" /></article>
      <PythonReportChart v-for="chart in looseCharts" :key="chart.id" :chart="chart" @status="chartStatus" />
      <ReportTable v-for="table in looseTables" :key="table.id" :table="table" />
      <section v-if="report.validation?.warnings?.length" class="report-notice" aria-label="分析限制"><h3>分析限制与产物状态</h3><ul><li v-for="(warning,index) in report.validation.warnings" :key="index">{{ warning }}</li></ul></section>
      <details class="report-provenance"><summary>数据来源与校验</summary><ul><li v-for="source in report.dataSources || []" :key="source">{{ source }}</li></ul><ul><li v-for="(check,index) in report.validation?.checks || []" :key="index">{{ check.name }}：{{ checkLabel(check.status) }} {{ check.detail }}</li></ul><p>图表、指标和表格来自同次 Python 执行产物。结构与文件完整性校验不等同于业务结论认证。</p><p v-if="report.execution">执行任务：{{ report.execution.taskId }} · 代码 SHA-256：{{ report.execution.codeSha256 }}</p></details>
    </template>
  </section>
</template>
<script setup>
import {computed,defineComponent,h,ref,watch,onBeforeUnmount} from 'vue';
import {reviewArchivedVisualReport} from '../js/utils/pythonVisualReport';
import PythonReportChart from './PythonReportChart.vue';
const props=defineProps({report:{type:Object,default:null},fallbackMarkdown:{type:String,default:''}});
const reviewedReport=ref(null);const report=computed(()=>reviewedReport.value||props.report);let reviewRequest;
watch(()=>props.report,value=>{
 reviewedReport.value=null;reviewRequest?.abort();reviewRequest=null;
 if(value?.status!=='FAILED'||value.artifactValidationStatus||!value.execution?.resultDatasetRef||!value.execution?.codeArtifact)return;
 const controller=new AbortController();reviewRequest=controller;
 reviewArchivedVisualReport(value,controller.signal).then(result=>{if(reviewRequest===controller)reviewedReport.value=result;}).catch(()=>{/* Keep the original failure visible when authorization/archive verification fails. */});
},{immediate:true});onBeforeUnmount(()=>reviewRequest?.abort());
const chartFailures=ref({});function chartStatus(event){if(event.error)chartFailures.value={...chartFailures.value,[event.id]:event.error};else{const next={...chartFailures.value};delete next[event.id];chartFailures.value=next;}}
watch(()=>props.report,()=>{chartFailures.value={};});
const deliveryStatus=computed(()=>Object.keys(chartFailures.value).length&&report.value?.status==='SUCCESS'?'PARTIAL':report.value?.status||'LOADING');
const statusLabel=computed(()=>({SUCCESS:'分析完成',PARTIAL:'部分完成',FAILED:'图文报告校验失败',NO_DATA:'没有可分析数据'}[deliveryStatus.value]||'正在生成'));
const checkLabel=status=>({PASSED:'分析程序检查通过',FAILED:'检查失败',UNRESOLVED:'尚未解决'}[status]||'未确认');
// Keep charts/tables while omitting the redundant chart-analysis prose in old and new reports.
const showSectionText=section=>String(section.title||'').trim()!=='图表分析';
const chartsFor=ids=>(report.value?.charts||[]).filter(chart=>(ids||[]).includes(chart.id));
const tablesFor=ids=>(report.value?.tables||[]).filter(table=>(ids||[]).includes(table.id));
const looseCharts=computed(()=>(report.value?.charts||[]).filter(chart=>!(report.value?.sections||[]).some(section=>section.chartIds?.includes(chart.id))));
const looseTables=computed(()=>(report.value?.tables||[]).filter(table=>!(report.value?.sections||[]).some(section=>section.tableIds?.includes(table.id))));
const ReportTable=defineComponent({props:{table:Object},setup:props=>()=>h('div',{class:'python-report-table'},[h('table',{},[h('caption',{},props.table.title),h('thead',{},[h('tr',{},props.table.columns.map(column=>h('th',{},String(column))))]),h('tbody',{},props.table.rows.map(row=>h('tr',{},row.map(cell=>h('td',{},cell==null?'—':String(cell))))))]),props.table.scope?h('p',{},props.table.scope):null])});
</script>
<style scoped>
.python-visual-report{min-width:0;color:#26374e;line-height:1.7}.python-visual-report h2{margin:4px 0 12px}.python-visual-report header>small,.report-provenance{color:#64748b;font-size:12px}.python-report-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:16px 0}.python-report-metrics>div{padding:12px;border:1px solid #dce5f2;border-radius:8px;background:#f7faff}.python-report-metrics dt{font-size:12px;color:#64748b}.python-report-metrics dd{margin:0;font-size:24px;font-weight:600;overflow-wrap:anywhere}.python-report-metrics small,.python-report-metrics p{font-size:11px;font-weight:400;margin:3px 0}.report-notice{padding:10px 14px;background:#fff8e8;border:1px solid #f3dfb0;border-radius:8px}.report-narrative{white-space:pre-wrap}.report-provenance{margin-top:16px;overflow-wrap:anywhere}.report-provenance summary{cursor:pointer}.python-visual-report :deep(.python-report-table){overflow:auto;margin:12px 0}.python-visual-report :deep(table){border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}.python-visual-report :deep(th),.python-visual-report :deep(td){padding:7px 10px;border-bottom:1px solid #e2e8f0;text-align:left;white-space:nowrap}.python-visual-report :deep(caption){text-align:left;font-weight:600}.python-visual-report :deep(.python-report-table p){font-size:12px;color:#64748b}
</style>
