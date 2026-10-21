<template>
  <details class="sql-user-documents" open>
    <summary>用户空间文档 <small>{{ modelValue.length }} 个已选</small></summary>
    <p>选择本次生成要引用的文档，并指定用途。</p>
    <div class="sql-document-actions"><label>上传文档<input aria-label="上传用户空间文档" type="file" multiple :accept="accept" :disabled="busy || uploading" @change="uploadFiles" /></label><button type="button" :disabled="busy || uploading || loading" @click="refresh">选择已有文档 / 刷新</button></div>
    <input v-model="query" type="search" aria-label="搜索用户空间文档" placeholder="搜索文档名称" />
    <p v-if="uploading" role="status">正在保存到用户空间…</p><p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !documents.length">暂无可用文档，可上传规范、字段说明、分析资料或 SQL 文件。</p>
    <p v-for="item in unavailable" :key="item.fileId" role="alert">{{ item.fileName || item.fileId }} 已不可用。<button type="button" :disabled="busy || uploading" @click="remove(item.fileId)">取消引用</button></p>
    <div v-for="file in visible" :key="file.id" class="sql-user-document">
      <label><input type="checkbox" :aria-label="'引用文档 ' + file.fileName" :checked="selected(file.id)" :disabled="busy || uploading" @change="toggle(file)" /><span :title="file.fileName">{{ file.fileName }}</span></label>
      <select v-if="selected(file.id)" :aria-label="'文档用途 ' + file.fileName" :value="selected(file.id).purpose" :disabled="busy || uploading" @change="setPurpose(file.id,$event.target.value)"><option value="RULES">参考规则</option><option value="EVIDENCE">分析依据</option></select>
    </div>
    <small>最多 8 个文档、合计 16 MiB。取消勾选保留用户空间原件。</small>
  </details>
</template>
<script setup>
import {computed,nextTick,onMounted,onBeforeUnmount,ref} from 'vue';
import {apiRequest} from '../../services/api';
const props=defineProps({modelValue:{type:Array,default:()=>[]},busy:Boolean});
const emit=defineEmits(['update:modelValue','uploading']);
const documents=ref([]),query=ref(''),loading=ref(false),uploading=ref(false),error=ref('');let disposed=false;
const accept='.pdf,.docx,.xlsx,.csv,.txt,.md,.json,.jsonl,.sql';
const supported=file=>file.kind==='DOCUMENT' && /\.(pdf|docx|xlsx|csv|txt|md|json|jsonl|sql)$/i.test(file.fileName);
const selected=id=>props.modelValue.find(item=>item.fileId===id);
const visible=computed(()=>documents.value.filter(file=>file.fileName.toLowerCase().includes(query.value.trim().toLowerCase())));
const unavailable=computed(()=>loading.value?[]:props.modelValue.filter(item=>!documents.value.some(file=>file.id===item.fileId)));
function remove(id){emit('update:modelValue',props.modelValue.filter(item=>item.fileId!==id));}
function choose(file,purpose='RULES') {
  if(selected(file.id))return;
  if(props.modelValue.length>=8)throw new Error('每次最多引用 8 个文档');
  if(documents.value.filter(item=>selected(item.id)).reduce((total,item)=>total+item.size,0)+file.size>16*1024*1024)throw new Error('本次文档合计不能超过 16 MiB');
  emit('update:modelValue',[...props.modelValue,{fileId:file.id,fileName:file.fileName,purpose}]);
}
function toggle(file){error.value='';try{if(selected(file.id))emit('update:modelValue',props.modelValue.filter(item=>item.fileId!==file.id));else choose(file);}catch(ex){error.value=ex.message;}}
function setPurpose(id,purpose){emit('update:modelValue',props.modelValue.map(item=>item.fileId===id?{...item,purpose}:item));}
async function refresh(){loading.value=true;error.value='';try{
  const latest=await apiRequest('/data-science/user-assets?kind=DOCUMENT&limit=100');
  // Selected older files remain available even when outside the latest 100 uploads.
  const missing=props.modelValue.filter(item=>!latest.some(file=>file.id===item.fileId));
  const existing=await Promise.all(missing.map(item=>apiRequest(`/data-science/user-assets/${item.fileId}`).catch(()=>null)));
  if(disposed)return;documents.value=[...latest,...existing.filter(Boolean)].filter(file=>file.state==='AVAILABLE'&&supported(file));
}catch(ex){if(!disposed)error.value=ex.message;}finally{if(!disposed)loading.value=false;}}
async function uploadFiles(event){
  const incoming=Array.from(event.target.files||[]);event.target.value='';if(!incoming.length||props.busy||uploading.value)return;
  error.value='';uploading.value=true;emit('uploading',true);
  try{
    if(incoming.length+props.modelValue.length>8)throw new Error('每次最多引用 8 个文档');
    const chosen=documents.value.filter(file=>selected(file.id));
    if([...chosen,...incoming].reduce((total,file)=>total+file.size,0)>16*1024*1024)throw new Error('本次文档合计不能超过 16 MiB');
    if(incoming.some(file=>!supported({kind:'DOCUMENT',fileName:file.name})||!file.size))throw new Error('请选择支持的非空文档');
    for(const file of incoming){
      const form=new FormData();form.append('file',file);
      const saved=await apiRequest('/data-science/user-assets',{method:'POST',body:form});
      if(disposed)return;
      documents.value=[saved,...documents.value.filter(item=>item.id!==saved.id)];choose(saved);
      // Let the parent apply each selection before the next file finishes uploading.
      await nextTick();
    }
  }catch(ex){if(!disposed)error.value=ex.message;}finally{if(!disposed){uploading.value=false;emit('uploading',false);}}
}
onMounted(refresh);onBeforeUnmount(()=>{disposed=true;emit('uploading',false);});
</script>
<style scoped>
.sql-user-documents{margin:12px 8px;font-size:12px}.sql-user-documents summary{cursor:pointer;font-weight:600}.sql-user-documents p,.sql-user-documents small{color:#64748b}.sql-document-actions{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}.sql-document-actions label{cursor:pointer;border:1px solid #cbd5e1;border-radius:4px;padding:5px}.sql-document-actions input{display:block;max-width:185px;font-size:11px}.sql-user-documents>input{box-sizing:border-box;width:100%;margin:4px 0;padding:5px}.sql-user-document{padding:6px 0;border-bottom:1px solid #e2e8f0}.sql-user-document label{display:flex;align-items:center;gap:5px}.sql-user-document span{overflow-wrap:anywhere}.sql-user-document select{margin:4px 0 0 22px;max-width:150px}.sql-user-documents [role=alert]{color:#b91c1c}
</style>
