<template>
  <details class="user-asset-library" @toggle="opened">
    <summary>我的文件与分析记录</summary>
    <p>保存原始资料，选择本次分析需要的文件；历史资料仅在勾选后使用。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="asset-actions">
      <label>上传资料 <input type="file" accept=".pdf,.docx,.xlsx,.csv,.parquet,.txt,.md,.json,.jsonl" :disabled="busy" @change="upload"></label>
      <button :disabled="busy" @click="refresh">刷新</button>
      <span v-if="space">已用 {{ size(space.usedBytes) }} / {{ size(space.quotaBytes) }}</span>
    </div>
    <ul>
      <li v-for="file in files" :key="file.id">
        <label><input v-model="selected" type="checkbox" :value="file.id" :disabled="busy || file.state!=='AVAILABLE' || !selectable(file)">
          {{ file.fileName }} · {{ size(file.size) }} <small>{{ label(file.kind) }}{{ file.state==='STAGED'?' · 上传待重试':'' }}</small></label>
        <button :disabled="busy || file.state!=='AVAILABLE'" @click="download(file)">下载</button>
        <button v-if="!file.taskId" :disabled="busy" @click="remove(file)">删除</button>
      </li>
    </ul>
    <p v-if="!files.length">暂无文件。支持 PDF、Word、Excel、CSV 和 Parquet 等资料。</p>
    <label>分析模型<select v-model="modelName" :disabled="busy"><option v-for="model in models" :key="model.value" :value="model.value">{{ model.label }}</option></select></label>
    <label>分析目标<textarea v-model="instruction" :disabled="busy" placeholder="说明需要计算的指标、口径和希望得到的结果"></textarea></label>
    <button :disabled="busy || !selected.length || !instruction.trim()" @click="analyze">{{ busy?'正在处理…':'分析选中文件' }}</button>
    <p v-if="status" role="status">{{ status }}</p>
    <pre v-if="report" class="asset-report">{{ report }}</pre>
  </details>
</template>
<script setup>
import {ref} from 'vue';
import {apiRequest,getStoredAuthSession,fetchPythonCodeModels} from '../services/api';
import {workspaceHeaders} from '../js/utils/workspaceContext';
const props=defineProps({workspaceContext:{type:Object,default:null}});
const models=ref([]),modelName=ref('');
const files=ref([]),selected=ref([]),space=ref(null),busy=ref(false),error=ref(''),instruction=ref(''),report=ref(''),status=ref('');
let loaded=false;
const label=kind=>({DOCUMENT:'资料',DATASET:'数据快照',CODE:'代码',LOG:'日志',ARTIFACT:'分析产物',CONTEXT:'历史上下文'}[kind]||kind);
const selectable=file=>['DOCUMENT','DATASET','ARTIFACT','CONTEXT'].includes(file.kind);
const size=n=>n>=1048576?`${(n/1048576).toFixed(1)} MB`:`${Math.ceil(n/1024)} KB`;
const options=()=>({workspaceContext:props.workspaceContext});
async function refresh(){try{error.value='';[files.value,space.value,models.value]=await Promise.all([apiRequest('/data-science/user-assets?limit=100',options()),apiRequest('/data-science/user-assets/space',options()),fetchPythonCodeModels()]);if(!models.value.some(model=>model.value===modelName.value))modelName.value=models.value.find(model=>model.defaultModel)?.value||models.value[0]?.value||'';selected.value=selected.value.filter(id=>files.value.some(file=>file.id===id));loaded=true;}catch(e){error.value=e.message;}}
function opened(event){if(event.target.open&&!loaded)refresh();}
async function upload(event){const file=event.target.files?.[0];if(!file)return;busy.value=true;try{error.value='';const body=new FormData();body.append('file',file);await apiRequest('/data-science/user-assets',{...options(),method:'POST',body});await refresh();}catch(e){error.value=e.message;}finally{busy.value=false;event.target.value='';}}
async function remove(file){busy.value=true;try{await apiRequest(`/data-science/user-assets/${encodeURIComponent(file.id)}`,{...options(),method:'DELETE'});await refresh();}catch(e){error.value=e.message;}finally{busy.value=false;}}
async function download(file){try{error.value='';const token=getStoredAuthSession()?.token;const response=await fetch(`/api/v1/data-science/user-assets/${encodeURIComponent(file.id)}/content`,{headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...workspaceHeaders(props.workspaceContext)}});if(!response.ok)throw new Error('下载失败，请刷新后重试');const url=URL.createObjectURL(await response.blob());const a=document.createElement('a');a.href=url;a.download=file.fileName;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){error.value=e.message;}}
async function analyze(){busy.value=true;error.value='';report.value='';status.value='正在分析选中的资料，请保持页面打开。';try{const chosen=files.value.filter(file=>selected.value.includes(file.id));const receipt=await apiRequest('/data-science/user-assets/analyses',{...options(),method:'POST',body:JSON.stringify({requestId:globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`,fileIds:chosen.filter(file=>file.kind!=='CONTEXT').map(file=>file.id),contextIds:chosen.filter(file=>file.kind==='CONTEXT').map(file=>file.id),instruction:instruction.value,modelName:modelName.value||undefined})});report.value=receipt.results?.[0]?.reportMarkdown||'';status.value=receipt.resultAvailable?'分析完成，代码与结果已归档。':`分析未完成：${receipt.summary||receipt.status||'请查看任务记录'}`;await refresh();}catch(e){error.value=e.message;status.value='请求未完成，请先核查任务和归档记录。';}finally{busy.value=false;}}
</script>
<style scoped>
.user-asset-library{margin:12px;padding:16px;background:var(--panel-bg,#fff);border:1px solid #dce3eb;border-radius:10px;color:#243447}.user-asset-library summary{cursor:pointer;font-weight:600}.asset-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.user-asset-library ul{max-height:300px;overflow:auto;padding:0;list-style:none}.user-asset-library li{display:flex;gap:8px;align-items:center;margin:8px 0}.user-asset-library li label{flex:1}.user-asset-library textarea{display:block;width:100%;min-height:80px;margin:8px 0}.asset-report{white-space:pre-wrap;overflow-wrap:anywhere;max-height:600px;overflow:auto;font:inherit}.user-asset-library button{cursor:pointer;padding:6px 10px}.user-asset-library [role=alert]{color:#a12929}
</style>
