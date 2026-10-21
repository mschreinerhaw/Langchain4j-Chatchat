<template>
  <section class="python-visual-report" aria-label="图文分析报告" :data-status="deliveryStatus">
    <p v-if="!report" role="status">正在整理图文报告…</p>
    <template v-else>
      <header><small>图文报告 · {{ statusLabel }}</small><h2>{{ report.title || '分析报告' }}</h2><p v-if="report.summary">{{ report.summary }}</p></header>
      <p v-if="deliveryStatus!=='SUCCESS'" class="report-notice" role="status">{{ statusLabel }}。已取得的分析内容会继续保留。</p>
      <dl v-if="report.metrics?.length" class="python-report-metrics"><div v-for="metric in report.metrics" :key="metric.id"><dt>{{ metric.label }}</dt><dd>{{ metric.value }} <small>{{ metric.unit }}</small></dd><p v-if="metric.calculation">{{ metric.calculation }}</p></div></dl>
      <article v-for="(section,index) in report.sections || []" :key="index"><h3>{{ section.title }}</h3><p class="report-narrative">{{ section.narrative }}</p><PythonReportChart v-for="chart in chartsFor(section.chartIds)" :key="chart.id" :chart="chart" @status="chartStatus" /><ReportTable v-for="table in tablesFor(section.tableIds)" :key="table.id" :table="table" /></article>
      <PythonReportChart v-for="chart in looseCharts" :key="chart.id" :chart="chart" @status="chartStatus" />
      <ReportTable v-for="table in looseTables" :key="table.id" :table="table" />
      <details v-if="report.sections?.length && markdown" class="report-full-text"><summary>完整分析正文</summary><ReportMarkdown :content="markdown" :render-markdown="renderMarkdown" /></details>
      <ReportMarkdown v-else-if="markdown" :content="markdown" :render-markdown="renderMarkdown" />
      <section v-if="report.validation?.warnings?.length" class="report-notice" aria-label="分析限制"><h3>分析限制与产物状态</h3><ul><li v-for="(warning,index) in report.validation.warnings" :key="index">{{ warning }}</li></ul></section>
      <details v-if="report.execution?.codeArtifact" class="report-code" @toggle="loadCode($event)"><summary>分析代码与执行状态</summary><p>{{ report.execution.status }} · {{ report.execution.executorStopped ? '执行器已停止' : '等待执行收据' }}</p><p v-if="codeError" role="alert">{{ codeError }}</p><pre v-else>{{ sourceCode || '正在读取已执行代码…' }}</pre></details>
      <details class="report-provenance"><summary>数据来源与校验</summary><ul><li v-for="source in report.dataSources || []" :key="source">{{ source }}</li></ul><ul><li v-for="(check,index) in report.validation?.checks || []" :key="index">{{ check.name }}：{{ check.status==='PASSED'?'分析程序检查通过':'检查失败' }} {{ check.detail }}</li></ul><p>图表、指标和表格来自同次 Python 执行产物。结构与文件完整性校验不等同于业务结论认证。</p><p v-if="report.execution">执行任务：{{ report.execution.taskId }} · 代码 SHA-256：{{ report.execution.codeSha256 }}</p></details>
    </template>
  </section>
