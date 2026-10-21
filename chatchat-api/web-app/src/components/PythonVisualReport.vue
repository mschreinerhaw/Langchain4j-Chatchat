<template>
  <section class="python-visual-report" aria-label="图文分析报告" :data-status="deliveryStatus">
    <p v-if="!report" role="status">正在整理图文报告…</p>
    <template v-else>
      <header><small>图文报告 · {{ statusLabel }}</small><h2>{{ report.title || '分析报告' }}</h2><p v-if="report.summary">{{ report.summary }}</p></header>
      <p v-if="deliveryStatus!=='SUCCESS'" class="report-notice" role="status">{{ statusLabel }}。已取得的分析内容会继续保留。</p>
      <dl v-if="report.metrics?.length" class="python-report-metrics"><div v-for="metric in report.metrics" :key="metric.id"><dt>{{ metric.label }}</dt><dd>{{ metric.value }} <small>{{ metric.unit }}</small></dd></div></dl>
      <article v-for="(section,index) in report.sections || []" :key="index"><h3>{{ section.title }}</h3><p class="report-narrative">{{ section.narrative }}</p><PythonReportChart v-for="chart in chartsFor(section.chartIds)" :key="chart.id" :chart="chart" @status="chartStatus" /><ReportTable v-for="table in tablesFor(section.tableIds)" :key="table.id" :table="table" /></article>
      <PythonReportChart v-for="chart in looseCharts" :key="chart.id" :chart="chart" @status="chartStatus" />
      <ReportTable v-for="table in looseTables" :key="table.id" :table="table" />
      <details v-if="report.sections?.length && markdown" class="report-full-text"><summary>完整分析正文</summary><ReportMarkdown :content="markdown" :render-markdown="renderMarkdown" /></details>
      <ReportMarkdown v-else-if="markdown" :content="markdown" :render-markdown="renderMarkdown" />
      <section v-if="report.limitations?.length" class="report-notice" aria-label="分析限制"><h3>解读提示</h3><ul><li v-for="(limitation,index) in report.limitations" :key="index">{{ limitation }}</li></ul></section>
    </template>
  </section>
