<template>
  <section aria-label="Python 编程交互日志" class="coding-trace">
    <h3>Python 编程交互日志</h3>
    <article v-for="entry in entries" :key="entry.eventId || `${entry.taskId}:${entry.sequence}`">
      <header><strong>{{ presentation(entry).title }}</strong> · #{{ entry.sequence }} · {{ entry.occurredAt }}</header>
      <p>{{ entry.summary }}</p>
      <small>{{ entry.taskId }} / {{ entry.stepId }} · {{ entry.eventState }}</small>
      <div v-if="isPython(entry)">
        <button v-for="kind in ['code','stdout','stderr']" :key="kind" :disabled="loading" @click="inspect(entry,kind)">{{ {code:'查看代码',stdout:'标准输出',stderr:'错误诊断'}[kind] }}</button>
        <button v-if="previous(entry)" :disabled="loading" @click="inspectDiff(entry)">与上一轮代码对比</button>
      </div>
      <details v-if="entry.evidenceRefs?.length"><summary>证据引用</summary><pre>{{ entry.evidenceRefs.join('\n') }}</pre></details>
    </article>
    <p v-if="!entries.length">本任务尚未产生 Python 编程事件。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="selected"><strong>{{ selected }}</strong><p v-if="truncated">内容已截断。</p><pre>{{ content }}</pre></section>
    <section v-if="diff"><strong>{{ diffLabel }}</strong><pre><span v-for="(line,index) in diff.lines" :key="index" :class="line.type">{{ line.type==='added'?'+':line.type==='deleted'?'-':' ' }} {{ line.content }}
</span></pre></section>
  </section>
</template>
<script setup>
import {computed,ref,watch} from 'vue';
import {apiRequest} from '../services/api';
import {runtimeObservationPresentation} from '../js/utils/runtimeObservationPresentation';
import {buildPythonLineDiff} from '../js/utils/pythonCodeDiff';
const props=defineProps({runId:String,observations:{type:Array,default:()=>[]}});
const entries=computed(()=>[...new Map(props.observations.map(item=>item.metadata)
  .filter(meta=>meta?.eventKind==='CODING_TRACE').map(meta=>[meta.eventId || `${meta.taskId}:${meta.sequence}`,meta])).values()]
  .sort((a,b)=>String(a.occurredAt).localeCompare(String(b.occurredAt)) || a.sequence-b.sequence));
const content=ref(''),selected=ref(''),error=ref(''),loading=ref(false),truncated=ref(false),diff=ref(null),diffLabel=ref('');
const presentation=metadata=>runtimeObservationPresentation({metadata});
const isPython=entry=>/^cline-[0-9a-f]{64}$/.test(entry.stepId || '');
function previous(entry){
  const operations=[...new Map(entries.value.filter(item=>item.taskId===entry.taskId && isPython(item))
    .map(item=>[item.stepId,item])).values()];
  const index=operations.findIndex(item=>item.stepId===entry.stepId);return index>0?operations[index-1]:null;
}
function read(entry,kind){return apiRequest(`/agent/runtime/runs/${encodeURIComponent(props.runId)}/coding/${encodeURIComponent(entry.taskId)}/${encodeURIComponent(entry.stepId)}/${kind}`);}
async function inspect(entry,kind){
  loading.value=true;error.value='';diff.value=null;diffLabel.value='';const run=props.runId;
  try{const result=await read(entry,kind);if(run!==props.runId)return;selected.value=`${entry.stepId} / ${kind}`;content.value=result.content;truncated.value=result.truncated;}
  catch{if(run===props.runId)error.value='产物尚未归档或当前用户无权查看。';}finally{loading.value=false;}
}
async function inspectDiff(entry){
  const before=previous(entry);if(!before)return;loading.value=true;error.value='';diff.value=null;selected.value='';diffLabel.value='';const run=props.runId;
  try{const [left,right]=await Promise.all([read(before,'code'),read(entry,'code')]);if(run!==props.runId)return;
    diff.value=buildPythonLineDiff(left.content,right.content);diffLabel.value=`${before.stepId} → ${entry.stepId}`;}
  catch{if(run===props.runId)error.value='代码版本尚未归档或当前用户无权查看。';}finally{loading.value=false;}
}
watch(()=>props.runId,()=>{content.value='';selected.value='';diff.value=null;diffLabel.value='';error.value='';});
</script>
<style scoped>
.coding-trace article{border-left:3px solid #4882db;padding:12px;margin:12px 0}.coding-trace pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:500px;overflow:auto}.coding-trace small,.coding-trace strong{overflow-wrap:anywhere}.coding-trace button{margin:8px 8px 0 0}.added{background:#daf5df}.deleted{background:#ffe0e0}
</style>