</template>
<script setup>
import {computed,defineComponent,h,ref,watch,onBeforeUnmount} from 'vue';
import {readReportArtifact} from '../js/utils/pythonVisualReport';import MarkdownIt from 'markdown-it';
import ReportMarkdown from './ReportMarkdown.vue';import PythonReportChart from './PythonReportChart.vue';
const props=defineProps({report:{type:Object,default:null},fallbackMarkdown:{type:String,default:''}});
const sourceCode=ref(''),codeError=ref('');let codeRequest;
async function loadCode(event){if(!event.target.open||sourceCode.value||codeRequest)return;codeRequest=new AbortController();const request=codeRequest;try{const bytes=await readReportArtifact(props.report.execution.codeArtifact,request.signal);if(codeRequest===request)sourceCode.value=new TextDecoder().decode(bytes);}catch(error){if(error.name!=='AbortError')codeError.value=error.message;}finally{if(codeRequest===request)codeRequest=null;}}
watch(()=>props.report?.execution?.codeArtifact?.fileId,()=>{codeRequest?.abort();codeRequest=null;sourceCode.value='';codeError.value='';});onBeforeUnmount(()=>codeRequest?.abort());
const markdown=computed(()=>props.report?.markdown||props.fallbackMarkdown);
const md=new MarkdownIt({html:false,breaks:true,linkify:false});md.disable('image');const renderMarkdown=value=>md.render(String(value||''));
const chartFailures=ref({});function chartStatus(event){if(event.error)chartFailures.value={...chartFailures.value,[event.id]:event.error};else{const next={...chartFailures.value};delete next[event.id];chartFailures.value=next;}}
watch(()=>props.report,()=>{chartFailures.value={};});
const deliveryStatus=computed(()=>Object.keys(chartFailures.value).length&&props.report?.status==='SUCCESS'?'PARTIAL':props.report?.status||'LOADING');
const statusLabel=computed(()=>({SUCCESS:'分析完成',PARTIAL:'部分完成',FAILED:'图文报告校验失败',NO_DATA:'没有可分析数据'}[deliveryStatus.value]||'正在生成'));
const chartsFor=ids=>(props.report?.charts||[]).filter(chart=>(ids||[]).includes(chart.id));
const tablesFor=ids=>(props.report?.tables||[]).filter(table=>(ids||[]).includes(table.id));
const looseCharts=computed(()=>(props.report?.charts||[]).filter(chart=>!(props.report?.sections||[]).some(section=>section.chartIds?.includes(chart.id))));
const looseTables=computed(()=>(props.report?.tables||[]).filter(table=>!(props.report?.sections||[]).some(section=>section.tableIds?.includes(table.id))));
const ReportTable=defineComponent({props:{table:Object},setup:props=>()=>h('div',{class:'python-report-table'},[h('table',{},[h('caption',{},props.table.title),h('thead',{},[h('tr',{},props.table.columns.map(column=>h('th',{},String(column))))]),h('tbody',{},props.table.rows.map(row=>h('tr',{},row.map(cell=>h('td',{},cell==null?'—':String(cell))))))]),props.table.scope?h('p',{},props.table.scope):null])});
</script>
<style scoped>
.python-visual-report{min-width:0;color:#26374e;line-height:1.7}.python-visual-report h2{margin:4px 0 12px}.python-visual-report header>small,.report-provenance{color:#64748b;font-size:12px}.python-report-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:16px 0}.python-report-metrics>div{padding:12px;border:1px solid #dce5f2;border-radius:8px;background:#f7faff}.python-report-metrics dt{font-size:12px;color:#64748b}.python-report-metrics dd{margin:0;font-size:24px;font-weight:600;overflow-wrap:anywhere}.python-report-metrics small,.python-report-metrics p{font-size:11px;font-weight:400;margin:3px 0}.report-notice{padding:10px 14px;background:#fff8e8;border:1px solid #f3dfb0;border-radius:8px}.report-narrative{white-space:pre-wrap}.report-provenance{margin-top:16px;overflow-wrap:anywhere}.report-provenance summary,.report-full-text summary{cursor:pointer}.python-visual-report :deep(.python-report-table){overflow:auto;margin:12px 0}.python-visual-report :deep(table){border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}.python-visual-report :deep(th),.python-visual-report :deep(td){padding:7px 10px;border-bottom:1px solid #e2e8f0;text-align:left;white-space:nowrap}.report-code{margin-top:12px}.report-code summary{cursor:pointer}.report-code pre{max-height:400px;overflow:auto;background:#f6f8fc;padding:12px;font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}.python-visual-report :deep(caption){text-align:left;font-weight:600}.python-visual-report :deep(.python-report-table p){font-size:12px;color:#64748b}
</style>
