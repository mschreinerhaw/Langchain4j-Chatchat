<template>
  <figure ref="container" class="python-report-chart" :data-chart-id="chart.id" :aria-busy="loading">
    <figcaption><strong>{{ chart.title }}</strong></figcaption>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="loading" role="status">正在加载图表…</p>
    <button v-else-if="!imageUrl&&!frameDocument" type="button" @click="load">加载图表</button>
    <img v-if="imageUrl" :src="imageUrl" :alt="chart.title" loading="lazy" @error="error='静态图表渲染失败，文字分析仍保留'" />
    <iframe v-if="frameDocument" ref="frame" :srcdoc="frameDocument" :title="chart.title" sandbox="allow-scripts" referrerpolicy="no-referrer" @load="sendFigure"></iframe>
    <p v-if="chart.caption" class="chart-caption">{{ chart.caption }}</p>
  </figure>
</template>
<script setup>
import {ref,onMounted,onBeforeUnmount,watch} from 'vue';
import plotlyRuntime from 'plotly.js-dist-min/plotly.min.js?url';
import {readReportArtifact,checkedPlotlyFigure,plotlyFrameDocument} from '../js/utils/pythonVisualReport';
const props=defineProps({chart:{type:Object,required:true}});
const emit=defineEmits(['status']);
const container=ref(null),frame=ref(null),imageUrl=ref(''),frameDocument=ref(''),loading=ref(false),error=ref('');
let controller,observer,figure,serial=0,renderTimeout,renderStarted=0;
watch(error,value=>emit('status',{id:props.chart.id,error:value}));
function recordTiming(){if(!renderStarted)return;performance.measure?.("runtime-report-chart-render",{start:renderStarted,end:performance.now()});window.dispatchEvent(new CustomEvent("runtime:report-chart-rendered",{detail:{fileId:props.chart.artifact?.fileId,kind:props.chart.kind,durationMs:Math.round(performance.now()-renderStarted)}}));renderStarted=0;}
function clear(){serial++;controller?.abort();clearTimeout(renderTimeout);if(imageUrl.value)URL.revokeObjectURL(imageUrl.value);imageUrl.value='';frameDocument.value='';figure=null;loading.value=false;}
async function load(){
  if(loading.value)return;clear();const turn=serial;controller=new AbortController();error.value='';loading.value=true;renderStarted=performance.now();
  try{
    const bytes=await readReportArtifact(props.chart.artifact,controller.signal);if(turn!==serial)return;
    if(props.chart.kind==='plotly'){
      figure=checkedPlotlyFigure(JSON.parse(new TextDecoder().decode(bytes)));frameDocument.value=plotlyFrameDocument(plotlyRuntime,window.location.href);
    }else{
      const media={png:'image/png',svg:'image/svg+xml'}[props.chart.kind];if(!media||props.chart.artifact.mediaType!==media)throw new Error('不支持的静态图表');
      imageUrl.value=URL.createObjectURL(new Blob([bytes],{type:media}));loading.value=false;recordTiming();
    }
  }catch(ex){if(turn===serial&&ex.name!=='AbortError'){error.value=ex.message||'图表读取失败';loading.value=false;}}
}
function sendFigure(){if(!figure)return;frame.value?.contentWindow?.postMessage({type:'runtime-plotly-figure',figure},'*');clearTimeout(renderTimeout);renderTimeout=setTimeout(()=>{if(loading.value){error.value='交互图渲染超时，原始分析结果仍保留';loading.value=false;}},20000);}
function rendered(event){if(event.source!==frame.value?.contentWindow)return;if(event.data?.type==='runtime-plotly-rendered'){clearTimeout(renderTimeout);loading.value=false;recordTiming();}if(event.data?.type==='runtime-plotly-snapshot' && /^data:image\/png;base64,/.test(event.data.image||'')){frame.value.dataset.pdfImage=event.data.image;}if(event.data?.type==='runtime-plotly-failed'){clearTimeout(renderTimeout);error.value='交互图渲染失败，原始分析结果仍保留';loading.value=false;}}
function prepareExport(event){event.detail.tasks.push((async()=>{if(!imageUrl.value&&!frameDocument.value)await load();const deadline=Date.now()+25000;while(loading.value||(frameDocument.value&&!frame.value?.dataset.pdfImage)){if(error.value||Date.now()>deadline)throw new Error('图表暂时无法导出，请重试');await new Promise(resolve=>setTimeout(resolve,100));}if(error.value)throw new Error('图表暂时无法导出，请重试');})());}
onMounted(()=>container.value?.addEventListener('report:prepare-export',prepareExport));
onBeforeUnmount(()=>container.value?.removeEventListener('report:prepare-export',prepareExport));
onMounted(()=>{window.addEventListener('message',rendered);if(typeof IntersectionObserver==='function'){observer=new IntersectionObserver(entries=>{if(entries.some(item=>item.isIntersecting)){observer.disconnect();void load();}},{rootMargin:'120px'});observer.observe(container.value);}else void load();});
watch(()=>props.chart.artifact?.fileId,()=>{clear();void load();});
onBeforeUnmount(()=>{clear();observer?.disconnect();window.removeEventListener('message',rendered);});
</script>
<style scoped>
.python-report-chart{margin:16px 0;border:1px solid #dce5f2;border-radius:10px;padding:12px;min-width:0;background:white}.python-report-chart figcaption{display:flex;justify-content:space-between;gap:8px}.python-report-chart small,.chart-caption{color:#64748b;font-size:12px}.python-report-chart img{display:block;width:100%;height:auto;max-height:900px;object-fit:contain;margin-top:10px}.python-report-chart iframe{display:block;border:0;width:100%;height:440px;margin-top:10px}.python-report-chart [role=alert]{color:#b45309}.python-report-chart button{border:1px solid #dce5f2;background:#f7faff;color:#2563eb;padding:5px 10px;border-radius:5px;cursor:pointer}@media(max-width:600px){.python-report-chart{padding:8px}.python-report-chart iframe{height:340px}}
</style>