</template>
<script setup>
import {computed,defineComponent,h,ref,watch} from 'vue';
import MarkdownIt from 'markdown-it';
import ReportMarkdown from './ReportMarkdown.vue';import PythonReportChart from './PythonReportChart.vue';
const props=defineProps({report:{type:Object,default:null},fallbackMarkdown:{type:String,default:''}});
const markdown=computed(()=>props.report?.markdown||props.fallbackMarkdown);
const md=new MarkdownIt({html:false,breaks:true,linkify:false});md.disable('image');const renderMarkdown=value=>md.render(String(value||''));
const chartFailures=ref({});function chartStatus(event){if(event.error)chartFailures.value={...chartFailures.value,[event.id]:event.error};else{const next={...chartFailures.value};delete next[event.id];chartFailures.value=next;}}
watch(()=>props.report,()=>{chartFailures.value={};});
const deliveryStatus=computed(()=>Object.keys(chartFailures.value).length&&props.report?.status==='SUCCESS'?'PARTIAL':props.report?.status||'LOADING');
const statusLabel=computed(()=>({SUCCESS:'分析完成',PARTIAL:'部分完成',FAILED:'图文报告暂不可用',NO_DATA:'没有可分析数据'}[deliveryStatus.value]||'正在生成'));
const chartsFor=ids=>(props.report?.charts||[]).filter(chart=>(ids||[]).includes(chart.id));
const tablesFor=ids=>(props.report?.tables||[]).filter(table=>(ids||[]).includes(table.id));
const looseCharts=computed(()=>(props.report?.charts||[]).filter(chart=>!(props.report?.sections||[]).some(section=>section.chartIds?.includes(chart.id))));
const looseTables=computed(()=>(props.report?.tables||[]).filter(table=>!(props.report?.sections||[]).some(section=>section.tableIds?.includes(table.id))));
const ReportTable=defineComponent({props:{table:Object},setup:props=>()=>h('div',{class:'python-report-table'},[h('table',{},[h('caption',{},props.table.title),h('thead',{},[h('tr',{},props.table.columns.map(column=>h('th',{},String(column))))]),h('tbody',{},props.table.rows.map(row=>h('tr',{},row.map(cell=>h('td',{},cell==null?'—':String(cell))))))]),props.table.scope?h('p',{},props.table.scope):null])});
</script>
<style scoped>
.python-visual-report{min-width:0;color:#26374e;line-height:1.7}.python-visual-report h2{margin:4px 0 12px}.python-visual-report header>small,.report-provenance{color:#64748b;font-size:12px}.python-report-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:16px 0}.python-report-metrics>div{padding:12px;border:1px solid #dce5f2;border-radius:8px;background:#f7faff}.python-report-metrics dt{font-size:12px;color:#64748b}.python-report-metrics dd{margin:0;font-size:24px;font-weight:600;overflow-wrap:anywhere}.python-report-metrics small,.python-report-metrics p{font-size:11px;font-weight:400;margin:3px 0}.report-notice{padding:10px 14px;background:#fff8e8;border:1px solid #f3dfb0;border-radius:8px}.report-narrative{white-space:pre-wrap}.report-provenance{margin-top:16px;overflow-wrap:anywhere}.report-provenance summary,.report-full-text summary{cursor:pointer}.python-visual-report :deep(.python-report-table){overflow:auto;margin:12px 0}.python-visual-report :deep(table){border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}.python-visual-report :deep(th),.python-visual-report :deep(td){padding:7px 10px;border-bottom:1px solid #e2e8f0;text-align:left;white-space:nowrap}.report-code{margin-top:12px}.report-code summary{cursor:pointer}.report-code pre{max-height:400px;overflow:auto;background:#f6f8fc;padding:12px;font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}.python-visual-report :deep(caption){text-align:left;font-weight:600}.python-visual-report :deep(.python-report-table p){font-size:12px;color:#64748b}
</style>
<style scoped>
.python-visual-report{background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:28px;box-shadow:0 8px 28px #17345b08;overflow-wrap:anywhere}
.python-visual-report>header{padding:0 0 22px;border-bottom:1px solid #e7edf5;margin-bottom:24px}
.python-visual-report header>small{display:inline-block;color:#2563eb;font-weight:600;letter-spacing:.06em}
.python-visual-report h2{font-size:26px;line-height:1.4;color:#142b49;margin:10px 0 14px}
.python-visual-report header>p{font-size:15px;line-height:1.9;color:#475569;margin-bottom:0}
.python-report-metrics{gap:14px;margin:24px 0}
.python-report-metrics>div{padding:18px;background:linear-gradient(135deg,#f0f6ff,#f8fbff);border:1px solid #e0eafb;border-radius:12px}
.python-report-metrics dd{margin-top:8px;color:#173f78;font-size:28px;font-variant-numeric:tabular-nums}
.python-visual-report>article{margin:28px 0;padding-bottom:20px;border-bottom:1px solid #eef2f7}
.python-visual-report h3{font-size:18px;color:#183b63;line-height:1.5;margin:0 0 12px}
.report-narrative{line-height:1.95;color:#334155}
.report-full-text{margin-top:24px;padding:14px 16px;border:1px solid #e2e8f0;border-radius:10px}
.python-visual-report :deep(th){background:#f1f5fa;color:#334d6f;font-weight:600}
.python-visual-report :deep(td){padding:11px 14px}
.python-visual-report :deep(tbody tr:nth-child(even)){background:#f8fafc}
.python-visual-report :deep(caption){padding:10px 0;color:#334155}
@media(max-width:600px){.python-visual-report{padding:16px;border-radius:12px}.python-visual-report h2{font-size:21px}.python-report-metrics{grid-template-columns:repeat(auto-fit,minmax(120px,1fr))}.python-report-metrics dd{font-size:23px}}
</style>
